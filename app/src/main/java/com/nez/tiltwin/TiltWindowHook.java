package com.nez.tiltwin;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Tilt perspektif (trapesium) seperti contoh video.
 *
 * Matrix SurfaceControl cuma 2D, jadi perspektif nggak bisa dibuat langsung di jendela app.
 * Cara yang dipakai:
 *  1. Hook SurfaceControl.Transaction.setMatrix di proses launcher (dipanggil tiap frame animasi).
 *  2. Di frame pertama, ambil snapshot leash jendela app (ScreenCapture.captureLayers).
 *  3. Jendela asli disembunyikan (digeser ke luar layar), diganti ImageView snapshot yang
 *     ditaruh di window launcher dan diberi rotationX/rotationY + cameraDistance (3D asli).
 *  4. Arah miring mengikuti posisi ikon (kanan/kiri/tengah/bawah beda).
 *
 * Catatan: snapshot di-ambil ulang sekali di tengah animasi supaya isinya tidak kosong,
 * jadi isi jendela tidak 100% live. API hidden bisa beda antar versi Android/XOS.
 */
public class TiltWindowHook implements IXposedHookLoadPackage {

    private static final String TAG = "[TiltWindow] ";

    // ===== Atur di sini =====
    private static final float RX_BASE   = 16f;    // miring atas-bawah (atas menjauh). Balik tanda kalau arahnya kebalik
    private static final float RX_BY_Y   = 4f;     // tambahan: ikon bawah lebih miring, atas lebih landai
    private static final float RY_BY_X   = -14f;   // miring kiri-kanan mengikuti posisi ikon (kanan/kiri beda arah)
    private static final float RY_CENTER = 0f;     // miring kiri-kanan untuk ikon tepat di tengah
    private static final float CAMERA_DIST = 2500f;// makin kecil = perspektif makin kuat (dikali density)
    private static final float AMP = 1.8f;         // penguat posisi ikon (pinggir -> miring penuh)
    private static final float CORNER_PX = 56f;    // radius sudut jendela
    private static final long  HIDE_DELAY = 90L;   // ms setelah frame terakhir, overlay dihapus

    private static final float MIN_SCALE = 0.05f;
    private static final float SCALE_RANGE = 0.85f;
    private static final long  NEW_ANIM_GAP = 250L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile WeakReference<Activity> launcherRef = new WeakReference<>(null);

    // state animasi (diakses dari thread hook)
    private volatile Bitmap bmp;
    private volatile long lastFrame = 0;
    private volatile boolean recaptured = false;
    private volatile boolean loggedFail = false;
    // state view (main thread)
    private ImageView overlay;

