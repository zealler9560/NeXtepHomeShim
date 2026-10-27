#!/usr/bin/env bash
# Build NeXtepHomeShim.apk with plain JDK + Android SDK build-tools (no Gradle).
# Requirements: JDK 17, Android SDK platform android-35, build-tools 35.0.0.
# Signing: set KEYSTORE / KS_PASS / KEY_PASS, or place a keystore at
# ~/workspace/android-tools/muse-test.keystore (alias "muse").
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
AJAR="$ANDROID_HOME/platforms/android-35/android.jar"
BT="$ANDROID_HOME/build-tools/35.0.0"
KEYSTORE="${KEYSTORE:-$HOME/workspace/android-tools/muse-test.keystore}"
KS_PASS="${KS_PASS:-}"
KEY_PASS="${KEY_PASS:-}"

rm -rf build && mkdir -p build/classes build/stubclasses build/dex
# 1) Compile the Xposed API stubs SEPARATELY. They are compile-time only:
#    they must never be dexed into the APK - LSPosed refuses to load a
#    module that contains the API classes ("The Xposed API classes are
#    compiled into the module's APK").
javac -source 17 -target 17 -cp "$AJAR" -d build/stubclasses $(find stubs -name '*.java')
# 2) Compile the module against android.jar + stubs, dex ONLY module classes.
javac -source 17 -target 17 -cp "$AJAR:build/stubclasses" -d build/classes $(find src -name '*.java')
"$BT/d8" --lib "$AJAR" --output build/dex $(find build/classes -name '*.class')
cp build/dex/classes.dex build/classes.dex
# 3) Resources + manifest, package, align, sign.
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$AJAR" --manifest AndroidManifest.xml build/res.zip
(cd build && zip -q base.apk classes.dex)
zip -q build/base.apk assets/xposed_init
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KEY_PASS" \
  --out NeXtepHomeShim.apk build/aligned.apk
"$BT/apksigner" verify NeXtepHomeShim.apk
echo "Built NeXtepHomeShim.apk"
