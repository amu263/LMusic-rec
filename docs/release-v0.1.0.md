# LMusic 0.1.0

**把本地音乐，听回它本来的样子。**

全格式、高解析、USB 独占与逐字歌词，集中在一个本地优先的播放器里。

## 本次亮点

### 全格式，不挑文件

APE、DSD（DSF/DFF）、WMA、WavPack、TTA、TAK、AIFF、ALAC、FLAC、MP3、AAC、WAV、OGG、Opus 均可播放。硬件解码优先，FFmpeg 软解自动接管，也可手动强制软解。

### 高解析输出

- 16-bit / 32-bit float / 24-bit packed 输出；
- 自动或手动 44.1–192 kHz 采样率；
- 32-tap sinc 重采样、10 段均衡器、DSD 增益、无间隙播放。

### USB 独占（实验性）

基于 libusb 的 UAC 等时传输，绕过 Android 混音与重采样：

- UAC2 异步反馈、采样率控制、硬件音量；
- 16-bit / 24-bit 位深直通；
- 失败自动回退 AudioTrack。

已在 **MOONDROP Dawn Pro** 上验证 16-bit/44.1 kHz 与 24-bit/44.1、48、96、192 kHz，传输过程 `underruns=0`、`errors=0`。

### 逐字歌词

SPL 行内逐字/逐词时间戳、翻译、延迟首字、显式行结尾；支持 `.spl` / `.lrc` 外挂歌词。

### Monet 视觉

播放条与首页头条采用毛玻璃 + 封面取色流光，文字随封面调色；无封面时使用黑白流光。

## 安装

1. 下载 `LMusic-0.1.0.apk`；
2. 允许安装未知来源应用；
3. 安装后授予本地音频读取权限。

- **系统**：Android 5.0（API 21）及以上
- **架构**：arm64-v8a / armeabi-v7a / x86_64
- **USB 独占原生驱动**：当前仅 arm64-v8a

> 本版本使用独立发布签名。若安装过开发版，请先卸载再安装。

## 校验

```text
SHA-256: 9d07ee4190bece43b985d7c72c1aa740f0dd1c6b30a62f2d20ef9df40e612f0e
```

## 反馈

USB DAC 兼容问题请附：设备型号、Android 版本、DAC 型号、音频格式，以及 `adb logcat` 中 `UsbAudioOutput` 相关日志。

- [问题反馈](https://github.com/amu263/LMusic-rec/issues)
- [音频能力文档](https://github.com/amu263/LMusic-rec/blob/main/docs/audio-and-lyrics.md)

## License

AGPL-3.0
