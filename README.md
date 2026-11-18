# RustPad (المرحلة 1)
1. غيّر `OWNER/REPO` في `app/src/main/java/dev/rustpad/BootstrapConfig.kt`.
2. شغّل workflow باسم Build APK من تبويب Actions.
3. نزّل RustPad-debug.apk من Releases (nightly) وثبّته.

## Bootstrap (حزم Termux)
شغّل workflow باسم **Build Bootstrap** (Actions ← Run workflow). ينشئ Release باسم `bootstrap-v1`
فيه ثلاثة أرشيفات: base وapk-tools وrust-analyzer. ثم ثبّت APK الجديد واضغط زر Bootstrap.
مهم: `applicationId` هو `dev.rustpd` (10 أحرف، بطول `com.termux`) كي تُعاد كتابة المسارات داخل الحزم.
