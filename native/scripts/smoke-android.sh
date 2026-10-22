#!/usr/bin/env bash
# Run only against the separate sandbox; no uninstall, clear-data, account login or sends.
set -euo pipefail
apk="${1:?APK path required}"
test_apk="${2:?Instrumentation APK path required}"
output="${3:-dist/smoke}"
: "${ANDROID_HOME:?Set ANDROID_HOME}"
adb="$ANDROID_HOME/platform-tools/adb"
aapt="$ANDROID_HOME/build-tools/35.0.0/aapt"
package=ir.electricconnect.tellegoli.sandbox
mkdir -p "$output"
"$aapt" dump badging "$apk" > "$output/APK-INFO.txt"
grep -Fq "package: name='$package'" "$output/APK-INFO.txt"
"$adb" get-state
"$adb" shell getprop ro.build.version.release > "$output/ANDROID-VERSION.txt"
"$adb" shell getprop ro.product.cpu.abi > "$output/ANDROID-ABI.txt"
"$adb" install -r "$apk" | tee "$output/INSTALL.txt"
"$adb" install -r "$test_apk" | tee "$output/INSTALL-TEST.txt"
"$adb" shell am instrument -w "$package.test/ir.electricconnect.nativefree.NativeSmokeInstrumentation" | tee "$output/INSTRUMENTATION.txt"
grep -Fq 'smoke_result=PASS' "$output/INSTRUMENTATION.txt"
"$adb" shell am force-stop "$package"
"$adb" install -r "$apk" | tee "$output/REINSTALL.txt"
"$adb" shell am instrument -w -e expectPersisted true "$package.test/ir.electricconnect.nativefree.NativeSmokeInstrumentation" | tee "$output/RESTART-INSTRUMENTATION.txt"
grep -Fq 'smoke_result=PASS' "$output/RESTART-INSTRUMENTATION.txt"
"$adb" exec-out run-as "$package" cat files/smoke-combined.xlsx > "$output/ANDROID-COMBINED.xlsx"
"$adb" exec-out run-as "$package" cat files/smoke-home.png > "$output/ANDROID-HOME.png"
"$adb" shell am force-stop "$package"
"$adb" shell am start -W -n "$package/ir.electricconnect.nativefree.MainActivity" | tee "$output/RELAUNCH.txt"
# Software emulators can exceed am start's first-frame timeout. Independently
# verify the resumed Activity instead of treating a command exit as a UI pass.
resumed=false
for attempt in {1..10}; do
  "$adb" shell dumpsys activity activities > "$output/RELAUNCH-STATE.txt"
  if grep -E 'mResumedActivity|topResumedActivity' "$output/RELAUNCH-STATE.txt" | grep -Fq "$package/ir.electricconnect.nativefree.MainActivity"; then
    resumed=true
    break
  fi
  sleep 2
done
test "$resumed" = true
"$adb" shell uiautomator dump /sdcard/telegram-connect-smoke.xml > "$output/UI-DUMP-STATUS.txt"
"$adb" exec-out cat /sdcard/telegram-connect-smoke.xml > "$output/UI.xml"
grep -Fq 'جستجوی خودکار وب هر ۱۵ دقیقه' "$output/UI.xml"
"$adb" exec-out screencap -p > "$output/ANDROID-HOME.png"
echo 'PASS: install, JNI, simplified automation dashboard, offline search, unapproved queue rejection, direct XLSX import/export, restart and reinstall persistence.'
