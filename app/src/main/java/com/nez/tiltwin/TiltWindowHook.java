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
 * Animasi buka/tutup app ala iOS: tilt jendela mengikuti posisi ikon + home zoom/fade,
 * semua dari satu sumber progres (sinkron), dan INTERRUPTIBLE:
 * kalau animasi dipotong di tengah jalan (buka lalu langsung tutup, atau pindah ke app lain),
 * tampilan tidak lompat. Selisih antara posisi yang sedang tampil dan posisi awal animasi
 * berikutnya disimpan sebagai "offset", lalu diredam mulus (critically damped, tanpa memantul)
 * sampai animasi baru benar-benar menggantikan.
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
    private static final boolean ICON_ANIM = true;
    private static final float ICON_ZOOM   = 0.14f;
    private static final float FADE_END    = 0.55f;
    private static final long  RESTORE_MS  = 300L;  // harus > jeda antar animasi saat buka-tutup cepat

    // ===== Peralihan mulus saat animasi dipotong =====
    private static final boolean SMOOTH_INTERRUPT = true; // false = matikan kalau malah glitch
    private static final float SETTLE_RATE = 14f;   // makin besar = peredaman lebih cepat (0.3 dtk ~ 90% selesai)
    private static final long  GRACE_MS    = 400L;  // jarak maksimal antar animasi yang dianggap "sambungan"
    private static final long  JUMP_GAP_MS = 60L;   // jeda frame segini = dianggap animasi baru
    private static final long  NEW_LEASH_GAP_MS = 34L;

    private static final float MIN_SCALE = 0.05f;
    private static final long  NEW_ANIM_GAP_MS = 250L;

    private volatile WeakReference<Activity> launcherRef = new WeakReference<>(null);
    private volatile boolean loggedContentFail = false;

    // crop
    private volatile Rect curRaw;
    private Rect lastDispCrop;
    private boolean pendingCropOffset, hasCropOffset;
    private int offL, offT, offR, offB;

    // state animasi
    private boolean lastValid, hasOffset;
    private long lastMs = 0, t0 = 0;
    private float prevTsx, prevTsy, prevTtx, prevTty;   // target dari launcher (frame sebelumnya)
    private float prevDsx, prevDsy, prevDtx, prevDty;   // yang benar-benar ditampilkan
    private float oSx, oSy, oTx, oTy;                    // offset peredaman
    private Object primaryLeash;

    // penghalusan tilt
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
                    try { onSetCrop(p); } catch (Throwable t) { XposedBridge.log(TAG + t); }
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

    // ---------- crop ----------
    private void onSetCrop(XC_MethodHook.MethodHookParam p) {
        if (p.args.length != 2 || !(p.args[1] instanceof Rect)) return;
        Rect raw = new Rect((Rect) p.args[1]);
        curRaw = raw;

        if (pendingCropOffset && lastDispCrop != null) {
            offL = lastDispCrop.left - raw.left;
            offT = lastDispCrop.top - raw.top;
            offR = lastDispCrop.right - raw.right;
            offB = lastDispCrop.bottom - raw.bottom;
            hasCropOffset = true;
            pendingCropOffset = false;
        }
        if (hasCropOffset) {
            float f = factor(nowMs());
            Rect d = new Rect(raw.left + Math.round(offL * f), raw.top + Math.round(offT * f),
                    raw.right + Math.round(offR * f), raw.bottom + Math.round(offB * f));
            if (d.right > d.left && d.bottom > d.top) {
                p.args[1] = d;
                lastDispCrop = d;
                return;
            }
        }
        lastDispCrop = raw;
    }

    // ---------- matrix ----------
    private void onSetMatrix(XC_MethodHook.MethodHookParam p) {
        if (p.args.length != 3 || !(p.args[1] instanceof Matrix)) return;

        Matrix src = (Matrix) p.args[1];
        float[] v = new float[9];
        src.getValues(v);
        float sx = v[Matrix.MSCALE_X];
        float sy = v[Matrix.MSCALE_Y];
        float tx = v[Matrix.MTRANS_X];
        float ty = v[Matrix.MTRANS_Y];
        if (Math.abs(v[Matrix.MSKEW_X]) > 1e-3f || Math.abs(v[Matrix.MSKEW_Y]) > 1e-3f) return;

        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
        final float w = dm.widthPixels, h = dm.heightPixels;

        long now = nowMs();
        Rect raw = curRaw;
        boolean cropped = raw != null && !raw.isEmpty()
                && (raw.width() < w * 0.98f || raw.height() < h * 0.98f);
        boolean fullTarget = sx >= 0.995f && !cropped;

        long dt = lastMs == 0 ? Long.MAX_VALUE / 4 : now - lastMs;
        boolean cont = lastValid && dt < GRACE_MS;

        // leash lain yang muncul bersamaan dengan animasi utama: abaikan
        if (cont && dt < NEW_LEASH_GAP_MS && primaryLeash != null && !sameLeash(primaryLeash, p.args[0])) return;

        if (fullTarget) {
            // animasi baru yang mulai dari full screen (mis. tutup app) tepat setelah animasi dipotong
            boolean keep = SMOOTH_INTERRUPT && cont && (prevDsx < 0.97f || !isSettled(now));
            if (!keep) { resetState(); return; }
        }
        if (sx <= MIN_SCALE || sy <= MIN_SCALE) return;

        if (!cont || dt >= NEW_LEASH_GAP_MS) primaryLeash = p.args[0];

        // ---- deteksi lompatan (animasi dipotong / diganti) lalu simpan offset kontinuitas ----
        if (cont) {
            float dScale = Math.abs(sx - prevTsx);
            float pcx = prevTtx + prevTsx * w / 2f, ncx = tx + sx * w / 2f;
            float pcy = prevTty + prevTsy * h / 2f, ncy = ty + sy * h / 2f;
            float dCen = (float) Math.hypot(ncx - pcx, ncy - pcy) / w;
            boolean jump = SMOOTH_INTERRUPT && (dt >= JUMP_GAP_MS || dScale > 0.12f || dCen > 0.25f);
            if (jump) {
                oSx = prevDsx - sx;
                oSy = prevDsy - sy;
                oTx = prevDtx - tx;
                oTy = prevDty - ty;
                t0 = now;
                hasOffset = true;
                hasCropOffset = false;
                pendingCropOffset = true;
                XposedBridge.log(TAG + "interupsi: tampil sx=" + prevDsx + " -> target sx=" + sx);
            }
        } else {
            hasOffset = false;
            hasCropOffset = false;
            pendingCropOffset = false;
        }

        // ---- nilai yang ditampilkan = target + offset yang meredam ----
        float f = factor(now);
        float dsx = Math.max(MIN_SCALE, Math.min(1.5f, sx + oSx * f));
        float dsy = Math.max(MIN_SCALE, Math.min(1.5f, sy + oSy * f));
        float dtx = tx + oTx * f;
        float dty = ty + oTy * f;

        Rect crop = displayedCrop(raw, f, (int) w, (int) h);
        float lcx = crop.exactCenterX(), lcy = crop.exactCenterY();
        float cx = dtx + lcx * dsx;
        float cy = dty + lcy * dsy;

        // progres tunggal: 0 = seukuran ikon, 1 = full screen (sumber sinkron jendela + ikon)
        float visW = crop.width() * dsx;
        float progress = clamp01((1f - visW / w) / 0.85f);
        float env = smoothstep(progress);

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

        boolean freshTilt = !cont || dt > NEW_ANIM_GAP_MS;
        if (freshTilt) {
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

        float wc = (1f - Math.abs(sDx)) * (1f - Math.abs(sDy));
        float rot   = (ROT_DEG_X * sDx + CENTER_ROT * wc) * env;
        float skewX = (SKEW_X_Y * sDy + CENTER_SKEW * wc + LEAN_GAIN * sLean) * env;
        float skewY = (SKEW_Y_X * sDx) * env;

        Matrix out;
        if (hasOffset && f > 0.002f) {
            out = new Matrix();
            out.setScale(dsx, dsy);
            out.postTranslate(dtx, dty);
        } else {
            out = new Matrix(src);
        }
        out.postRotate(rot, cx, cy);
        out.postSkew(skewX, skewY, cx, cy);
        p.args[1] = out;

        prevTsx = sx; prevTsy = sy; prevTtx = tx; prevTty = ty;
        prevDsx = dsx; prevDsy = dsy; prevDtx = dtx; prevDty = dty;
        lastValid = true;
        lastMs = now;
    }

    private Rect displayedCrop(Rect raw, float f, int w, int h) {
        Rect base = (raw == null || raw.isEmpty()) ? new Rect(0, 0, w, h) : new Rect(raw);
        if (hasCropOffset) {
            Rect d = new Rect(base.left + Math.round(offL * f), base.top + Math.round(offT * f),
                    base.right + Math.round(offR * f), base.bottom + Math.round(offB * f));
            if (d.right > d.left && d.bottom > d.top) return d;
        }
        return base;
    }

    /** Peredam critically damped: 1 di t=0, turun mulus ke 0 tanpa memantul. */
    private float factor(long now) {
        if (!hasOffset) return 0f;
        float tt = Math.max(0f, (now - t0) / 1000f);
        return (1f + SETTLE_RATE * tt) * (float) Math.exp(-SETTLE_RATE * tt);
    }

    private boolean isSettled(long now) {
        if (!hasOffset) return true;
        float f = factor(now);
        boolean ok = Math.abs(oSx * f) < 0.01f && Math.abs(oSy * f) < 0.01f
                && Math.abs(oTx * f) < 4f && Math.abs(oTy * f) < 4f;
        if (ok && hasCropOffset) {
            ok = Math.abs(offL * f) < 4f && Math.abs(offT * f) < 4f
                    && Math.abs(offR * f) < 4f && Math.abs(offB * f) < 4f;
        }
        return ok;
    }

    private void resetState() {
        lastValid = false;
        lastMs = 0;
        hasOffset = false;
        hasCropOffset = false;
        pendingCropOffset = false;
        primaryLeash = null;
        curRaw = null;
        lastDispCrop = null;
    }

    private static boolean sameLeash(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return true;
        try {
            Object r = XposedHelpers.callMethod(a, "isSameSurface", b);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            return true;   // tidak bisa dibedakan -> anggap sama
        }
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
            float fade = smoothstep(clamp01(p / FADE_END));
            float zoom = 1f + ICON_ZOOM * easeOut(p);
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

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
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
