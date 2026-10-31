#!/system/bin/sh
# 虫虫式"解压中"等价脚本：从整合 APK 提取 OBB 到系统路径 + 提取并安装游戏
# 用法: sh install_from_integrated.sh <整合.apk> <游戏包名> [游戏apk输出路径]
APK="$1"; PKG="$2"; OUT="${3:-/sdcard/Download/游戏.apk}"
if [ -z "$APK" ] || [ -z "$PKG" ]; then
  echo "用法: sh $0 <整合.apk> <游戏包名>"
  echo "例:  sh $0 YanlessFATAS_集成版.apk com.YandereDev.YanlessFATAS"
  exit 1
fi

OBC="/sdcard/Android/obb/$PKG"
STAGE="/sdcard/Download/_obb_installer_stage"
mkdir -p "$OBC" "$STAGE"

echo "[1/3] 提取 OBB -> $OBC"
unzip -o -j "$APK" "assets/sdcard/Android/obb/$PKG/*" -d "$OBC"
echo "      OBB 已就位："
ls -lh "$OBC"

echo "[2/3] 提取游戏 APK -> $OUT"
rm -rf "$STAGE"
unzip -o "$APK" "assets/infoplay" -d "$STAGE" >/dev/null
mv "$STAGE/assets/infoplay" "$OUT"
chmod 644 "$OUT"
rm -rf "$STAGE"

echo "[3/3] 安装游戏"
if pm install -r "$OUT" >/dev/null 2>&1; then
  echo "     安装成功，直接打开游戏即可（它会去 $OBC 读 OBB）"
else
  echo "     pm 安装失败（通常需要 root / Shizuku，或系统限制）。"
  echo "     请手动用文件管理器点开 $OUT 完成安装。"
fi
