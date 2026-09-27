#!/usr/bin/env bash
# Cross-builds the dependency stack used by the "機器試験" (equipment diagnostic)
# feature to talk to Pluto directly over libiio and modulate/demodulate DVB-S2
# on-device via aff3ct/dvbs2 -- the Android equivalent of the iOS port's
# DVBS2Vendor/LibiioVendor static libraries (see
# ios/Shonan/DVBS2/LibiioSession.swift and
# Shonan-ipad/DVBS2_iOS_Port_PoC.md sections 1-9 for the original iOS
# cross-build recipe this script mirrors for Android NDK/arm64-v8a).
#
# This is a *separate* dependency stack from third_party/build_ffmpeg_mpegts.sh
# (used by the normal Tx/Rx UDP-TS screens) -- the diagnostic feature does not
# go through UDP TS at all, it drives Pluto's AD9361 RF chain directly.
#
# Pinned commits (matching the versions validated on iOS, plus whatever HEAD
# was current when this Android port was validated):
#   zstd     v1.5.7   f8745da6ff1ad1e7bab384bd1f9d742439278e99
#   libxml2  v2.12.9  00301f0fe8bccdb9945fb684e9bbd72449b961a5
#   libiio   (HEAD)   cd0049bd3908b7e54f5d73e8ef2bf8ac7d4d9a4a
#   aff3ct/dvbs2 (HEAD) e029b0928ed302235b77ad84eb15e9b87729b9fc
set -euo pipefail

NDK="${ANDROID_NDK:-$HOME/Library/Android/sdk/ndk/27.3.13750724}"
TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
STRIP="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-strip"
ADDR2LINE="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-addr2line"
ANDROID_PLATFORM=android-26
NPROC="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$HERE/.build-tmp-dvbs2"
INSTALL="$HERE/dvbs2-deps-install-arm64-v8a"

ZSTD_COMMIT=f8745da6ff1ad1e7bab384bd1f9d742439278e99
LIBXML2_COMMIT=00301f0fe8bccdb9945fb684e9bbd72449b961a5
LIBIIO_COMMIT=cd0049bd3908b7e54f5d73e8ef2bf8ac7d4d9a4a
DVBS2_COMMIT=e029b0928ed302235b77ad84eb15e9b87729b9fc

mkdir -p "$WORK"
cd "$WORK"

cmake_android_common=(
  -G"Unix Makefiles"
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM="$ANDROID_PLATFORM"
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_INSTALL_PREFIX="$INSTALL"
  -DBUILD_SHARED_LIBS=OFF
)

# 1. zstd -- required by libiio's IIOD_CLIENT (compressed metadata) feature.
[ -d zstd ] || git clone https://github.com/facebook/zstd.git
(cd zstd && git checkout -q "$ZSTD_COMMIT")
rm -rf zstd/build/cmake/build-android
mkdir -p zstd/build/cmake/build-android && cd zstd/build/cmake/build-android
cmake .. "${cmake_android_common[@]}" \
  -DZSTD_BUILD_SHARED=OFF -DZSTD_BUILD_STATIC=ON \
  -DZSTD_BUILD_PROGRAMS=OFF -DZSTD_BUILD_TESTS=OFF
make -j"$NPROC"
make install
cd "$WORK"

# 2. libxml2 -- required by libiio's XML backend (device context descriptions).
# Not bundled with the Android NDK (unlike iOS SDK's libxml2.tbd), so it must
# be built from source.
[ -d libxml2 ] || git clone https://github.com/GNOME/libxml2.git
(cd libxml2 && git checkout -q "$LIBXML2_COMMIT")
rm -rf libxml2/build-android
mkdir -p libxml2/build-android && cd libxml2/build-android
cmake .. "${cmake_android_common[@]}" \
  -DLIBXML2_WITH_PYTHON=OFF -DLIBXML2_WITH_ICONV=OFF -DLIBXML2_WITH_LZMA=OFF \
  -DLIBXML2_WITH_ZLIB=OFF -DLIBXML2_WITH_TESTS=OFF -DLIBXML2_WITH_PROGRAMS=OFF
make -j"$NPROC"
make install
rm -rf "$INSTALL/share"
cd "$WORK"

