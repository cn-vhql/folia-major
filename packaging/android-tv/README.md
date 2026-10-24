# Folia Android TV

当前目录提供 Folia Web 的 Android TV 外壳。应用不复制音乐业务代码，而是加载你部署的
Folia Web/Docker 地址；因此在线音乐、歌词、主题、账户、同步、设置和后续 Web 更新都与
该地址提供的 Web 版本一致。

## 功能

- Android TV Leanback 启动入口和普通 Android 启动入口。
- 首次启动配置 Folia 服务器地址，支持 HTTP 和 HTTPS。
- 方向键按屏幕几何位置移动焦点，确认键激活当前控件。
- 返回键优先执行 Web 页面自己的 Escape/返回层级，再使用网页历史。
- 媒体键映射播放/暂停、上一首、下一首、快退和快进。
- 遥控器 MENU/INFO/SETTINGS 键随时打开服务器设置。
- 默认拒绝无效 TLS 证书；可在设置中仅为当前服务器显式允许自签名证书。
- 保留 Cookie、localStorage、IndexedDB 和 WebView 缓存。

## 构建

需要 Android SDK Platform 36、Build Tools 36.0.0 和 JDK 17。构建不依赖 Gradle 或网络：

```bash
chmod +x packaging/android-tv/build-apk.sh
packaging/android-tv/build-apk.sh
```

产物为 `packaging/android-tv/build/FoliaTV.apk`。脚本首次构建会在
`packaging/android-tv/.signing/` 下生成调试签名密钥；保留该密钥才能覆盖安装后续构建。
要发布稳定版本，请通过 `FOLIA_TV_KEYSTORE` 指向自己的长期密钥。

## 安装

可以复制 APK 到 U 盘后使用电视盒子的文件管理器安装，也可以使用 ADB：

```bash
adb connect <电视盒子IP>:5555
adb install -r packaging/android-tv/build/FoliaTV.apk
```

首次启动输入 Web 部署地址，例如 `http://192.168.1.10:18080`。

## 平台边界

Web 版依赖浏览器能力。目录级本地音乐导入需要 File System Access API，多数 Android TV
WebView 不提供该 API；视频图层通过普通文件选择器可用。其他 Web/Docker 功能由所连接的
Folia 服务决定。Android 7.0 以下、系统 WebView 过旧或不允许 WebView 更新的设备不在支持范围。
