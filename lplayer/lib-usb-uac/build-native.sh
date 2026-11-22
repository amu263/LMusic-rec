#!/usr/bin/env bash
set -euo pipefail
module="$(cd "$(dirname "$0")" && pwd)"
output="$(realpath -m "${1:-$module/src/main/jniLibs}")"
work="$(realpath -m "${2:-$module/build/manual/native}")"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ndk="${ANDROID_NDK_HOME:-$sdk/ndk/26.1.10909125}"
cmake="$sdk/cmake/3.22.1/bin/cmake"
commit=d52e355daa09f17ce64819122cb067b8a2ee0d4b

mkdir -p "$work"
if [[ ! -d "$work/libusb/.git" ]]; then
    git clone --depth 1 --branch v1.0.27 https://github.com/libusb/libusb.git "$work/libusb"
fi
test "$(git -C "$work/libusb" rev-parse HEAD)" = "$commit"
abi=arm64-v8a
"$cmake" -S "$module/src/main/cpp" -B "$work/$abi" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$sdk/cmake/3.22.1/bin/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release -DLIBUSB_SOURCE="$work/libusb"
"$cmake" --build "$work/$abi"
install -Dm755 "$work/$abi/libusbuac.so" "$output/$abi/libusbuac.so"
"$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$output/$abi/libusbuac.so"
