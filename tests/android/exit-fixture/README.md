# Android 原生退出回归

在仓库根目录运行：

```powershell
./tests/android/run-exit-regression.ps1 -DeviceSerial emulator-5580 -Label verification
```

`-BuildOnly` 仅生成隔离 APK，不连接设备。真机需要显式序列号和 `-AllowPhysicalDevice`；此回归自行安装 `org.floatmusic.exitfixture`，不会启动、覆盖或卸载正式 `org.floatmusic.player`。只使用本机 Android SDK/JDK、生成的静音 WAV 和测试包自己的文件及偏好，不连接音源。

八个场景覆盖：冷启动无服务的工作线程／主线程退出、重复退出、前台服务尚未执行 `onCreate` 时退出、实际播放并退到后台后退出、暂停现场退出、仅剩服务时退出、返回键保持后台语义。真实 `PlayerActivity`、`PlayerBridge`、`PlaybackService` 和通知／悬浮窗执行 Android 生命周期；断言任务从最近任务移除、MediaPlayer 与两窗释放、通知消失、系统服务停止、现场在 Activity 销毁前保存，重新启动服务后歌曲和进度恢复为暂停。

`QtActivity.java` 只替换 Qt 的原生加载边界，使 ARM64 项目 Java 能在隔离 x86 模拟器执行。它继承真实 Android `Activity`，不模拟任务或服务。此测试不覆盖 QML 点击、JNI、Qt 原生终止或完整 ARM64 APK；这些仍需完整应用构建和目标设备验收。生产源码另由 `run-v14-contract-tests.ps1` 配合真实 Qt 绑定编译。

构建、APK、结果写入 `artifacts/v1.5-2026-10-25/exit/<Label>-<时间>/`。本机原生 `aapt` 在受限执行中曾以 `-1073741819` 崩溃，允许本地 Android 构建工具执行后成功；这不是退出测试的红结果。
