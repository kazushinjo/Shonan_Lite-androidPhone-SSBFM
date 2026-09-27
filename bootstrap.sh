#!/usr/bin/env bash
# Shonan_Lite-androidPhoneをまだクローンしていないマシン向けのブートストラップ。
# リポジトリをclone(または既存なら更新)し、install.shへ処理を引き継ぐ。
#
# このリポジトリはPrivateなので、匿名のcurl | bashではraw取得できない。
# git/gh の既存の認証(SSH鍵、または `gh auth login` 済みでgitのcredential
# helperにghが登録されている状態)が前提。このファイル自体を未クローンの
# 状態から取得するには、例えば以下のように `gh api` 経由で1ファイルだけ
# 先に取得してから実行する:
#
#   gh api repos/kazushinjo/Shonan_Lite-androidPhone/contents/bootstrap.sh \
#     --jq '.content' | base64 -d > bootstrap.sh
#   chmod +x bootstrap.sh
#   ./bootstrap.sh
set -euo pipefail

REPO_URL="https://github.com/kazushinjo/Shonan_Lite-androidPhone.git"
TARGET_DIR="${1:-$HOME/Shonan_Lite-androidPhone}"

if ! command -v git >/dev/null 2>&1; then
  echo "エラー: gitが見つかりません。'brew install git' 等でインストールしてください。" >&2
  exit 1
fi

if ! command -v git-lfs >/dev/null 2>&1; then
  echo "git-lfsが見つかりません。インストールを試みます..."
  if command -v brew >/dev/null 2>&1; then
    brew install git-lfs
  else
    echo "エラー: git-lfsを自動インストールできません。手動でインストールしてください(https://git-lfs.com/)。" >&2
    exit 1
  fi
fi

git lfs install --skip-repo

if [ -d "$TARGET_DIR/.git" ]; then
  echo "既存のクローンを更新します: $TARGET_DIR"
  git -C "$TARGET_DIR" fetch origin
  git -C "$TARGET_DIR" checkout main
  git -C "$TARGET_DIR" pull --ff-only origin main
elif [ -e "$TARGET_DIR" ]; then
  echo "エラー: $TARGET_DIR は既に存在しますがgitリポジトリではありません。" >&2
  exit 1
else
  echo "クローンします: $REPO_URL -> $TARGET_DIR"
  git clone "$REPO_URL" "$TARGET_DIR"
fi

echo "install.shへ引き継ぎます..."
exec "$TARGET_DIR/install.sh" "${@:2}"
