package com.nez.tiltwin;

import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
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
 * Tilt perspektif (trapesium) untuk animasi buka/tutup app di launcher.
 *
 * Launcher menganimasikan jendela app dengan 2 hal: matrix (scale+translate) DAN crop
 * (area jendela yang kelihatan, mengecil jadi bentuk ikon). Versi sebelumnya cuma
 * membaca matrix, jadi posisi/bentuknya melenceng. Versi ini juga membaca setWindowCrop.
 *
 * Cara: snapshot leash jendela -> jendela asli disembunyikan -> ImageView snapshot di window
 * launcher diberi crop yang sama + rotationX/Y + cameraDistance (perspektif asli).
 */
public class TiltWindowHook implements IXposedHookLoadPackage {

    private static final String TAG = "[TiltWindow] ";

    // ===== Atur di sini =====
    private static final float RX_BASE   = 16f;    // miring atas-bawah (atas menjauh). Balik tanda kalau kebalik
    private static final float RX_BY_Y   = 4f;     // ikon bawah lebih miring, atas lebih landai
    private static final float RY_BY_X   = -14f;   // miring kiri-kanan menurut posisi ikon
    private static final float RY_CENTER = 0f;     // miring kiri-kanan untuk ikon tepat di tengah
    private static final float CAMERA_DIST = 2500f;// makin kecil = perspektif makin kuat (dikali density)
    private static final float AMP = 1.8f;         // penguat posisi ikon
    private static final float CORNER_SCREEN_PX = 56f; // radius sudut di layar
    private static final long  HIDE_DELAY = 90L;

    private static final float MIN_SCALE = 0.05f;
    private static final long  NEW_ANIM_GAP = 250L;

    private Handler mainH;
    private Handler main() {
        if (mainH == null) mainH = new Handler(Looper.getMainLooper());
        return mainH;
    }

    private volatile WeakReference<Activity> launcherRef = new WeakReference<>(null);

    // state animasi (thread hook)
    private volatile Bitmap bmp;
    private volatile Rect curCrop;
    private volatile long lastFrame = 0;
    private volatile boolean recaptured = false;
    private volatile boolean loggedFail = false;
    // state view (main thread)
    private ImageView overlay;
    private volatile Rect outlineRect = null;
    private volatile float outlineRadius = 0f;

