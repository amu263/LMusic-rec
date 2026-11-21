# LMusic

#### 介绍
一个简洁且独特的音乐播放器，遵循最小权限原则，目标是打造一个简洁好用的音乐播放器，还要够方便够好用。

在其中学习使用了MVVM架构，现已从传统View架构转为Compose架构，大量使用了Kotlin Coroutines Flow、协程，以用来形成单向数据流。


|![APP主页面](https://gitee.com/lalilu/lmusic/raw/master/screenshots/202205112338172.png "主页面")主页面|![歌词页](https://gitee.com/lalilu/lmusic/raw/master/screenshots/20220511233817.jpg "歌词页")歌词页|
|--|--|
|![歌曲页](https://gitee.com/lalilu/lmusic/raw/master/screenshots/202205112338171.jpg "歌曲页")歌曲页|![专辑页](https://gitee.com/lalilu/lmusic/raw/master/screenshots/202205112338171.png "专辑页")专辑页|

##### Tips
在这个项目中尝试过自定义View，也尝试修改Google的Material库以获得满足目标的效果，但大部分都已经用Compose重新实现了，需要看旧的传统View的实现请查阅v1.4.9以前版本的代码。

#### 功能增强（1.6.0）

- 全格式音频软解：APE、DSD（DSF/DFF）、WMA、WavPack、TTA、TAK、AIFF、ALAC 等，硬件优先、FFmpeg 自动接管，也可强制仅软解。
- 音频输出：采样率/位深选择、32-tap sinc 重采样、DSD 增益、10 段均衡器、无间隙播放、USB 音频直连（实验性）。
- SPL 逐字歌词：行内逐字/逐词时间戳、`<>` 延迟首字、翻译、显式行结尾、`.spl`/`.lrc` 外挂歌词。
- 视觉：首页头条与播放条使用毛玻璃 + Monet 取色流光文字，保持原有布局与交互。
- 详细说明见 [`docs/audio-and-lyrics.md`](docs/audio-and-lyrics.md)。

#### Build 指南
1. clone 本仓库
2. 准备 Android SDK（compileSdk 35）与 NDK 26.1.10909125
3. 直接执行 `./gradlew :app:assembleDebug`

`lmedia` 及其 `taglib`/`utfcpp` 依赖已作为普通源码包含在仓库中，不再需要 `git submodule`。
`lplayer/lib-ffmpeg` 已包含 arm64-v8a / armeabi-v7a / x86_64 的预编译 `libmedia3ext.so`；重新生成 FFmpeg 见 `tools/build_ffmpeg_android.sh`。

#### 参与贡献
欢迎提Issues和PR，你的Issues就是我最大的动力，你的PR是对我最大的支持，十分感谢！！！

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request

