#include "descriptors.h"

#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <thread>

namespace {
using Bytes = std::vector<uint8_t>;
constexpr int TransferCount = 8;
constexpr unsigned ControlTimeoutMs = 250;

void check(int result, const std::string& operation) {
    if (result < 0) throw std::runtime_error(operation + ": " + libusb_error_name(result));
}

struct Device {
    libusb_context* context{};
    libusb_device_handle* handle{};
    libusb_config_descriptor* config{};

    explicit Device(int fd) {
        try {
            const libusb_init_option option{LIBUSB_OPTION_NO_DEVICE_DISCOVERY, {}};
            check(libusb_init_context(&context, &option, 1), "libusb init");
            check(libusb_wrap_sys_device(context, fd, &handle), "wrap USB fd");
            check(libusb_get_active_config_descriptor(libusb_get_device(handle), &config),
                  "read active configuration");
        } catch (...) {
            close();
            throw;
        }
    }

    void close() {
        if (config) libusb_free_config_descriptor(config);
        if (handle) libusb_close(handle);
        if (context) libusb_exit(context);
    }

    ~Device() { close(); }
};

int control(Device& device, int type, int request, int value, int index, uint8_t* data, int size) {
    return libusb_control_transfer(device.handle, type, request, value, index, data, size, ControlTimeoutMs);
}

bool readClock(Device& device, const AudioAlt& alt, int entity, int selector, uint8_t* data, int size) {
    return control(device, 0xa1, 1, selector << 8, (entity << 8) | alt.control, data, size) == size;
}

const Bytes* entity(const AudioAlt& alt, int id) {
    for (const auto& d : alt.entities) if (d[3] == id) return &d;
    return nullptr;
}

const Bytes* clockSource(Device& device, const AudioAlt& alt) {
    const auto* terminal = entity(alt, alt.terminal);
    if (!terminal || terminal->size() < 17 || (*terminal)[2] != 2) return nullptr;
    int id = (*terminal)[7];
    for (size_t hop = 0; hop < alt.entities.size(); ++hop) {
        const auto* d = entity(alt, id);
        if (!d) return nullptr;
        if ((*d)[2] == 0x0a && d->size() >= 8) return d;
        if ((*d)[2] != 0x0b || d->size() < 7 || d->size() < size_t(7 + (*d)[4])) return nullptr;
        uint8_t pin = 0;
        if (!readClock(device, alt, id, 1, &pin, 1) || pin < 1 || pin > (*d)[4]) return nullptr;
        id = (*d)[4 + pin];
    }
    return nullptr;
}

bool setRate(Device& device, const AudioAlt& alt, int rate) {
    uint8_t data[4] = {uint8_t(rate), uint8_t(rate >> 8), uint8_t(rate >> 16), uint8_t(rate >> 24)};
    if (alt.version == 1) {
        if (!alt.rateControl) return !alt.continuous && alt.rates.size() == 1 && alt.rates[0] == rate;
        if (control(device, 0x22, 1, 0x100, alt.endpoint, data, 3) != 3) return false;
        return control(device, 0xa2, 0x81, 0x100, alt.endpoint, data, 3) == 3 &&
               little(data, 3) == unsigned(rate);
    }
    const auto* source = clockSource(device, alt);
    if (!source) return false;
    const int id = (*source)[3];
    const int access = (*source)[5] & 3;
    if (access != 1 && access != 3) return false;
    uint8_t current[4]{};
    if (readClock(device, alt, id, 1, current, 4) && little(current, 4) == unsigned(rate)) return true;
    if (access != 3) return false;

    // RANGE is optional on some otherwise usable devices. A well-formed reply is authoritative.
    uint8_t ranges[2 + 12 * 64]{};
    const int length = control(device, 0xa1, 2, 0x100, (id << 8) | alt.control, ranges, sizeof(ranges));
    if (length >= 2) {
        const auto count = little(ranges, 2);
        if (count > 0 && count <= unsigned((length - 2) / 12)) {
            bool supported = false;
            for (unsigned i = 0; i < count; ++i) {
                const auto* range = ranges + 2 + i * 12;
                const unsigned low = little(range, 4), high = little(range + 4, 4), step = little(range + 8, 4);
                if (unsigned(rate) >= low && unsigned(rate) <= high &&
                    (!step || (unsigned(rate) - low) % step == 0)) supported = true;
            }
            if (!supported) return false;
        }
    }
    if (control(device, 0x21, 1, 0x100, (id << 8) | alt.control, data, 4) != 4) return false;
    return readClock(device, alt, id, 1, current, 4) && little(current, 4) == unsigned(rate);
}

const Bytes* masterFeature(const AudioAlt& alt) {
    // Restrict hardware volume to a playback Feature Unit immediately downstream of the
    // USB streaming terminal. In particular, do not write to a capture or monitor unit.
    for (const auto& d : alt.entities) {
        if (d[2] != 6 || d.size() < 7 || d[4] != alt.terminal) continue;
        if (alt.version == 2 && d.size() >= 10 && (d[5] & 0x0c) == 0x0c) return &d;
        if (alt.version == 1 && d[5] && (d[6] & 2)) return &d;
    }
    return nullptr;
}

struct Stream {
    struct Slot {
        Stream* stream;
        bool feedback;
        bool active{};
        Bytes data;
        std::vector<int> audioFrames;
        libusb_transfer* transfer;

