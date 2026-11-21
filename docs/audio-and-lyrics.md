# LMusic 音频引擎与歌词增强

本次开发在保留 LMusic 原有页面结构、导航和交互逻辑的前提下，补齐了音频解码、音频输出、逐字歌词与 Pro 音频能力，并对首页推荐卡片 / 播放条的视觉做了毛玻璃 + Monet 取色流光升级。

## 1. 解码：硬件优先 + 全格式软解

- 保留 Media3 的 `MediaCodecAudioRenderer`，AAC、MP3、FLAC、Opus、Vorbis 等优先使用系统硬件/平台解码器。
- 新增 `lplayer/lib-ffmpeg`：基于 FFmpeg 9.0.1 的音频解码扩展，通过 FFmpeg 的 demuxer 读取容器，再交给 FFmpeg 解码器或平台解码器。
- 解码策略可在“设置 → 音频输出 → 解码方式”切换：
  - 自动（硬件优先）：平台解码器优先，失败时自动交给 FFmpeg。
  - 软解优先：FFmpeg 解码器排在平台解码器之前。
  - 仅软解：清空 `MediaCodecSelector`，只使用 FFmpeg。
- 已覆盖的格式（取决于文件实际编码）：
  - 常见：MP3、AAC/M4A、FLAC、ALAC、WAV、AIFF/AIFC、OGG Vorbis、Opus、AC3/EAC3、DTS/DTS-HD、TrueHD/MLP、AMR。
  - 扩展：APE（Monkey's Audio）、WavPack、TTA、TAK、WMA v1/v2/Pro/Lossless、Shorten、DSF（DSD）、DFF（未压缩 DSD）、DST（由 FFmpeg 解码器支持时）。
- `MP4/M4A` 的 ALAC 通过 FFmpeg demuxer + ALAC 解码器处理；MediaStore 把 ALAC 误报为 `audio/mpeg` 时，会读取扫描器写入的真实文件路径判断扩展名。
- 容器探测增加 `format_whitelist`，避免任意网络/外部协议；自定义 `AVIO` 只读取当前 DataSource。
- 自定义文件夹扫描（SAF 或直接目录）现在会合并进媒体库，不再只出现在文件夹页。

## 2. 输出与音质

- 输出采样率：自动 / 44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz；自动模式保留源采样率，超过 192 kHz 的 DSD 自动下采样。
- 输出位深：16-bit / 32-bit float（依赖设备 AudioTrack 支持）。
- 自研 32-tap Lanczos 加窗 sinc 重采样器，带抗混叠截止频率和滤波历史，流式处理不中断播放。
- DSD / 高采样率增益：0–12 dB，用于 DSD 转 PCM 后的电平补偿。
- 10 段均衡器（31 Hz–16 kHz，±12 dB），内置平直/流行/摇滚/爵士/古典/低音/人声/轻音乐预设。
- 无间隙播放开关；播放条与进度条使用 Monet 取色的流光渐变。
- USB 音频（Pro/实验性）：检测 USB DAC、申请 USB 权限、展示采样率/位深能力，并把 `AudioTrack` 的 preferred device 固定到 USB 输出。
  - 说明：Android 公共 API 不提供等时 USB 音频传输，真正的 bit-perfect USB 独占需要 UAC 驱动/系统支持；当前实现提供直连路由与能力展示，不做虚假的“位完美”承诺。

## 3. 逐字歌词 / SPL

- 新增 `SplParser`，按 SPL（Salt Player Lyrics）标准解析增强型 LRC：
  - `[mm:ss.xxx]` 行时间戳，1–6 位毫秒、1–3 位分钟。
  - 同行多时间戳（重复行简写）。
  - 行尾/独立结束时间戳（显式行结尾）。
  - 同一时间戳的第二行作为翻译；无时间戳的紧邻下一行也作为翻译。
  - 行内逐字/逐词时间戳：`你好[00:13.00]椒盐音乐[00:14.00]`。
  - 延迟首字：`<00:11.00>你好…`。
  - 忽略 LX Music `[awlrc:...]` 兼容字段。
- 保留原有 TTML 解析与 `LyricContentWords` 卡拉 OK 渲染；普通 LRC 仍可回退到 `LrcParser`。
- 支持同目录 `.spl` / `.lrc` 外挂歌词，优先于内嵌歌词；`LSong.toMediaItem()` 写入 `DATA` extra 供外挂歌词定位。
- 原有歌词时间偏移、字体、模糊、翻译显隐等设置保持可用。

## 4. 视觉升级（不改布局）

- 首页推荐卡片：底部信息面板改为深色毛玻璃（真实模糊封面 + 半透明遮罩 + 描边/阴影），标题与副标题使用 Monet 取色的流动渐变文字。
- 播放条 / 展开播放页：毛玻璃背景、Monet 动态渐变、流动文字、进度条渐变；封面灰阶时自动补一个低饱和紫色 Monet 强调色，避免灰暗。
- 播放条标题、副标题、时间文字会随当前封面调色板变化，而不是固定白色。

## 5. 验证

在 Redmi K20 Pro（Android 16 / arm64-v8a）上：

- Debug 构建可正常启动、扫描 MediaStore 与自定义目录。
- 实际播放通过：APE、DSF、WavPack、TTA、WMA、AIFF、ALAC、FLAC、Hi-Res FLAC、MP3、AAC/M4A、WAV、OGG、Opus；DFF 由专用 `DffExtractor` 支持（本次回归未单独列出该文件）。
- Alpha（R8 混淆）构建可正常启动与播放，JNI 保活规则已加入 `consumer-rules.pro`。
- 单元测试：
  - `:lmedia:testDebugUnitTest`：SPL 解析（翻译、重复时间戳、逐字、延迟字、awlrc、行尾时间戳）。
  - `:lplayer:testDebugUnitTest`：sinc 重采样输出帧数、单位增益。

## 6. 已知限制

- 部分历史预编译 `.so`（libblur、libflacJNI、libfpcalc、libtaglib）仍为 4 KB ELF 对齐；在 Android 16 的 debug 构建中会出现 16 KB 兼容性提示。本项目新增的 `libmedia3ext.so` 已按 16 KB 链接。
- 真 USB 独占 / UAC 等时传输未实现；当前是 preferred device 直连路由。
- DFF 仅支持未压缩 DSD chunk；DST 压缩 DSDIFF 未实现。
- 24-bit packed 输出尚未开放（当前 16-bit / 32-bit float）。
- TAK/Shorten 等格式视 FFmpeg 解码器与容器支持，未做逐格式回归。

## 7. 构建 FFmpeg（可选）

仓库已包含各 ABI 的预编译 `libmedia3ext.so`。若需重新生成：

```bash
ABIS="arm64-v8a armeabi-v7a x86_64" ./tools/build_ffmpeg_android.sh
# 然后按脚本输出中的 cmake 命令重新链接 libmedia3ext.so，并复制到
# lplayer/lib-ffmpeg/src/main/jniLibs/<abi>/
```
