#!/usr/bin/env bash
# Cross-builds a minimal FFmpeg (libavformat/libavcodec/libavutil only,
# MPEG-TS muxer+demuxer + file protocol) for Android arm64-v8a, used by
# ts_bridge (JNI) to mux/demux MPEG-TS carrying MediaCodec-encoded H.264 --
# the Android equivalent of the iOS port's ffmpeg.xcframework dependency
# (see ios/Scripts/build_ffmpeg_xcframework.sh in this repo, and
# ios/Shonan/TS/ffmpeg_ts_bridge.h for the C API this stack backs).
#
# No encoders/decoders are built: encoding/decoding happens on-device via
# MediaCodec, and we only ever mux/demux already-encoded H.264 Annex-B
# access units into/out of MPEG-TS.
#
# Another related project's third_party/build_ffmpeg.sh builds a
# *different* FFmpeg configuration (RTMP protocol + FLV muxer only, no
# mpegts) for its RTMP-push use case; that artifact cannot be reused here.
set -euo pipefail

NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/27.3.13750724}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$HERE/.build-tmp"
INSTALL="$HERE/ffmpeg-mpegts-install-arm64-v8a"

mkdir -p "$WORK"
cd "$WORK"

[ -d ffmpeg ] || git clone --depth 1 --branch n7.1 https://github.com/FFmpeg/FFmpeg.git ffmpeg
cd ffmpeg

./configure \
  --prefix="$INSTALL" \
  --target-os=android \
  --arch=aarch64 \
  --cpu=armv8-a \
  --enable-cross-compile \
  --cross-prefix="$TOOLCHAIN/bin/llvm-" \
  --cc="$TOOLCHAIN/bin/aarch64-linux-android26-clang" \
  --cxx="$TOOLCHAIN/bin/aarch64-linux-android26-clang++" \
  --ar="$TOOLCHAIN/bin/llvm-ar" \
  --ranlib="$TOOLCHAIN/bin/llvm-ranlib" \
  --strip="$TOOLCHAIN/bin/llvm-strip" \
  --nm="$TOOLCHAIN/bin/llvm-nm" \
  --sysroot="$TOOLCHAIN/sysroot" \
  --extra-cflags="-fPIC" \
  --enable-pic \
  --disable-shared \
  --enable-static \
  --disable-everything \
  --enable-avformat \
  --enable-avcodec \
  --enable-avutil \
  --enable-muxer=mpegts \
  --enable-demuxer=mpegts \
  --enable-protocol=file \
  --enable-bsf=h264_mp4toannexb \
  --enable-parser=h264 \
  --disable-network \
  --disable-programs \
  --disable-doc \
  --disable-avdevice \
  --disable-swscale \
  --disable-swresample \
  --disable-postproc \
  --disable-avfilter \
  --disable-symver \
  --disable-asm

make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc)"
make install

echo "Done: $INSTALL"
