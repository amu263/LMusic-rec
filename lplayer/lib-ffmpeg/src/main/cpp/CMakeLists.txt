cmake_minimum_required(VERSION 3.22.1)
project(media3ext CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Location of the prebuilt FFmpeg static libraries (built by tools/build_ffmpeg.sh).
set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ffbuild/out/${ANDROID_ABI}" CACHE PATH "FFmpeg static library root")
include_directories("${FFMPEG_ROOT}/include")

add_library(media3ext SHARED
        ffmain.cpp
        ffcommon.cpp
        ffaudio.cpp
        ffextractor.cpp)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,--no-rosegment")

target_link_libraries(media3ext
        ${FFMPEG_ROOT}/lib/libavformat.a
        ${FFMPEG_ROOT}/lib/libavcodec.a
        ${FFMPEG_ROOT}/lib/libswresample.a
        ${FFMPEG_ROOT}/lib/libavutil.a
        log android z m)
