#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
整合包打包脚本（APK + OBB 单包 / 仿虫虫助手）
---------------------------------------------
把一个"真实游戏 APK"和一个"OBB 数据文件"打包进一个外层壳 APK。
两个内容都以 ZIP_STORED（不压缩）形式存入 assets/，原因：
  1. OBB/Unity 数据本身已压缩，再压无收益还浪费 CPU；
  2. 不压缩才能对大文件做流式 seek 提取，不用整体解压。
OBB 在 assets 里的路径直接镜像它在设备上的目标路径，
提取时只需去掉 "assets/" 前缀即可原样落盘到 /sdcard/Android/obb/<pkg>/。
"""
import argparse, os, zipfile, sys

def build(game_apk, obb_file, package, out_apk):
    if not os.path.isfile(game_apk):
        sys.exit(f"[!] 游戏 APK 不存在: {game_apk}")
    if not os.path.isfile(obb_file):
        sys.exit(f"[!] OBB 文件不存在: {obb_file}")

    obb_name = os.path.basename(obb_file)
    # 外壳不压缩，内部两个 asset 也不压缩（ZIP_STORED）
    with zipfile.ZipFile(out_apk, 'w', compression=zipfile.ZIP_STORED) as out:
        print(f"[*] 写入 assets/infoplay  <- {game_apk}")
        out.write(game_apk, 'assets/infoplay')
        dest = f'assets/sdcard/Android/obb/{package}/{obb_name}'
        print(f"[*] 写入 {dest}  <- {obb_file}")
        out.write(obb_file, dest)

    size = os.path.getsize(out_apk)
    print(f"[+] 生成成功: {out_apk}  ({size/1024/1024:.2f} MB)")

if __name__ == '__main__':
    p = argparse.ArgumentParser(description='打包 APK+OBB 整合包（单包）')
    p.add_argument('--game',    required=True, help='真实游戏 APK 路径')
    p.add_argument('--obb',     required=True, help='OBB 文件路径 (main.<ver>.<pkg>.obb)')
    p.add_argument('--package', required=True, help='游戏包名, 如 com.example.game')
    p.add_argument('--out',     default='integrated.apk', help='输出整合 APK 路径')
    a = p.parse_args()
    build(a.game, a.obb, a.package, a.out)
