<div align="center">

# LMusic

**把本地音乐，听回它本来的样子。**

全格式 · 高解析 · 逐字歌词 · 本地优先

[![Release](https://img.shields.io/github/v/release/amu263/LMusic-rec?style=flat-square&color=111111)](https://github.com/amu263/LMusic-rec/releases)
[![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](https://github.com/amu263/LMusic-rec/releases)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-AGPL--3.0-111111?style=flat-square)](LICENSE)

[下载 V0.2.0](https://github.com/amu263/LMusic-rec/releases/tag/v0.2.0) · [音频能力](docs/audio-and-lyrics.md) · [问题反馈](https://github.com/amu263/LMusic-rec/issues)

</div>

---

## 为什么是 LMusic

大多数播放器在“能播”之后就不再向前。LMusic 想解决的是另一件事：**让本地收藏里的每一种格式、每一层动态、每一句歌词，都被认真对待。**

没有广告，没有强制登录，没有云端曲库绑架。你的音乐留在你的设备里，LMusic 只负责把它放好。

## 核心能力

| | |
|---|---|
| **全格式播放** | APE、DSD（DSF/DFF）、WMA、WavPack、TTA、TAK、AIFF、ALAC、FLAC、MP3、AAC、WAV、OGG、Opus。硬件优先，FFmpeg 自动接管。 |
| **高解析输出** | 16-bit / 32-bit float / 24-bit packed，自动或手动采样率，32-tap sinc 重采样。 |
| **USB 音频** | AudioTrack 直连兼容模式；基于 libusb UAC 等时传输的 bit-perfect 独占，支持 UAC2 异步反馈与采样率控制。 |
| **逐字歌词** | SPL 行内逐字/逐词时间戳、翻译、延迟首字、显式行结尾，支持 `.spl` / `.lrc` 外挂。 |
| **Monet 视觉** | 毛玻璃播放条与首页头条，封面取色 + 黑白流光，文字随封面调色。 |
| **本地优先** | 只申请必要权限，不上传歌单与播放记录。 |

## 截图

<div align="center">

| 播放页 | 歌词页 |
|:---:|:---:|
| ![播放页](screenshots/202205112338172.png) | ![歌词页](screenshots/20220511233817.jpg) |

| 歌曲库 | 专辑 |
|:---:|:---:|
| ![歌曲库](screenshots/202205112338171.jpg) | ![专辑](screenshots/202205112338173.png) |

</div>

> 截图为界面预览，实际样式以当前版本为准。

## 下载与安装

1. 打开 [Releases](https://github.com/amu263/LMusic-rec/releases/tag/v0.2.0)，下载 `LMusic-0.2.0.apk`。
2. Android 允许“安装未知来源应用”后安装。
3. 首次启动授予本地音频读取权限，LMusic 会扫描设备中的音乐。

**系统要求**：Android 5.0（API 21）及以上。  
**架构**：arm64-v8a / armeabi-v7a / x86_64；USB 独占原生驱动当前为 arm64-v8a。

> V0.2 使用独立发布签名。若你之前安装过其他签名的版本，请先卸载再安装正式版。

## 适合谁

- 本地曲库里有大量 FLAC、DSD、APE 的收藏型用户；
- 使用 USB DAC / 小尾巴，希望绕过系统混音与重采样的用户；
- 在意逐字歌词、界面质感和隐私的日常听众；
- 想要一个“打开就能听”，而不是先注册账号的播放器的人。

## LMusic Pro

V0.2 先把手艺做扎实：高解析输出、USB 独占、逐字歌词与 Monet 界面全部开放体验。

后续 Pro 将围绕更深度的音频与资料库能力展开，例如：

- 更完整的 UAC 设备兼容、硬件音量与安全音量策略；
- 多设备音频配置、房间/耳机预设；
- 歌词与元数据批量整理、云备份；
- 面向音乐收藏者的高级资料库工具。

商业合作、定制与分发咨询请通过 [Issues](https://github.com/amu263/LMusic-rec/issues) 联系。

## 构建

```bash
# 需要 JDK 17、Android SDK 35、NDK 26.1.10909125
./gradlew :app:assembleDebug
```

发布 APK：

```bash
./gradlew :app:assembleRelease
```

仓库已内置 `lmedia`、`taglib`、`utfcpp` 与各 ABI 的 FFmpeg JNI 库，无需初始化子模块。USB UAC 原生库可用 `lplayer/lib-usb-uac/build-native.sh` 重建。

## 文档

- [音频输出、USB 独占与逐字歌词](docs/audio-and-lyrics.md)
- [USB UAC 模块说明](lplayer/lib-usb-uac/README.md)

## 反馈

遇到播放异常、USB DAC 兼容问题或想提功能建议，请附上：

- 设备型号与 Android 版本；
- 音频格式 / 采样率 / 位深；
- DAC 型号（USB 问题）；
- `adb logcat` 中 `UsbAudioOutput` 相关日志。

## 致谢

LMusic 站在许多开源项目之上：Media3 / ExoPlayer、FFmpeg、TagLib、libusb、Kotlin、Jetpack Compose、Koin、Voyager、Room、Coil 等。

感谢每一位提交 Issue、PR 和反馈听感的用户。

## License

[AGPL-3.0](LICENSE)

LMusic 允许个人使用、学习与修改；基于本项目的分发需遵循 AGPL-3.0。第三方组件遵循各自许可证。
