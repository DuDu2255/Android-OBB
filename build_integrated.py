#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
整合包打包脚本（APK + OBB 单包 / 仿虫虫助手）
---------------------------------------------
把一个“真实游戏 APK”和一个“OBB 数据文件”打包进一个外层壳 APK。
两个内容都以 ZIP_STORED（不压缩）形式存入 assets/，原因：
  1. OBB/Unity 数据本身已压缩，再压无收益还浪费 CPU；
  2. 不压缩才能对大文件做流式 seek 提取，不用整体解压。
OBB 在 assets 里的路径直接镜像它在设备上的目标路径，
提取时只需去掉 "assets/" 前缀即可原样落盘到 /sdcard/Android/obb/<pkg>/。

关键点：游戏只会按固定规则去找 OBB：
    /sdcard/Android/obb/<pkg>/main.<versionCode>.<pkg>.obb
（patch 包则是 patch.<versionCode>.<pkg>.obb）
所以 OBB 的文件名必须规范，否则游戏“读不了”。

本脚本会自动从游戏 APK 的 manifest 里读出 package 和 versionCode，
并把 OBB 自动重命名为规范名，免去“记原名”的麻烦。
（若 aapt 读不出超大/特殊 APK，可用 --package / --ver 手动指定）
"""

import argparse, os, zipfile, sys, re, subprocess

def read_apk_info(apk):
    """用 aapt 从游戏 APK 读出 (package, versionCode)。读不出返回 (None, None)。"""
    try:
        out = subprocess.run(['aapt', 'dump', 'badging', apk],
                             capture_output=True, text=True, timeout=120).stdout
    except Exception as e:
        print("[!] aapt 执行失败: %s" % e)
        return None, None
    m = re.search(r"package:\s*name='([^']+)'[^]]*versionCode='([^']+)'", out)
    if m:
        return m.group(1), m.group(2)
    pm = re.search(r"name='([^']+)'", out)
    vm = re.search(r"versionCode='([^']+)'", out)
    if pm and vm:
        return pm.group(1), vm.group(1)
    return None, None

def normalize_obb_name(obb_file, package, ver):
    """把 OBB 规范命名为 main.<ver>.<pkg>.obb（若已经是 main/patch.*.<pkg>.obb 则保留）。"""
    base = os.path.basename(obb_file)
    if re.match(r'^(main|patch)\.\d+\.[^.]+\.obb$', base):
        return base
    return 'main.%s.%s.obb' % (ver, package)

def build(game_apk, obb_file, package, ver, out_apk):
    if not os.path.isfile(game_apk):
        sys.exit("[!] 游戏 APK 不存在: %s" % game_apk)
    if not os.path.isfile(obb_file):
        sys.exit("[!] OBB 文件不存在: %s" % obb_file)

    if not package or not ver:
        ap, av = read_apk_info(game_apk)
        if not package:
            if not ap:
                sys.exit("[!] 无法从 APK 自动读取包名，请用 --package 手动指定。")
            package = ap
            print("[*] 从 APK 自动读取包名: %s" % package)
        if not ver:
            if not av:
                sys.exit("[!] 无法从 APK 自动读取 versionCode，请用 --ver 手动指定。")
            ver = av
            print("[*] 从 APK 自动读取 versionCode: %s" % ver)

    obb_name = normalize_obb_name(obb_file, package, ver)
    print("[*] OBB 规范名: %s" % obb_name)

    with zipfile.ZipFile(out_apk, 'w', compression=zipfile.ZIP_STORED,
                         allowZip64=True) as out:
        print("[*] 写入 assets/infoplay  <- %s" % game_apk)
        out.write(game_apk, 'assets/infoplay')
        dest = 'assets/sdcard/Android/obb/%s/%s' % (package, obb_name)
        print("[*] 写入 %s  <- %s" % (dest, obb_file))
        out.write(obb_file, dest, compress_type=zipfile.ZIP_STORED)

    size = os.path.getsize(out_apk)
    print("[+] 生成成功: %s  (%.2f MB)" % (out_apk, size/1024/1024))
    print("[+] 安装后 OBB 将落盘到: /sdcard/Android/obb/%s/%s" % (package, obb_name))

if __name__ == '__main__':
    p = argparse.ArgumentParser(description='打包 APK+OBB 整合包（单包，自动规范 OBB 名）')
    p.add_argument('--game',    required=True, help='真实游戏 APK 路径')
    p.add_argument('--obb',     required=True, help='OBB 文件路径（名字随便，脚本会自动规范）')
    p.add_argument('--package', default=None,  help='游戏包名(可省，自动从 APK 读)')
    p.add_argument('--ver',     default=None,  help='游戏 versionCode(可省，自动从 APK 读)')
    p.add_argument('--out',     default='integrated.apk', help='输出整合 APK 路径')
    a = p.parse_args()
    build(a.game, a.obb, a.package, a.ver, a.out)
