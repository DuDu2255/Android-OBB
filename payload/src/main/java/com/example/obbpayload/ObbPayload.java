package com.example.obbpayload;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public final class ObbPayload {
    public static final String ACTION = "com.example.obbinstaller.INSTALL_OBB";
    public static final String ACTION_STATUS = "com.example.obbinstaller.OBB_STATUS";
    public static final String EXTRA_NAMES = "com.example.obbinstaller.OBB_NAMES";
    public static final String EXTRA_STAGE = "com.example.obbinstaller.OBB_STAGE";
    public static final String EXTRA_MESSAGE = "com.example.obbinstaller.OBB_MESSAGE";

    private ObbPayload() {}

    public static boolean handle(Activity activity) {
        Intent intent = activity.getIntent();
        if (intent == null || !ACTION.equals(intent.getAction())) return false;

        report(activity, "entered", "OBB 载荷已进入游戏启动 Activity");
        new Handler(Looper.getMainLooper()).post(() -> showCopyUi(activity, intent));

        // Let the game's original onCreate call Activity.onCreate before showing our UI.
        return false;
    }

    private static void showCopyUi(Activity activity, Intent intent) {
        if (activity.isFinishing()) {
            report(activity, "error", "游戏 Activity 在复制开始前已结束");
            return;
        }
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        TextView status = new TextView(activity);
        status.setText("正在把 OBB 写入游戏目录...");
        ProgressBar progress = new ProgressBar(activity, null,
                android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        Button launch = new Button(activity);
        launch.setText("启动游戏");
        launch.setVisibility(View.GONE);
        layout.addView(status);
        layout.addView(progress);
        layout.addView(launch);
        activity.setContentView(layout);
        report(activity, "ui", "OBB 复制界面已显示");

        launch.setOnClickListener(v -> {
            Intent clean = new Intent(intent);
            clean.setAction(Intent.ACTION_MAIN);
            clean.setData(null);
            clean.setClipData(null);
            clean.removeExtra(EXTRA_NAMES);
            clean.setFlags(0);
            activity.setIntent(clean);
            activity.recreate();
        });

        new Thread(() -> copyObbs(activity, intent, status, progress, launch)).start();
    }

    private static void copyObbs(Activity activity, Intent intent, TextView status,
                                 ProgressBar progress, Button launch) {
        try {
            ClipData clip = intent.getClipData();
            String[] names = intent.getStringArrayExtra(EXTRA_NAMES);
            if (clip == null || clip.getItemCount() == 0 || names == null
                    || names.length != clip.getItemCount()) {
                throw new IllegalArgumentException("未收到完整的 OBB 文件信息");
            }
            File obbDir = activity.getObbDir();
            if (obbDir == null) throw new IllegalStateException("系统未提供游戏 OBB 目录");
            report(activity, "directory", "getObbDir=" + obbDir.getAbsolutePath()
                    + ", exists=" + obbDir.exists() + ", writable=" + obbDir.canWrite());
            if (!obbDir.exists() && !obbDir.mkdirs() && !obbDir.isDirectory()) {
                throw new IllegalStateException("游戏无法创建自己的 OBB 目录：" + obbDir);
            }
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri source = clip.getItemAt(i).getUri();
                File destination = new File(obbDir, names[i]);
                long total = -1;
                try {
                    total = activity.getContentResolver().openFileDescriptor(source, "r").getStatSize();
                } catch (Exception ignored) {}
                final int fileIndex = i;
                final int fileCount = clip.getItemCount();
                final long fileSize = total;
                report(activity, "copy", "开始复制 " + names[i] + ", size=" + total);
                activity.runOnUiThread(() -> status.setText("正在复制 "
                        + (fileIndex + 1) + "/" + fileCount + "：" + names[fileIndex]));
                try (InputStream in = activity.getContentResolver().openInputStream(source);
                     OutputStream out = new FileOutputStream(destination)) {
                    if (in == null) throw new IllegalStateException("无法打开 OBB 文件");
                    byte[] buffer = new byte[1024 * 1024];
                    long done = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                        done += count;
                        if (fileSize > 0) {
                            final int value = (int) Math.min(100, done * 100 / fileSize);
                            activity.runOnUiThread(() -> progress.setProgress(value));
                        }
                    }
                }
                report(activity, "copied", "复制完成 " + names[i]
                        + ", bytes=" + destination.length());
            }
            report(activity, "complete", "全部 OBB 已写入 " + obbDir.getAbsolutePath());
            activity.runOnUiThread(() -> {
                progress.setProgress(100);
                status.setText("OBB 已写入游戏目录。现在可以启动游戏。");
                launch.setVisibility(View.VISIBLE);
            });
        } catch (Throwable error) {
            report(activity, "error", error.toString());
            activity.runOnUiThread(() -> status.setText("复制 OBB 失败：" + error));
        }
    }

    private static void report(Activity activity, String stage, String message) {
        try {
            Intent status = new Intent(ACTION_STATUS);
            status.setPackage("com.example.obbinstaller");
            status.putExtra(EXTRA_STAGE, stage);
            status.putExtra(EXTRA_MESSAGE, message);
            activity.sendBroadcast(status);
        } catch (Throwable ignored) {}
    }
}
