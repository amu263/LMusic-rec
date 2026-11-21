# lib-ffmpeg

Media3 的 FFmpeg 音频解码扩展，派生自 [NextLib](https://github.com/anilbeesetti/nextlib)（GPL-3.0）的 JNI/demuxer 思路，适配 Media3 1.6.0。

## 内容

- `src/main/java/.../ffdecoder`：FFmpeg demuxer（ProgressiveMediaExtractor）、音频 Renderer/Decoder、DFF extractor、MP4/APE/WMA 等扩展 MIME 映射。
- `src/main/cpp`：FFmpeg JNI（容器读取、音轨回调、解码、重采样输出）以及 CMake 工程。
- `src/main/jniLibs/<abi>/libmedia3ext.so`：预编译产物，Gradle 构建直接打包。
- `consumer-rules.pro`：保持 JNI 类/方法名，避免 R8 混淆后 native 回调失败。

## 重新生成

1. 运行仓库根目录的 `tools/build_ffmpeg_android.sh`。
2. 对每个 ABI 使用 NDK 工具链构建：

```bash
cmake -S lplayer/lib-ffmpeg/src/main/cpp -B build-arm64 -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=21 \
  -DFFMPEG_ROOT=<repo>/ffbuild/out/arm64-v8a
cmake --build build-arm64
cp build-arm64/libmedia3ext.so src/main/jniLibs/arm64-v8a/
```

## 许可证

- 本目录中的 NextLib 派生代码：GPL-3.0（与上层 AGPL-3.0 项目兼容）。
- FFmpeg：按所启用组件为 LGPL-2.1+；本构建仅启用解码器/demuxer，不启用 GPL 组件。
- 完整许可文本见仓库根目录 `LICENSE` 及 FFmpeg 源码 `COPYING.LGPLv2.1`。
