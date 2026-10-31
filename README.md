# OBB 自动安装器（虫虫同款「点开/选择即自动解压」）
================================================
一个 Android 安装器 APK：把游戏 APK + OBB 放进去（或运行时从手机选择），
**自动识别 OBB 应放的路径**并安装游戏。无需硬编码包名。

## 自动识别规则
OBB 文件名形如 `main.<版本>.<包名>.obb` 或 `patch.<版本>.<包名>.obb`，
安装器据此自动放到：`/sdcard/Android/obb/<包名>/<原文件名>`。
（若素材已放在 `assets/sdcard/Android/obb/...` 下，则直接镜像该路径。）

## 两种用法
1. 打包进安装器（单包）：把游戏 APK 命名为 `infoplay`、OBB 放到
   `assets/sdcard/Android/obb/<包名>/` 打进本 APK，启动即自动处理。
2. 运行时选择：点「选择外部 APK / OBB」，从手机里选文件，自动识别并安装。

## 构建
- 本地：Android Studio 打开 `installer_app`，Build APK。
- 云编译：推到 GitHub 后，Actions 自动构建（见 .github/workflows/build.yml），
  产物在 Actions 页面的 artifact 里下载。

## 注意
- Android 11+ 需授予「所有文件访问」权限（代码已自动引导）。
- OBB 文件名必须是 `main.<ver>.<包名>.obb`，否则无法识别包名。
