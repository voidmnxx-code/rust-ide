#!@PREFIX@/bin/bash
# rustpad-export <project-dir> : cargo (release, cdylib) -> APK من القشرة الجاهزة -> توقيع محلي.
set -euo pipefail
PROJ="${1:?usage: rustpad-export <project-dir>}"
cd "$PROJ"
[ -f app.properties ] || { echo "ERROR: app.properties not found (create the project as 'Android app')"; exit 2; }
prop() { grep "^$1=" app.properties | head -1 | cut -d= -f2-; }
PKG=$(prop package); LABEL=$(prop label); VCODE=$(prop version_code); VNAME=$(prop version_name)
PERMS=$(prop permissions); SIGNING=$(prop signing); AUTOINC=$(prop auto_increment)
: "${PKG:?package missing}" "${LABEL:=App}" "${VCODE:=1}" "${VNAME:=1.0}"

SHARE="$PREFIX/share/rustpad"
ANDROID_JAR="$PREFIX/share/android/android.jar"
[ -f "$SHARE/shell/classes.dex" ] || { echo "ERROR: shell classes.dex missing (reinstall the app)"; exit 3; }
[ -f "$ANDROID_JAR" ] || { echo "ERROR: android.jar missing (apk-tools bootstrap stage)"; exit 3; }

echo "== [1/4] cargo build --release"
JOBS="${RUSTPAD_JOBS:-$(( $(nproc) / 2 ))}"; [ "$JOBS" -ge 1 ] || JOBS=1
cargo build --release --lib -j "$JOBS"
SO=$(ls target/release/lib*.so 2>/dev/null | head -1 || true)
[ -n "$SO" ] || { echo "ERROR: no .so produced. Cargo.toml needs [lib] crate-type = [\"cdylib\"]"; exit 4; }

echo "== [2/4] signing key"
mkdir -p "$HOME/.rustpad"
if [ "$SIGNING" = "release" ]; then
  KS="$HOME/.rustpad/release.jks"; PASSF="$HOME/.rustpad/release.pass"
  if [ ! -f "$KS" ]; then
    od -An -N16 -tx1 /dev/urandom | tr -d ' \n' > "$PASSF"; chmod 600 "$PASSF"
    keytool -genkeypair -keystore "$KS" -alias release -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$(cat "$PASSF")" -keypass "$(cat "$PASSF")" -dname "CN=$LABEL" >/dev/null 2>&1
    echo "created RELEASE key $KS (+ $PASSF). Back both up: updates must use the same key."
  fi
  KSPASS="$(cat "$PASSF")"
else
  KS="$HOME/.rustpad/rustpad.jks"; KSPASS=android
  if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias rustpad -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass android -keypass android -dname "CN=RustPad Local Key" >/dev/null 2>&1
    echo "created $KS (back it up: apps updated later must use the same key)"
  fi
fi

echo "== [3/4] assembling APK ($PKG)"
WORK=$(mktemp -d "$TMPDIR/export.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
esc() { printf '%s' "$1" | sed 's/&/\&amp;/g; s/</\&lt;/g; s/"/\&quot;/g'; }
USES=""
for p in $(echo "$PERMS" | tr ',' ' '); do
  case "$p" in *.*) ;; *) p="android.permission.$p";; esac
  USES="$USES    <uses-permission android:name=\"$p\" />
"
done
ICON=""; [ -f res/icon.png ] && ICON=res/icon.png || { [ -f res/logo.png ] && ICON=res/logo.png; } || true
ICONATTR=""
if [ -n "$ICON" ]; then
  mkdir -p "$WORK/res/mipmap-xxhdpi"; cp "$ICON" "$WORK/res/mipmap-xxhdpi/ic_launcher.png"
  aapt2 compile --dir "$WORK/res" -o "$WORK/res.zip"
  ICONATTR='android:icon="@mipmap/ic_launcher"'
fi
cat > "$WORK/AndroidManifest.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$PKG">
$USES    <application
        $ICONATTR
        android:label="$(esc "$LABEL")"
        android:extractNativeLibs="true"
        android:theme="@android:style/Theme.DeviceDefault.Light">
        <activity android:name="dev.rustpad.shell.MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
XML
RESZIP=""; [ -f "$WORK/res.zip" ] && RESZIP="$WORK/res.zip"
aapt2 link -o "$WORK/base.apk" -I "$ANDROID_JAR" $RESZIP --manifest "$WORK/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 --version-code "$VCODE" --version-name "$VNAME"
mkdir -p "$WORK/stage/lib/arm64-v8a"
cp "$SO" "$WORK/stage/lib/arm64-v8a/libapp.so"
cp "$SHARE"/shell/classes*.dex "$WORK/stage/"
( cd "$WORK/stage" && zip -q -r ../base.apk . )

echo "== [4/4] signing"
mkdir -p dist
OUT="$PWD/dist/$(basename "$PWD").apk"
rm -f "$OUT"
apksigner sign --ks "$KS" --ks-pass pass:"$KSPASS" --key-pass pass:"$KSPASS" --out "$OUT" "$WORK/base.apk"
apksigner verify "$OUT"
if [ -d "$HOME/storage/shared/Download" ]; then
  cp "$OUT" "$HOME/storage/shared/Download/" && echo "copied to Download/$(basename "$OUT")"
fi
if [ "$AUTOINC" = "true" ]; then
  sed -i "s/^version_code=.*/version_code=$((VCODE+1))/" app.properties
  echo "version_code -> $((VCODE+1)) for the next export"
fi
echo "APK: $OUT"
