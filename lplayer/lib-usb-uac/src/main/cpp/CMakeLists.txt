cmake_minimum_required(VERSION 3.22.1)
project(usbuac LANGUAGES C CXX)

set(usb "${LIBUSB_SOURCE}/libusb")
add_library(usb STATIC
    "${usb}/core.c" "${usb}/descriptor.c" "${usb}/hotplug.c"
    "${usb}/io.c" "${usb}/sync.c" "${usb}/strerror.c"
    "${usb}/os/linux_usbfs.c" "${usb}/os/linux_netlink.c"
    "${usb}/os/events_posix.c" "${usb}/os/threads_posix.c")
target_include_directories(usb PUBLIC "${usb}" PRIVATE "${LIBUSB_SOURCE}/android" "${usb}/os")
set_target_properties(usb PROPERTIES POSITION_INDEPENDENT_CODE ON C_VISIBILITY_PRESET hidden)
target_link_libraries(usb PRIVATE log)

add_library(usbuac SHARED uac.cpp descriptors.cpp)
target_compile_features(usbuac PRIVATE cxx_std_17)
target_compile_options(usbuac PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_options(usbuac PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(usbuac PRIVATE usb log)
