package com.nez.tiltwin;

import android.content.res.Resources;
import android.graphics.Matrix;
import android.util.DisplayMetrics;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Tilt jendela aplikasi yang arahnya mengikuti posisi ikon yang diketuk:
 * kanan, kiri, tengah, dan bawah/atas masing-masing miring beda.
 *
 * Posisi ikon dibaca dari pusat jendela saat animasi (di awal animasi pusat jendela
 * = posisi ikon). Miringnya hilang pelan-pelan sampai full screen.
 *
 * Batasan: SurfaceControl cuma menerima matrix 2D (rotasi + skew), tanpa perspektif.
 */
public class TiltWindowHook implements IXposedHookLoadPackage {

    private static final String TAG = "[TiltWindow] ";

    // ===== Atur di sini (tanda +/- membalik arah) =====
    private static final float ROT_DEG_X   = 6f;    // rotasi (derajat): ikon kanan = +, kiri = -
    private static final float SKEW_Y_X    = 0.10f; // geser vertikal: kanan vs kiri
    private static final float SKEW_X_Y    = 0.10f; // geser horizontal: bawah vs atas
    private static final float CENTER_ROT  = 2f;    // tilt halus kalau ikon di tengah
    private static final float CENTER_SKEW = 0.03f;
    private static final float AMP         = 1.8f;  // penguat posisi (ikon pinggir -> tilt penuh)

    private static final float MIN_SCALE = 0.05f;
    private static final float SCALE_RANGE = 0.85f;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!lpp.packageName.equals("com.transsion.launcher3")
                && !lpp.packageName.equals("com.android.launcher3")) return;

        XposedBridge.log(TAG + "loaded in " + lpp.packageName);

        try {
            Class<?> tx = XposedHelpers.findClass("android.view.SurfaceControl$Transaction", lpp.classLoader);
            XposedBridge.hookAllMethods(tx, "setMatrix", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (p.args.length != 3 || !(p.args[1] instanceof Matrix)) return;

                        Matrix src = (Matrix) p.args[1];
                        float[] v = new float[9];
                        src.getValues(v);
                        float sx = v[Matrix.MSCALE_X];
                        float sy = v[Matrix.MSCALE_Y];
                        if (sx >= 0.995f || sx <= MIN_SCALE || sy <= MIN_SCALE) return;

                        float progress = Math.min(1f, (1f - sx) / SCALE_RANGE);

                        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
                        float w = dm.widthPixels, h = dm.heightPixels;
                        float cx = v[Matrix.MTRANS_X] + w * sx / 2f;
                        float cy = v[Matrix.MTRANS_Y] + h * sy / 2f;

                        // posisi relatif terhadap tengah layar, -1 (kiri/atas) .. +1 (kanan/bawah)
                        float dx = clamp((cx - w / 2f) / (w / 2f) * AMP);
                        float dy = clamp((cy - h / 2f) / (h / 2f) * AMP);
                        // bobot "tengah": 1 di tengah, 0 di pinggir
                        float wc = (1f - Math.abs(dx)) * (1f - Math.abs(dy));

                        float rot   = (ROT_DEG_X * dx + CENTER_ROT * wc) * progress;
                        float skewX = (SKEW_X_Y * dy + CENTER_SKEW * wc) * progress;
                        float skewY = (SKEW_Y_X * dx) * progress;

                        Matrix out = new Matrix(src);   // salin, jangan ubah matrix asli launcher
                        out.postRotate(rot, cx, cy);
                        out.postSkew(skewX, skewY, cx, cy);
                        p.args[1] = out;
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

    private static float clamp(float f) {
        return Math.max(-1f, Math.min(1f, f));
    }
}
