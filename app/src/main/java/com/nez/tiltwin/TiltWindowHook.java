package com.nez.tiltwin;

import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.View;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Animasi buka/tutup app ala iOS: jendela miring mengikuti posisi ikon + ikon/konten home
 * ikut zoom & fade, semuanya digerakkan dari SATU sumber progres (frame jendela yang sama)
 * sehingga selalu sinkron dan paralel.
 *
 *  - Jendela : rotasi + skew pada matrix (2D), arah mengikuti posisi ikon, dihaluskan antar frame.
 *  - Konten  : workspace + hotseat zoom membesar & memudar saat app membuka, kebalikannya saat menutup
 *              (animator konten bawaan launcher dinetralkan supaya tidak berebut).
 *
 * Batasan: matrix SurfaceControl cuma 2D, jadi tilt jendela = rotasi + skew, bukan perspektif 3D asli.
 */
public class TiltWindowHook implements IXposedHookLoadPackage {

    private static final String TAG = "[TiltWindow] ";

    // ===== Tilt jendela (tanda +/- membalik arah) =====
    private static final float ROT_DEG_X   = 6f;
    private static final float SKEW_Y_X    = 0.10f;
    private static final float SKEW_X_Y    = 0.10f;
    private static final float CENTER_ROT  = 2f;
    private static final float CENTER_SKEW = 0.03f;
    private static final float LEAN_GAIN   = 0.03f; // 0 = mati
    private static final float AMP         = 1.8f;
    private static final float SMOOTH_MS   = 45f;

    // ===== Animasi ikon / konten home =====
    private static final boolean ICON_ANIM = true;  // false = matikan animasi ikon
    private static final float ICON_ZOOM   = 0.14f; // seberapa besar home membesar (0.14 = 114%)
    private static final float FADE_END    = 0.55f; // home sudah hilang total di progres segini (0..1)
    private static final long  RESTORE_MS  = 110L;  // setelah frame terakhir, kembalikan home ke normal

    private static final float MIN_SCALE = 0.05f;
    private static final long  NEW_ANIM_GAP_MS = 250L;

    private volatile Rect curCrop;
    private volatile WeakReference<Activity> launcherRef = new WeakReference<>(null);
    private volatile boolean loggedContentFail = false;

    // penghalusan tilt (thread hook)
    private long lastMs = 0;
    private float sDx, sDy, sLean, lastCx;

    // konten home (main thread)
    private static class Saved { View v; float alpha, sx, sy; }
    private Saved[] saved;
    private Handler mainH;
    private final Runnable restoreRun = this::restoreContent;

    private Handler main() {                       // dibuat belakangan: looper belum ada saat fork Zygote
        if (mainH == null) mainH = new Handler(Looper.getMainLooper());
        return mainH;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!lpp.packageName.equals("com.transsion.launcher3")
                && !lpp.packageName.equals("com.android.launcher3")) return;

        XposedBridge.log(TAG + "loaded in " + lpp.packageName);

