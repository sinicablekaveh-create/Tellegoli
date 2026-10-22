#!/usr/bin/env bash
set -euo pipefail
apk="${1:-native/app/build/outputs/apk/debug/app-debug.apk}"
output="${2:-dist}"
variant="${3:-normal}"
case "$variant" in
  normal) package=ir.electricconnect.tellegoli; version=0.10.2; filename=Tellegoli_0.10.2.apk ;;
  sandbox) package=ir.electricconnect.tellegoli.sandbox; version=0.10.2-sandbox; filename=Tellegoli_0.10.2_Sandbox.apk ;;
  *) echo 'Expected variant: normal or sandbox' >&2; exit 1 ;;
esac
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
mkdir -p "$output"
apksigner="$ANDROID_HOME/build-tools/35.0.0/apksigner"
aapt="$ANDROID_HOME/build-tools/35.0.0/aapt"
"$aapt" dump badging "$apk" > "$output/APK-INFO.txt"
"$apksigner" verify --verbose --print-certs "$apk" > "$output/SIGNATURE.txt"
python3 - "$apk" "$output/APK-INFO.txt" "$package" "$version" <<'PY'
import sys,zipfile
info=open(sys.argv[2]).read()
assert f"package: name='{sys.argv[3]}' versionCode='21' versionName='{sys.argv[4]}'" in info
with zipfile.ZipFile(sys.argv[1]) as z:
    for abi in ('arm64-v8a','armeabi-v7a'):
        name=f'lib/{abi}/libtdjni.so'
        assert len(z.read(name))>1_000_000, name
    assert 'AndroidManifest.xml' in z.namelist()
print('Verified package, version, both ARM JNI libraries and APK signature.')
PY
cp "$apk" "$output/$filename"
(cd "$output" && if command -v sha256sum >/dev/null 2>&1; then sha256sum "$filename"; else shasum -a 256 "$filename"; fi > SHA256SUMS.txt)
git rev-parse HEAD > "$output/SOURCE-COMMIT.txt"
git diff --binary HEAD -- native .github > "$output/SOURCE-CHANGES.patch"
git ls-files --others --exclude-standard > "$output/UNTRACKED-SOURCE.txt"
if [ -n "${EC_SIGNING_STORE:-}" ]; then
  echo 'Configured repeatable signing key. Compare certificate with the installed APK before upgrading.' > "$output/UPGRADE.txt"
else
  echo 'TEST APK: debug signing. In-place upgrade of an existing installation has not been verified. Keep the installed app and its data. The sandbox uses a separate application ID. See native/DELIVERY_0.10.2_FA.md.' > "$output/UPGRADE.txt"
fi
