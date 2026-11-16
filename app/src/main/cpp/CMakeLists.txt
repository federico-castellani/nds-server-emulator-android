cmake_minimum_required(VERSION 3.22)
project(ndscore LANGUAGES C CXX)

set(MELONDS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../external/melonDS")
get_filename_component(MELONDS_DIR "${MELONDS_DIR}" ABSOLUTE)

# melonDS core only: no Qt frontend, no OpenGL, no GDB stub, no LTO.
set(BUILD_QT_SDL OFF CACHE BOOL "" FORCE)
set(ENABLE_OGLRENDERER OFF CACHE BOOL "" FORCE)
set(ENABLE_GDBSTUB OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO_RELEASE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

list(APPEND CMAKE_MODULE_PATH "${MELONDS_DIR}/cmake")
add_subdirectory(${MELONDS_DIR} melonDS EXCLUDE_FROM_ALL)

# From melonDS' networking only Net/PacketDispatcher and the bundled libslirp are needed;
# its net-utils target also pulls in ENet for LAN/netplay, which this app does not use.
add_subdirectory(${MELONDS_DIR}/src/net/libslirp melonDS-slirp EXCLUDE_FROM_ALL)

add_library(ndscore SHARED
    Core.cpp
    Jni.cpp
    NetStats.cpp
    Platform.cpp
    SlirpDriver.cpp
    ${MELONDS_DIR}/src/net/Net.cpp
    ${MELONDS_DIR}/src/net/PacketDispatcher.cpp
)

target_include_directories(ndscore PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${MELONDS_DIR}/src ${MELONDS_DIR}/src/net)
target_link_libraries(ndscore PRIVATE core slirp aaudio log android)
target_compile_options(ndscore PRIVATE -Wall -Wno-unused-parameter)
# 16 KB page alignment (Android 15+), also set by ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES.
target_link_options(ndscore PRIVATE "-Wl,-z,max-page-size=16384")
