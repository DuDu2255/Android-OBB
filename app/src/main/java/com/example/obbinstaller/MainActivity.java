package com.example.obbinstaller;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
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
    static final int REQ_INSTALL = 1002;
    TextView tv;
    ProgressBar pb;
    private boolean started = false;
    private volatile Runnable pendingAfterInstall;
    private volatile String pendingPackageName;

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
            // 未授予「所有文件访问」权限：弹出询问框，确认后跳系统设置页；启动逻辑交给 onResume 统一处理
            showAllFilesPermissionDialog();
            return;
        }
        // 已授权（或 Android 10 及以下）：交给 onResume 启动安装
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Winlator 同款思路：从设置页返回后再次检查；已获权限则启动安装（仅一次），未获授权则等待用户去设置
        if (Build.VERSION.SDK_INT >= 30 && !safeIsExternalStorageManager()) return;
        if (!started) {
            started = true;
            new Thread(this::installBundled).start();
        }
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
        // 打开安装器时弹出：是否前往系统设置授予「管理所有文件」权限（不再有「禁止不再询问」记忆）
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle("需要「管理所有文件」权限");
        b.setMessage("本工具需要把 OBB 写入 /Android/obb 目录，必须授予「所有文件访问」权限才能继续。\n\n是否前往系统设置开启？");
        b.setPositiveButton("允许（去开启）", (d, w) -> openAllFilesSettings());
        b.setNegativeButton("退出", (d, w) -> finish());
        b.setCancelable(false);
        b.show();
    }

    void openAllFilesSettings() {
        // Winlator 同款：优先跳到本应用的「所有文件访问」设置页（更精准），失败则跳总设置页
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        } catch (Throwable ignored) {}
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
            startActivity(intent);
        } catch (Throwable t) {
            setStatus("无法打开“所有文件访问”设置页，请手动到系统设置授予本应用“所有文件访问”权限后重开。");
        }
    }

    void pickFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(i, REQ_PICK); } catch (Throwable t) { showError(t); }
    }

    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getData() != null) uris.add(data.getData());
            else if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    uris.add(data.getClipData().getItemAt(i).getUri());
            }
            for (Uri uri : uris) {
                try {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {}
            }
            new Thread(() -> handlePicked(uris)).start();
        } else if (req == REQ_INSTALL) {
            new Thread(() -> finishInstall(res)).start();
        } else super.onActivityResult(req, res, data);
    }

    void finishInstall(int resultCode) {
        Runnable afterInstall = pendingAfterInstall;
        String packageName = pendingPackageName;
        boolean installed = resultCode == RESULT_OK || waitForPackageInstalled(packageName);
        pendingAfterInstall = null;
        pendingPackageName = null;
        if (installed && afterInstall != null) {
            afterInstall.run();
        } else {
            setStatus("未检测到游戏 APK，请确认安装成功后重试。");
        }
    }

    boolean waitForPackageInstalled(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        for (int i = 0; i < 10; i++) {
            try {
                getPackageManager().getPackageInfo(packageName, 0);
                return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(500); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
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
            if (gameApk == null) {
                setStatus("未找到游戏 APK，无法先安装游戏再复制 OBB。");
                return;
            }
            File apk = new File(getCacheDir(), "game.apk");
            setStatus("正在取出游戏 APK 并调起安装...");
            copyAsset(am, gameApk, apk);
            installApk(apk, () -> copyBundledObbs(am, obbs));
        } catch (Exception e) { setStatus("出错：" + e); }
    }

    void copyBundledObbs(AssetManager am, List<String> obbs) {
        try {
            for (String obb : obbs) {
                String dest = obbDest(obb);
                setStatus("游戏安装完成，正在解压 OBB -> " + dest);
                setIndeterminate(false);
                copyAsset(am, obb, new File(dest));
                updateProgress(100);
            }
            setStatus(obbs.isEmpty() ? "完成：游戏已安装，未发现 OBB。" : "完成：游戏和 OBB 已安装。");
        } catch (Exception e) { setStatus("游戏已安装，但复制 OBB 出错：" + e); }
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
            List<Uri> obbs = new ArrayList<>();
            for (Uri u : uris) {
                String name = docName(u);
                if (name.endsWith(".obb")) {
                    obbs.add(u);
                } else if (name.endsWith(".apk")) {
                    apk = new File(getCacheDir(), "game.apk");
                    setStatus("正在取出游戏 APK...");
                    copyUri(u, apk);
                }
            }
            if (apk == null) {
                setStatus("未选择游戏 APK，无法先安装游戏再复制 OBB。");
                return;
            }
            installApk(apk, () -> copyPickedObbs(obbs));
        } catch (Exception e) { setStatus("出错：" + e); }
    }

    void copyPickedObbs(List<Uri> obbs) {
        try {
            String obbMsg = "";
            for (Uri u : obbs) {
                String name = docName(u);
                String dest = obbDest(name);
                File f = new File(dest);
                setStatus("游戏安装完成，正在复制 OBB -> " + dest);
                setIndeterminate(false);
                copyUri(u, f);
                updateProgress(100);
                obbMsg = f.getParent();
            }
            setStatus("完成：游戏已安装" + (obbMsg.isEmpty() ? "，未选择 OBB。" : ("，OBB 已复制到 " + obbMsg)));
        } catch (Exception e) { setStatus("游戏已安装，但复制 OBB 出错：" + e); }
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
    void installApk(File apk, Runnable afterInstall) {
        runOnUiThread(() -> {
            try {
                PackageInfo archiveInfo = getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                pendingAfterInstall = afterInstall;
                pendingPackageName = archiveInfo == null ? null : archiveInfo.packageName;
                Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
                i.setData(FileProvider.getUriForFile(this, AUTHORITY, apk));
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                i.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                setStatus("请完成游戏 APK 安装，安装成功后将自动复制 OBB。");
                startActivityForResult(i, REQ_INSTALL);
            } catch (Throwable t) {
                pendingAfterInstall = null;
                pendingPackageName = null;
                setStatus("调起安装失败：" + t);
            }
        });
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