        Slot(Stream* owner, bool input, int packets, int maxPacket)
            : stream(owner), feedback(input), data(packets * maxPacket), audioFrames(packets),
              transfer(libusb_alloc_transfer(packets)) {
            if (!transfer) throw std::bad_alloc();
        }
        ~Slot() { libusb_free_transfer(transfer); }
    };

    Device device;
    AudioAlt chosen;
    int claimed{-1};
    int controlClaimed{-1};
    int rate, inputBytes, channelCount, inputFrame, outputFrame{}, baseHz{}, periods{};
    int clockId{};
    double nominal{}, framesPerPacket{}, fraction{};
    std::thread worker;
    std::vector<std::unique_ptr<Slot>> slots;
    std::mutex mutex;
    std::condition_variable wake;
    Bytes ring;
    size_t readAt{}, writeAt{}, queued{};
    std::atomic<bool> running{false}, closing{false}, draining{false};
    std::atomic<int> failure{0};
    std::atomic<int64_t> submitted{0}, completed{0}, underruns{0}, errors{0}, feedbackCount{0};
    std::atomic<float> requestedVolume{-1};
    std::atomic<int> volumeResult{0};
    int consecutiveErrors{};
    bool primed{}, resume{};
    std::chrono::steady_clock::time_point feedbackDeadline;

    Stream(int fd, int sampleRate, int channels, int bits)
        : device(fd), rate(sampleRate), inputBytes(bits / 8), channelCount(channels),
          inputFrame(channels * inputBytes) {
        try {
            select(bits);
            ring.resize(size_t(std::max(1, rate / 4)) * inputFrame);
            const int packets = std::clamp(baseHz / periods / 500, 1, 16);
            for (int i = 0; i < TransferCount; ++i) addSlot(false, packets, chosen.packet, chosen.endpoint);
            if (chosen.feedback) {
                for (int i = 0; i < 2; ++i) addSlot(true, 1, chosen.feedbackPacket, chosen.feedback);
            }
            worker = std::thread([this] { loop(); });
        } catch (...) {
            releaseInterface();
            throw;
        }
    }

    void releaseInterface() {
        if (claimed >= 0) {
            libusb_set_interface_alt_setting(device.handle, claimed, 0);
            libusb_release_interface(device.handle, claimed);
            claimed = -1;
        }
        if (controlClaimed >= 0) {
            libusb_release_interface(device.handle, controlClaimed);
            controlClaimed = -1;
        }
    }

    ~Stream() {
        closing = true;
        wake.notify_all();
        worker.join();
        releaseInterface();
    }

