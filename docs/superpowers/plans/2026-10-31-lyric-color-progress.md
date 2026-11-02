# Lyric Color Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** 双端两处歌词提供可关闭、播放同步的连续颜色覆盖。
**Architecture:** QML 共享进度时钟及文字裁剪组件；Android 使用共享纯 Java 时间规则、TextView 绘制覆盖及已有悬浮位图裁剪。只给可见当前句安排帧更新。
**Tech Stack:** Qt Quick / C++17、Android Java。
**Spec:** ../specs/2026-10-31-lyric-color-progress-design.md

## Global Constraints
- 不改变原有布局、曲线透明度、复制、滚动及穿透。
- 默认开启，一个设置控制播放器和独立歌词，两处设置入口；中英文。
- 无逐字数据时按句匀速覆盖，无有效时间区间时整句高亮。
- 保留已有 1.7 变更，产出本地双端安装预览，不上传。

## Review Focus
- 暂停/恢复、向前向后定位与偏移不遗留旧进度。
- 间奏空时间戳不能延长前句覆盖，最后一句使用歌曲时长。
- 长句换行、双语、居中或缩小文字从真实文字边缘开始。
- 效果关闭或歌词不可见时不遗留帧循环。
- 移动端覆盖过程不增加位图重建或窗口移动。

### Task 1: Desktop progress and settings
**Files:** qml/LyricProgress.js, qml/LyricProgressClock.qml, qml/ProgressLyricText.qml, qml/Main.qml, qml/DesktopLyrics.qml, tests/player_tests.cpp, CMakeLists.txt.
**Interfaces:** Clock.progressFor(index) → real [0,1]; text.progress real and progressEnabled bool.
- [x] Add QML rendering/timing/settings tests and confirm feature absent (RED).
- [x] Implement shared clock, per-line clipped text and persistent setting.
- [x] Run desktop targeted tests (GREEN), inspect light/dark progress images.

### Task 2: Android progress and delivery
**Files:** android/src/org/floatmusic/player/LyricProgress.java, ProgressLyricTextView.java, OverlayWindow.java, FloatingLyricsWindow.java, src/uilanguage_en.inc, UiCatalog.java, tests/android/LyricProgressTest.java, LyricWindowRegressionTest.java, version files and release notes.
**Interfaces:** LyricProgress.fraction(start,end,position) → float; update(position,offset,duration,playing,now), position(now); shared preference lyricColorProgress.
- [x] Add pure timing and native rendering/settings/lifecycle checks; verify RED.
- [x] Implement timestamp interpolation and clipped draw without list/window/bitmap churn.
- [x] Run native checks and lyric regression, inspect screenshots.
- [x] Update preview metadata, build Windows/Android, run full desktop suite, verify packaged source/signature and record results.
- [x] Review final scoped diff and deliver local preview links.

Pre-flight: desktop/mobile timing share semantics, no shared mutable runtime. Both settings entries bind to the same per-platform preference. Existing full timestamp rows remain authoritative even when the independent lyric display omits blank rows.

## Completion evidence

- Desktop setting initially failed because progressEnabled did not exist; Android native two render checks initially failed because no within-line color change occurred. Shared pure Java clock initially absent. All are GREEN after implementation.
- Existing lineSpacing persistence test caught premature initialization caused by sharing the existing Settings object; isolated desktop preference to `lyricColorProgress/enabled`. Tight/wide preferences and full player suite GREEN.
- Three independent reviewer notes addressed: offscreen active row stops its frame loop (native RED→GREEN); complete/fallback desktop progress stops timer (RED→GREEN); mobile bitmap preparation moved out of onDraw. No unresolved Important/Critical or deferred minor findings.
- Wrapped and fitted text resize test exposed transient Repeater bounds during reflow, now guarded; no QML engine warnings in the rendering test.
- Final desktop CTest 19/19; player 29 passes, one explicitly opt-in live network case skipped. Native 12 broader lyric scenes and 5 final feature/lifecycle scenes pass, plus pure Java timing/fade tests. Screenshots inspected in both themes.
- Windows staged source 147 files match; Android packaged snapshot 107 files matches. Preview version 1.7.0-preview.3, Android code 33 and unchanged signing certificate. SHA256 and full evidence in `artifacts/v1.7-preview3/acceptance.json`.
- Current lyric source has line-level timestamps, so this implements uniform within-line color progress, not word-level singing synchronization. No remote publication requested or performed.
