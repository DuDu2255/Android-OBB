package com.example.obbinstaller;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.content.res.AssetManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.documentfile.provider.DocumentFile;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final String AUTHORITY = "com.example.obbinstaller.fileprovider";
    static final int REQ_PICK = 1001;
    static final int REQ_INSTALL = 1002;
    static final int REQ_STORAGE = 1003;
    static final int REQ_OBB_TREE = 1004;
    static final int REQ_UNINSTALL = 1005;
    TextView tv;
    ProgressBar pb;
    private boolean started = false;
    private boolean storagePermissionRequested = false;
    TextView logView;
    private volatile Runnable pendingAfterInstall;
    private volatile String pendingPackageName;

    private volatile List<Uri> pendingSafObbs;
    private volatile File pendingPatchedApk;
    private volatile String pendingPatchedPackage;
    private volatile List<Uri> pendingPatchedObbs;
    private final Object logLock = new Object();
    private final StringBuilder diagnosticLog = new StringBuilder();
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
        File oldLog = diagnosticFile();
        if (oldLog.exists()) oldLog.delete();
        logEnvironment();

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
        logDiagnostic("onResume: allFiles=" + safeIsExternalStorageManager()
                + ", started=" + started + ", pendingInstall=" + (pendingAfterInstall != null));
        // Winlator 同款思路：从设置页返回后再次检查；已获权限则启动安装（仅一次），未获授权则等待用户去设置
        if (Build.VERSION.SDK_INT >= 30 && !safeIsExternalStorageManager()) return;
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT <= 32
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            if (!storagePermissionRequested) {
                storagePermissionRequested = true;
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_STORAGE);
            }
            return;
        }
        startBundledOnce();
    }

    void startBundledOnce() {
        if (!started) {
            started = true;
            new Thread(this::installBundled).start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_STORAGE) return;
        storagePermissionRequested = false;
        logDiagnostic("storagePermissionResult: WRITE_EXTERNAL_STORAGE="
                + (Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED));
        if (Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            startBundledOnce();
        } else {
            setStatus("未授予存储权限，无法复制 OBB。");
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
        Button logButton = new Button(this);
        logButton.setText("复制诊断日志");
        logButton.setOnClickListener(v -> copyDiagnosticLog());
        logView = new TextView(this);
        logView.setTextIsSelectable(true);
        logView.setTextSize(11);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 40, 40, 40);
        ll.addView(tv);
        ll.addView(pb);
        ll.addView(btn);
        ll.addView(logButton);
        ll.addView(logView);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(ll);
        setContentView(scroll);
    }

    File diagnosticFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) dir = getFilesDir();
        return new File(dir, "obb-installer-diagnostic.log");
    }

    void logDiagnostic(String message) {
        String line = System.currentTimeMillis() + " | " + message + "\n";
        String snapshot;
        synchronized (logLock) {
            diagnosticLog.append(line);
            snapshot = diagnosticLog.toString();
            try (FileOutputStream out = new FileOutputStream(diagnosticFile(), true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
        if (logView != null) runOnUiThread(() -> logView.setText(snapshot));
    }

    void logEnvironment() {
        int writePermission = Build.VERSION.SDK_INT < 23
                ? PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        logDiagnostic("appVersion=2.0.2(7), sdk=" + Build.VERSION.SDK_INT
                + ", android=" + Build.VERSION.RELEASE
                + ", device=" + Build.MANUFACTURER + " " + Build.MODEL
                + ", targetSdk=" + getApplicationInfo().targetSdkVersion);
        logDiagnostic("isExternalStorageManager=" + safeIsExternalStorageManager()
                + ", WRITE_EXTERNAL_STORAGE=" + (writePermission == PackageManager.PERMISSION_GRANTED));
        File root = Environment.getExternalStorageDirectory();
        logDiagnostic(describeFile("externalRoot", root));
        logDiagnostic(describeFile("Android", new File(root, "Android")));
        logDiagnostic(describeFile("Android/obb", new File(root, "Android/obb")));
        logDiagnostic("logFile=" + diagnosticFile().getAbsolutePath());
    }

    String describeFile(String label, File file) {
        return label + "=" + file.getAbsolutePath()
                + " {exists=" + file.exists()
                + ", directory=" + file.isDirectory()
                + ", readable=" + file.canRead()
                + ", writable=" + file.canWrite() + "}";
    }

    void copyDiagnosticLog() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        String text;
        synchronized (logLock) { text = diagnosticLog.toString(); }
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("OBB 安装器诊断日志", text));
            toast("诊断日志已复制");
        }
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
            new Thread(() -> finishInstall(res, data)).start();
        } else if (req == REQ_OBB_TREE && res == RESULT_OK && data != null && data.getData() != null) {
            Uri treeUri = data.getData();
            int flags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(treeUri, flags); }
            catch (Exception e) { logDiagnostic("takeTreePermission failed: " + e); }
            List<Uri> obbs = pendingSafObbs;
            pendingSafObbs = null;
            new Thread(() -> copyPickedObbsWithSaf(obbs, treeUri)).start();
        } else if (req == REQ_OBB_TREE) {
            pendingSafObbs = null;
            setStatus("未授权 OBB 目录，无法继续复制。");
        } else if (req == REQ_UNINSTALL) {
            new Thread(this::continueAfterUninstall).start();
        } else super.onActivityResult(req, res, data);
    }

    void finishInstall(int resultCode, Intent data) {
        Runnable afterInstall = pendingAfterInstall;
        String packageName = pendingPackageName;
        boolean installed = resultCode == RESULT_OK || waitForPackageInstalled(packageName);
        int packageStatus = data == null ? Integer.MIN_VALUE
                : data.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        int legacyStatus = data == null ? Integer.MIN_VALUE
                : data.getIntExtra("android.intent.extra.INSTALL_RESULT", Integer.MIN_VALUE);
        String statusMessage = data == null ? null
                : data.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        logDiagnostic("installResult: resultCode=" + resultCode + ", package=" + packageName
                + ", detectedInstalled=" + installed + ", packageStatus=" + packageStatus
                + ", legacyStatus=" + legacyStatus + ", message=" + statusMessage);
        pendingAfterInstall = null;
        pendingPackageName = null;
        if (installed && afterInstall != null) {
            afterInstall.run();
        } else {
            setStatus("修补版安装失败：result=" + resultCode + "，status=" + packageStatus
                    + "，legacy=" + legacyStatus + "，" + statusMessage);
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
                logDiagnostic("pickedFile: name=" + name + ", uri=" + u);
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
            setStatus("正在修补游戏 APK，使游戏能够写入自己的 OBB 目录...");
            GameApkPatcher.Result patched = new GameApkPatcher(
                    this, message -> logDiagnostic("PATCH: " + message)).patch(apk);
            preparePatchedInstall(patched, obbs);
        } catch (Exception e) { setStatus("出错：" + e); }
    }

    void preparePatchedInstall(GameApkPatcher.Result patched, List<Uri> obbs) {
        pendingPatchedApk = patched.apk;
        pendingPatchedPackage = patched.packageName;
        pendingPatchedObbs = new ArrayList<>(obbs);
        if (isPackageInstalled(patched.packageName)) {
            setStatus("修补版使用新签名。请确认卸载当前游戏；卸载完成后会自动安装修补版。");
            runOnUiThread(() -> {
                try {
                    Intent uninstall = new Intent(Intent.ACTION_DELETE,
                            Uri.parse("package:" + patched.packageName));
                    uninstall.putExtra(Intent.EXTRA_RETURN_RESULT, true);
                    startActivityForResult(uninstall, REQ_UNINSTALL);
                } catch (Throwable t) {
                    setStatus("无法调起卸载：" + t);
                }
            });
        } else {
            installPendingPatchedApk();
        }
    }

    void continueAfterUninstall() {
        String packageName = pendingPatchedPackage;
        if (packageName != null && isPackageInstalled(packageName)) {
            setStatus("原游戏仍然存在，无法安装不同签名的修补版。");
            return;
        }
        installPendingPatchedApk();
    }

    void installPendingPatchedApk() {
        File apk = pendingPatchedApk;
        String packageName = pendingPatchedPackage;
        List<Uri> obbs = pendingPatchedObbs;
        if (apk == null || packageName == null || obbs == null) {
            setStatus("修补安装状态已丢失，请重新选择 APK 和 OBB。");
            return;
        }
        installApk(apk, () -> {
            pendingPatchedApk = null;
            pendingPatchedPackage = null;
            pendingPatchedObbs = null;
            launchPatchedGame(packageName, obbs);
        });
    }

    boolean isPackageInstalled(String packageName) {
        if (packageName == null) return false;
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    void launchPatchedGame(String packageName, List<Uri> obbs) {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launch == null) throw new IOException("找不到修补版游戏的启动 Activity");
            String[] names = new String[obbs.size()];
            ClipData clip = null;
            for (int i = 0; i < obbs.size(); i++) {
                Uri uri = obbs.get(i);
                names[i] = docName(uri);
                ClipData.Item item = new ClipData.Item(uri);
                if (clip == null) {
                    clip = new ClipData("OBB", new String[]{"application/octet-stream"}, item);
                } else {
                    clip.addItem(item);
                }
            }
            if (clip == null) throw new IOException("未选择 OBB 文件");
            launch.setAction("com.example.obbinstaller.INSTALL_OBB");
            launch.setClipData(clip);
            launch.putExtra("com.example.obbinstaller.OBB_NAMES", names);
            launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            logDiagnostic("launchPatchedGame: package=" + packageName + ", obbCount=" + obbs.size());
            runOnUiThread(() -> {
                setStatus("修补版已安装，正在由游戏自身复制 OBB...");
                startActivity(launch);
            });
        } catch (Exception e) {
            setStatus("无法启动修补版写入 OBB：" + e);
        }
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
        } catch (Exception e) {
            logDiagnostic("direct OBB copy failed: " + e);
            requestSafObbAccess(obbs);
        }
    }

    void requestSafObbAccess(List<Uri> obbs) {
        pendingSafObbs = new ArrayList<>(obbs);
        setStatus("直接写入被系统拒绝。请在目录选择器中打开“内部存储/Android/obb”，"
                + "然后点击“使用此文件夹”。");
        runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                if (Build.VERSION.SDK_INT >= 26) {
                    Uri initial = DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", "primary:Android/obb");
                    intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
                }
                startActivityForResult(intent, REQ_OBB_TREE);
            } catch (Throwable t) {
                pendingSafObbs = null;
                setStatus("无法打开系统目录授权器：" + t);
            }
        });
    }

    void copyPickedObbsWithSaf(List<Uri> obbs, Uri treeUri) {
        if (obbs == null || obbs.isEmpty()) {
            setStatus("没有待复制的 OBB 文件。");
            return;
        }
        try {
            DocumentFile selected = DocumentFile.fromTreeUri(this, treeUri);
            if (selected == null || !selected.canWrite()) {
                throw new IOException("所选目录不可写：" + treeUri);
            }
            logDiagnostic("SAF selected: uri=" + treeUri + ", name=" + selected.getName()
                    + ", canWrite=" + selected.canWrite());
            for (Uri source : obbs) {
                String name = docName(source);
                String pkg = obbPackage(name);
                if (pkg == null) throw new IOException("无法从 OBB 文件名识别包名：" + name);
                DocumentFile packageDir = resolveSafPackageDir(selected, pkg);
                DocumentFile old = packageDir.findFile(name);
                if (old != null && !old.delete()) {
                    throw new IOException("无法覆盖已有 OBB：" + name);
                }
                DocumentFile destination = packageDir.createFile("application/octet-stream", name);
                if (destination == null) throw new IOException("无法创建 OBB 文件：" + name);
                long total = -1;
                try { total = getContentResolver().openFileDescriptor(source, "r").getStatSize(); }
                catch (Exception ignored) {}
                logDiagnostic("SAF copy: source=" + source + ", destination="
                        + destination.getUri() + ", size=" + total);
                try (InputStream in = getContentResolver().openInputStream(source);
                     OutputStream out = getContentResolver().openOutputStream(destination.getUri(), "w")) {
                    if (in == null || out == null) throw new IOException("无法打开 OBB 输入或输出流");
                    pipe(in, out, total);
                }
            }
            setStatus("完成：已通过系统目录授权复制 OBB。");
        } catch (Exception e) {
            setStatus("系统目录授权写入仍然失败：" + e);
        }
    }

    DocumentFile resolveSafPackageDir(DocumentFile selected, String pkg) throws IOException {
        String selectedName = selected.getName();
        if (pkg.equals(selectedName)) return selected;
        DocumentFile base = selected;
        if (!"obb".equalsIgnoreCase(selectedName)) {
            if ("Android".equalsIgnoreCase(selectedName)) base = findOrCreateDirectory(base, "obb");
            else {
                DocumentFile android = base.findFile("Android");
                if (android != null && android.isDirectory()) base = findOrCreateDirectory(android, "obb");
            }
        }
        return findOrCreateDirectory(base, pkg);
    }

    DocumentFile findOrCreateDirectory(DocumentFile parent, String name) throws IOException {
        DocumentFile child = parent.findFile(name);
        if (child != null && child.isDirectory()) return child;
        child = parent.createDirectory(name);
        if (child == null) throw new IOException("无法通过目录授权创建：" + name);
        return child;
    }

    String obbPackage(String name) {
        int first = name.indexOf('.');
        int second = first < 0 ? -1 : name.indexOf('.', first + 1);
        if (second < 0 || !(name.startsWith("main.") || name.startsWith("patch."))) return null;
        String pkg = name.substring(second + 1);
        return pkg.endsWith(".obb") ? pkg.substring(0, pkg.length() - 4) : pkg;
    }

    String docName(Uri u) {
        String n = null;
        Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (c != null) { if (c.moveToFirst()) n = c.getString(0); c.close(); }
        if (n == null) n = u.getLastPathSegment();
        return n == null ? "file.obb" : n;
    }

    void ensureDirectory(File dir) throws IOException {
        logDiagnostic("ensureDirectory: " + describeFile("target", dir));
        if (dir.exists()) {
            if (!dir.isDirectory()) throw new IOException("目标路径存在但不是目录: " + dir);
            return;
        }
        File ancestor = dir.getParentFile();
        if (ancestor != null && !ancestor.exists()) ensureDirectory(ancestor);
        if (ancestor != null) logDiagnostic(describeFile("parent", ancestor));
        try {
            Os.mkdir(dir.getAbsolutePath(), 0775);
            logDiagnostic("Os.mkdir success: " + dir);
        } catch (ErrnoException e) {
            logDiagnostic("Os.mkdir failed: path=" + dir + ", errno=" + e.errno
                    + ", detail=" + e.getMessage());
            if (!dir.isDirectory()) {
                throw new IOException("无法创建目录: " + dir + "；errno=" + e.errno
                        + " (" + e.getMessage() + ")。诊断日志可在界面复制。", e);
            }
        } catch (Throwable t) {
            logDiagnostic("Os.mkdir unexpected failure: " + t);
            if (!dir.mkdirs() && !dir.isDirectory()) {
                throw new IOException("无法创建目录: " + dir + "；" + t
                        + "。诊断日志可在界面复制。", t);
            }
        }
        logDiagnostic("directory result: " + describeFile("target", dir));
    }

    void copyAsset(AssetManager am, String asset, File out) throws Exception {
        File parent = out.getParentFile();
        logDiagnostic("copyAsset: source=" + asset + ", destination=" + out);
        if (parent != null) ensureDirectory(parent);
        long total = -1;
        try { total = am.openFd(asset).getLength(); } catch (Exception ignored) {}
        InputStream in = am.open(asset);
        FileOutputStream fo = new FileOutputStream(out);
        pipe(in, fo, total);
        in.close(); fo.close();
    }
    void copyUri(Uri u, File out) throws Exception {
        File parent = out.getParentFile();
        logDiagnostic("copyUri: source=" + u + ", destination=" + out);
        if (parent != null) ensureDirectory(parent);
        long total = -1;
        try { total = getContentResolver().openFileDescriptor(u, "r").getStatSize(); } catch (Exception ignored) {}
        logDiagnostic("copyUri: sourceSize=" + total + ", parent="
                + (parent == null ? "null" : describeFile("target", parent)));
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
                logDiagnostic("installApk: apk=" + apk + ", parsedPackage=" + pendingPackageName
                        + ", size=" + apk.length());
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
    void setStatus(final String s) {
        logDiagnostic("STATUS: " + s);
        runOnUiThread(() -> { if (tv != null) tv.setText(s); });
    }
    void updateProgress(final int p) { runOnUiThread(() -> { if (pb != null) { pb.setIndeterminate(false); pb.setProgress(p); } }); }
    void setIndeterminate(final boolean ind) { runOnUiThread(() -> { if (pb != null) pb.setIndeterminate(ind); }); }
    void showError(Throwable t) {
        String msg = "发生错误（已避免闪退）：\n" + (t == null ? "未知" : t.toString());
        logDiagnostic("UNCAUGHT: " + msg);
        if (tv != null) tv.setText(msg);
        else { TextView t2 = new TextView(this); t2.setText(msg); setContentView(t2); }
    }
    void toast(final String s) {
        runOnUiThread(() -> android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show());
    }
}
