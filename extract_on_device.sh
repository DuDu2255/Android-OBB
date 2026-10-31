#!/system/bin/sh
# 设备端（Termux / adb shell）提取整合 APK 到系统位置
# 用法: sh extract_on_device.sh <整合.apk> <包名>
# 需要: 存储权限（或 root）才能写 /sdcard/Android/obb/
APK="$1"
PKG="$2"
if [ -z "$APK" ] || [ -z "$PKG" ]; then
  echo "用法: sh $0 <整合.apk> <包名>"
  exit 1
fi

OBC="/sdcard/Android/obb/$PKG"
mkdir -p "$OBC"

# 提取 OBB 到系统 obb 目录
unzip -o -j "$APK" "assets/sdcard/Android/obb/$PKG/*" -d "$OBC"
echo "[+] OBB 已放到 $OBC"

# 提取内层游戏 APK 并安装
TMP="/data/local/tmp/game_$$.apk"
rm -rf /data/local/tmp/assets
unzip -o "$APK" "assets/infoplay" -d /data/local/tmp/ >/dev/null
mv /data/local/tmp/assets/infoplay "$TMP"
pm install -r "$TMP" && echo "[+] 游戏已安装" || echo "[!] 安装失败(可用 pm/自身商店安装 game_extracted.apk)"
rm -f "$TMP"
