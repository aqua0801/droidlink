#!/usr/bin/env bash
# Builds the Linux desktop packages into OUT_DIR (default: ./release):
#   DroidLink-<version>-linux-<arch>.deb      Debian / Ubuntu package
#   DroidLink-<version>-linux-<arch>.tar.gz   portable build for other distributions
#
# Run it on Linux (or WSL) with a JDK 17+ and the Android SDK (ANDROID_HOME, local.properties
# or ~/Android/Sdk; d8 builds the phone-side helper). jpackage only packages for the OS it runs
# on, so this is the Linux counterpart of `gradlew :desktopApp:packageExe` on Windows.
#
# Usage: scripts/package-linux.sh [--export] [OUT_DIR]
#   --export  build from a clean `git archive HEAD` copy under ~/.cache instead of this checkout
#             (used by release.ps1 -Linux: keeps WSL builds off /mnt/c and away from the
#             Windows build directories, and makes the packages match the commit)
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
if [ "${1:-}" = "--export" ]; then
    shift
    out=$(mkdir -p "${1:-$root/release}" && cd "${1:-$root/release}" && pwd)
    work="$HOME/.cache/droidlink-release"
    rm -rf "$work" && mkdir -p "$work"
    git -c safe.directory='*' -C "$root" archive HEAD | tar -x -C "$work"
    exec bash "$work/scripts/package-linux.sh" "$out"
fi
out=$(mkdir -p "${1:-$root/release}" && cd "${1:-$root/release}" && pwd)
cd "$root"

fail() { echo "ERROR: $*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

version=$(sed -n 's/^droidlink.version=//p' gradle.properties | tr -d '\r')
[ -n "$version" ] || fail "droidlink.version is missing from gradle.properties"
case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64) arch=arm64 ;;
    *) arch=$(uname -m) ;;
esac

# jpackage itself needs dpkg-deb and fakeroot to build the .deb.
for tool in dpkg-deb fakeroot; do
    command -v "$tool" >/dev/null || fail "$tool not found (Debian/Ubuntu: sudo apt install dpkg-dev fakeroot)"
done
if [ -z "${JAVA_HOME:-}" ] && ! command -v java >/dev/null; then
    fail "no JDK found; set JAVA_HOME or install a JDK 17+"
fi
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    [ -d "$HOME/Android/Sdk" ] || fail "Android SDK not found; set ANDROID_HOME"
    export ANDROID_HOME="$HOME/Android/Sdk"
fi

step "Building DroidLink $version for linux-$arch"
chmod +x gradlew
./gradlew --console=plain :desktopApp:packageDeb :desktopApp:createDistributable

binaries=desktopApp/build/compose/binaries/main
deb=$(ls -t "$binaries"/deb/*.deb 2>/dev/null | head -1)
[ -n "$deb" ] || fail ".deb not found under $binaries/deb"

# jpackage derives Depends from the build machine. Ubuntu 24.04 / Debian 13 renamed some
# libraries for the 64-bit time_t transition (libasound2 -> libasound2t64), so accept either
# name: the package then also installs on Ubuntu 22.04 / Debian 12. The menu registration in
# the maintainer scripts must not fail the install on systems without a menu directory.
step "Adjusting package metadata"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
dpkg-deb -R "$deb" "$work/pkg"
sed -i -E '/^Depends:/ s/([a-z0-9.+-]+)t64([ ,]|$)/\1t64 | \1\2/g' "$work/pkg/DEBIAN/control"
for script in postinst prerm; do
    [ -f "$work/pkg/DEBIAN/$script" ] && sed -i -E 's/^(xdg-desktop-menu .*[^;])$/\1 || true/' "$work/pkg/DEBIAN/$script"
done
grep '^Depends:' "$work/pkg/DEBIAN/control"

name="DroidLink-$version-linux-$arch"
dpkg-deb --root-owner-group -Zxz -b "$work/pkg" "$out/$name.deb" >/dev/null

step "Creating portable archive"
tar -C "$binaries/app" -czf "$out/$name.tar.gz" DroidLink

ls -lh "$out/$name.deb" "$out/$name.tar.gz"