# 3. libiio -- talks to Pluto's iiod over the network backend only (no
# USB/serial/local backends needed; we always connect to Pluto over Wi-Fi).
[ -d libiio ] || git clone https://github.com/analogdevicesinc/libiio.git
(cd libiio && git checkout -q "$LIBIIO_COMMIT")

# iiod_client_refresh_format() dereferences client->responder without checking
# iiod_client_uses_binary_interface() first (every other function in this file does).
# When the binary interface isn't in use, this is a NULL pointer read that crashes with
# SIGSEGV inside LibiioSession::connect() (same bug the iOS port hit and patched, see
# DVBS2_iOS_Port_PoC.md section 12.1). Applied idempotently.
if ! grep -A5 "^int iiod_client_refresh_format" libiio/iiod-client.c | grep -q "iiod_client_uses_binary_interface"; then
  patch -p1 -d libiio < "$HERE/libiio_refresh_format_null_guard.patch"
fi

rm -rf libiio/build-android
mkdir -p libiio/build-android && cd libiio/build-android
cmake .. "${cmake_android_common[@]}" \
  -DWITH_USB_BACKEND=OFF -DWITH_SERIAL_BACKEND=OFF -DWITH_LOCAL_BACKEND=OFF \
  -DWITH_NETWORK_BACKEND=ON -DWITH_IIOD=OFF -DWITH_ZSTD=ON -DHAVE_DNS_SD=OFF \
  -DCPP_BINDINGS=OFF -DWITH_UTILS=OFF \
  -DLIBZSTD_LIBRARIES="$INSTALL/lib/libzstd.a" -DLIBZSTD_INCLUDE_DIR="$INSTALL/include" \
  -DLIBXML2_LIBRARIES="$INSTALL/lib/libxml2.a" -DLIBXML2_INCLUDE_DIR="$INSTALL/include/libxml2"
make -j"$NPROC"
make install
# libiiod-responder.a (iiod-client.cが依存するiiod_io_*シンボルを含む別の静的ライブラリ)
# はinstallターゲットに含まれないため手動でコピーする(iOS版DVBS2_iOS_Port_PoC.md
# section 11で同じ問題に遭遇している)。
cp libiiod-responder.a "$INSTALL/lib/"
cd "$WORK"

# 4. aff3ct/dvbs2 -- the DVB-S2 modulator/demodulator itself. No install
# target, so artifacts are copied by hand (same approach the iOS port used,
# see DVBS2_iOS_Port_PoC.md section 5).
[ -d dvbs2 ] || git clone --recursive https://github.com/aff3ct/dvbs2.git
(cd dvbs2 && git checkout -q "$DVBS2_COMMIT")
for i in 1 2 3; do (cd dvbs2 && git submodule update --init --recursive); done

# aff3ct's Decoder_BCH_std allocates its elp/discrepancy/l/u_lu working arrays sized
# (N_p2_1+2)xN_p2_1 (next-power-of-two based; ~16383 for DVB-S2 QPSK-S_3/5), which is
# a ~1.07GB single malloc that causes memory runaway/SIGKILL on-device within seconds
# of RX start (this is what actually killed the equivalent iOS RX path before this fix
# -- see Shonan-ipad/DVBS2_iOS_Port_PoC.md 12.8). Only t2 (=2x correctable-error-count,
# ~24 for DVB-S2 BCH) worth of entries is ever used in _decode(), so shrink the
# allocation. Same upstream aff3ct source/commit as the iOS port (v4.0.0, a54ea87e).
if ! grep -q "Shonan調査" dvbs2/lib/aff3ct/src/Module/Decoder/BCH/Standard/Decoder_BCH_std.cpp; then
  patch -p1 -d dvbs2/lib/aff3ct < "$HERE/aff3ct_bch_decoder_overalloc.patch"
fi

# Radio_user_binary::_send() writes each TX frame via std::ofstream::write(), which was
# found (on the iOS port) to leak resident memory that grows without bound the longer TX
# streams -- iostream buffering/exception-state bookkeeping accumulating per-call. Switch
# to a raw write(2) loop instead. Applied before the FIFO/EOF patch below since they touch
# disjoint regions of the same file (constructor/destructor/_send vs reset()).
if ! grep -q "output_fd" dvbs2/src/common/Module/Radio/Radio_user/Radio_user_binary.cpp; then
  patch -p1 -d dvbs2 < "$HERE/radio_user_binary_write2.patch"
fi

