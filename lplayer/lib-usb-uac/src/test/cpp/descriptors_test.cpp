#include "descriptors.h"

#include <cassert>

namespace {
void uac2Composite() {
    const uint8_t associations[] = {8, 0x0b, 0, 2, 1, 0, 0x20, 0};
    const uint8_t secondAssociation[] = {8, 0x0b, 2, 2, 1, 0, 0x20, 0};
    const uint8_t terminal[] = {
        17, 0x24, 2, 1, 1, 1, 0, 9, 2, 3, 0, 0, 0, 0, 0, 0, 0,
        8, 0x24, 0x0a, 9, 3, 3, 0, 0,
    };
    const uint8_t format[] = {
        16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0,
        6, 0x24, 2, 1, 4, 24,
    };
    libusb_endpoint_descriptor endpoints[2]{};
    endpoints[0].bEndpointAddress = 1;
    endpoints[0].bmAttributes = 5;
    endpoints[0].bInterval = 1;
    endpoints[0].wMaxPacketSize = 0x1400;
    endpoints[0].extra = secondAssociation;
    endpoints[0].extra_length = sizeof(secondAssociation);
    endpoints[1].bEndpointAddress = 0x81;
    endpoints[1].bmAttributes = 0x11;
    endpoints[1].bInterval = 4;
    endpoints[1].wMaxPacketSize = 4;
    libusb_interface_descriptor control{};
    control.bInterfaceClass = 1;
    control.bInterfaceSubClass = 1;
    control.bInterfaceProtocol = 0x20;
    control.extra = terminal;
    control.extra_length = sizeof(terminal);
    libusb_interface_descriptor streaming = control;
    streaming.bInterfaceNumber = 1;
    streaming.bInterfaceSubClass = 2;
    streaming.bAlternateSetting = 3;
    streaming.extra = format;
    streaming.extra_length = sizeof(format);
    streaming.bNumEndpoints = 2;
    streaming.endpoint = endpoints;
    auto secondControl = control;
    secondControl.bInterfaceNumber = 2;
    auto secondStream = streaming;
    secondStream.bInterfaceNumber = 3;
    const libusb_interface interfaces[] = {
        {&control, 1}, {&streaming, 1}, {&secondControl, 1}, {&secondStream, 1}
    };
    libusb_config_descriptor config{};
    config.bNumInterfaces = 4;
    config.interface = interfaces;
    config.extra = associations;
    config.extra_length = sizeof(associations);

    const auto parsed = parseAudio(config);
    assert(parsed.size() == 2);
    const auto& first = parsed[0];
    assert(first.version == 2 && first.control == 0 && first.interface == 1 && first.alt == 3);
    assert(first.terminal == 1 && first.channels == 2 && first.bytes == 4 && first.bits == 24);
    assert(first.endpoint == 1 && first.feedback == 0x81 && first.feedbackPacket == 4);
    assert(first.packet == 3072 && first.sync == 1 && first.interval == 1);
    assert(parsed[1].control == 2 && parsed[1].interface == 3);

    streaming.extra_length = sizeof(format) - 1;
    assert(parseAudio(config).size() == 1);
}

void uac1Continuous() {
    const uint8_t header[] = {9, 0x24, 1, 0, 1, 9, 0, 1, 5};
    const uint8_t rateControl[] = {7, 0x25, 1, 1, 0, 0, 0};
    const uint8_t format[] = {
        7, 0x24, 1, 1, 0, 1, 0,
        14, 0x24, 2, 1, 2, 2, 16, 0, 0x44, 0xac, 0, 0x80, 0xbb, 0,
    };
    libusb_endpoint_descriptor out{};
    out.bEndpointAddress = 2;
    out.bmAttributes = 9;
    out.bInterval = 1;
    out.wMaxPacketSize = 192;
    out.extra = rateControl;
    out.extra_length = sizeof(rateControl);
    libusb_interface_descriptor control{};
    control.bInterfaceClass = 1;
    control.bInterfaceSubClass = 1;
    control.extra = header;
    control.extra_length = sizeof(header);
    libusb_interface_descriptor streaming = control;
    streaming.bInterfaceNumber = 5;
    streaming.bInterfaceSubClass = 2;
    streaming.bAlternateSetting = 2;
    streaming.extra = format;
    streaming.extra_length = sizeof(format);
    streaming.bNumEndpoints = 1;
    streaming.endpoint = &out;
    const libusb_interface interfaces[] = {{&control, 1}, {&streaming, 1}};
    libusb_config_descriptor config{};
    config.bNumInterfaces = 2;
    config.interface = interfaces;

    const auto parsed = parseAudio(config);
    assert(parsed.size() == 1);
    const auto& alt = parsed.front();
    assert(alt.version == 1 && alt.control == 0 && alt.interface == 5 && alt.alt == 2);
    assert(alt.bits == 16 && alt.channels == 2 && alt.bytes == 2);
    assert(alt.rateControl && alt.continuous && (alt.rates == std::vector<int>{44100, 48000}));
    assert(alt.packet == 192 && alt.endpoint == 2 && alt.feedback == 0);
}
}

int main() {
    uac2Composite();
    uac1Continuous();
}
