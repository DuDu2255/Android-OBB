#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从整合 APK 解包出内层游戏 APK 和 OBB（用于校验 / PC 侧处理）"""
import argparse, os, zipfile

def extract(apk, out_dir):
    os.makedirs(out_dir, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        # 1) 内层游戏
        if 'assets/infoplay' in names:
            z.extract('assets/infoplay', out_dir)
            src = os.path.join(out_dir, 'assets', 'infoplay')
            dst = os.path.join(out_dir, 'game_extracted.apk')
            os.replace(src, dst)
            os.rmdir(os.path.join(out_dir, 'assets'))
            print("[+] 已解包游戏 APK -> game_extracted.apk")
        else:
            print("[!] 未找到 assets/infoplay")
        # 2) OBB（还原到 sdcard/Android/obb/... 的本地镜像）
        found = False
        for n in names:
            if n.startswith('assets/sdcard/Android/obb/'):
                z.extract(n, out_dir)
                rel = n[len('assets/'):]            # sdcard/Android/obb/...
                dst = os.path.join(out_dir, 'obb_mirror', rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                os.replace(os.path.join(out_dir, n), dst)
                print(f"[+] 已解包 OBB -> obb_mirror/{rel}")
                found = True
        if not found:
            print("[!] 未找到 OBB 条目")
    print("[*] 解包完成")

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--apk', required=True, help='整合 APK 路径')
    p.add_argument('--out', default='extracted', help='输出目录')
    a = p.parse_args()
    extract(a.apk, a.out)
