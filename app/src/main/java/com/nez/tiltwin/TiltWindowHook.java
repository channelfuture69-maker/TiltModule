package com.nez.tiltwin;

import android.animation.AnimatorSet;
import android.app.Activity;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.Arrays;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Animasi buka/tutup app ala iOS: tilt jendela mengikuti posisi ikon + home zoom/fade,
 * semua dari satu sumber progres (sinkron).
 *
 * Saat animasi dipotong (mis. setengah buka lalu langsung tutup, atau sebaliknya):
 *  - Jalur gerak jendela (scale, posisi, crop per progres) direkam dari animasi yang sedang jalan.
 *  - Animasi baru memutar balik MENYUSURI jalur yang sama mulai dari posisi sekarang,
 *    jadi gerakan, tilt, dan animasi ikon-nya identik dengan animasi normal, tanpa lompat.
 *  - Kalau tidak bisa dibalik (mis. ikon lain), dipakai peralihan mulus (offset teredam).
 *
 * Mode PERSPEKTIF (trapesium seperti iOS): snapshot jendela app diambil lalu digambar sebagai overlay
 * 3D (rotationX/Y + cameraDistance) di window launcher, jendela asli disembunyikan sementara.
 * Kalau snapshot gagal diambil, otomatis jatuh balik ke tilt 2D (rotasi + skew) dan muncul Toast alasannya.
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
    private static final long  RESTORE_MS  = 300L;

    // ===== Perspektif 3D (trapesium) =====
    private static final boolean PERSPECTIVE = true;   // false = selalu pakai tilt 2D
    private static final float RX_BASE   = 20f;   // atas menyempit/melebar (balik tanda kalau kebalik)
    private static final float RX_BY_Y   = 4f;    // ikon bawah lebih miring, atas lebih landai
    private static final float RY_BY_X   = -12f;  // miring kiri-kanan menurut posisi ikon
    private static final float RY_CENTER = 0f;
    private static final float CAPTURE_SCALE = 0.75f;  // resolusi snapshot (kecil = capture lebih cepat, sedikit lebih buram)
    private static final float CAMERA_DIST = 1300f; // makin kecil = perspektif makin kuat
    private static final float CORNER_SMALL = 90f;      // radius sudut saat kecil (px lokal)
    private static final float CORNER_FULL  = 58f;      // radius sudut saat hampir full (samakan dgn sudut layar)
    private static final float CORNER_LOCAL_UNUSED = 0f;      // radius sudut jendela (px, ikut mengecil bersama jendela)
    private static final float WIN_ALPHA_MIN = 0.15f;   // jendela hampir transparan saat seukuran ikon (crossfade dengan ikon)
    private static final float WIN_FADE_IN = 0.12f;     // jendela penuh opak setelah progres ini

    // ===== Saat animasi dipotong =====
    private static final boolean SMOOTH_INTERRUPT = true;  // false = matikan semua penanganan potong
    private static final boolean REVERSE_MODE     = true;  // putar balik menyusuri jalur yang sama
    private static final float SETTLE_RATE = 14f;   // peredam cadangan (kalau tidak bisa putar balik)
    private static final long  GRACE_MS    = 400L;
    private static final long  JUMP_GAP_MS = 60L;
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
    private boolean lastValid, hasOffset, holdFull;
    private long lastMs = 0, t0 = 0;
    private float prevTsx, prevTsy, prevTtx, prevTty;
    private float prevDsx, prevDsy, prevDtx, prevDty, prevDprog;
    private float oSx, oSy, oTx, oTy;
    private Object primaryLeash;

    // putar balik
    private static final int PN = 64;
    private final boolean[] pValid = new boolean[PN + 1];
    private final float[][] pGeo = new float[PN + 1][8];   // sx, sy, tx, ty, cropL, cropT, cropR, cropB
    private int pCount = 0;
    private volatile boolean remap = false;
    private boolean remapClosing;
    private float remapP0;
    private volatile Rect remapCrop;

    // penghalusan tilt
    private float sDx, sDy, sLean, lastCx;

    // perspektif
    private Bitmap snap;
    private float cornerPx = CORNER_SMALL;
    private boolean snapRetaken, animOpening, perspOff, toastShown;
    private int perspFails = 0;
    private String lastCaptureErr = "?";
    private ImageView overlay;                 // main thread
    private volatile boolean overlayReady = false;
    private final Runnable overlayRemoveRun = this::removeOverlay;

    // konten home (main thread)
    private static class Saved { View v; float alpha, sx, sy; }
    private Saved[] saved;
    private Handler mainH;
    private final Runnable restoreRun = this::restoreContent;

    private Handler main() {
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

        Rect rc = remapCrop;
        if (remap && rc != null) {                 // mode putar balik: pakai crop dari jalur
            Rect d = new Rect(rc);
            p.args[1] = d;
            lastDispCrop = d;
            return;
        }

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
            boolean naturalEnd = !remap && prevDprog >= 0.75f;   // buka hampir selesai -> frame full = akhir animasi
            boolean keep = SMOOTH_INTERRUPT && cont && !naturalEnd && (remap || prevDsx < 0.97f || !isSettled(now));
            if (!keep) { resetState(); return; }
        }
        if (sx <= MIN_SCALE || sy <= MIN_SCALE) return;

        if (!cont || dt >= NEW_LEASH_GAP_MS) primaryLeash = p.args[0];

        Rect rawCrop = (raw == null || raw.isEmpty()) ? new Rect(0, 0, (int) w, (int) h) : raw;
        float tauT = progOf(rawCrop, sx, w);     // progres target dari launcher (1 = full, 0 = ikon)

        // ---- deteksi animasi dipotong / diganti ----
        boolean jumped = false;
        if (cont) {
            float dScale = Math.abs(sx - prevTsx);
            float pcx = prevTtx + prevTsx * w / 2f, ncx = tx + sx * w / 2f;
            float pcy = prevTty + prevTsy * h / 2f, ncy = ty + sy * h / 2f;
            float dCen = (float) Math.hypot(ncx - pcx, ncy - pcy) / w;
            boolean jump = SMOOTH_INTERRUPT && (dt >= JUMP_GAP_MS || dScale > 0.12f || dCen > 0.25f);
            if (jump) {
                jumped = true;
                boolean done = false;
                float p0 = prevDprog;
                boolean closingNew = tauT > 0.85f, openingNew = tauT < 0.15f;
                if (REVERSE_MODE && pCount >= 6 && p0 > 0.03f && p0 < 0.97f && (closingNew || openingNew)) {
                    boolean okIcon = true;
                    if (openingNew) {                       // ikon harus sama dengan jalur yang direkam
                        float[] g0 = tableLookup(0f);
                        if (g0 == null) {
                            okIcon = false;
                        } else {
                            float gcx = g0[2] + ((g0[4] + g0[6]) / 2f) * g0[0];
                            float gcy = g0[3] + ((g0[5] + g0[7]) / 2f) * g0[1];
                            float tcx = tx + rawCrop.exactCenterX() * sx;
                            float tcy = ty + rawCrop.exactCenterY() * sy;
                            okIcon = Math.hypot(tcx - gcx, tcy - gcy) / w < 0.2f;
                        }
                    }
                    if (okIcon) {
                        remap = true;
                        remapClosing = closingNew;
                        remapP0 = p0;
                        hasOffset = false;
                        hasCropOffset = false;
                        pendingCropOffset = false;
                        done = true;
                        XposedBridge.log(TAG + "putar balik dari p=" + p0 + (closingNew ? " (tutup)" : " (buka)"));
                    }
                }
                if (!done) {
                    remap = false;
                    remapCrop = null;
                    oSx = prevDsx - sx;
                    oSy = prevDsy - sy;
                    oTx = prevDtx - tx;
                    oTy = prevDty - ty;
                    t0 = now;
                    hasOffset = true;
                    hasCropOffset = false;
                    pendingCropOffset = true;
                    XposedBridge.log(TAG + "interupsi (peralihan mulus): tampil sx=" + prevDsx + " -> target sx=" + sx);
                }
            }
        } else {
            hasOffset = false;
            hasCropOffset = false;
            pendingCropOffset = false;
            remap = false;
            remapCrop = null;
            tableClear();
        }

        // animasi tutup yang dimulai dari full screen setelah dipotong: beberapa frame awal masih full
        if (fullTarget && jumped) holdFull = true;
        else if (!fullTarget) holdFull = false;

        // ---- geometri yang ditampilkan ----
        float dsx = sx, dsy = sy, dtx = tx, dty = ty;
        Rect crop = null;
        float f = 0f;
        boolean remapped = false;

        if (remap) {
            float pp = remapClosing ? remapP0 * tauT : remapP0 + (1f - remapP0) * tauT;
            float[] g = tableLookup(pp);
            if (g != null) {
                dsx = g[0]; dsy = g[1]; dtx = g[2]; dty = g[3];
                crop = new Rect(Math.round(g[4]), Math.round(g[5]), Math.round(g[6]), Math.round(g[7]));
                if (crop.right > crop.left && crop.bottom > crop.top) {
                    remapCrop = new Rect(crop);
                    remapped = true;
                }
            }
            boolean ended = remapClosing ? tauT <= 0.01f : tauT >= 0.99f;
            if (!remapped || ended) {
                remap = false;
                remapCrop = null;
            }
        }
        if (!remapped) {
            f = factor(now);
            dsx = Math.max(MIN_SCALE, Math.min(1.5f, sx + oSx * f));
            dsy = Math.max(MIN_SCALE, Math.min(1.5f, sy + oSy * f));
            dtx = tx + oTx * f;
            dty = ty + oTy * f;
            crop = displayedCrop(raw, f, (int) w, (int) h);
        }

        float lcx = crop.exactCenterX(), lcy = crop.exactCenterY();
        float cx = dtx + lcx * dsx;
        float cy = dty + lcy * dsy;

        // progres tunggal dari yang BENAR-BENAR tampil (sumber sinkron jendela + ikon)
        float progress = progOf(crop, dsx, w);
        float env = smoothstep(1f - progress);   // tilt kuat saat kecil, hilang saat full

        // rekam jalur hanya dari frame normal (bukan hasil putar balik / peralihan)
        if (!remapped && !(hasOffset && f > 0.02f)) {
            tableRecord(progress, dsx, dsy, dtx, dty, crop);
        }

        if (ICON_ANIM) {
            final Activity act = launcherRef.get();
            if (act != null) {
                final float pp2 = progress;
                final int iw = (int) w, ih = (int) h;
                runMain(() -> applyContent(act, pp2, iw, ih));
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

        // ---- mode perspektif 3D: snapshot + overlay ----
        if (PERSPECTIVE && !perspOff) {
            if (fullTarget && !jumped && !holdFull && !(remap && remapClosing)) {
                // akhir animasi buka: jendela asli harus kelihatan lagi, overlay dibuang
                resetState();
                return;
            }
            final Activity actP = launcherRef.get();
            if (actP != null) {
                boolean newAnimFrame = !cont || (jumped && !remap);
                if (newAnimFrame) {
                    snap = capture(p.args[0]);
                    snapRetaken = false;
                    animOpening = progress < 0.5f;
                    if (snap == null) {
                        perspFails++;
                        reportOnce(actP, lastCaptureErr);
                        if (perspFails >= 3) { perspOff = true; XposedBridge.log(TAG + "perspektif dimatikan, pakai tilt 2D"); }
                    } else {
                        perspFails = 0;
                    }
                } else if (snap != null && !snapRetaken && animOpening && progress > 0.4f) {
                    snapRetaken = true;
                    Bitmap b2 = capture(p.args[0]);
                    if (b2 != null) snap = b2;
                }
                final Bitmap sb = snap;
                if (sb != null) {
                    final float rx = (RX_BASE + RX_BY_Y * sDy) * env;
                    final float ry = (RY_BY_X * sDx + RY_CENTER * (1f - Math.abs(sDx))) * env;
                    // iOS: jendela tetap berbentuk layar penuh (rasio dipertahankan), mengecil seragam ke ikon
                    final float fS = Math.max(0.04f, Math.min(1.05f, crop.width() * dsx / w));
                    final float fA = WIN_ALPHA_MIN + (1f - WIN_ALPHA_MIN) * smoothstep(clamp01(progress / WIN_FADE_IN));
                    final float fcx = cx, fcy = cy;
                    final int iw = (int) w, ih = (int) h;
                    final float dens = dm.density;
                    final float fFull = progress;
                    runMain(() -> updateOverlay(actP, sb, iw, ih, fcx, fcy, fS, fA, fFull, rx, ry, dens));
                    if (overlayReady) {
                        Matrix off = new Matrix();
                        off.setTranslate(-10000f, -10000f);   // jendela asli disembunyikan
                        p.args[1] = off;
                        prevTsx = sx; prevTsy = sy; prevTtx = tx; prevTty = ty;
                        prevDsx = dsx; prevDsy = dsy; prevDtx = dtx; prevDty = dty; prevDprog = progress;
                        lastValid = true;
                        lastMs = now;
                        return;
                    }
                }
            }
        }

        float wc = (1f - Math.abs(sDx)) * (1f - Math.abs(sDy));
        float rot   = (ROT_DEG_X * sDx + CENTER_ROT * wc) * env;
        float skewX = (SKEW_X_Y * sDy + CENTER_SKEW * wc + LEAN_GAIN * sLean) * env;
        float skewY = (SKEW_Y_X * sDx) * env;

        Matrix out;
        if (remapped || (hasOffset && f > 0.002f)) {
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
        prevDsx = dsx; prevDsy = dsy; prevDtx = dtx; prevDty = dty; prevDprog = progress;
        lastValid = true;
        lastMs = now;
    }

    // ---------- jalur gerak (progres -> geometri) ----------
    private static float progOf(Rect crop, float sx, float w) {
        return clamp01((crop.width() * sx / w - 0.15f) / 0.85f);   // 0 = seukuran ikon, 1 = full screen
    }

    private void tableClear() {
        Arrays.fill(pValid, false);
        pCount = 0;
    }

    private void tableRecord(float prog, float sx, float sy, float tx, float ty, Rect c) {
        int b = Math.round(clamp01(prog) * PN);
        if (!pValid[b]) { pValid[b] = true; pCount++; }
        float[] g = pGeo[b];
        g[0] = sx; g[1] = sy; g[2] = tx; g[3] = ty;
        g[4] = c.left; g[5] = c.top; g[6] = c.right; g[7] = c.bottom;
    }

    private float[] tableLookup(float pp) {
        float x = clamp01(pp) * PN;
        int b0 = (int) Math.floor(x);
        int b1 = Math.min(PN, b0 + 1);
        int lo = b0, hi = b1;
        while (lo >= 0 && !pValid[lo] && b0 - lo < 4) lo--;
        while (hi <= PN && !pValid[hi] && hi - b1 < 4) hi++;
        boolean okLo = lo >= 0 && pValid[lo];
        boolean okHi = hi <= PN && pValid[hi];
        if (!okLo && !okHi) return null;
        float[] out = new float[8];
        if (okLo && okHi) {
            float t = (hi == lo) ? 0f : clamp01((x - lo) / (hi - lo));
            for (int i = 0; i < 8; i++) out[i] = pGeo[lo][i] + (pGeo[hi][i] - pGeo[lo][i]) * t;
        } else {
            System.arraycopy(pGeo[okLo ? lo : hi], 0, out, 0, 8);
        }
        return out;
    }

    // ---------- peralihan mulus cadangan ----------
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
        holdFull = false;
        lastMs = 0;
        hasOffset = false;
        hasCropOffset = false;
        pendingCropOffset = false;
        remap = false;
        remapCrop = null;
        primaryLeash = null;
        curRaw = null;
        lastDispCrop = null;
        snap = null;
        if (overlay != null) runMain(this::removeOverlay);
    }

    private static boolean sameLeash(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return true;
        try {
            Object r = XposedHelpers.callMethod(a, "isSameSurface", b);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------- snapshot + overlay perspektif ----------
    private Bitmap capture(Object leash) {
        try {
            Class<?> sc = XposedHelpers.findClass("android.window.ScreenCapture", null);
            Class<?> bld = XposedHelpers.findClass("android.window.ScreenCapture$LayerCaptureArgs$Builder", null);
            long t0c = System.nanoTime();
            Object builder = XposedHelpers.newInstance(bld, leash);
            try { XposedHelpers.callMethod(builder, "setFrameScale", CAPTURE_SCALE); } catch (Throwable ignored) { }
            Object args = XposedHelpers.callMethod(builder, "build");
            Object buf = XposedHelpers.callStaticMethod(sc, "captureLayers", args);
            if (buf == null) { lastCaptureErr = "captureLayers null"; return null; }
            Bitmap bm = (Bitmap) XposedHelpers.callMethod(buf, "asBitmap");
            if (bm == null) { lastCaptureErr = "asBitmap null"; return null; }
            XposedBridge.log(TAG + "capture " + ((System.nanoTime() - t0c) / 1_000_000L) + "ms " + bm.getWidth() + "x" + bm.getHeight());
            return bm;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            String m = c.getClass().getSimpleName() + ": " + c.getMessage();
            lastCaptureErr = m.length() > 90 ? m.substring(0, 90) : m;
            XposedBridge.log(TAG + "capture: " + t);
            return null;
        }
    }

    private void reportOnce(final Activity act, final String msg) {
        XposedBridge.log(TAG + "snapshot gagal: " + msg);
        if (toastShown) return;
        toastShown = true;
        runMain(() -> {
            try {
                Toast.makeText(act.getApplicationContext(), "TiltWindow snapshot gagal: " + msg,
                        Toast.LENGTH_LONG).show();
            } catch (Throwable ignored) { }
        });
    }

    private void updateOverlay(Activity act, Bitmap b, int w, int h, float cx, float cy,
                               float scale, float alpha, float full, float rx, float ry, float density) {
        try {
            if (overlay == null) {
                ImageView iv = new ImageView(act);
                iv.setScaleType(ImageView.ScaleType.FIT_XY);
                iv.setClipToOutline(true);
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override
                    public void getOutline(View view, Outline o) {
                        o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
                    }
                });
                iv.setCameraDistance(CAMERA_DIST * density);
                iv.setPivotX(w / 2f);
                iv.setPivotY(h / 2f);
                ViewGroup decor = (ViewGroup) act.getWindow().getDecorView();
                decor.addView(iv, new FrameLayout.LayoutParams(w, h));
                overlay = iv;
            }
            if (overlay.getTag() != b) {
                overlay.setImageBitmap(b);
                overlay.setTag(b);
            }
            overlay.setTranslationX(cx - w / 2f);   // pusat jendela mendarat di pusat area yang kelihatan
            overlay.setTranslationY(cy - h / 2f);
            overlay.setScaleX(scale);
            overlay.setScaleY(scale);
            overlay.setAlpha(alpha);
            overlay.setRotationX(rx);
            overlay.setRotationY(ry);
            cornerPx = CORNER_SMALL + (CORNER_FULL - CORNER_SMALL) * smoothstep(full);
            overlay.invalidateOutline();
            overlayReady = true;

            main().removeCallbacks(overlayRemoveRun);
            main().postDelayed(overlayRemoveRun, 120L);
        } catch (Throwable t) {
            overlayReady = false;
            perspFails++;
            XposedBridge.log(TAG + "overlay: " + t);
            if (perspFails >= 2) { perspOff = true; XposedBridge.log(TAG + "perspektif dimatikan, pakai tilt 2D"); }
        }
    }

    private void removeOverlay() {
        try {
            main().removeCallbacks(overlayRemoveRun);
            overlayReady = false;
            if (overlay != null) {
                ViewGroup parent = (ViewGroup) overlay.getParent();
                if (parent != null) parent.removeView(overlay);
                overlay = null;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "remove overlay: " + t);
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
                s.v.setVisibility(View.VISIBLE);
                s.v.setAlpha(1f - fade);
                s.v.setScaleX(zoom);
                s.v.setScaleY(zoom);
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
                    s.v.setAlpha(1f);
                    s.v.setScaleX(1f);
                    s.v.setScaleY(1f);
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
