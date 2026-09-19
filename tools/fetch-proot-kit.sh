#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "$script_dir/.." && pwd)"
dest="${VODKA_JNILIBS:-$root_dir/app/src/main/jniLibs/arm64-v8a}"
work="${VODKA_PROOT_WORK:-$root_dir/build/proot-kit}"
base="https://packages.termux.dev/apt/termux-main/pool/main"
proot_deb="proot_5.1.107.92_aarch64.deb"
talloc_deb="libtalloc_2.4.3_aarch64.deb"
shmem_deb="libandroid-shmem_0.7_aarch64.deb"

usage() {
  cat <<EOF
Assemble the Android-native proot kit into the app's jniLibs directory.

proot is a glibc program upstream and cannot run on Android's Bionic; Termux ships a
Bionic build. Android 10+ also forbids executing code from app-writable storage, so proot
and its libraries must be packaged as APK native libraries and run from nativeLibraryDir.

Downloaded (Termux): proot, libtalloc, libandroid-shmem.
proot's DT_NEEDED "libtalloc.so.2" is rewritten to "libtalloc.so" so the library can be
packaged as a normal \`lib*.so\` android native library.

Environment:
  VODKA_JNILIBS   destination (default: app/src/main/jniLibs/arm64-v8a)
  VODKA_PROOT_WORK  scratch directory (default: build/proot-kit)
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

for tool in curl ar tar python3; do
  command -v "$tool" >/dev/null 2>&1 || { echo "error: '$tool' not found" >&2; exit 1; }
done

mkdir -p "$work" "$dest"
cd "$work"

fetch_deb() {
  local name="$1"
  local dir="$2"
  if [ ! -f "$name" ]; then
    curl -sS -L --fail -O "$base/$dir/$name"
  fi
  rm -rf "x_$name"
  mkdir "x_$name"
  ( cd "x_$name" && ar x "../$name" && \
    if [ -f data.tar.xz ]; then tar -xJf data.tar.xz; \
    elif [ -f data.tar.zst ]; then tar --zstd -xf data.tar.zst; \
    else tar -xf data.tar.gz; fi )
}

fetch_deb "$proot_deb" "p/proot"
fetch_deb "$talloc_deb" "libt/libtalloc"
fetch_deb "$shmem_deb" "liba/libandroid-shmem"

prefix="data/data/com.termux/files/usr"
proot="x_$proot_deb/$prefix/bin/proot"
loader="x_$proot_deb/$prefix/libexec/proot/loader"
talloc="x_$talloc_deb/$prefix/lib/libtalloc.so.2.4.3"
shmem=$(find "x_$shmem_deb" -name 'libandroid-shmem.so' | head -1)

[ -f "$proot" ] || { echo "error: proot not found in deb" >&2; exit 1; }
[ -f "$loader" ] || { echo "error: proot loader not found in deb" >&2; exit 1; }
[ -f "$talloc" ] || { echo "error: libtalloc not found in deb" >&2; exit 1; }
[ -n "$shmem" ] || { echo "error: libandroid-shmem not found in deb" >&2; exit 1; }

cp -f "$proot" "$dest/libproot.so"
cp -f "$loader" "$dest/libproot_loader.so"
cp -f "$talloc" "$dest/libtalloc.so"
cp -f "$shmem" "$dest/libandroid-shmem.so"
chmod 755 "$dest/libproot.so" "$dest/libproot_loader.so"
chmod 644 "$dest/libtalloc.so" "$dest/libandroid-shmem.so"

python3 - "$dest/libproot.so" <<'PY'
import sys
path = sys.argv[1]
data = bytearray(open(path, 'rb').read())
old = b'libtalloc.so.2\x00'
new = b'libtalloc.so\x00'
i = data.find(old)
if i < 0:
    sys.exit('libtalloc.so.2 not found in proot; nothing to patch')
data[i:i + len(new)] = new
open(path, 'wb').write(data)
print('patched DT_NEEDED libtalloc.so.2 -> libtalloc.so')
PY

echo "installed proot kit into $dest:"
ls -l "$dest"/libproot.so "$dest"/libproot_loader.so "$dest"/libtalloc.so "$dest"/libandroid-shmem.so