    void select(int bits) {
        const int speed = libusb_get_device_speed(libusb_get_device(device.handle));
        if (speed != LIBUSB_SPEED_FULL && speed != LIBUSB_SPEED_HIGH)
            throw std::runtime_error("Only full-speed and high-speed USB audio are supported");
        baseHz = speed == LIBUSB_SPEED_FULL ? 1000 : 8000;
        auto choices = parseAudio(*device.config);
        std::stable_sort(choices.begin(), choices.end(), [](const auto& a, const auto& b) {
            if (a.version != b.version) return a.version > b.version;
            if (a.bits != b.bits) return a.bits < b.bits;
            return a.bytes < b.bytes;
        });
        // Android may forbid detaching its kernel driver. Claim failures are surfaced to the sink.
        const int detach = libusb_set_auto_detach_kernel_driver(device.handle, 1);
        if (detach != LIBUSB_ERROR_NOT_SUPPORTED) check(detach, "enable USB driver detach");
        std::string reason = "No matching PCM alternate setting";
        for (const auto& alt : choices) {
            if (alt.channels != channelCount || alt.bits < bits || alt.bytes < inputBytes) continue;
            if (alt.sync == 1 && (!alt.feedback || alt.feedbackPacket < 3)) {
                reason = describe(alt) + ": asynchronous endpoint needs explicit feedback";
                continue;
            }
            if (alt.version == 2 && alt.control < 0) {
                reason = describe(alt) + ": AudioControl interface not found";
                continue;
            }
            if (alt.version == 1) {
                const bool supported = alt.continuous
                    ? alt.rates.size() == 2 && rate >= alt.rates[0] && rate <= alt.rates[1]
                    : std::find(alt.rates.begin(), alt.rates.end(), rate) != alt.rates.end();
                if (!supported) continue;
            }
            periods = 1 << (alt.interval - 1);
            nominal = double(rate) * periods / baseHz;
            outputFrame = alt.bytes * channelCount;
            if ((std::ceil(nominal) + (alt.sync == 1 && nominal == std::ceil(nominal) ? 1 : 0)) *
                outputFrame > alt.packet) continue;
            int result = libusb_claim_interface(device.handle, alt.interface);
            if (result < 0) {
                reason = describe(alt) + ": claim AS: " + libusb_error_name(result);
                continue;
            }
            claimed = alt.interface;
            if (alt.control >= 0) {
                result = libusb_claim_interface(device.handle, alt.control);
                if (result == 0) controlClaimed = alt.control;
                else if (alt.version == 2) {
                    reason = describe(alt) + ": claim AC: " + libusb_error_name(result);
                    releaseInterface();
                    continue;
                }
            }
            if (alt.version == 2) setRate(device, alt, rate);
            result = libusb_set_interface_alt_setting(device.handle, alt.interface, alt.alt);
            if (result == 0 && setRate(device, alt, rate)) {
                chosen = alt;
                if (alt.version == 2) {
                    const auto* source = clockSource(device, alt);
                    clockId = source ? (*source)[3] : 0;
                }
                framesPerPacket = nominal;
                return;
            }
            reason = describe(alt) + (result < 0 ? std::string(": set alt: ") + libusb_error_name(result)
                                               : ": could not set/verify requested sample rate");
            releaseInterface();
        }
        throw std::runtime_error(reason + " (" + std::to_string(rate) + "Hz, " +
                                 std::to_string(bits) + "bit, " + std::to_string(channelCount) + "ch)");
    }

    void addSlot(bool feedback, int packets, int maxPacket, int endpoint) {
        auto slot = std::make_unique<Slot>(this, feedback, packets, maxPacket);
        libusb_fill_iso_transfer(slot->transfer, device.handle, endpoint, slot->data.data(),
                                 slot->data.size(), packets, onTransfer, slot.get(), 500);
        slots.push_back(std::move(slot));
    }

    int write(const uint8_t* data, int frames) {
        std::lock_guard<std::mutex> lock(mutex);
        frames = std::min(frames, int((ring.size() - queued) / inputFrame));
        const size_t size = size_t(frames) * inputFrame;
        const size_t tail = std::min(size, ring.size() - writeAt);
        memcpy(ring.data() + writeAt, data, tail);
        memcpy(ring.data(), data + tail, size - tail);
        writeAt = (writeAt + size) % ring.size();
        queued += size;
        submitted += frames;
        wake.notify_one();
        return frames;
    }

    int fill(uint8_t* output, int frames) {
        std::lock_guard<std::mutex> lock(mutex);
        const int available = std::min(frames, int(queued / inputFrame));
        const int padding = chosen.bytes - inputBytes;
        if (!padding) {
            const size_t size = size_t(available) * inputFrame;
            const size_t tail = std::min(size, ring.size() - readAt);
            memcpy(output, ring.data() + readAt, tail);
            memcpy(output + tail, ring.data(), size - tail);
            readAt = (readAt + size) % ring.size();
        } else {
            for (int i = 0; i < available * channelCount; ++i) {
                memset(output + i * chosen.bytes, 0, padding);
                // Read indices and ring size are sample-aligned.
                memcpy(output + i * chosen.bytes + padding, ring.data() + readAt, inputBytes);
                readAt = (readAt + inputBytes) % ring.size();
            }
        }
        queued -= size_t(available) * inputFrame;
        if (available < frames) {
            memset(output + available * outputFrame, 0, (frames - available) * outputFrame);
            if (!draining) ++underruns;
        }
        return available;
    }

