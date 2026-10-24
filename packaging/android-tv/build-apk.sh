#!/usr/bin/env bash
set -euo pipefail

# Current file: builds a small WebView-based Android TV APK without downloading a Gradle project.
project_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
build_tools_version="${ANDROID_BUILD_TOOLS_VERSION:-36.0.0}"
platform_version="${ANDROID_PLATFORM_VERSION:-36}"
build_tools="$sdk_root/build-tools/$build_tools_version"
android_jar="$sdk_root/platforms/android-$platform_version/android.jar"
build_dir="$project_dir/build"
source_dir="$project_dir/src/main"
output_apk="${FOLIA_TV_APK_OUTPUT:-$build_dir/FoliaTV.apk}"
keystore="${FOLIA_TV_KEYSTORE:-$project_dir/.signing/debug.keystore}"
keystore_password="${FOLIA_TV_KEYSTORE_PASSWORD:-foliatvdebug}"

case "$build_dir" in
    "$project_dir"/build) ;;
    *)
        echo "Refusing to clean unexpected build directory: $build_dir" >&2
        exit 1
        ;;
esac

for tool in "$build_tools/aapt2" "$build_tools/d8" "$build_tools/zipalign" "$build_tools/apksigner" "$android_jar"; do
    if [[ ! -e "$tool" ]]; then
        echo "Missing Android build dependency: $tool" >&2
        exit 1
    fi
done

rm -rf "$build_dir"
mkdir -p "$build_dir/classes" "$build_dir/dex" "$build_dir/gen"

"$build_tools/aapt2" compile \
    --dir "$source_dir/res" \
    -o "$build_dir/resources.zip"

"$build_tools/aapt2" link \
    -o "$build_dir/app-unsigned.apk" \
    -I "$android_jar" \
    --manifest "$project_dir/AndroidManifest.xml" \
    --java "$build_dir/gen" \
    --auto-add-overlay \
    -A "$source_dir/assets" \
    "$build_dir/resources.zip"

mapfile -t java_sources < <(find "$source_dir/java" "$build_dir/gen" -name '*.java' -type f | sort)
javac \
    -encoding UTF-8 \
    -source 8 \
    -target 8 \
    -Xlint:-options \
    -bootclasspath "$android_jar" \
    -d "$build_dir/classes" \
    "${java_sources[@]}"

mapfile -t class_files < <(find "$build_dir/classes" -name '*.class' -type f | sort)
"$build_tools/d8" \
    --min-api 23 \
    --lib "$android_jar" \
    --output "$build_dir/dex" \
    "${class_files[@]}"

cp "$build_dir/app-unsigned.apk" "$build_dir/app-with-dex.apk"
(
    cd "$build_dir/dex"
    zip -q "$build_dir/app-with-dex.apk" classes.dex
)

"$build_tools/zipalign" -f 4 "$build_dir/app-with-dex.apk" "$build_dir/app-aligned.apk"

if [[ ! -f "$keystore" ]]; then
    mkdir -p "$(dirname -- "$keystore")"
    keytool -genkeypair \
        -keystore "$keystore" \
        -storepass "$keystore_password" \
        -keypass "$keystore_password" \
        -alias foliatv \
        -dname "CN=Folia TV Debug, OU=Folia, O=Folia, L=Local, ST=Local, C=CN" \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -noprompt >/dev/null
fi

mkdir -p "$(dirname -- "$output_apk")"
"$build_tools/apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias foliatv \
    --ks-pass "pass:$keystore_password" \
    --key-pass "pass:$keystore_password" \
    --out "$output_apk" \
    "$build_dir/app-aligned.apk"

"$build_tools/apksigner" verify --verbose "$output_apk"
printf 'Built %s\n' "$output_apk"
