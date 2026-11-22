# Direct USB Audio Class output

In LMusic, open **音频设置 → USB 音频（Pro） → USB 独占（bit-perfect，实验性）**. Select a DAC and grant USB permission. A single attached DAC can be selected automatically when no preference is stored. **USB 直连（AudioTrack routing）** selects Android's USB AudioTrack route. The USB section shows the active path and available alternate settings. Logcat tag `UsbAudioOutput` records configuration and fallback reasons.

## Android API

- `UsbAudioSession.requestPermission(manager, device, result)` uses the caller's package-scoped permission result intent.
- `UsbAudioSession.describe(manager, device)` reads Type I PCM alternate settings after permission.
- `UsbAudioSession.open(manager, device, Pcm(sampleRate, channels, bits), onStatus)` opens and owns an Android USB connection. It wraps the connection's file descriptor using libusb and selects a streaming alternate setting. Open and control operations can block.
- `write(directByteBuffer, frames)` queues whole signed little-endian PCM frames and advances the buffer by the number accepted. A short write applies backpressure. `getPositionFrames()` counts audio frames in completed OUT packets. It excludes generated silence and the DAC's internal latency.
- `start()` and `stop()` resume and pause submission. Already submitted transfers can complete after pause. `drain()` finishes queued PCM. `flush()` cancels queued/in-flight audio and closes the session. Call `open()` again to resume after a flush. `close()` is idempotent.
- `setVolume(gain)` queues a hardware request when a supported master Feature Unit is present. The result is reported in `status()`. `publishStatus()` also invokes the caller's callback. Call the session from one owner thread.

LMusic's Media3 sink sends decoded PCM16, packed PCM24 and PCM32 at the source sample rate and channel count. Integer samples keep their value, with zero padding when a DAC accepts only a wider subframe or resolution. Float PCM is rounded and clipped to signed PCM32 and identified in diagnostics. Decoder precision depends on the selected decoder. The USB sink bypasses the app's normal resampling, EQ, gain, silence skipping and fades. The AudioTrack sink takes over if device acquisition, format negotiation or streaming fails. Up to the queued USB audio can be lost during a transfer failure before fallback.

The native worker keeps several isochronous OUT transfers in flight, uses explicit feedback when present and fills underruns with silence. It tries UAC2 clock-source controls first, including the current input of a clock selector, then UAC1 endpoint controls or descriptor-declared fixed rates. Supported hardware uses full-speed or high-speed USB and Type I PCM. Implicit-feedback endpoints, clock multipliers, alternate USB configurations, and formats requiring channel remapping or encoder trimming fall back. A DAC that the Android kernel will not release also falls back.

## Build and reproduce

The Gradle build packages `src/main/jniLibs/arm64-v8a/libusbuac.so`. Non-arm64 installations use AudioTrack when the library is unavailable. To regenerate the binary with NDK 26.1.10909125 and CMake 3.22.1 on Linux:

```sh
ANDROID_SDK_ROOT=/home/amu/Android/Sdk bash lplayer/lib-usb-uac/build-native.sh
bash gradlew :lplayer:lib-usb-uac:assembleDebug :app:assembleDebug -Dorg.gradle.jvmargs=-Xmx3072m
```

The build script fetches and verifies libusb v1.0.27 at commit `d52e355daa09f17ce64819122cb067b8a2ee0d4b`. libusb is statically linked into the JNI shared library. Its source and license are available from the pinned upstream release and the license is included in [libusb-COPYING](libusb-COPYING). The direct CMake build retains the JNI object files and static archive in `build/manual/native/arm64-v8a` for relinking with a modified libusb source tree. [libusb v1.0.27 source](https://github.com/libusb/libusb/tree/d52e355daa09f17ce64819122cb067b8a2ee0d4b)

To exercise the native descriptor parser with a composite UAC2 fixture and a continuous-rate UAC1 fixture on the host, run from this module after the direct build:

```sh
c++ -std=c++17 -fsanitize=address,undefined \
  -Ibuild/manual/native/libusb/libusb -Isrc/main/cpp \
  src/main/cpp/descriptors.cpp src/test/cpp/descriptors_test.cpp -o /tmp/uac-descriptors-test
/tmp/uac-descriptors-test
```
