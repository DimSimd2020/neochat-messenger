#!/usr/bin/env bash
# Fetches the sherpa-onnx runtime (Apache-2.0; ONNX Runtime inside is MIT) that the apps use for
# on-device speech recognition of voice messages. The archives are checked against pinned SHA-256
# and are not committed: run it before building a client.
#
#   scripts/fetch-sherpa.sh windows   -> third_party/sherpa-onnx/windows/{include,lib}
#   scripts/fetch-sherpa.sh android   -> apps/android/app/src/main/jniLibs/<abi>/
#   scripts/fetch-sherpa.sh ios       -> apps/ios/Vendor/SherpaOnnxC.xcframework
set -euo pipefail

VERSION=v1.13.8
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${NEOCHAT_FETCH_CACHE:-${TMPDIR:-/tmp}/neochat-sherpa}"
mkdir -p "$CACHE"

fetch() { # name url sha256
  local file="$CACHE/$1"
  if [ -f "$file" ] && echo "$3  $file" | sha256sum -c --status 2>/dev/null; then return; fi
  curl -fsSL --retry 4 --retry-delay 3 -o "$file" "$2"
  echo "$3  $file" | sha256sum -c --status || { echo "Контрольная сумма $1 не совпала" >&2; rm -f "$file"; exit 1; }
}

case "${1:-}" in
  windows)
    name=sherpa-onnx-$VERSION-win-x64-shared-MD-Release.tar.bz2
    fetch "$name" "$BASE/$VERSION/$name" 3e971a04b2e0ba4dfa53d381a006367ce8c9f5f09b4ae00043e9845c2baded22
    out="$ROOT/third_party/sherpa-onnx/windows"
    rm -rf "$out"; mkdir -p "$out"
    (cd "$CACHE" && tar xjf "$name")
    src="$CACHE/sherpa-onnx-$VERSION-win-x64-shared-MD-Release"
    cp -r "$src/include" "$out/include"
    mkdir -p "$out/lib"
    cp "$src/lib/sherpa-onnx-c-api.dll" "$src/lib/sherpa-onnx-c-api.lib" "$src/lib/onnxruntime.dll" "$out/lib/"
    ;;
  android)
    name=sherpa-onnx-$VERSION-android.tar.bz2
    fetch "$name" "$BASE/$VERSION/$name" 2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44
    tmp="$CACHE/android"
    rm -rf "$tmp"; mkdir -p "$tmp"
    (cd "$CACHE" && tar xjf "$name" -C "$tmp")
    for abi in arm64-v8a x86_64; do
      mkdir -p "$ROOT/apps/android/app/src/main/jniLibs/$abi"
      cp "$tmp/jniLibs/$abi/libonnxruntime.so" "$tmp/jniLibs/$abi/libsherpa-onnx-jni.so" "$ROOT/apps/android/app/src/main/jniLibs/$abi/"
    done
    ;;
  ios)
    name=sherpa-onnx-$VERSION-ios-shared-onnxruntime-static.xcframework.zip
    fetch "$name" "$BASE/xcframework/$name" e259a7d3b38ad7dec49bb078252a30bb42ede8355e2bb130cf8c1c78ed131f75
    out="$ROOT/apps/ios/Vendor"
    rm -rf "$out/SherpaOnnxC.xcframework"; mkdir -p "$out"
    unzip -q -o "$CACHE/$name" -d "$out"
    ;;
  *)
    echo "usage: $0 windows|android|ios" >&2
    exit 2
    ;;
esac
echo "sherpa-onnx $VERSION: $1 готов"
