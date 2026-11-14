# TiltModule

Modul LSPosed: animasi buka/tutup aplikasi miring (tilt) seperti iOS untuk XOS Launcher.

- Hook: `SurfaceControl.Transaction.setMatrix` di proses launcher, skew mengikuti progres animasi.
- Atur nilai di `TiltWindowHook.java` (`MAX_SKEW_X`, `MAX_SKEW_Y`).
- Scope: `com.transsion.XOSLauncher` (ubah di `res/values/arrays.xml` kalau beda).

## Build
Push ke GitHub, lalu buka tab Actions -> Build APK -> unduh artifact `TiltModule-debug`.
Aktifkan modul di LSPosed, centang XOS Launcher, lalu restart launcher.
