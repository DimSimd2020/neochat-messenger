cmake_minimum_required(VERSION 3.21)
project(NeoChat VERSION 0.6.9.9 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Widgets Svg)

# The Rust core (E2EE, storage, sync) is linked statically. It is built with
# `cargo build --release --lib` in ../../core; the release CRT (/MD) must match,
# so only Release/RelWithDebInfo configurations are supported.
set(NEOCORE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../core")
set(NEOCORE_LIB "${NEOCORE_DIR}/target/release/neocore.lib" CACHE FILEPATH "Path to the neocore static library")

add_custom_target(neocore
    COMMAND cargo build --release --lib
    WORKING_DIRECTORY "${NEOCORE_DIR}"
    BYPRODUCTS "${NEOCORE_LIB}"
    USES_TERMINAL)

add_library(neochat_ui STATIC
    src/core.cpp src/core.h
    src/keystore.cpp src/keystore.h
    src/theme.cpp src/theme.h
    src/widgets.cpp src/widgets.h
    src/sheet.cpp src/sheet.h
    src/transition.cpp src/transition.h
    src/popupmenu.cpp src/popupmenu.h
    src/filepicker.cpp src/filepicker.h
    src/chrome.cpp src/chrome.h
    src/onboarding.cpp src/onboarding.h
    src/email.cpp src/email.h
    src/chatlist.cpp src/chatlist.h
    src/messages.cpp src/messages.h
    src/composer.cpp src/composer.h
    src/emoji.cpp src/emoji.h
    src/dialogs.cpp src/dialogs.h
    src/appearance.cpp
    src/profiles.cpp src/profiles.h
    src/profilestyle.cpp src/profilestyle.h
    src/mainwindow.cpp src/mainwindow.h
    src/calls.cpp src/calls.h
    src/style.cpp src/style.h
    src/resources.qrc)
add_dependencies(neochat_ui neocore)
# Russian strings for Qt's own widgets (context menus, file dialogs), embedded in the binary.
qt_add_resources(neochat_ui "qt_ru" PREFIX "/i18n" BASE "${QT6_INSTALL_PREFIX}/${QT6_INSTALL_TRANSLATIONS}"
    FILES "${QT6_INSTALL_PREFIX}/${QT6_INSTALL_TRANSLATIONS}/qtbase_ru.qm")
target_include_directories(neochat_ui PUBLIC "${NEOCORE_DIR}/include" src)
# A build that is not released from git (alpha) says so next to its version.
set(NEOCHAT_TAG "" CACHE STRING "Build tag shown next to the version, e.g. alpha")
set(NEOCHAT_VERSION_LABEL "${PROJECT_VERSION}")
if(NEOCHAT_TAG)
    set(NEOCHAT_VERSION_LABEL "${PROJECT_VERSION} ${NEOCHAT_TAG}")
endif()
target_compile_definitions(neochat_ui PUBLIC NEOCHAT_VERSION="${PROJECT_VERSION}" NEOCHAT_VERSION_LABEL="${NEOCHAT_VERSION_LABEL}" NEOCHAT_TAG="${NEOCHAT_TAG}" UNICODE _UNICODE)
target_link_libraries(neochat_ui PUBLIC Qt6::Widgets Qt6::Svg "${NEOCORE_LIB}"
    crypt32 bcrypt advapi32 gdi32 user32 ws2_32 ntdll userenv dbghelp legacy_stdio_definitions)
if(MSVC)
    target_compile_options(neochat_ui PUBLIC /utf-8 /W3)
endif()

# Calls: neocall.dll wraps the prebuilt libwebrtc (crow-misia/libwebrtc-bin,
# release build). libwebrtc uses the static CRT, so it lives in its own DLL with
# a C ABI and never shares C++ objects with Qt (/MD).
set(WEBRTC_DIR "E:/dev/webrtc" CACHE PATH "Unpacked libwebrtc-win-x64 (include/, release/webrtc.lib)")
add_library(neocall SHARED neocall/neocall.cpp neocall/neocall.h)
set_target_properties(neocall PROPERTIES MSVC_RUNTIME_LIBRARY "MultiThreaded" AUTOMOC OFF)
target_include_directories(neocall PRIVATE "${WEBRTC_DIR}/include" "${WEBRTC_DIR}/include/third_party/abseil-cpp"
    "${WEBRTC_DIR}/include/third_party/libyuv/include")
target_compile_definitions(neocall PRIVATE WEBRTC_WIN NOMINMAX WIN32_LEAN_AND_MEAN RTC_ENABLE_VP9)
if(MSVC)
    target_compile_options(neocall PRIVATE /utf-8 /W3 /wd4068 /wd4100 /wd4146 /wd4244 /wd4267 /wd4996 /Zc:__cplusplus)
endif()
target_link_libraries(neocall PRIVATE "${WEBRTC_DIR}/release/webrtc.lib"
    winmm secur32 dmoguids wmcodecdspuuid msdmo strmiids iphlpapi ws2_32 crypt32 ole32 oleaut32 uuid avrt
    d3d11 dxgi dwmapi shcore user32 gdi32 advapi32)
add_executable(neocall_selftest neocall/selftest.cpp)
target_link_libraries(neocall_selftest PRIVATE neocall)
target_include_directories(neochat_ui PUBLIC neocall)
# neocall.dll is loaded on the first call or device query (delay-load), so the
# 12 MB libwebrtc never slows the start of the messenger.
target_link_libraries(neochat_ui PUBLIC neocall winmm delayimp)
target_link_options(neochat_ui INTERFACE /DELAYLOAD:neocall.dll)

add_executable(neochat WIN32 src/main.cpp packaging/neochat.rc)
target_link_libraries(neochat PRIVATE neochat_ui)

# UI scenario test: drives the real widgets offscreen against a local server
# (see scripts/uitest.sh) and saves screenshots of each step.
find_package(Qt6 COMPONENTS Test)
if(Qt6Test_FOUND)
    add_executable(neochat_uitest tests/uitest.cpp)
    target_link_libraries(neochat_uitest PRIVATE neochat_ui Qt6::Test)
    add_executable(neochat_interactiontest tests/interactiontest.cpp)
    target_link_libraries(neochat_interactiontest PRIVATE neochat_ui Qt6::Test)
endif()

install(TARGETS neochat neocall RUNTIME DESTINATION .)
