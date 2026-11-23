#!/usr/bin/env bash
set -euo pipefail

root=$(cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
: "${sdk:?Set ANDROID_SDK_ROOT to an Android SDK with platform 35 and build-tools 35.0.0}"
tools=${ANDROID_BUILD_TOOLS:-$sdk/build-tools/35.0.0}
platform=${ANDROID_PLATFORM_JAR:-$sdk/platforms/android-35/android.jar}
runtime=${HALCYON_RUNTIME_DIR:-$root/android/build/runtime}
output=${HALCYON_APK_OUTPUT:-$root/android/build/Halcyon.apk}
key=${HALCYON_KEYSTORE:-$root/android/build/local-signing.jks}
alias_name=${HALCYON_KEY_ALIAS:-halcyon-local}
app=$root/android/app/src/main

mkdir -p "$runtime" "$(dirname -- "$output")" "$(dirname -- "$key")" "$root/android/build"
stage=$(mktemp -d "${HALCYON_BUILD_TMP:-$root/android/build}/apk.XXXXXXXX")
trap 'python3 -c "import shutil, sys; shutil.rmtree(sys.argv[1])" "$stage"' EXIT
mkdir -p "$stage/assets" "$stage/dex" "$stage/classes" "$stage/generated" "$stage/downloads" "$stage/native"

for pair in arm64-v8a:arm64 x86_64:amd64; do
    abi=${pair%:*}
    architecture=${pair#*:}
    image=$runtime/runtime-$architecture.tar.gz
    if [[ ! -s "$image" ]] || ! tar -tzf "$image" > "$stage/runtime-files" ||
       ! rg -q '(^|/)opt/halcyon-venv/bin/python$' "$stage/runtime-files"; then
        builder=()
        if [[ -n ${HALCYON_BUILDX_BUILDER:-} ]]; then builder=(--builder "$HALCYON_BUILDX_BUILDER"); fi
        ca=()
        if [[ -n ${HALCYON_BUILD_CA:-} ]]; then ca=(--secret "id=pip_ca,src=$HALCYON_BUILD_CA"); fi
        docker buildx build "${builder[@]}" "${ca[@]}" --platform "linux/$architecture" \
            -f android/runtime.Dockerfile --output type=tar,dest=- . | gzip -1n > "$image.part"
        mv "$image.part" "$image"
    fi
    python3 android/pack_runtime.py "$image" "$stage/assets/ubuntu-$abi.tar.gz" \
        vendor/telegram/halcyon_tl/network/mtprotosender.py
done

while read -r abi package digest; do
    [[ $abi == \# || -z $abi ]] && continue
    archive=$stage/downloads/${package##*/}
    curl --fail --location --silent --show-error --retry 2 \
        "https://packages.termux.dev/apt/termux-main/$package" --output "$archive"
    printf '%s  %s\n' "$digest" "$archive" | sha256sum --check --status
    dpkg-deb -x "$archive" "$stage/native/$abi"
done < android/native-packages.txt

for abi in arm64-v8a x86_64; do
    prefix=$stage/native/$abi/data/data/com.termux/files/usr
    lib=$stage/lib/$abi
    mkdir -p "$lib"
    cp "$prefix/libexec/proot/loader" "$lib/libproot_loader.so"
    cp "$prefix/lib/libandroid-shmem.so" "$lib/libandroid-shmem.so"
    # Android extracts only lib*.so names. Keep offsets intact while adapting the ELF names.
    python3 - "$prefix" "$lib" <<'PY'
import sys
from pathlib import Path

prefix, output = map(Path, sys.argv[1:])
for source, target in ((prefix / 'bin/proot', 'libproot.so'),
                       (prefix / 'lib/libtalloc.so.2.5.0', 'libtalloc.so')):
    original = source.read_bytes()
    assert b'libtalloc.so.2\0' in original, source
    (output / target).write_bytes(original.replace(b'libtalloc.so.2\0', b'libtalloc.so\0\0\0'))
PY
done

tar --format=gnu --exclude='__pycache__' --exclude='*.pyc' --sort=name -cf - \
    halcyon assets README.md LICENSE | gzip -n > "$stage/assets/project.tar.gz"
sha256sum "$stage"/assets/*.gz | cut -d' ' -f1 | sha256sum | cut -c1-16 > "$stage/assets/build-id"

"$tools/aapt2" compile --dir "$app/res" -o "$stage/resources.zip"
"$tools/aapt2" link -I "$platform" --manifest "$app/AndroidManifest.xml" \
    --java "$stage/generated" -0 gz -A "$stage/assets" -o "$stage/base.apk" "$stage/resources.zip"
sources=()
while IFS= read -r -d '' source; do sources+=("$source"); done < <(find "$app/java" "$stage/generated" -name '*.java' -print0)
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -classpath "$platform" -d "$stage/classes" "${sources[@]}"
jar --create --file "$stage/classes.jar" -C "$stage/classes" .
"$tools/d8" --min-api 28 --lib "$platform" --output "$stage/dex" "$stage/classes.jar"
cp "$stage/dex/classes.dex" "$stage/classes.dex"
(cd "$stage" && zip -q -0 base.apk classes.dex && zip -q -r base.apk lib)
"$tools/zipalign" -P 16 -f 4 "$stage/base.apk" "$stage/aligned.apk"

if [[ ! -f "$key" ]]; then
    if [[ -n ${HALCYON_KEYSTORE:-} ]]; then
        printf 'Signing key does not exist: %s\n' "$key" >&2
        exit 1
    fi
    keytool -genkeypair -keystore "$key" -storepass android -keypass android \
        -alias "$alias_name" -dname 'CN=Halcyon Local Build' -keyalg RSA -keysize 3072 -validity 3650
fi
signing=(--ks "$key" --ks-key-alias "$alias_name")
if [[ -z ${HALCYON_KEYSTORE:-} ]]; then signing+=(--ks-pass pass:android --key-pass pass:android); fi
"$tools/apksigner" sign "${signing[@]}" --v4-signing-enabled false --out "$output" "$stage/aligned.apk"
"$tools/apksigner" verify --verbose "$output"
printf '%s\n' "$output"