    private final Runnable removeRun = this::removeOverlay;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!lpp.packageName.equals("com.transsion.launcher3")
                && !lpp.packageName.equals("com.android.launcher3")) return;

        XposedBridge.log(TAG + "loaded in " + lpp.packageName);

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
                    try { onSetMatrix(p); } catch (Throwable t) { XposedBridge.log(TAG + t); }
                }
            });

            XposedBridge.hookAllMethods(tx, "setWindowCrop", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 2 && p.args[1] instanceof Rect) {
                            Rect c = new Rect((Rect) p.args[1]);
                            curCrop = c;
                            if (overlay != null) runMain(() -> applyOutline(c));
                        }
                    } catch (Throwable t) { XposedBridge.log(TAG + t); }
                }
            });
            XposedBridge.log(TAG + "hooked setMatrix + setWindowCrop");
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
        float tx = v[Matrix.MTRANS_X];
        float ty = v[Matrix.MTRANS_Y];

        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        final int w = dm.widthPixels, h = dm.heightPixels;

        Rect crop = curCrop;
        boolean cropped = crop != null && !crop.isEmpty()
                && (crop.width() < w * 0.98f || crop.height() < h * 0.98f);

        if (sx >= 0.995f && !cropped) {            // normal / frame akhir
            if (overlay != null) runMain(this::removeOverlay);
            return;
        }
        if (sx <= MIN_SCALE || sy <= MIN_SCALE) return;

        Activity act = launcherRef.get();
        if (act == null) return;

        long now = System.currentTimeMillis();
        boolean newAnim = now - lastFrame > NEW_ANIM_GAP;
        lastFrame = now;

        if (crop == null || crop.isEmpty()) crop = new Rect(0, 0, w, h);

        float visW = crop.width() * sx;
        float progress = Math.max(0f, Math.min(1f, (1f - visW / w) / 0.85f));

        if (newAnim) {
            recaptured = false;
            bmp = capture(p.args[0]);
            XposedBridge.log(TAG + "anim mulai sx=" + sx + " crop=" + crop
                    + " bmp=" + (bmp == null ? "null" : bmp.getWidth() + "x" + bmp.getHeight()));
        } else if (!recaptured && progress < 0.5f) {
            recaptured = true;
            Bitmap b2 = capture(p.args[0]);
            if (b2 != null) bmp = b2;
        }
        final Bitmap b = bmp;
        if (b == null) {
            if (!loggedFail) { loggedFail = true; XposedBridge.log(TAG + "capture gagal, jendela asli dibiarkan"); }
            return;
        }

        // pusat area yang kelihatan (koordinat lokal jendela) dan posisinya di layar
        final float lcx = crop.exactCenterX(), lcy = crop.exactCenterY();
        final float cx = tx + lcx * sx;
        final float cy = ty + lcy * sy;

        float dx = clamp((cx - w / 2f) / (w / 2f) * AMP);
        float dy = clamp((cy - h / 2f) / (h / 2f) * AMP);
        final float rx = (RX_BASE + RX_BY_Y * dy) * progress;
        final float ry = (RY_BY_X * dx + RY_CENTER * (1f - Math.abs(dx))) * progress;

        final float fsx = sx, fsy = sy, ftx = tx, fty = ty;
        final float density = dm.density;
        final Rect fcrop = new Rect(crop);

        runMain(() -> updateOverlay(act, b, w, h, lcx, lcy, ftx, fty, fsx, fsy, rx, ry, density, fcrop));

        Matrix off = new Matrix();
        off.setTranslate(-10000f, -10000f);
        p.args[1] = off;
    }

    // ---------- overlay (main thread) ----------
    private void updateOverlay(Activity act, Bitmap b, int w, int h, float lcx, float lcy,
                               float tx, float ty, float sx, float sy, float rx, float ry,
                               float density, Rect crop) {
        try {
            if (overlay == null) {
                ImageView iv = new ImageView(act);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setClipToOutline(true);
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline o) {
                        Rect r = outlineRect;
                        if (r == null) r = new Rect(0, 0, view.getWidth(), view.getHeight());
                        o.setRoundRect(r, outlineRadius);
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
            overlay.setPivotX(lcx);
            overlay.setPivotY(lcy);
            // titik lokal (lcx,lcy) harus mendarat di tx + lcx*sx
            overlay.setTranslationX(tx + lcx * (sx - 1f));
            overlay.setTranslationY(ty + lcy * (sy - 1f));
            overlay.setScaleX(sx);
            overlay.setScaleY(sy);
            overlay.setRotationX(rx);
            overlay.setRotationY(ry);

            outlineRadius = Math.min(Math.min(crop.width(), crop.height()) / 2f, CORNER_SCREEN_PX / sx);
            applyOutline(crop);

            main().removeCallbacks(removeRun);
            main().postDelayed(removeRun, HIDE_DELAY);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "overlay: " + t);
        }
    }

    private void applyOutline(Rect c) {
        outlineRect = c;
        if (overlay != null) overlay.invalidateOutline();
    }

    private void removeOverlay() {
        try {
            main().removeCallbacks(removeRun);
            if (overlay != null) {
                ViewGroup parent = (ViewGroup) overlay.getParent();
                if (parent != null) parent.removeView(overlay);
                overlay = null;
            }
            bmp = null;
            curCrop = null;
            outlineRect = null;
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
        else main().post(r);
    }

    private static float clamp(float f) {
        return Math.max(-1f, Math.min(1f, f));
    }
}
