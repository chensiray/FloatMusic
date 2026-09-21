# 网易云 Lua 接口 Windows 实测工具

独立 Qt/C++ 命令行程序，不链接或修改 FloatMusic 业务代码，不读取用户歌单、账号或应用配置，也不加入自动 CTest。只在明确需要时请求真实外部服务。

依赖：Qt 6.8+ 的 Core、Network、Multimedia；本次使用 Qt 6.11.2 / MinGW 13.1 / Windows 11。

## 编译

在项目根目录打开 PowerShell，逐条运行。使用独立英文目录避开工具链的中文路径兼容问题：

```powershell
$probeRoot = 'C:\Dev\FloatMusicApiProbe'
New-Item -ItemType Directory -Path $probeRoot -Force | Out-Null
Copy-Item -LiteralPath 'tests\live_api\CMakeLists.txt','tests\live_api\main.cpp' -Destination $probeRoot -Force
$env:PATH = 'C:\Qt\Tools\mingw1310_64\bin;C:\Qt\Tools\Ninja;C:\Qt\6.11.2\mingw_64\bin;' + $env:PATH
$env:QT_PLUGIN_PATH = 'C:\Qt\6.11.2\mingw_64\plugins'
& 'C:\Qt\Tools\CMake_64\bin\cmake.exe' -S $probeRoot -B "$probeRoot\build" -G Ninja '-DCMAKE_PREFIX_PATH=C:/Qt/6.11.2/mingw_64' '-DCMAKE_CXX_COMPILER=C:/Qt/Tools/mingw1310_64/bin/g++.exe' '-DCMAKE_MAKE_PROGRAM=C:/Qt/Tools/Ninja/ninja.exe'
& 'C:\Qt\Tools\CMake_64\bin\cmake.exe' --build "$probeRoot\build" --parallel 4
New-Item -ItemType Directory -Path 'artifacts\api-probe' -Force | Out-Null
```

## 运行

完整请求：搜索、榜单、歌单详情、推荐网页、两首歌详情和歌词、普通外链、五档音质。网络不佳时可能需要数分钟。

```powershell
& 'C:\Dev\FloatMusicApiProbe\build\live_api_probe.exe' --out 'artifacts/api-probe/full.json' *> 'artifacts/api-probe/full.log'
```

补充检查：HTTPS 搜索/排行榜、手机 User-Agent、批量详情、无效 ID、翻译歌词：

```powershell
& 'C:\Dev\FloatMusicApiProbe\build\live_api_probe.exe' --diagnostics --out 'artifacts/api-probe/diagnostics.json' *> 'artifacts/api-probe/diagnostics.log'
```

只测五档音质；下列代理是本机本次已存在的配置，其他电脑应换成实际地址，或去掉 `--proxy`：

```powershell
& 'C:\Dev\FloatMusicApiProbe\build\live_api_probe.exe' --quality-only --proxy 'http://127.0.0.1:7897' --out 'artifacts/api-probe/quality-retry.json' *> 'artifacts/api-probe/quality-retry.log'
```

其他参数：`--song 347230` 修改第三方音质和普通链接的种子歌曲；`--keyword 纯音乐` 修改搜索词。

## 怎样解读

- `httpStatus`、`businessCode`、`schemaValid` 分别表示传输、业务、样本字段检查，不能只看 HTTP 200。
- `schemaValid=false` 对无效 ID 测试是预期结果，不是测试程序出错。当前字段校验只覆盖脚本需要的关键形状，不是完整协议规范。
- `plainUrlValid` 只说明正文是 HTTP(S) URL；后续 Range 和 Qt 播放才验证音频有效性。
- `passed=true` 的播放项要求：收到有效解码音频、暂停保持位置、跳到目标进度并续播。播放器静音，不代表真人听感验收，也不保证完整曲目从头到尾无误。
- `sampleRate/channels` 是解码输出格式，可能已被重采样；`flacSampleRate/flacBitsPerSample` 来自 FLAC STREAMINFO，才用于判断该源文件规格。
- 音频 Range 请求最多保留 64 KiB；Qt 为播放和跳转另行流式读取。其他响应最多 2 MiB。不导出整首音乐，不保存歌词正文。
- 请求设有超时。JSON 在每项结束后写入，遇到中断可查看已完成结果。程序正常退出码为 0 只表示探测完成，不代表所有外部服务可用。
- `--proxy` 只影响 Qt Network 的接口/Range 请求。Qt Multimedia 的 FFmpeg 网络读取使用其进程环境，两者要分别诊断。本次沙箱的代理端口 9 曾造成假性播放失败，沙箱外复测恢复。
- 不执行 Lua、不运行远端脚本、不关闭 TLS 校验、不修改原应用设置。
- 结果文件位于 Git 忽略的 `artifacts/`。原始日志可能包含短期有效的 CDN URL，不应作为固定播放地址或提交仓库。

本次实测结论见 `docs/网易云API-Windows实测报告-2026-09-16.md`。
