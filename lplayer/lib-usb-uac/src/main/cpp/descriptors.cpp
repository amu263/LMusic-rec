#include "descriptors.h"

#include <array>
#include <sstream>

unsigned little(const uint8_t* bytes, int size) {
    unsigned result = 0;
    for (int i = 0; i < size; ++i) result |= unsigned(bytes[i]) << (8 * i);
    return result;
}

namespace {
template<class Visitor>
void descriptors(const uint8_t* data, int size, Visitor visit) {
    while (size >= 2 && data[0] >= 2 && data[0] <= size) {
        visit(data, int(data[0]));
        size -= data[0];
        data += data[0];
    }
}

int packetBytes(const libusb_endpoint_descriptor& endpoint) {
    return (endpoint.wMaxPacketSize & 0x7ff) * (1 + ((endpoint.wMaxPacketSize >> 11) & 3));
}
}

std::vector<AudioAlt> parseAudio(const libusb_config_descriptor& config) {
    // libusb can retain an IAD in the preceding interface or endpoint's extra descriptors.
    std::array<int, 256> audioFunctions;
    audioFunctions.fill(-1);
    auto scanIad = [&](const uint8_t* data, int length) {
        descriptors(data, length, [&](const uint8_t* d, int size) {
            if (size < 8 || d[1] != 0x0b || d[4] != 1 || d[2] + d[3] > 256) return;
            for (int number = d[2]; number < d[2] + d[3]; ++number) audioFunctions[number] = d[2];
        });
    };
    scanIad(config.extra, config.extra_length);
    for (int i = 0; i < config.bNumInterfaces; ++i) {
        const auto& interface = config.interface[i];
        for (int a = 0; a < interface.num_altsetting; ++a) {
            const auto& alt = interface.altsetting[a];
            scanIad(alt.extra, alt.extra_length);
            for (int e = 0; e < alt.bNumEndpoints; ++e)
                scanIad(alt.endpoint[e].extra, alt.endpoint[e].extra_length);
        }
    }

    std::vector<AudioAlt> choices;
    for (int i = 0; i < config.bNumInterfaces; ++i) {
        const auto& interface = config.interface[i];
        for (int a = 0; a < interface.num_altsetting; ++a) {
            const auto& as = interface.altsetting[a];
            if (as.bInterfaceClass != 1 || as.bInterfaceSubClass != 2 ||
                (as.bInterfaceProtocol != 0 && as.bInterfaceProtocol != 0x20)) continue;

            AudioAlt alt;
            alt.version = as.bInterfaceProtocol == 0x20 ? 2 : 1;
            alt.interface = as.bInterfaceNumber;
            alt.alt = as.bAlternateSetting;
            bool pcm = false;
            descriptors(as.extra, as.extra_length, [&](const uint8_t* d, int length) {
                if (length < 4 || d[1] != 0x24) return;
                if (d[2] == 1 && alt.version == 2 && length >= 16) {
                    alt.terminal = d[3];
                    pcm = d[5] == 1 && (d[6] & 1);
                    alt.channels = d[10];
                } else if (d[2] == 1 && alt.version == 1 && length >= 7) {
                    alt.terminal = d[3];
                    pcm = little(d + 5, 2) == 1;
                } else if (d[2] == 2 && d[3] == 1) {
                    if (alt.version == 2 && length >= 6) {
                        alt.bytes = d[4];
                        alt.bits = d[5];
                    } else if (alt.version == 1 && length >= 8) {
                        alt.channels = d[4];
                        alt.bytes = d[5];
                        alt.bits = d[6];
                        alt.continuous = d[7] == 0;
                        const int count = alt.continuous ? 2 : d[7];
                        if (length >= 8 + count * 3) {
                            for (int r = 0; r < count; ++r) alt.rates.push_back(little(d + 8 + 3 * r, 3));
                        }
                    }
                }
            });
            if (!pcm || !alt.channels || alt.bytes < 2 || alt.bytes > 4 ||
                !alt.bits || alt.bits > 8 * alt.bytes) continue;

            int feedbackAddress = 0;
            for (int e = 0; e < as.bNumEndpoints; ++e) {
                const auto& ep = as.endpoint[e];
                if ((ep.bmAttributes & 3) != 1 || (ep.bEndpointAddress & 0x80) ||
                    (ep.bmAttributes & 0x30)) continue;
                alt.endpoint = ep.bEndpointAddress;
                alt.interval = ep.bInterval;
                alt.packet = packetBytes(ep);
                alt.sync = (ep.bmAttributes >> 2) & 3;
                feedbackAddress = ep.bSynchAddress;
                descriptors(ep.extra, ep.extra_length, [&](const uint8_t* d, int length) {
                    if (length >= 4 && d[1] == 0x25 && d[2] == 1) alt.rateControl = d[3] & 1;
                });
                break;
            }
            if (!alt.endpoint || !alt.packet || alt.interval < 1 || alt.interval > 16) continue;
            for (int e = 0; e < as.bNumEndpoints; ++e) {
                const auto& ep = as.endpoint[e];
                if ((ep.bmAttributes & 3) == 1 && (ep.bEndpointAddress & 0x80) &&
                    (((ep.bmAttributes & 0x30) == 0x10) || ep.bEndpointAddress == feedbackAddress)) {
                    alt.feedback = ep.bEndpointAddress;
                    alt.feedbackPacket = packetBytes(ep);
                    break;
                }
            }

            for (int ac = 0; ac < config.bNumInterfaces; ++ac) {
                const auto& candidate = config.interface[ac];
                if (!candidate.num_altsetting) continue;
                const auto& control = candidate.altsetting[0];
                if (control.bInterfaceClass != 1 || control.bInterfaceSubClass != 1 ||
                    control.bInterfaceProtocol != as.bInterfaceProtocol) continue;
                bool matches = false;
                std::vector<std::vector<uint8_t>> entities;
                descriptors(control.extra, control.extra_length, [&](const uint8_t* d, int length) {
                    if (length < 4 || d[1] != 0x24) return;
                    if (alt.version == 1 && d[2] == 1 && length >= 8 && length >= 8 + d[7]) {
                        for (int index = 0; index < d[7]; ++index) {
                            if (d[8 + index] == alt.interface) matches = true;
                        }
                    }
                    if (d[2] > 1) entities.emplace_back(d, d + length);
                });
                if (alt.version == 2) {
                    const int function = audioFunctions[alt.interface];
                    if (function >= 0 && audioFunctions[control.bInterfaceNumber] != function) continue;
                    for (const auto& d : entities) {
                        if (d.size() >= 17 && d[2] == 2 && d[3] == alt.terminal &&
                            little(d.data() + 4, 2) == 0x0101) matches = true;
                    }
                }
                if (matches) {
                    if (alt.control >= 0) {
                        // Ambiguous class-specific IDs without a usable function association.
                        alt.control = -1;
                        alt.entities.clear();
                        break;
                    }
                    alt.control = control.bInterfaceNumber;
                    alt.entities = std::move(entities);
                }
            }
            choices.push_back(std::move(alt));
        }
    }
    return choices;
}

std::string describe(const AudioAlt& alt) {
    std::ostringstream out;
    out << "UAC" << alt.version << " AC=" << alt.control << " AS=" << alt.interface
        << " alt=" << alt.alt << " " << alt.channels << "ch " << alt.bits << "bit/"
        << alt.bytes << "B OUT=0x" << std::hex << alt.endpoint << " feedback=0x"
        << alt.feedback << std::dec << " packet=" << alt.packet << "B interval=" << alt.interval;
    if (alt.version == 2) out << " rates=clock";
    if (alt.continuous && alt.rates.size() == 2) out << " rates=" << alt.rates[0] << "-" << alt.rates[1];
    else for (int rate : alt.rates) out << " " << rate << "Hz";
    return out.str();
}

double feedbackSamplesPerPacket(uint32_t raw, int periods, int baseHz) {
    if (baseHz <= 0) return 0.0;
    return (raw / 65536.0) * periods * 1000.0 / baseHz;
}
