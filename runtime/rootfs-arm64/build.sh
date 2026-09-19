#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root_dir="$(cd "$script_dir/../.." && pwd)"
out_dir="${VODKA_ROOTFS_OUT:-$root_dir/build/rootfs}"
image="${VODKA_ROOTFS_IMAGE:-vodka-rootfs-arm64:bookworm}"
engine="${VODKA_ENGINE:-docker}"
platform="linux/arm64"
archive="rootfs-arm64.tar.gz"

usage() {
  cat <<EOF
Build the Vodka ARM64 rootfs.

Environment:
  VODKA_ENGINE       container engine (default: docker; podman also works)
  VODKA_ROOTFS_OUT   output directory (default: build/rootfs)
  VODKA_ROOTFS_IMAGE image tag (default: vodka-rootfs-arm64:bookworm)

Output:
  \$VODKA_ROOTFS_OUT/$archive
  \$VODKA_ROOTFS_OUT/$archive.sha256
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

for tool in "$engine" sha256sum; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: required tool '$tool' not found" >&2
    exit 1
  fi
done

if command -v pigz >/dev/null 2>&1; then
  compress() { pigz -9; }
elif command -v gzip >/dev/null 2>&1; then
  compress() { gzip -9; }
else
  echo "error: neither pigz nor gzip found" >&2
  exit 1
fi

mkdir -p "$out_dir"

echo "==> building image $image ($platform)"
if [ "$engine" = "docker" ]; then
  "$engine" buildx build --platform "$platform" --load -t "$image" -f "$script_dir/Dockerfile" "$script_dir"
else
  "$engine" build --platform "$platform" -t "$image" -f "$script_dir/Dockerfile" "$script_dir"
fi

echo "==> exporting $archive"
container="$("$engine" create --platform "$platform" "$image" /bin/true)"
"$engine" export "$container" | compress > "$out_dir/$archive"
"$engine" rm "$container" >/dev/null

echo "==> checksum"
( cd "$out_dir" && sha256sum "$archive" > "$archive.sha256" )

echo "done: $out_dir/$archive"
