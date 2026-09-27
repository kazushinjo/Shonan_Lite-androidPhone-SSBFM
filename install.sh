#!/usr/bin/env bash
# Shonan Android (com.shinjo.shonanandroid) をビルドし、接続中のAndroid端末へ
# インストールする一般ユーザー向けスクリプト。Android StudioでSDK/NDKを
# インストール済みであることを前提とする(NDK 27.3.13750724、SDKのデフォルト
# パスは $HOME/Library/Android/sdk)。third_party/配下のクロスビルド済み
# ネイティブライブラリはリポジトリに含まれているため、別途ビルドする必要はない。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$HERE/android"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK_VERSION="27.3.13750724"

BUILD_TYPE="debug"
DO_INSTALL=1
CLEAN=0

usage() {
  cat <<EOF
使い方: $(basename "$0") [--release] [--build-only] [--clean]

  --release     assembleRelease でビルドする。android/keystore/keystore.properties が
                あれば署名して自動インストールも行う。無ければ未署名APKになり
                自動インストールはできない(動作確認・サイズ確認用)
  --build-only  ビルドのみ行い、adb installは実行しない
  --clean       ビルド前に ./gradlew clean を実行する
EOF
}

for arg in "$@"; do
  case "$arg" in
    --release) BUILD_TYPE="release" ;;
    --build-only) DO_INSTALL=0 ;;
    --clean) CLEAN=1 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "不明なオプション: $arg" >&2; usage; exit 1 ;;
  esac
done

if [ ! -d "$SDK_ROOT" ]; then
  echo "エラー: Android SDKが見つかりません ($SDK_ROOT)。" >&2
  echo "Android Studioをインストールするか、環境変数 ANDROID_SDK_ROOT でSDKの場所を指定してください。" >&2
  exit 1
fi

if [ ! -d "$SDK_ROOT/ndk/$NDK_VERSION" ]; then
  echo "エラー: NDK $NDK_VERSION が見つかりません ($SDK_ROOT/ndk/$NDK_VERSION)。" >&2
  echo "Android StudioのSDK Manager > SDK Tools > NDK (Side by side) から同バージョンをインストールしてください。" >&2
  exit 1
fi

if [ ! -f "$ANDROID_DIR/local.properties" ]; then
  echo "sdk.dir=$SDK_ROOT" > "$ANDROID_DIR/local.properties"
fi

cd "$ANDROID_DIR"

if [ "$CLEAN" = "1" ]; then
  ./gradlew clean
fi

TASK="assembleDebug"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ "$BUILD_TYPE" = "release" ]; then
  TASK="assembleRelease"
  APK_PATH="app/build/outputs/apk/release/app-release.apk"
fi

./gradlew "$TASK"

if [ "$BUILD_TYPE" = "release" ] && [ ! -f "$APK_PATH" ]; then
  # keystore/keystore.properties が無い環境では未署名APKになる
  APK_PATH="app/build/outputs/apk/release/app-release-unsigned.apk"
fi

if [ ! -f "$APK_PATH" ]; then
  echo "エラー: APKが生成されませんでした ($ANDROID_DIR/$APK_PATH)。" >&2
  exit 1
fi

echo "ビルド完了: $ANDROID_DIR/$APK_PATH"

if [ "$BUILD_TYPE" = "release" ] && [[ "$APK_PATH" == *unsigned* ]]; then
  echo "release APKは未署名のため自動インストールは行いません(keystore/keystore.propertiesが無いため)。"
  echo "実機で使うにはこのAPKに署名するか、--releaseを付けずにdebugビルドを使ってください。"
  exit 0
fi

if [ "$DO_INSTALL" = "0" ]; then
  exit 0
fi

ADB="$SDK_ROOT/platform-tools/adb"
command -v "$ADB" >/dev/null 2>&1 || ADB="adb"

if ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adbが見つかりません。$ANDROID_DIR/$APK_PATH を端末へ手動で転送してインストールしてください。"
  exit 0
fi

DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
DEVICE_COUNT=0
[ -n "$DEVICES" ] && DEVICE_COUNT=$(echo "$DEVICES" | wc -l | tr -d ' ')

if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "接続中のAndroid端末が見つかりません。"
  echo "端末の開発者向けオプションでUSBデバッグを有効にしてUSB接続するか、"
  echo "$ANDROID_DIR/$APK_PATH を手動で端末へ転送してインストールしてください。"
  exit 0
fi

if [ "$DEVICE_COUNT" -gt 1 ]; then
  echo "複数の端末が接続されています。インストール対象を指定して手動実行してください:"
  echo "$DEVICES"
  echo "例: \"$ADB\" -s <デバイスID> install -r \"$ANDROID_DIR/$APK_PATH\""
  exit 0
fi

echo "端末 $DEVICES へインストールします..."
"$ADB" -s "$DEVICES" install -r "$APK_PATH"
echo "インストール完了。"
