#pragma once

#include <libusb.h>
#include <cstdint>
#include <string>
#include <vector>

struct AudioAlt {
    int version{}, control{-1}, interface{}, alt{}, terminal{};
    int channels{}, bits{}, bytes{}, endpoint{}, packet{}, interval{};
    int feedback{}, feedbackPacket{}, sync{};
    bool rateControl{};
    std::vector<int> rates;
    bool continuous{};
    std::vector<std::vector<uint8_t>> entities;
};

unsigned little(const uint8_t* bytes, int size);
std::vector<AudioAlt> parseAudio(const libusb_config_descriptor& config);
std::string describe(const AudioAlt& alt);