    private final Runnable removeRun = this::removeOverlay;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!lpp.packageName.equals("com.transsion.launcher3")
                && !lpp.packageName.equals("com.android.launcher3")) return;

        XposedBridge.log(TAG + "loaded in " + lpp.packageName);

        // simpan activity launcher untuk tempat menaruh overlay
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    String n = p.thisObject.getClass().getName();
                    if (n.contains("Launcher") && !n.contains("Settings")) {
                        launcherRef = new WeakReference<>((Activity) p.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal hook Activity: " + t);
        }

        try {
            Class<?> tx = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", lpp.classLoader);
            XposedBridge.hookAllMethods(tx, "setMatrix", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        onSetMatrix(p);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + t);
                    }
                }
            });
            XposedBridge.log(TAG + "hooked SurfaceControl.Transaction.setMatrix");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "gagal hook: " + t);
        }
    }

    private void onSetMatrix(XC_MethodHook.MethodHookParam p) {
        if (p.args.length != 3 || !(p.args[1] instanceof Matrix)) return;

        Matrix src = (Matrix) p.args[1];
        float[] v = new float[9];
        src.getValues(v);
        float sx = v[Matrix.MSCALE_X];
        float sy = v[Matrix.MSCALE_Y];

        if (sx >= 0.995f) {                       // frame akhir / normal: bersihkan overlay
            if (overlay != null) runMain(this::removeOverlay);
            return;
        }
        if (sx <= MIN_SCALE || sy <= MIN_SCALE) return;

        Activity act = launcherRef.get();
        if (act == null) return;

        long now = System.currentTimeMillis();
        boolean newAnim = now - lastFrame > NEW_ANIM_GAP;
        lastFrame = now;
        float progress = Math.min(1f, (1f - sx) / SCALE_RANGE);

        if (newAnim) {
            recaptured = false;
            bmp = capture(p.args[0]);
        } else if (!recaptured && progress < 0.4f) {   // sudah mulai membesar -> ambil ulang sekali
            recaptured = true;
            Bitmap b2 = capture(p.args[0]);
            if (b2 != null) bmp = b2;
        }
        final Bitmap b = bmp;
        if (b == null) {
            if (!loggedFail) { loggedFail = true; XposedBridge.log(TAG + "capture gagal, jendela asli dibiarkan"); }
            return;
        }

        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        final int w = dm.widthPixels, h = dm.heightPixels;
        final float cx = v[Matrix.MTRANS_X] + w * sx / 2f;
        final float cy = v[Matrix.MTRANS_Y] + h * sy / 2f;

        float dx = clamp((cx - w / 2f) / (w / 2f) * AMP);
        float dy = clamp((cy - h / 2f) / (h / 2f) * AMP);
        final float rx = (RX_BASE + RX_BY_Y * dy) * progress;
        final float ry = (RY_BY_X * dx + RY_CENTER * (1f - Math.abs(dx))) * progress;
        final float fsx = sx, fsy = sy;
        final float density = dm.density;

        runMain(() -> updateOverlay(act, b, w, h, cx, cy, fsx, fsy, rx, ry, density));

        // sembunyikan jendela asli: geser jauh ke luar layar
        Matrix off = new Matrix();
        off.setTranslate(-10000f, -10000f);
        p.args[1] = off;
    }

    // ---------- overlay (main thread) ----------
    private void updateOverlay(Activity act, Bitmap b, int w, int h, float cx, float cy,
                               float sx, float sy, float rx, float ry, float density) {
        try {
            if (overlay == null) {
                ImageView iv = new ImageView(act);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setClipToOutline(true);
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline o) {
                        o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), CORNER_PX);
                    }
                });
                iv.setCameraDistance(CAMERA_DIST * density);
                ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
                decor.addView(iv, new FrameLayout.LayoutParams(w, h));
                overlay = iv;
            }
            if (overlay.getTag() != b) {
                overlay.setImageBitmap(b);
                overlay.setTag(b);
            }
            overlay.setTranslationX(cx - w / 2f);
            overlay.setTranslationY(cy - h / 2f);
            overlay.setScaleX(sx);
            overlay.setScaleY(sy);
            overlay.setRotationX(rx);
            overlay.setRotationY(ry);

            main.removeCallbacks(removeRun);
            main.postDelayed(removeRun, HIDE_DELAY);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "overlay: " + t);
        }
    }

    private void removeOverlay() {
        try {
            main.removeCallbacks(removeRun);
            if (overlay != null) {
                ViewGroup parent = (ViewGroup) overlay.getParent();
                if (parent != null) parent.removeView(overlay);
                overlay = null;
            }
            bmp = null;
            lastFrame = 0;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "remove: " + t);
        }
    }

    // ---------- snapshot leash ----------
    private Bitmap capture(Object leash) {
        try {
            Class<?> sc = XposedHelpers.findClass("android.window.ScreenCapture", null);
            Class<?> bld = XposedHelpers.findClass("android.window.ScreenCapture$LayerCaptureArgs$Builder", null);
            Object builder = XposedHelpers.newInstance(bld, leash);
            Object args = XposedHelpers.callMethod(builder, "build");
            Object buf = XposedHelpers.callStaticMethod(sc, "captureLayers", args);
            if (buf == null) return null;
            return (Bitmap) XposedHelpers.callMethod(buf, "asBitmap");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "capture: " + t);
            return null;
        }
    }

    private void runMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    private static float clamp(float f) {
        return Math.max(-1f, Math.min(1f, f));
    }
}
