# 浮音应用图标与圆形悬浮图标

用户已批准将生成的珠光音符图作为浮音应用图标，并另做同风格圆形版本用于悬浮窗。

**Goal:** 双端实际采用批准的图标，保留原有界面与操作。

**Architecture:** PNG 设计源经固定尺寸和格式适配后保存到 `assets/icons`。Qt 使用资源文件，Windows 同时嵌入 ICO；Android 使用启动器资源及圆形悬浮按钮背景。无播放逻辑变更。

**Tech Stack:** ImageGen、PNG／ICO、Qt Quick、CMake／Windows RC、Android 原生 Drawable／mipmap。

## 范围与检查

- [x] 应用图标采用 `artifacts/icon-design-2026-10-19/floatmusic-pearl-icon-v1.png`，保留批准的音符造型、配色和材质。
- [x] 使用生图制作同一音符的圆形玻璃版本，圆盘外透明，保留源图及提示词。
- [x] 格式适配为 Qt PNG、Windows 多尺寸 ICO、Android 常规密度与自适应启动器资源。
- [x] Qt 应用／窗口、Windows EXE、Android 启动器与欢迎页使用统一音符身份；欢迎页使用透明圆形变体。
- [x] 两端收起后的悬浮窗使用圆形 PNG，保留原 64px／56dp、拖动、点击、无障碍及反馈。
- [x] 独立目录构建预览并检查资源解码、小尺寸实际渲染和原有界面回归。
- [x] 交付可运行包与两份图标，不替换播放器内的播放、暂停、音量等操作图标。

Android 图标修订包为 `1.3.0-preview.1`／code18；桌面保持 `1.3.0-preview`。保留此前 1.3 交付包及所有原有未提交改动。当前手机未连接时完成构建与本机验证，最终明确安装状态。
