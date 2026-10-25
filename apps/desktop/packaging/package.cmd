@echo off
rem Builds build\NeoChat-<version>-x64.msi from a release build (run build.cmd first).
setlocal
if "%QT_DIR%"=="" set QT_DIR=E:\dev\Qt\6.8.3
set VERSION=0.7.0.3
set VS=C:\Program Files\Microsoft Visual Studio\2022\Community
cd /d "%~dp0\.."
set STAGE=build\stage
if exist %STAGE% rmdir /s /q %STAGE%
mkdir %STAGE% || exit /b 1
copy /y build\neochat.exe %STAGE%\ >nul || exit /b 1
rem WebRTC for calls, loaded on the first call.
copy /y build\neocall.dll %STAGE%\ >nul || exit /b 1
rem Speech recognition (sherpa-onnx), loaded when a voice message is turned into text.
copy /y ..\..\third_party\sherpa-onnx\windows\lib\*.dll %STAGE%\ >nul || exit /b 1
"%QT_DIR%\bin\windeployqt.exe" --release --no-translations --no-system-d3d-compiler --no-opengl-sw --no-compiler-runtime %STAGE%\neochat.exe >nul || exit /b 1
rem App-local VC runtime (the Rust core and Qt both link the release CRT).
for /d %%D in ("%VS%\VC\Redist\MSVC\14.*") do copy /y "%%D\x64\Microsoft.VC143.CRT\*.dll" %STAGE%\ >nul
copy /y ..\..\LICENSE %STAGE%\LICENSE.txt >nul 2>nul
wix build -wx -arch x64 -ext WixToolset.UI.wixext -ext WixToolset.Util.wixext -culture ru-RU -d Version=%VERSION% -d StageDir="%CD%\%STAGE%" packaging\neochat.wxs -b packaging -o build\NeoChat-%VERSION%-x64.msi || exit /b 1
echo build\NeoChat-%VERSION%-x64.msi
