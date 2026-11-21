#!/bin/bash
# Builds the audio-only FFmpeg static libraries used by lplayer/lib-ffmpeg.
#
# Requirements: Android NDK 26.1.10909125 (or set NDK), CMake/ninja are not needed here,
# host gcc/make/pkg-config, and ~4 GB free disk per ABI.
#
# Usage:
#   ./tools/build_ffmpeg_android.sh                 # arm64-v8a + armeabi-v7a + x86_64
#   ABIS="arm64-v8a" ./tools/build_ffmpeg_android.sh
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
BUILD_ROOT="${BUILD_ROOT:-$ROOT/ffbuild}"
SRC="$BUILD_ROOT/ffmpeg-9.0.1"
NDK="${NDK:-${ANDROID_NDK_HOME:-$HOME/Android/Sdk/ndk/26.1.10909125}}"
TOOL="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
API=21
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64}"
VERSION=9.0.1

mkdir -p "$BUILD_ROOT"
if [[ ! -d "$SRC" ]]; then
    curl -L --retry 3 -o "$BUILD_ROOT/ffmpeg-$VERSION.tar.gz" "https://ffmpeg.org/releases/ffmpeg-$VERSION.tar.gz"
    tar xzf "$BUILD_ROOT/ffmpeg-$VERSION.tar.gz" -C "$BUILD_ROOT"
fi

DECODERS="aac,ac3,eac3,alac,ape,dca,flac,mp3,opus,vorbis,wavpack,wmav1,wmav2,wmapro,wmalossless,dsd_lsbf,dsd_msbf,dsd_lsbf_planar,dsd_msbf_planar,dst,tta,tak,shorten,truehd,mlp,pcm_u8,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be,pcm_alaw,pcm_mulaw,pcm_s16le_planar,pcm_s24le_planar,pcm_s32le_planar,pcm_f32le_planar"
DEMUXERS="aiff,ape,asf,dsf,mov,matroska,flac,mp3,ogg,wav,w64,wv,tta,tak,aac,ac3,amr,au,caf,mpegts,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_s32be,pcm_f32le,pcm_f32be"
PARSERS="aac,aac_latm,ac3,flac,mpegaudio,opus,vorbis"

build() {
    local ABI="$1" ARCH="$2" CPU="$3" TARGET="$4"
    local OUT="$BUILD_ROOT/out/$ABI"
    cd "$SRC"
    make distclean >/dev/null 2>&1 || true
    ./configure \
        --prefix="$OUT" --target-os=android --arch="$ARCH" --cpu="$CPU" \
        --enable-cross-compile --sysroot="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot" \
        --cc="$TOOL/${TARGET}${API}-clang" --cxx="$TOOL/${TARGET}${API}-clang++" \
        --ar="$TOOL/llvm-ar" --ranlib="$TOOL/llvm-ranlib" --strip="$TOOL/llvm-strip" --nm="$TOOL/llvm-nm" \
        --enable-static --disable-shared --enable-pic --disable-asm --disable-doc --disable-programs \
        --disable-everything --enable-avformat --enable-swresample --enable-avcodec --enable-avutil \
        --disable-network --disable-avdevice --disable-avfilter --disable-swscale \
        --disable-debug --disable-symver --disable-vulkan --disable-autodetect \
        --enable-decoder="$DECODERS" --enable-demuxer="$DEMUXERS" --enable-parser="$PARSERS" \
        --enable-protocol=file,pipe,filedescriptor \
        --extra-cflags="-O3 -fPIC -D__ANDROID_API__=${API}" \
        --extra-ldflags="-Wl,-z,max-page-size=16384"
    make -j"$(nproc)"
    make install
}

for ABI in $ABIS; do
    case "$ABI" in
        arm64-v8a) build "$ABI" aarch64 armv8-a aarch64-linux-android ;;
        armeabi-v7a) build "$ABI" arm armv7-a armv7a-linux-androideabi ;;
        x86_64) build "$ABI" x86_64 x86-64 x86_64-linux-android ;;
        *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
    esac
done

cat <<MSG

FFmpeg static libraries are in:
  $BUILD_ROOT/out/<abi>
To rebuild libmedia3ext.so for an ABI:
  cmake -S lplayer/lib-ffmpeg/src/main/cpp -B build-$ABI -G Ninja \\
    -DCMAKE_TOOLCHAIN_FILE=\$NDK/build/cmake/android.toolchain.cmake \\
    -DANDROID_ABI=<abi> -DANDROID_PLATFORM=21 \\
    -DFFMPEG_ROOT=$BUILD_ROOT/out/<abi>
  cmake --build build-$ABI
MSG
