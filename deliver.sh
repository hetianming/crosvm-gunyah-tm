#!/usr/bin/env bash
# 统一交付脚本：无论编译多少次，工作区根目录永远只有「一个 APK + 一个说明」
# 用法： ./deliver.sh [新编译的APK路径] [新版说明HTML路径]
#   不传参数时默认取 /tmp/gunyabuild/app/outputs/apk/release/app-release.apk
# 交付闭环：跑完本脚本后，必须用系统分享面板把 FIXED_APK 分享到用户手机
#   （mobile_share_files），不要只靠会话产物卡片——用户翻不到。
set -e

WS="/workspace"
ARC="$WS/_历史归档_旧版APK与说明"
SRC_APK="${1:-/tmp/gunyabuild/app/outputs/apk/release/app-release.apk}"
SRC_DOC="${2:-}"
FIXED_APK="$WS/GunyahLauncher-最新版.apk"
FIXED_DOC="$WS/使用说明.html"

mkdir -p "$ARC"

if [ ! -f "$SRC_APK" ]; then
  echo "错误：找不到新 APK：$SRC_APK" >&2
  exit 1
fi

# 1) 先把根目录里所有「带版本号的」旧交付物清进归档，避免累积
for f in "$WS"/*.apk "$WS"/*.html; do
  [ -e "$f" ] || continue
  case "$(basename "$f")" in
    "GunyahLauncher-最新版.apk"|"使用说明.html") continue ;;
  esac
  mv -f "$f" "$ARC/" 2>/dev/null || true
done

# 2) 覆盖固定名 APK（永远同名，客户端只会看到这一个）
cp -f "$SRC_APK" "$FIXED_APK"

# 3) 覆盖固定名说明（有传新说明才覆盖，否则保留现有）
if [ -n "$SRC_DOC" ] && [ -f "$SRC_DOC" ]; then
  cp -f "$SRC_DOC" "$FIXED_DOC"
fi

echo "=== 交付完成：工作区根目录交付物 ==="
ls -la "$FIXED_APK" "$FIXED_DOC"
AAPT=/root/Android/Sdk/build-tools/35.0.0/aapt
if [ -x "$AAPT" ]; then
  echo -n "版本："
  "$AAPT" dump badging "$FIXED_APK" 2>/dev/null \
    | grep -oE "versionCode='[0-9]+' versionName='[0-9.]+'" | head -1
fi
echo "归档目录累计文件数：$(ls -1 "$ARC" 2>/dev/null | wc -l)"
