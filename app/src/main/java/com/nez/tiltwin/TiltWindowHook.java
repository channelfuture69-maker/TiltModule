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
 * Modul beda: yang dimiringkan adalah JENDELA APLIKASI itu sendiri.
 *
 * Cara: hook SurfaceControl.Transaction.setMatrix(SurfaceControl, Matrix, float[])
 * di proses launcher. Saat animasi buka/tutup, launcher mengirim matrix scale+translate
 * ke leash jendela app. Di sini matrix itu disalin lalu ditambah skew yang besarnya
 * mengikuti progres (makin kecil jendela, makin miring; full screen = tidak miring).
 *
 * Batasan: matrix SurfaceControl cuma 2D, jadi ini skew (pseudo-3D), bukan rotasi 3D asli.
 *
 * Daftarkan di assets/xposed_init:  com.nez.tiltwin.TiltWindowHook
 * Scope LSPosed: XOS Launcher (com.transsion.launcher3)
 */
public class TiltWindowHook implements IXposedHookLoadPackage {

    private static final String TAG = "[TiltWindow] ";
    private static final float MAX_SKEW_X = 0.10f;   // geser horizontal per tinggi (0 = mati)
    private static final float MAX_SKEW_Y = 0.00f;   // geser vertikal per lebar
    private static final float MIN_SCALE = 0.05f;    // abaikan leash yang terlalu kecil
    private static final float FULL_SCALE_RANGE = 0.85f; // rentang scale 1.0 -> ~0.15

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        XposedBridge.log(TAG + "loaded in " + lpp.packageName);
        if (!lpp.packageName.equals("com.transsion.launcher3")
                && !lpp.packageName.equals("com.android.launcher3")) return;

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
                        // hanya sentuh leash yang sedang di-scale (animasi buka/tutup)
                        if (sx >= 0.995f || sx <= MIN_SCALE || sy <= MIN_SCALE) return;

                        float progress = Math.min(1f, (1f - sx) / FULL_SCALE_RANGE);

                        DisplayMetrics dm = Resources.getSystem().getDisplayMetrics();
                        float cx = v[Matrix.MTRANS_X] + dm.widthPixels * sx / 2f;
                        float cy = v[Matrix.MTRANS_Y] + dm.heightPixels * sy / 2f;

                        Matrix out = new Matrix(src);   // salin, jangan ubah matrix asli launcher
                        out.postSkew(MAX_SKEW_X * progress, MAX_SKEW_Y * progress, cx, cy);
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
}