        try {
            XposedHelpers.findAndHookMethod(android.app.Activity.class, "onResume", new XC_MethodHook() {
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

        // netralkan animator konten bawaan launcher supaya tidak berebut dengan animasi kita
        if (ICON_ANIM) {
            String[] cands = {
                    "com.android.launcher3.QuickstepTransitionManager",
                    "com.android.launcher3.uioverrides.QuickstepTransitionManager"
            };
            boolean ok = false;
            for (String cn : cands) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, lpp.classLoader);
                    XposedBridge.hookAllMethods(c, "getLauncherContentAnimator", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            Object r = p.getResult();
                            if (r instanceof Pair && ((Pair<?, ?>) r).first instanceof AnimatorSet) {
                                p.setResult(new Pair<Object, Object>(new AnimatorSet(), ((Pair<?, ?>) r).second));
                            }
                        }
                    });
                    XposedBridge.log(TAG + "animator konten bawaan dinetralkan (" + cn + ")");
                    ok = true;
                    break;
                } catch (Throwable ignored) { }
            }
            if (!ok) XposedBridge.log(TAG + "QuickstepTransitionManager tidak ketemu; animasi ikon bawaan mungkin ikut jalan");
        }

        try {
            Class<?> tx = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", lpp.classLoader);

            XposedBridge.hookAllMethods(tx, "setWindowCrop", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 2 && p.args[1] instanceof Rect) curCrop = new Rect((Rect) p.args[1]);
                    } catch (Throwable t) { XposedBridge.log(TAG + t); }
                }
            });

            XposedBridge.hookAllMethods(tx, "setMatrix", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try { onSetMatrix(p); } catch (Throwable t) { XposedBridge.log(TAG + t); }
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
        final float w = dm.widthPixels, h = dm.heightPixels;

        Rect crop = curCrop;
        boolean cropped = crop != null && !crop.isEmpty()
                && (crop.width() < w * 0.98f || crop.height() < h * 0.98f);

        if (sx >= 0.995f && !cropped) {          // normal / frame akhir: jangan disentuh
            lastMs = 0;
            curCrop = null;
            return;
        }
        if (sx <= MIN_SCALE || sy <= MIN_SCALE) return;
        if (crop == null || crop.isEmpty()) crop = new Rect(0, 0, (int) w, (int) h);

        float lcx = crop.exactCenterX(), lcy = crop.exactCenterY();
        float cx = tx + lcx * sx;
        float cy = ty + lcy * sy;

        // progres tunggal: 0 = seukuran ikon, 1 = full screen (sumber sinkron jendela + ikon)
        float visW = crop.width() * sx;
        float progress = clamp01((1f - visW / w) / 0.85f);
        float env = smoothstep(progress);

        // ---- animasi ikon/konten, paralel dengan jendela ----
        if (ICON_ANIM) {
            final Activity act = launcherRef.get();
            if (act != null) {
                final float pp = progress;
                final int iw = (int) w, ih = (int) h;
                runMain(() -> applyContent(act, pp, iw, ih));
            }
        }

        // ---- tilt jendela ----
        float dx = clamp((cx - w / 2f) / (w / 2f) * AMP);
        float dy = clamp((cy - h / 2f) / (h / 2f) * AMP);

        long now = System.nanoTime() / 1_000_000L;
        long dt = now - lastMs;
        boolean fresh = lastMs == 0 || dt > NEW_ANIM_GAP_MS;
        if (fresh) {
            sDx = dx; sDy = dy; sLean = 0f; lastCx = cx;
            XposedBridge.log(TAG + "anim mulai sx=" + sx + " crop=" + crop + " dx=" + dx + " dy=" + dy);
        } else {
            float d = Math.max(1f, Math.min(50f, dt));
            float a = 1f - (float) Math.exp(-d / SMOOTH_MS);
            sDx += (dx - sDx) * a;
            sDy += (dy - sDy) * a;
            float vx = (cx - lastCx) / d * 1000f;
            sLean += (clamp(vx / (w * 4f)) - sLean) * a;
            lastCx = cx;
        }
        lastMs = now;

        float wc = (1f - Math.abs(sDx)) * (1f - Math.abs(sDy));
        float rot   = (ROT_DEG_X * sDx + CENTER_ROT * wc) * env;
        float skewX = (SKEW_X_Y * sDy + CENTER_SKEW * wc + LEAN_GAIN * sLean) * env;
        float skewY = (SKEW_Y_X * sDx) * env;

        Matrix out = new Matrix(src);
        out.postRotate(rot, cx, cy);
        out.postSkew(skewX, skewY, cx, cy);
        p.args[1] = out;
    }

    // ---------- konten home (main thread) ----------
    private void applyContent(Activity act, float p, int w, int h) {
        try {
            if (saved == null) {
                saved = new Saved[]{
                        save(act, "getWorkspace", w, h),
                        save(act, "getHotseat", w, h)
                };
            }
            float fade = smoothstep(clamp01(p / FADE_END));       // home memudar lebih cepat dari jendela
            float zoom = 1f + ICON_ZOOM * easeOut(p);              // dan membesar pelan-pelan
            for (Saved s : saved) {
                if (s == null) continue;
                s.v.setAlpha(s.alpha * (1f - fade));
                s.v.setScaleX(s.sx * zoom);
                s.v.setScaleY(s.sy * zoom);
            }
            main().removeCallbacks(restoreRun);
            main().postDelayed(restoreRun, RESTORE_MS);
        } catch (Throwable t) {
            if (!loggedContentFail) { loggedContentFail = true; XposedBridge.log(TAG + "konten: " + t); }
        }
    }

    private Saved save(Activity act, String getter, int w, int h) {
        try {
            Object o = XposedHelpers.callMethod(act, getter);
            if (!(o instanceof View)) return null;
            View v = (View) o;
            Saved s = new Saved();
            s.v = v;
            s.alpha = v.getAlpha();
            s.sx = v.getScaleX();
            s.sy = v.getScaleY();
            // pivot = tengah layar (pivot relatif terhadap view)
            v.setPivotX(w / 2f - v.getLeft());
            v.setPivotY(h / 2f - v.getTop());
            return s;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "ambil " + getter + ": " + t);
            return null;
        }
    }

    private void restoreContent() {
        try {
            if (saved != null) {
                for (Saved s : saved) {
                    if (s == null) continue;
                    s.v.setAlpha(s.alpha);
                    s.v.setScaleX(s.sx);
                    s.v.setScaleY(s.sy);
                    s.v.resetPivot();
                }
                saved = null;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "restore: " + t);
        }
    }

    private void runMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main().post(r);
    }

    private static float easeOut(float t) {
        t = clamp01(t);
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static float smoothstep(float t) {
        t = clamp01(t);
        return t * t * (3f - 2f * t);
    }

    private static float clamp01(float f) {
        return Math.max(0f, Math.min(1f, f));
    }

    private static float clamp(float f) {
        return Math.max(-1f, Math.min(1f, f));
    }
}