    void submit(Slot& slot) {
        auto* transfer = slot.transfer;
        if (slot.feedback) {
            libusb_set_iso_packet_lengths(transfer, chosen.feedbackPacket);
        } else {
            int length = 0;
            for (int p = 0; p < transfer->num_iso_packets; ++p) {
                fraction += framesPerPacket;
                const int frames = int(fraction);
                fraction -= frames;
                if (frames * outputFrame > chosen.packet) {
                    failure = LIBUSB_ERROR_OVERFLOW;
                    return;
                }
                slot.audioFrames[p] = fill(slot.data.data() + length, frames);
                transfer->iso_packet_desc[p].length = frames * outputFrame;
                length += frames * outputFrame;
            }
            transfer->length = length;
        }
        const int result = libusb_submit_transfer(transfer);
        if (result == 0) slot.active = true;
        else {
            ++errors;
            failure = result;
        }
    }

    void acceptFeedback(const uint8_t* data, unsigned length) {
        uint32_t raw;
        if (length == 4) raw = little(data, 4);
        else if (length == 3 && baseHz == 1000) raw = little(data, 3) << 2;
        else return;
        // UAC2 reports samples per 1 ms USB frame (16.16); convert to per-packet.
        double value = feedbackSamplesPerPacket(raw, periods, baseHz);
        if (value >= nominal * 0.97 && value <= nominal * 1.03 &&
            std::ceil(value) * outputFrame <= chosen.packet) {
            framesPerPacket = value;
            ++feedbackCount;
            feedbackDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
        }
    }

    static void LIBUSB_CALL onTransfer(libusb_transfer* transfer) {
        auto& slot = *static_cast<Slot*>(transfer->user_data);
        auto& stream = *slot.stream;
        slot.active = false;
        if (stream.closing || transfer->status == LIBUSB_TRANSFER_CANCELLED) return;
        if (transfer->status == LIBUSB_TRANSFER_NO_DEVICE) {
            ++stream.errors;
            stream.failure = LIBUSB_ERROR_NO_DEVICE;
            return;
        }
        bool bad = transfer->status != LIBUSB_TRANSFER_COMPLETED;
        for (int i = 0; i < transfer->num_iso_packets; ++i) {
            const auto& packet = transfer->iso_packet_desc[i];
            if (transfer->status == LIBUSB_TRANSFER_COMPLETED && packet.status == LIBUSB_TRANSFER_COMPLETED) {
                if (slot.feedback) {
                    stream.acceptFeedback(libusb_get_iso_packet_buffer_simple(transfer, i), packet.actual_length);
                } else if (packet.actual_length == packet.length) {
                    stream.completed += slot.audioFrames[i];
                } else {
                    bad = true;
                    if (slot.audioFrames[i]) stream.failure = LIBUSB_ERROR_IO;
                }
            } else {
                bad = true;
                if (!slot.feedback && slot.audioFrames[i]) stream.failure = LIBUSB_ERROR_IO;
            }
        }
        if (bad) {
            ++stream.errors;
            if (!slot.feedback && ++stream.consecutiveErrors >= 3) stream.failure = LIBUSB_ERROR_IO;
        } else if (!slot.feedback) {
            stream.consecutiveErrors = 0;
        }
    }

    void loop() {
        bool cancelling = false;
        while (true) {
            const bool terminate = closing || failure != 0;
            if (terminate && !cancelling) {
                cancelling = true;
                for (const auto& slot : slots) if (slot->active) libusb_cancel_transfer(slot->transfer);
            }
            if (!terminate) {
                const float gain = requestedVolume.exchange(-1);
                if (gain >= 0) volumeResult = volume(gain) ? 1 : -1;
            }
            if (!terminate && running) {
                const auto pendingFrames = submitted - completed;
                if (!primed && pendingFrames > 0 && (draining || pendingFrames >= rate / 100 || resume)) {
                    primed = true;
                    resume = true;
                    feedbackDeadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
                }
                if (primed && chosen.sync == 1 && std::chrono::steady_clock::now() > feedbackDeadline) {
                    ++errors;
                    failure = LIBUSB_ERROR_TIMEOUT;
                }
                if (primed && (!draining || submitted > completed)) {
                    for (const auto& slot : slots) {
                        if (!slot->active && !failure) submit(*slot);
                    }
                }
            } else {
                primed = false;
            }

            const bool active = std::any_of(slots.begin(), slots.end(), [](const auto& s) { return s->active; });
            if (!active) {
                if (terminate) break;
                std::unique_lock<std::mutex> lock(mutex);
                wake.wait_for(lock, std::chrono::milliseconds(2));
                continue;
            }
            timeval timeout{0, 2000};
            const int result = libusb_handle_events_timeout(device.context, &timeout);
            if (result < 0 && result != LIBUSB_ERROR_INTERRUPTED && !failure) {
                ++errors;
                failure = result;
            }
        }
    }

