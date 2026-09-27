#!/usr/bin/env bash
# Cross-builds gr-dvbs2rx (the on-device GNU Radio DVB-S2 RX pipeline used by
# RxController/Dvbs2rxPipeline when "オンデバイス受信" is enabled) for Android
# arm64-v8a, and installs the resulting libgnuradio-dvbs2rx.so into both
# gr-dvbs2rx-install-arm64-v8a/lib and app/src/main/jniLibs/arm64-v8a.
#
# Why this exists: upstream gr-dvbs2rx/lib/CMakeLists.txt unconditionally
# passes -march=native (NATIVE_OPTIMIZATIONS=ON by default). When cross-
# compiling on an Apple Silicon Mac host for an Android aarch64 *target*,
# clang's -march=native resolves against the HOST's CPU features (which
# include ARMv8.1 LSE atomics), not the target device's. That bakes a raw
# `ldaddal` instruction into every std::shared_ptr refcount release path
# (e.g. gr::dvbs2rx::rotator_cc::make -> ~shared_ptr), which SIGILLs on any
# Android device whose CPU lacks LSE (e.g. Cortex-A53-based chips such as
# Qualcomm "bengal" -- confirmed via /proc/cpuinfo missing the "atomics"
# feature flag). Building with -DNATIVE_OPTIMIZATIONS=OFF avoids the
# baked-in -march=native; -moutline-atomics additionally makes libc++'s
# atomic refcounting probe LSE support at process startup (via HWCAP) and
# fall back to a portable LDXR/STXR loop when unavailable, instead of
# assuming the target always has it.
#
# The Android port also needs a handful of source patches beyond that one
# flag (guarding the Python/pybind11 + Boost.Test-only paths that have no
# equivalent in this JNI-embedded build, an NDK API<28 aligned_alloc()
# fallback, and the 3 on-device SIGSEGV fixes in symbol_sync_cc_impl.cc /
# pl_freq_sync.h / plsync_cc_impl.cc already validated during RF loopback
# testing -- see gr_dvbs2rx_android_port.patch, applied below).
#
# Depends on the dependency SDKs already vendored in this directory
# (gnuradio-install-arm64-v8a, volk-install-arm64-v8a, gmp-install-arm64-v8a,
# spdlog-install-arm64-v8a, boost-install-arm64-v8a), which are themselves
# built once and checked in as prebuilt blobs -- same pattern as
# gr-dvbs2rx-install-arm64-v8a, dvbs2-deps-install-arm64-v8a, and
# ffmpeg-mpegts-install-arm64-v8a in this same third_party/. Rebuilding
# those from scratch (GNU Radio + VOLK + Boost + GMP + spdlog) is out of
# scope for this script.
set -euo pipefail

NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/27.3.13750724}"
ANDROID_PLATFORM=android-26
GR_DVBS2RX_COMMIT=130c31576cfeebb1a5842b24ccaf4a561b6a9990
NPROC="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$HERE/.build-tmp-gr-dvbs2rx"
GR="$HERE/gnuradio-install-arm64-v8a"
VOLK="$HERE/volk-install-arm64-v8a"
GMP="$HERE/gmp-install-arm64-v8a"
SPDLOG="$HERE/spdlog-install-arm64-v8a"
BOOST="$HERE/boost-install-arm64-v8a"
GR_DVBS2RX_INSTALL="$HERE/gr-dvbs2rx-install-arm64-v8a"
JNILIBS="$HERE/../app/src/main/jniLibs/arm64-v8a"

rm -rf "$WORK"
mkdir -p "$WORK"
git clone https://github.com/igorauad/gr-dvbs2rx.git "$WORK/src"
cd "$WORK/src"
git checkout -q "$GR_DVBS2RX_COMMIT"
git submodule update --init --recursive
git apply "$HERE/gr_dvbs2rx_android_port.patch"

mkdir -p "$WORK/src/build-android-arm64"
cd "$WORK/src/build-android-arm64"
cmake -G "Unix Makefiles" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$WORK/src/build-android-arm64/install" \
  -DBUILD_SHARED_LIBS=OFF \
  -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH \
  -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH \
  -DCMAKE_PREFIX_PATH="$GR;$SPDLOG;$VOLK" \
  -DBOOST_ROOT="$BOOST/include" \
  -DBoost_NO_SYSTEM_PATHS=ON \
  -DBoost_LIBRARY_DIR="$BOOST/lib" \
  -DGMP_INCLUDE_DIR="$GMP/include" \
  -DGMP_LIBRARY="$GMP/lib/libgmp.a" \
  -DGMPXX_LIBRARY="$GMP/lib/libgmpxx.a" \
  -DDEBUG_LOGS=ON \
  -DENABLE_DOXYGEN=OFF \
  -DENABLE_GRC=OFF \
  -DENABLE_MANPAGES=OFF \
  -DENABLE_PYTHON=OFF \
  -DENABLE_TESTING=OFF \
  -DNATIVE_OPTIMIZATIONS=OFF \
  -DCMAKE_CXX_FLAGS="-moutline-atomics" \
  -DCMAKE_C_FLAGS="-moutline-atomics" \
  ..
make -j"$NPROC"

cp lib/libgnuradio-dvbs2rx.so "$GR_DVBS2RX_INSTALL/lib/libgnuradio-dvbs2rx.so"
cp lib/libgnuradio-dvbs2rx.so "$JNILIBS/libgnuradio-dvbs2rx.so"
echo "Installed: $GR_DVBS2RX_INSTALL/lib/libgnuradio-dvbs2rx.so"
echo "Installed: $JNILIBS/libgnuradio-dvbs2rx.so"
