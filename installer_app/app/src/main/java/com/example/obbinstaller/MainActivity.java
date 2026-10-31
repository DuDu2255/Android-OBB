package com.example.obbinstaller;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String AUTHORITY = "com.example.obbinstaller.fileprovider";
    static final int REQ_PICK = 1001;
    TextView tv;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        tv = new TextView(this);
        tv.setText("OBB 自动安装器\n\n"
                + "1) 若已把「游戏APK + OBB」打包进本安装器，启动即自动识别路径并安装；\n"
                + "2) 也可点下方按钮，从手机里选「APK」和「OBB」，自动识别包名后放置。");
        Button btn = new Button(this);
        btn.setText("选择外部 APK / OBB");
        btn.setOnClickListener(v -> pickFiles());
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.addView(tv);
        ll.addView(btn);
        setContentView(ll);

        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
            toast("请授予「所有文件访问」权限后重开");
            return;
        }
        new Thread(this::installBundled).start();
    }

    void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getData() != null) uris.add(data.getData());
            else if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
            }
            new Thread(() -> handlePicked(uris)).start();
        } else super.onActivityResult(req, res, data);
    }

    // ===== 自动识别 OBB 应放的路径 =====
    // 规则：main.<ver>.<包名>.obb / patch.<ver>.<包名>.obb
    //   -> /sdcard/Android/obb/<包名>/<原文件名>
    // 若素材已放在 assets/sdcard/Android/obb/... 下，直接镜像该路径。
    String obbDest(String nameOrPath) {
        if (nameOrPath.contains("sdcard/Android/obb/")) {
            int i = nameOrPath.indexOf("sdcard/Android/obb/");
            return "/" + nameOrPath.substring(i);
        }
        int first = nameOrPath.indexOf('.');
        if (first >= 0) {
            int second = nameOrPath.indexOf('.', first + 1);
            if (second >= 0 && (nameOrPath.startsWith("main.") || nameOrPath.startsWith("patch."))) {
                String pkg = nameOrPath.substring(second + 1);
                if (pkg.endsWith(".obb")) pkg = pkg.substring(0, pkg.length() - 4);
                return "/sdcard/Android/obb/" + pkg + "/" + nameOrPath;
            }
        }
        return "/sdcard/Android/obb/" + nameOrPath + "/" + nameOrPath;
    }

    // ===== 处理已打包进本安装器的素材 =====
    void installBundled() {
        try {
            AssetManager am = getAssets();
            List<String> obbs = new ArrayList<>();
            collectAssets(am, "", obbs);
            String gameApk = null;
            if (exists(am, "infoplay")) gameApk = "infoplay";
            else for (String s : am.list("")) if (s.endsWith(".apk")) { gameApk = s; break; }
            if (obbs.isEmpty() && gameApk == null) {
                setText("未发现打包的素材。请点「选择外部 APK/OBB」。");
                return;
            }
            for (String obb : obbs) {
                String dest = obbDest(obb);
                setText("解压 OBB -> " + dest);
                copyAsset(am, obb, new File(dest));
            }
            if (gameApk != null) {
                File apk = new File(getCacheDir(), "game.apk");
                setText("取出游戏 APK 并调起安装...");
                copyAsset(am, gameApk, apk);
                installApk(apk);
            }
            setText("完成：OBB 已就位，游戏已调起安装。");
        } catch (Exception e) { setText("出错：" + e); }
    }

    void collectAssets(AssetManager am, String path, List<String> out) {
        try {
            String[] items = am.list(path);
            if (items == null) return;
            for (String s : items) {
                String full = path.isEmpty() ? s : path + "/" + s;
                try { am.open(full).close(); if (full.endsWith(".obb")) out.add(full); }
                catch (Exception e) { collectAssets(am, full, out); }
            }
        } catch (Exception ignored) {}
    }

    boolean exists(AssetManager am, String name) {
        try { am.open(name).close(); return true; } catch (Exception e) { return false; }
    }

    // ===== 处理用户从手机选的文件 =====
    void handlePicked(List<Uri> uris) {
        try {
            File apk = null;
            String obbMsg = "";
            for (Uri u : uris) {
                String name = docName(u);
                if (name.endsWith(".obb")) {
                    String dest = obbDest(name);
                    File f = new File(dest);
                    f.getParentFile().mkdirs();
                    setText("解压 OBB -> " + dest);
                    copyUri(u, f);
                    obbMsg = f.getParent();
                } else if (name.endsWith(".apk")) {
                    apk = new File(getCacheDir(), "game.apk");
                    setText("取出游戏 APK...");
                    copyUri(u, apk);
                }
            }
            if (apk != null) installApk(apk);
            setText("完成：OBB 已就位" + (obbMsg.isEmpty() ? "" : (" 路径 " + obbMsg))
                    + "，游戏已调起安装。");
        } catch (Exception e) { setText("出错：" + e); }
    }

    String docName(Uri u) {
        String n = null;
        Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) { if (c.moveToFirst()) n = c.getString(0); c.close(); }
        if (n == null) n = u.getLastPathSegment();
        return n == null ? "file.obb" : n;
    }

    void copyAsset(AssetManager am, String asset, File out) throws Exception {
        out.getParentFile().mkdirs();
        InputStream in = am.open(asset);
        FileOutputStream fo = new FileOutputStream(out);
        pipe(in, fo); in.close(); fo.close();
    }
    void copyUri(Uri u, File out) throws Exception {
        out.getParentFile().mkdirs();
        InputStream in = getContentResolver().openInputStream(u);
        FileOutputStream fo = new FileOutputStream(out);
        pipe(in, fo); in.close(); fo.close();
    }
    void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1 << 20]; int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
    void installApk(File apk) {
        Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        i.setData(FileProvider.getUriForFile(this, AUTHORITY, apk));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(i);
    }
    void setText(final String s) { runOnUiThread(() -> tv.setText(s)); }
    void toast(final String s) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show());
    }
}
