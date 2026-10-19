cmake_minimum_required(VERSION 3.21)
project(NeoChat VERSION 0.4.1 LANGUAGES CXX)

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
    src/onboarding.cpp src/onboarding.h
    src/chatlist.cpp src/chatlist.h
    src/messages.cpp src/messages.h
    src/composer.cpp src/composer.h
    src/dialogs.cpp src/dialogs.h
    src/mainwindow.cpp src/mainwindow.h
    src/style.cpp src/style.h
    src/resources.qrc)
add_dependencies(neochat_ui neocore)
# Russian strings for Qt's own widgets (context menus, file dialogs), embedded in the binary.
qt_add_resources(neochat_ui "qt_ru" PREFIX "/i18n" BASE "${QT6_INSTALL_PREFIX}/${QT6_INSTALL_TRANSLATIONS}"
    FILES "${QT6_INSTALL_PREFIX}/${QT6_INSTALL_TRANSLATIONS}/qtbase_ru.qm")
target_include_directories(neochat_ui PUBLIC "${NEOCORE_DIR}/include" src)
target_compile_definitions(neochat_ui PUBLIC NEOCHAT_VERSION="${PROJECT_VERSION}" UNICODE _UNICODE)
target_link_libraries(neochat_ui PUBLIC Qt6::Widgets Qt6::Svg "${NEOCORE_LIB}"
    crypt32 bcrypt advapi32 gdi32 user32 ws2_32 ntdll userenv dbghelp legacy_stdio_definitions)
if(MSVC)
    target_compile_options(neochat_ui PUBLIC /utf-8 /W3)
endif()

add_executable(neochat WIN32 src/main.cpp packaging/neochat.rc)
target_link_libraries(neochat PRIVATE neochat_ui)

# UI scenario test: drives the real widgets offscreen against a local server
# (see scripts/uitest.sh) and saves screenshots of each step.
find_package(Qt6 COMPONENTS Test)
if(Qt6Test_FOUND)
    add_executable(neochat_uitest tests/uitest.cpp)
    target_link_libraries(neochat_uitest PRIVATE neochat_ui Qt6::Test)
endif()

install(TARGETS neochat RUNTIME DESTINATION .)
