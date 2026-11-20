# CarNat

无需使用转接盒，无需 Root 与 Shizuku，仅一个应用实现 CarWith 对 CarPlay 的兼容！  
通过替换 HyperOS 的 CarLife 组件，实现几乎无感且稳定的连接体验。  
使用原生 Android Jetpack Compose，实现无与伦比，易于使用的 Material Design 3 界面。

## 功能

- 将 CarWith 通过 CarPlay 连接到车机
- 实现对视频、音频及触控转发的支持
- 支持连上车机蓝牙后自动连接（CarWith 功能，无需设置自启动）
- 支持对分辨率，帧率，码率，音频缓冲的自定义设置，以及详细的监测面板
- 支持车机麦克风输入（实验性）

## 安装与连接

需要一台带 CarWith 的小米手机，以及支持无线 CarPlay 的车机或测试接收端。实际能否投屏取决于 HyperOS、CarWith 版本和接收端。

[GitHub Releases](https://github.com/TiansinKinyon/CarNat/releases) 提供了最新的 APK 安装包。代码仓库与发行版已同步至 Gitee，也可以在 [Gitee 发行版](https://gitee.com/TiansinKinyon/CarNat/releases) 获取最新安装包，对于中国大陆访问与下载更快。

### 使用 CarWith 发起 连接方式（默认，推荐）

1. 先卸载原有的 百度 CarLife 组件
2. 打开手机蓝牙和 WLAN，完成手机与车机的蓝牙配对，建议可以一并在 CarNat 中填写本机蓝牙地址。
3. 打开 CarWith 手机端。在 **CarLife 连接 → 无线连接 → 车辆热点** 中选择车机蓝牙并开始连接。
4. 连接过程中会提示连接车机热点，请允许连接。成功后可在 CarWith 的「我的车」中检查「无线自动连接」设置。

若遇到问题可以使用 CarNat 接管连接，了解更多请在 CarNat 帮助页面获取更多信息。

反馈问题时可附上手机型号、HyperOS 和 CarWith 版本、车机型号和固件版本，以及脱敏后的诊断日志。

## 构建与测试

用 Android Studio 打开仓库根目录，安装 Android SDK Platform 37。项目使用 Gradle Wrapper，仓库配置的 Gradle Daemon JVM 为 Java 25。

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest
```

macOS 和 Linux 可使用 `./gradlew :app:assembleDebug :app:testDebugUnitTest`。调试 APK 位于 `app/build/outputs/apk/debug/`。

## 参考项目

- [CarDeck](https://github.com/shaonialife/cardeck) 提供了 Android 作为无线 CarPlay 发送端的实现和协议笔记。
- [mCarPlay](https://github.com/wangxso/mcarplay) 提供了小米 CarWith 到无线 CarPlay 的桥接实现及接收端兼容性记录。
- [CarPToCarL](https://github.com/jjuugk/CarPToCarL) 提供了车机发现、音频和重连的实车记录。
- [HyperCar](https://github.com/fw867/hypercar) 提供了宝马与大众车机的具体连接实现
- CarLife+ Bridge 参考了其通过替换 CarLife 组件实现连接的思路。

## 许可

CarNat 自有代码计划以 MIT 许可证开源。第三方依赖和材料适用其各自的许可。

## 安全驾驶须知

本应用程序并未获得手机厂商与汽车厂商的官方支持，请勿依赖该软件。  
请严格遵守交通法规，保持安全驾驶，严禁在行驶途中操作本应用程序。

若在驾驶途中遇到断开连接等故障，严禁在行驶途中调试本软件，在确认安全的前提下驻车进行检查。  
若行驶中导航功能失效，应当按照当前道路与交通标志要求安全行驶，在确认安全的前提下驻车进行检查。

## 免责声明

CarNat 是独立开发的实验性项目，与 Apple、小米、百度及各车企无隶属或合作关系，也未获得其认证。CarPlay、CarWith、CarLife 等名称仅用于说明兼容对象，相关商标归各自权利人所有。

本软件按现状提供。连接、音频和触控表现会随手机系统、CarWith 版本和车机固件而变化，投屏也可能中断。请在停车时安装、配置和测试，驾驶过程中请勿操作手机或调整投屏设置，并保留原厂导航等备用方案。

## AI 生成内容披露

协议部分使用 Codex 开发，UI界面由 AI 辅助开发。