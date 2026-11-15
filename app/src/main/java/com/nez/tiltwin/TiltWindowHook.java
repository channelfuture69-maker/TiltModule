package com.nez.tiltwin;

import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.util.DisplayMetrics;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Tilt animasi buka/tutup app ala iOS, arah miringnya mengikuti posisi ikon
 * (kanan, kiri, tengah, bawah/atas beda). Tanpa snapshot/overlay: yang diubah hanya
 * matrix jendela, jadi aman dan tidak ada view tambahan.
 *
 * Supaya gerakannya fluid:
 *  - arah miring (posisi ikon) dihaluskan antar frame (low-pass), tidak patah-patah
 *  - besar miring mengikuti kurva smoothstep terhadap progres animasi, jadi
 *    masuk pelan, kuat di awal, lalu mengendur halus sampai full screen
 *  - ada sedikit "lean" mengikuti arah gerak jendela
 *  - pivot memakai pusat area jendela yang kelihatan (crop), bukan pusat layar
 *
 * Batasan: matrix SurfaceControl cuma 2D, jadi efeknya rotasi + skew, bukan perspektif 3D asli.
 */
public class TiltWindowHook implements IXposedHookLoadPackage {

    private static final String TAG = "[TiltWindow] ";

    // ===== Atur di sini (tanda +/- membalik arah) =====
    private static final float ROT_DEG_X   = 6f;    // rotasi (derajat): ikon kanan = +, kiri = -
    private static final float SKEW_Y_X    = 0.10f; // geser vertikal: kanan vs kiri
    private static final float SKEW_X_Y    = 0.10f; // geser horizontal: bawah vs atas
    private static final float CENTER_ROT  = 2f;    // tilt halus kalau ikon di tengah
    private static final float CENTER_SKEW = 0.03f;
    private static final float LEAN_GAIN   = 0.03f; // miring ikut arah gerak jendela (0 = mati)
    private static final float AMP         = 1.8f;  // penguat posisi (ikon pinggir -> tilt penuh)
    private static final float SMOOTH_MS   = 45f;   // makin besar = makin lembut/lambat mengikuti

    private static final float MIN_SCALE = 0.05f;
    private static final long  NEW_ANIM_GAP_MS = 250L;

    private volatile Rect curCrop;

    // state penghalusan (dipakai di thread hook)
    private long lastMs = 0;
    private float sDx, sDy, sLean, lastCx;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!lpp.packageName.equals("com.transsion.launcher3")
                && !lpp.packageName.equals("com.android.launcher3")) return;

        XposedBridge.log(TAG + "loaded in " + lpp.packageName);

        try {
            Class<?> tx = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", lpp.classLoader);

            // crop = area jendela yang kelihatan (launcher mengecilkannya jadi bentuk ikon)
            XposedBridge.hookAllMethods(tx, "setWindowCrop", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length == 2 && p.args[1] instanceof Rect) {
                            curCrop = new Rect((Rect) p.args[1]);
                        }
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

        // pusat area yang kelihatan, di koordinat lokal jendela dan di layar
        float lcx = crop.exactCenterX(), lcy = crop.exactCenterY();
        float cx = tx + lcx * sx;
        float cy = ty + lcy * sy;

        // progres 0 (ukuran ikon) .. 1 (full screen) dari lebar yang kelihatan
        float visW = crop.width() * sx;
        float progress = clamp01((1f - visW / w) / 0.85f);
        float env = smoothstep(progress);   // 0 -> 1 -> mulus; sekaligus 0 saat full screen

        // posisi relatif terhadap tengah layar, -1 .. +1
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
            float vx = (cx - lastCx) / d * 1000f;             // px/detik
            float lean = clamp(vx / (w * 4f));
            sLean += (lean - sLean) * a;
            lastCx = cx;
        }
        lastMs = now;

        float wc = (1f - Math.abs(sDx)) * (1f - Math.abs(sDy));   // bobot "tengah"
        float rot   = (ROT_DEG_X * sDx + CENTER_ROT * wc) * env;
        float skewX = (SKEW_X_Y * sDy + CENTER_SKEW * wc + LEAN_GAIN * sLean) * env;
        float skewY = (SKEW_Y_X * sDx) * env;

        Matrix out = new Matrix(src);          // salin, jangan ubah matrix asli launcher
        out.postRotate(rot, cx, cy);
        out.postSkew(skewX, skewY, cx, cy);
        p.args[1] = out;
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
