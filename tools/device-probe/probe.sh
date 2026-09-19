#!/bin/sh
set -u

section() {
  printf '\n== %s ==\n' "$1"
}

getprop_or() {
  if command -v getprop >/dev/null 2>&1; then
    getprop "$1" 2>/dev/null
  else
    printf '%s\n' "$2"
  fi
}

section kernel
uname -a
printf 'page_size=%s\n' "$(getconf PAGESIZE 2>/dev/null || echo unknown)"
printf 'pointer_auth=%s\n' "$(grep -m1 -o 'paca\|pacg' /proc/cpuinfo 2>/dev/null || echo unknown)"

section android
printf 'sdk=%s\n' "$(getprop_or ro.build.version.sdk unknown)"
printf 'release=%s\n' "$(getprop_or ro.build.version.release unknown)"
printf 'soc=%s\n' "$(getprop_or ro.soc.model unknown)"
printf 'board=%s\n' "$(getprop_or ro.board.platform unknown)"
printf 'abis=%s\n' "$(getprop_or ro.product.cpu.abilist unknown)"

section security
if [ -r /proc/sys/kernel/unprivileged_userns_clone ]; then
  printf 'unprivileged_userns_clone=%s\n' "$(cat /proc/sys/kernel/unprivileged_userns_clone)"
else
  printf 'unprivileged_userns_clone=absent\n'
fi
printf 'seccomp=%s\n' "$(grep -m1 Seccomp /proc/self/status 2>/dev/null || echo unknown)"
printf 'selinux=%s\n' "$(getprop_or ro.boot.selinux unknown)"

section gpu
if command -v vulkaninfo >/dev/null 2>&1; then
  vulkaninfo --summary 2>/dev/null
else
  printf 'vulkaninfo=absent\n'
fi

section storage
if [ -d /sdcard ]; then
  printf 'sdcard=present\n'
  df -h /sdcard 2>/dev/null
else
  printf 'sdcard=absent\n'
fi