# Radio_user_binary(USER_BIN --src-fifo/--rad-rx-file-path)'s EOF/auto_reset handling
# assumes a seekable regular file. Against a FIFO (our case, connecting dvbs2_tx/rx to
# the JNI bridge's I/O threads), the writer side closing on stop() surfaces as EOF, but
# reset()'s seekg(0) fails (non-seekable) and the original code throws
# "Unknown error during file reading." on every RX diagnostic stop. Patch it to treat a
# failed reset() as an intentional stop rather than an error (see the patch for the
# full rationale). Applied idempotently -- skipped if already applied.
if ! grep -q "Android移植向けの変更点" dvbs2/src/common/Module/Radio/Radio_user/Radio_user_binary.cpp; then
  patch -p1 -d dvbs2 < "$HERE/radio_user_binary_fifo_eof.patch"
fi

rm -rf dvbs2/build-android
mkdir -p dvbs2/build-android && cd dvbs2/build-android
# SPU_STACKTRACE=OFF: cpptrace's exception-time stack trace resolution shells
# out to addr2line at runtime via the build-machine path baked in via
# CPPTRACE_ADDR2LINE_PATH, which doesn't exist on the Android device. When an
# aff3ct/streampu exception is thrown (e.g. Sequence::exec() hitting an error),
# calling what() on it triggers cpptrace's backtrace resolution, which crashes
# with "FORTIFY: fclose: null FILE*" (fopen() on the missing binary path
# returns NULL, and cpptrace doesn't check before fclose()'ing it). Disabling
# stack traces avoids the whole codepath; we only need the exception message.
cmake .. -G"Unix Makefiles" \
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
  -DCMAKE_BUILD_TYPE=Release \
  -DSPU_LINK_HWLOC=OFF -DDVBS2_LINK_UHD=OFF \
  -DSPU_STACKTRACE=OFF -DSPU_STACKTRACE_SEGFAULT=OFF \
  -DCPPTRACE_ADDR2LINE_PATH="$ADDR2LINE"
make -j"$NPROC"

mkdir -p "$INSTALL/lib" "$INSTALL/include"
cp lib/aff3ct/lib/libaff3ct-4.0.0.a "$INSTALL/lib/"
cp lib/streampu/lib/libstreampu.a "$INSTALL/lib/"
cp libdvbs2_common.a "$INSTALL/lib/"
"$STRIP" --strip-debug "$INSTALL/lib/libaff3ct-4.0.0.a" "$INSTALL/lib/libstreampu.a" \
  "$INSTALL/lib/libdvbs2_common.a"

# Headers: same "flatten src/common into include/" fix the iOS port needed
# (DVBS2_iOS_Port_PoC.md section 5), and .hxx (AFF3CT template impl files)
# must be included alongside .h/.hpp. The full set of include dirs below was
# extracted verbatim from CMakeFiles/dvbs2_tx.dir/flags.make (CXX_INCLUDES)
# after a successful build, rather than guessed -- aff3ct/streampu pull in
# several vendored header-only libs (MIPP, date, json, rang, cpptrace) that
# aren't obvious from the top-level include/ dirs alone.
cd "$WORK/dvbs2"
copy_headers() {
    local src="$1"
    [ -d "$src" ] || { echo "warning: header dir not found: $src" >&2; return; }
    (cd "$src" && find . \( -name '*.h' -o -name '*.hpp' -o -name '*.hxx' \) -print | cpio -pdm "$INSTALL/include" 2>/dev/null)
}
copy_headers "src/common"
copy_headers "lib/aff3ct/include"
copy_headers "lib/aff3ct/lib/cli/src"
copy_headers "lib/aff3ct/lib/MIPP/src"
copy_headers "lib/aff3ct/lib/date/include/date"
copy_headers "lib/streampu/include"
copy_headers "lib/streampu/lib/rang/include"
copy_headers "lib/streampu/lib/json/include"
copy_headers "lib/streampu/lib/cpptrace/include"
copy_headers "build-android/lib/streampu/lib/cpptrace/include"

# conf/ (mod/*.mod etc.) is read by aff3ct at runtime via relative paths
# (../conf/mod/...) -- packaged as an Android asset and copied to a writable
# dir + chdir at runtime, mirroring DVBS2WorkingDirectory.swift.
rm -rf "$INSTALL/conf"
cp -r conf "$INSTALL/conf"

echo "Done: $INSTALL"