    bool volume(float gain) {
        if (failure || controlClaimed < 0) return false;
        const auto* feature = masterFeature(chosen);
        if (!feature) return false;
        const int index = ((*feature)[3] << 8) | chosen.control;
        uint8_t data[256]{};
        int low, high, step;
        auto signed16 = [](const uint8_t* p) { return int(int16_t(little(p, 2))); };
        if (chosen.version == 2) {
            const int length = control(device, 0xa1, 2, 0x200, index, data, sizeof(data));
            if (length < 8 || little(data, 2) != 1) return false;
            low = signed16(data + 2);
            high = signed16(data + 4);
            step = signed16(data + 6);
        } else {
            if (control(device, 0xa1, 0x82, 0x200, index, data, 2) != 2) return false;
            low = signed16(data);
            if (control(device, 0xa1, 0x83, 0x200, index, data, 2) != 2) return false;
            high = signed16(data);
            if (control(device, 0xa1, 0x84, 0x200, index, data, 2) != 2) return false;
            step = signed16(data);
        }
        if (low > high || step < 0) return false;
        int db = gain <= 0 ? low : int(std::round(20 * std::log10(gain) * 256));
        db = std::clamp(db, low, high);
        if (step > 0) db = low + ((db - low) / step) * step;
        data[0] = uint8_t(db);
        data[1] = uint8_t(db >> 8);
        return control(device, 0x21, 1, 0x200, index, data, 2) == 2;
    }
};

Stream& stream(jlong handle) { return *reinterpret_cast<Stream*>(handle); }

void throwIo(JNIEnv* env, const std::exception& e) {
    env->ThrowNew(env->FindClass("java/io/IOException"), e.what());
}
}

#define JNI_METHOD(name) Java_com_lalilu_usbuac_UsbNative_##name

extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(probe)(JNIEnv* env, jobject, jint fd) {
    try {
        Device device(fd);
        std::string result;
        for (const auto& alt : parseAudio(*device.config)) result += describe(alt) + "\n";
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        throwIo(env, e);
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv* env, jobject, jint fd,
        jint rate, jint channels, jint bits) {
    try {
        if (rate < 8000 || rate > 768000 || channels < 1 || channels > 32 ||
            (bits != 16 && bits != 24 && bits != 32)) throw std::runtime_error("Invalid PCM format");
        return reinterpret_cast<jlong>(new Stream(fd, rate, channels, bits));
    } catch (const std::exception& e) {
        throwIo(env, e);
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL JNI_METHOD(configuration)(JNIEnv* env, jobject, jlong handle) {
    const auto& s = stream(handle);
    auto value = describe(s.chosen) + " configured=" + std::to_string(s.rate) + "Hz";
    value += " input=" + std::to_string(s.inputBytes * 8) + "bit";
    if (s.clockId) value += " clock=" + std::to_string(s.clockId);
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT jint JNICALL JNI_METHOD(write)(JNIEnv* env, jobject, jlong handle,
        jobject buffer, jint offset, jint frames) {
    auto& s = stream(handle);
    if (const int error = s.failure) return error;
    const auto* address = static_cast<const uint8_t*>(env->GetDirectBufferAddress(buffer));
    const auto size = env->GetDirectBufferCapacity(buffer);
    if (!address || offset < 0 || frames < 0 || offset + int64_t(frames) * s.inputFrame > size)
        return LIBUSB_ERROR_INVALID_PARAM;
    return s.write(address + offset, frames);
}

extern "C" JNIEXPORT jlongArray JNICALL JNI_METHOD(stats)(JNIEnv* env, jobject, jlong handle) {
    const auto& s = stream(handle);
    const jlong values[] = {s.submitted, s.completed, s.underruns, s.errors, s.feedbackCount, s.failure, s.volumeResult};
    auto result = env->NewLongArray(7);
    if (result) env->SetLongArrayRegion(result, 0, 7, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(run)(JNIEnv*, jobject, jlong handle, jboolean play) {
    auto& s = stream(handle);
    s.running = play;
    s.wake.notify_one();
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(drain)(JNIEnv*, jobject, jlong handle) {
    stream(handle).draining = true;
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(volume)(JNIEnv*, jobject, jlong handle, jfloat gain) {
    auto& s = stream(handle);
    if (!std::isfinite(gain) || s.failure || s.controlClaimed < 0 || !masterFeature(s.chosen)) return false;
    s.requestedVolume = std::clamp(gain, 0.0f, 1.0f);
    s.wake.notify_one();
    return true;
}

extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv*, jobject, jlong handle) {
    delete &stream(handle);
}
