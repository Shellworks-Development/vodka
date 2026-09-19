#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "$script_dir/../.." && pwd)"
work_dir="${VODKA_FEX_WORK:-$root_dir/build/fex}"
out_dir="${VODKA_FEX_OUT:-$root_dir/build/fex-out}"
src_dir="$work_dir/FEX"
staging="$work_dir/staging"
prefix="${VODKA_FEX_PREFIX:-/usr}"
ref="${VODKA_FEX_REF:-main}"

usage() {
  cat <<EOF
Cross-compile FEX-Emu for aarch64 Linux (glibc) and package it for the Vodka ARM64 rootfs.

Environment:
  VODKA_FEX_REF        git ref to build (default: main, e.g. FEX-2609)
  VODKA_FEX_WORK       build workspace (default: build/fex)
  VODKA_FEX_OUT        output directory (default: build/fex-out)
  VODKA_FEX_PREFIX     install prefix inside the rootfs (default: /usr)
  VODKA_AARCH64_CC     aarch64 C compiler (default: aarch64-linux-gnu-gcc)
  VODKA_AARCH64_CXX    aarch64 C++ compiler (default: aarch64-linux-gnu-g++)

Output:
  \$VODKA_FEX_OUT/fex-arm64.tar.gz   (extract into the ARM64 rootfs)
  \$VODKA_FEX_OUT/fex-arm64.tar.gz.sha256
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

cc="${VODKA_AARCH64_CC:-aarch64-linux-gnu-gcc}"
cxx="${VODKA_AARCH64_CXX:-aarch64-linux-gnu-g++}"

for tool in git cmake ninja "$cc" "$cxx" sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: required tool '$tool' not found" >&2
    exit 1
  fi
done

mkdir -p "$work_dir" "$out_dir"

if [ ! -d "$src_dir/.git" ]; then
  echo "==> cloning FEX ($ref)"
  git clone --recurse-submodules --depth 1 --branch "$ref" https://github.com/FEX-Emu/FEX.git "$src_dir"
else
  echo "==> updating FEX ($ref)"
  git -C "$src_dir" fetch --depth 1 origin "$ref"
  git -C "$src_dir" checkout FETCH_HEAD
  git -C "$src_dir" submodule update --init --recursive --depth 1
fi

echo "==> configuring (aarch64)"
cmake -S "$src_dir" -B "$src_dir/build" -G Ninja \
  -DCMAKE_SYSTEM_NAME=Linux \
  -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_C_COMPILER="$cc" \
  -DCMAKE_CXX_COMPILER="$cxx" \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_INSTALL_PREFIX="$prefix" \
  -DENABLE_LTO=True \
  -DBUILD_THUNKS=True \
  -DBUILD_FEXCONFIG=False

echo "==> building"
cmake --build "$src_dir/build" --parallel

echo "==> staging"
rm -rf "$staging"
DESTDIR="$staging" cmake --install "$src_dir/build"

echo "==> packaging"
tar -czf "$out_dir/fex-arm64.tar.gz" -C "$staging" .
( cd "$out_dir" && sha256sum "fex-arm64.tar.gz" > "fex-arm64.tar.gz.sha256" )

echo "done: $out_dir/fex-arm64.tar.gz"
echo "note: install into the ARM64 rootfs; it provides bin/FEX and lib/fex-emu/HostThunks."
