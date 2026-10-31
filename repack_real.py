import sys, zipfile, shutil
src, dst, pkg = sys.argv[1], sys.argv[2], sys.argv[3]
BUF = 8*1024*1024
print(f"[*] 源: {src}\n[*] 输出: {dst}\n[*] 包名: {pkg}", flush=True)
with zipfile.ZipFile(src, allowZip64=True) as zin, \
     zipfile.ZipFile(dst,'w',zipfile.ZIP_STORED, allowZip64=True) as zout:
    print("[*] 复制 assets/infoplay (游戏 APK) ...", flush=True)
    with zin.open('assets/infoplay') as f, zout.open('assets/infoplay','w',force_zip64=True) as o:
        shutil.copyfileobj(f,o,BUF)
    obbs=[n for n in zin.namelist() if n.startswith('assets/sdcard/Android/obb/')]
    if not obbs:
        sys.exit("[!] 未在源包中找到 OBB")
    obb=obbs[0]; obbname=obb.split('/')[-1]
    dest=f'assets/sdcard/Android/obb/{pkg}/{obbname}'
    print(f"[*] 复制 OBB -> {dest} ...", flush=True)
    with zin.open(obb) as f, zout.open(dest,'w',force_zip64=True) as o:
        shutil.copyfileobj(f,o,BUF)
print(f"[+] 完成: {dst}", flush=True)
