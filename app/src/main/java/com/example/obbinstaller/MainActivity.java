package com.example.obbinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.AssetManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String AUTHORITY = "com.example.obbinstaller.fileprovider";
    static final int REQ_PICK = 1001;
    static final int REQ_PERM = 1002;
    TextView tv;
    ProgressBar pb;

    protected void onCreate(Bundle b) {
        super.onCreate(b);
        try {
            setupUi();
        } catch (Throwable t) {
            showError(t);
            return;
        }
        // 全局异常兜底：任何未捕获异常直接显示在界面，避免"闪退"
        Thread.setDefaultUncaughtExceptionHandler((th, ex) -> runOnUiThread(() -> showError(ex)));

        if (Build.VERSION.SDK_INT >= 30 && !safeIsExternalStorageManager()) {
            // 未授予「所有文件访问」权限：打开安装器时先弹窗询问，确认后再跳系统设置页
            showAllFilesPermissionDialog();
            return;
        }
        new Thread(this::installBundled).start();
    }

    void setupUi() {
        tv = new TextView(this);
        tv.setText("OBB 自动安装器\n\n此工具可自动识别 OBB 路径并安装游戏。\n" +
                "• 若已把游戏 APK 和 OBB 打包进本安装器，启动会自动处理；\n" +
                "• 也可点击下方按钮，从手机选择 APK 和 OBB 文件。");
        pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setMax(100);
        Button btn = new Button(this);
        btn.setText("选择 APK 和 OBB");
        btn.setOnClickListener(v -> pickFiles());
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 40, 40, 40);
        ll.addView(tv);
        ll.addView(pb);
        ll.addView(btn);
        setContentView(ll);
    }

    boolean safeIsExternalStorageManager() {
        try { return Environment.isExternalStorageManager(); } catch (Throwable t) { return false; }
    }

    void showAllFilesPermissionDialog() {
        // 打开安装器时弹出：是否允许「管理所有文件」权限
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("需要「管理所有文件」权限");
        b.setMessage("本工具需要把 OBB 写入 /Android/obb 目录，必须授予「所有文件访问」权限才能继续。\n\n是否前往系统设置开启？");
        b.setPositiveButton("去开启", (d, w) -> openAllFilesSettings());
        b.setNegativeButton("退出", (d, w) -> finish());
        b.setCancelable(false);
        b.show();
    }

    void openAllFilesSettings() {
        Intent i = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        if (i.resolveActivity(getPackageManager()) != null) {
            try { startActivityForResult(i, REQ_PERM); } catch (Throwable t) { showError(t); }
        } else {
            setStatus("无法打开“所有文件访问”设置页，请手动到系统设置授予本应用“所有文件访问”权限后重开。");
        }
    }

    void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(i, REQ_PICK); } catch (Throwable t) { showError(t); }
    }

    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PERM) {
            // 从「所有文件访问」设置页返回：若已授权则自动继续安装，否则再次弹窗询问。
            if (safeIsExternalStorageManager()) {
                setStatus("已获得权限，开始处理...");
                new Thread(this::installBundled).start();
            } else {
                showAllFilesPermissionDialog();
            }
            return;
        }
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

    String extRoot() {
        // 用系统 API 取真实外置存储根目录（通常是 /storage/emulated/0），
        // 不要硬编码 /sdcard（只是 symlink，某些设备上应用进程建目录会失败）。
        return Environment.getExternalStorageDirectory().getAbsolutePath();
    }

    String obbDest(String nameOrPath) {
        String root = extRoot();
        if (nameOrPath.contains("sdcard/Android/obb/")) {
            int i = nameOrPath.indexOf("sdcard/Android/obb/");
            return root + "/Android/obb/" + nameOrPath.substring(i + "sdcard/Android/obb/".length());
        }
        int first = nameOrPath.indexOf('.');
        if (first >= 0) {
            int second = nameOrPath.indexOf('.', first + 1);
            if (second >= 0 && (nameOrPath.startsWith("main.") || nameOrPath.startsWith("patch."))) {
                String pkg = nameOrPath.substring(second + 1);
                if (pkg.endsWith(".obb")) pkg = pkg.substring(0, pkg.length() - 4);
                return root + "/Android/obb/" + pkg + "/" + nameOrPath;
            }
        }
        return root + "/Android/obb/" + nameOrPath + "/" + nameOrPath;
    }

    void installBundled() {
        try {
            AssetManager am = getAssets();
            List<String> obbs = new ArrayList<>();
            collectAssets(am, "", obbs);
            String gameApk = null;
            if (exists(am, "infoplay")) gameApk = "infoplay";
            else for (String s : am.list("")) if (s.endsWith(".apk")) { gameApk = s; break; }
            if (obbs.isEmpty() && gameApk == null) {
                setStatus("未发现打包的素材。请点击下方「选择 APK 和 OBB」按钮，从手机里选择文件。");
                return;
            }
            for (String obb : obbs) {
                String dest = obbDest(obb);
                setStatus("正在解压 OBB -> " + dest);
                setIndeterminate(false);
                copyAsset(am, obb, new File(dest));
                updateProgress(100);
            }
            if (gameApk != null) {
                File apk = new File(getCacheDir(), "game.apk");
                setStatus("正在取出游戏 APK 并调起安装...");
                copyAsset(am, gameApk, apk);
                installApk(apk);
            }
            setStatus("完成：OBB 已就位，游戏已调起安装。");
        } catch (Exception e) { setStatus("出错：" + e); }
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
                    setStatus("正在解压 OBB -> " + dest);
                    setIndeterminate(false);
                    copyUri(u, f);
                    updateProgress(100);
                    obbMsg = f.getParent();
                } else if (name.endsWith(".apk")) {
                    apk = new File(getCacheDir(), "game.apk");
                    setStatus("正在取出游戏 APK...");
                    copyUri(u, apk);
                }
            }
            if (apk != null) installApk(apk);
            setStatus("完成：OBB 已就位" + (obbMsg.isEmpty() ? "" : (" 路径 " + obbMsg)) + "，游戏已调起安装。");
        } catch (Exception e) { setStatus("出错：" + e); }
    }

    String docName(Uri u) {
        String n = null;
        Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) { if (c.moveToFirst()) n = c.getString(0); c.close(); }
        if (n == null) n = u.getLastPathSegment();
        return n == null ? "file.obb" : n;
    }

    void copyAsset(AssetManager am, String asset, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException("无法创建目录: " + parent + "（请确认已授予本应用「所有文件访问」权限，且存储可写）");
        long total = -1;
        try { total = am.openFd(asset).getLength(); } catch (Exception ignored) {}
        InputStream in = am.open(asset);
        FileOutputStream fo = new FileOutputStream(out);
        pipe(in, fo, total);
        in.close(); fo.close();
    }
    void copyUri(Uri u, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs())
            throw new IOException("无法创建目录: " + parent + "（请确认已授予本应用「所有文件访问」权限，且存储可写）");
        long total = -1;
        try { total = getContentResolver().openFileDescriptor(u, "r").getStatSize(); } catch (Exception ignored) {}
        InputStream in = getContentResolver().openInputStream(u);
        FileOutputStream fo = new FileOutputStream(out);
        pipe(in, fo, total);
        in.close(); fo.close();
    }
    void pipe(InputStream in, OutputStream out, long total) throws Exception {
        byte[] buf = new byte[1 << 20]; int n; long done = 0;
        setIndeterminate(total <= 0);
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (total > 0) updateProgress((int)(done * 100 / total));
        }
    }
    void installApk(File apk) {
        try {
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(FileProvider.getUriForFile(this, AUTHORITY, apk));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) { setStatus("调起安装失败：" + t); }
    }
    void setStatus(final String s) { runOnUiThread(() -> { if (tv != null) tv.setText(s); }); }
    void updateProgress(final int p) { runOnUiThread(() -> { if (pb != null) { pb.setIndeterminate(false); pb.setProgress(p); } }); }
    void setIndeterminate(final boolean ind) { runOnUiThread(() -> { if (pb != null) pb.setIndeterminate(ind); }); }
    void showError(Throwable t) {
        String msg = "发生错误（已避免闪退）：\n" + (t == null ? "未知" : t.toString());
        if (tv != null) tv.setText(msg);
        else { TextView t2 = new TextView(this); t2.setText(msg); setContentView(t2); }
    }
    void toast(final String s) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show());
    }
}
