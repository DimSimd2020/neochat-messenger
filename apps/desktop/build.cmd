@echo off
rem Release build of the desktop client. Needs Visual Studio 2022 (C++), Qt 6.8,
rem Rust and Strawberry Perl (for the bundled OpenSSL of SQLCipher).
setlocal
if "%QT_DIR%"=="" set QT_DIR=E:\dev\Qt\6.8.3
if "%PERL%"=="" set PERL=C:\Strawberry\perl\bin\perl.exe
if "%PROTOC%"=="" set PROTOC=E:\dev\protoc\bin\protoc.exe
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul || exit /b 1
cd /d "%~dp0"
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_PREFIX_PATH=%QT_DIR% || exit /b 1
cmake --build build || exit /b 1
