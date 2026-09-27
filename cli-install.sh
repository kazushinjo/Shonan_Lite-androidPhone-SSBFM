#!/usr/bin/env bash
# Android Studioをインストールしていない一般ユーザー向けの、コマンドラインのみで
# 完結するビルド・インストールスクリプト。Android SDK Command-line Toolsを自動で
# ダウンロードし、必要なplatform/NDKをsdkmanagerでセットアップしたうえで、
# 既存の install.sh にビルド・インストール処理を引き継ぐ。
#
# 前提: macOS、Java (JDK 17以上)がインストール済みであること
#       (未インストールの場合は `brew install openjdk@17` 等で用意してください)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK_VERSION="27.3.13750724"
PLATFORM_VERSION="android-35"
CMDLINE_TOOLS_VERSION="11076708"
CMDLINE_TOOLS_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-mac-${CMDLINE_TOOLS_VERSION}_latest.zip"

if ! command -v java >/dev/null 2>&1; then
  echo "エラー: Javaが見つかりません。'brew install openjdk@17' 等でインストールしてください。" >&2
  exit 1
fi

SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

if [ ! -x "$SDKMANAGER" ]; then
  echo "Android SDK Command-line Toolsが見つかりません。取得します: $SDK_ROOT"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  TMP_ZIP="$(mktemp -t cmdline-tools).zip"
  curl -fL -o "$TMP_ZIP" "$CMDLINE_TOOLS_ZIP_URL"
  TMP_EXTRACT="$(mktemp -d)"
  unzip -q "$TMP_ZIP" -d "$TMP_EXTRACT"
  rm -f "$TMP_ZIP"
  mv "$TMP_EXTRACT/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rmdir "$TMP_EXTRACT" 2>/dev/null || true
fi

echo "SDKライセンスに同意します..."
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null

echo "必要なSDKコンポーネントをインストールします(platform-tools, $PLATFORM_VERSION, NDK $NDK_VERSION)..."
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" "platforms;$PLATFORM_VERSION" "ndk;$NDK_VERSION"

export ANDROID_SDK_ROOT="$SDK_ROOT"
export ANDROID_HOME="$SDK_ROOT"

echo "install.shへ引き継ぎます..."
exec "$HERE/install.sh" "$@"
