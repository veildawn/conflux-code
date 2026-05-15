#!/usr/bin/env bash
#
# build-rootfs.sh — build a pre-baked Ubuntu rootfs with Node.js + Claude CLI
# already installed, ready to drop into app/src/main/assets/rootfs/.
#
# Run this ONCE on a Linux host (or macOS with docker) per ABI you want to
# ship. Output is written to app/build-support/prebuilt-rootfs/ and picked
# up by the `bundlePrebuiltRootfs` Gradle task at APK build time.
#
# Usage:
#   ./build-rootfs.sh arm64-v8a
#   ./build-rootfs.sh armeabi-v7a
#   ./build-rootfs.sh x86_64
#   ./build-rootfs.sh all   # build every ABI listed in APK_ABIS
#
# Requirements:
#   - docker (with binfmt_misc enabled for cross-arch emulation; docker
#     desktop on macOS already has this).
#   - ~6 GB free disk per ABI during build (final artifact is ~100-140 MB).
#
# Notes:
#   - Uses the official ubuntu:24.04 multi-arch image. docker will pull the
#     right variant based on the --platform flag.
#   - The installed Claude CLI version is pinned via CLAUDE_VERSION so the
#     image hash is reproducible. Bump it to upgrade.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$SCRIPT_DIR/prebuilt-rootfs"
APK_ABIS=(arm64-v8a armeabi-v7a x86_64 x86)

# Bump when the Claude CLI, Node.js, or rootfs base needs to be refreshed.
# This version string is also written into the tarball as
# `/.claudemobile-bundled-version` so the runtime can detect upgrades.
BUNDLED_VERSION="ubuntu24.04-node18-claude-$(date -u +%Y.%m.%d)"

NPM_VERSION="${NPM_VERSION:-10.8.2}"
CLAUDE_VERSION="${CLAUDE_VERSION:-latest}"
UBUNTU_MIRROR="${UBUNTU_MIRROR:-https://mirrors.aliyun.com/ubuntu-ports/}"

# Maps the Android ABI string to docker --platform.
abi_to_platform() {
    case "$1" in
        arm64-v8a)   echo "linux/arm64" ;;
        armeabi-v7a) echo "linux/arm/v7" ;;
        x86_64)      echo "linux/amd64" ;;
        x86)         echo "linux/386" ;;
        *) echo "unknown ABI: $1" >&2; exit 2 ;;
    esac
}

# Build one ABI's rootfs tarball.
build_one() {
    local abi="$1"
    local platform
    platform="$(abi_to_platform "$abi")"

    local build_dir
    mkdir -p "$OUT_DIR/.tmp"
    build_dir="$(mktemp -d "$OUT_DIR/.tmp/claudemobile-rootfs.XXXXXX")"

    echo
    echo "=========================================================="
    echo "Building rootfs for ABI=$abi  platform=$platform"
    echo "=========================================================="

    local container="claudemobile-rootfs-$abi-$$"
    local host_ca="$OUT_DIR/host-ca.pem"
    local docker_volume_args=()

    if [ -n "${HOST_CA_BUNDLE:-}" ] && [ -f "$HOST_CA_BUNDLE" ]; then
        cp "$HOST_CA_BUNDLE" "$host_ca"
    elif [ -f /etc/ssl/cert.pem ]; then
        cp /etc/ssl/cert.pem "$host_ca"
    elif [ -f /opt/homebrew/etc/ca-certificates/cert.pem ]; then
        cp /opt/homebrew/etc/ca-certificates/cert.pem "$host_ca"
    fi

    if [ -s "$host_ca" ]; then
        docker_volume_args+=(
            -v "$host_ca:/tmp/host-ca.pem:ro"
        )
    fi

    # Start the container but do not run the setup yet — we want to mount a
    # volume for extraction and then commit the installed state into a tar.
    docker run --rm -i \
        --platform "$platform" \
        --name "$container" \
        -v "$build_dir:/out" \
        "${docker_volume_args[@]}" \
        -e DEBIAN_FRONTEND=noninteractive \
        -e NPM_VERSION="$NPM_VERSION" \
        -e CLAUDE_VERSION="$CLAUDE_VERSION" \
        -e BUNDLED_VERSION="$BUNDLED_VERSION" \
        -e UBUNTU_MIRROR="$UBUNTU_MIRROR" \
        ubuntu:24.04 \
        bash -euxo pipefail <<'CONTAINER_SH'

# ---------------------------------------------------------------------------
# Inside the emulated container. Install the minimum required components,
# clean apt caches, and dump the rootfs as a tar stream to /out/rootfs.tar.
# ---------------------------------------------------------------------------

if [ -f /tmp/host-ca.pem ]; then
    mkdir -p /etc/ssl/certs
    cp /tmp/host-ca.pem /etc/ssl/certs/ca-certificates.crt
fi

if [ -n "${UBUNTU_MIRROR:-}" ] && [ -f /etc/apt/sources.list.d/ubuntu.sources ]; then
    sed -i "s|http://ports.ubuntu.com/ubuntu-ports/|${UBUNTU_MIRROR}|g" \
        /etc/apt/sources.list.d/ubuntu.sources
fi

apt-get update

# Matches the bootstrap requirements: nodejs + ca-certificates, then a
# standalone npm tarball from the registry (not the apt 'npm' meta-package,
# which pulls 400+ node-* deps and explodes dpkg time).
apt-get -y --no-install-recommends install \
    ca-certificates \
    nodejs \
    curl

# Refresh CA bundle so `node` can talk to the npm registry over HTTPS.
/usr/sbin/update-ca-certificates --fresh

# Install a standalone npm so we don't carry the apt meta-package's tail of
# node-* packages.
NPM_TARBALL=/tmp/npm.tgz
curl -fsSL "https://registry.npmjs.org/npm/-/npm-${NPM_VERSION}.tgz" -o "$NPM_TARBALL"
mkdir -p /usr/local/lib/node_modules/npm
tar -xzf "$NPM_TARBALL" -C /usr/local/lib/node_modules/npm --strip-components=1
ln -sf ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm
ln -sf ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
chmod +x /usr/local/bin/npm /usr/local/bin/npx
rm -f "$NPM_TARBALL"

npm --version
node --version

# ---------------------------------------------------------------------------
# Install Claude CLI globally.
#
# Two qemu-related quirks make a vanilla `npm install -g` unsafe:
#   1. The bundled npm in Ubuntu 24.04's nodejs has prefix=/usr; even after
#      we install our own npm, default prefix can fall back to /usr depending
#      on env. Force --prefix=/usr/local explicitly.
#   2. claude-code's native binary is shipped as an optionalDependency
#      (~60 MB). Under qemu emulation npm sometimes silently skips that
#      optional dep, leaving a placeholder stub at bin/claude.exe. Even
#      `npm install <pkg>` directly says "up to date" but does not actually
#      materialize the package contents on disk.
#
# To make this 100% deterministic we therefore:
#   1. `npm install -g` the wrapper at /usr/local (small, reliable).
#   2. `npm pack` the platform-specific tarball and untar it ourselves into
#      the wrapper's node_modules tree. This mirrors what npm would do, but
#      uses plain tar(1) so qemu cannot lose files mid-copy.
#   3. Run install.cjs to perform the wrapper's linkSync(NATIVE_BIN, claude.exe).
#   4. Force the placeholder to be the native binary as a belt-and-suspenders
#      step so the result is identical regardless of install.cjs behaviour.
#   5. Strict-validate that bin/claude.exe is the multi-MB native binary,
#      not the placeholder stub.
# ---------------------------------------------------------------------------
export NODE_EXTRA_CA_CERTS=/etc/ssl/certs/ca-certificates.crt

NPM_GLOBAL_PREFIX=/usr/local
PLATFORM_CPU="$(node -e 'process.stdout.write(process.arch)')"
PLATFORM_PKG="@anthropic-ai/claude-code-linux-${PLATFORM_CPU}"

# Step 1: install the wrapper.
npm install --prefix "$NPM_GLOBAL_PREFIX" -g \
    --include=optional --no-audit --no-fund \
    "@anthropic-ai/claude-code@${CLAUDE_VERSION}"

CLAUDE_DIR="$NPM_GLOBAL_PREFIX/lib/node_modules/@anthropic-ai/claude-code"
if [ ! -d "$CLAUDE_DIR" ]; then
    echo "FATAL: claude-code package not present at $CLAUDE_DIR after npm install" >&2
    ls -la "$NPM_GLOBAL_PREFIX/lib/node_modules" || true
    exit 1
fi

# Step 2: fetch the platform tarball with `npm pack` (downloads the .tgz to
# CWD without trying to install it) and extract it manually. This avoids the
# npm-under-qemu bug where it claims success but never copies file contents.
PACK_DIR="$(mktemp -d)"
trap 'rm -rf "$PACK_DIR"' EXIT
echo "[build-rootfs] downloading $PLATFORM_PKG@${CLAUDE_VERSION} via npm pack"
PLATFORM_TARBALL="$(cd "$PACK_DIR" && npm pack \
    --silent --no-audit --no-fund \
    "$PLATFORM_PKG@${CLAUDE_VERSION}")"
if [ -z "$PLATFORM_TARBALL" ] || [ ! -f "$PACK_DIR/$PLATFORM_TARBALL" ]; then
    echo "FATAL: npm pack did not produce a tarball for $PLATFORM_PKG" >&2
    ls -la "$PACK_DIR" >&2 || true
    exit 1
fi
NATIVE_PKG_DIR="$CLAUDE_DIR/node_modules/@anthropic-ai/claude-code-linux-${PLATFORM_CPU}"
mkdir -p "$NATIVE_PKG_DIR"
# npm tarballs always have a top-level `package/` directory; --strip-components=1
# unwraps it.
tar -xzf "$PACK_DIR/$PLATFORM_TARBALL" -C "$NATIVE_PKG_DIR" --strip-components=1
NATIVE_BIN="$NATIVE_PKG_DIR/claude"
if [ ! -f "$NATIVE_BIN" ]; then
    echo "FATAL: native binary still missing at $NATIVE_BIN after manual untar" >&2
    ls -la "$NATIVE_PKG_DIR" >&2 || true
    exit 1
fi
NATIVE_BIN_SIZE="$(stat -c %s "$NATIVE_BIN")"
if [ "$NATIVE_BIN_SIZE" -lt 1048576 ]; then
    echo "FATAL: native binary at $NATIVE_BIN is only $NATIVE_BIN_SIZE bytes; tarball appears truncated" >&2
    exit 1
fi
chmod +x "$NATIVE_BIN"
echo "[build-rootfs] native binary OK: ${NATIVE_BIN_SIZE} bytes"

# Step 3: run install.cjs so the wrapper's bookkeeping (if any) is performed.
# Failure here is non-fatal because step 4 will overwrite the placeholder
# regardless.
if [ -f "$CLAUDE_DIR/install.cjs" ]; then
    (cd "$CLAUDE_DIR" && node install.cjs) || true
fi

# Step 4: deterministic placeholder replacement.
PLACEHOLDER="$CLAUDE_DIR/bin/claude.exe"
mkdir -p "$CLAUDE_DIR/bin"
rm -f "$PLACEHOLDER"
ln "$NATIVE_BIN" "$PLACEHOLDER" 2>/dev/null \
    || cp "$NATIVE_BIN" "$PLACEHOLDER"
chmod +x "$PLACEHOLDER"

# Step 5: strict validation.
PLACEHOLDER_SIZE="$(stat -c %s "$PLACEHOLDER")"
if [ "$PLACEHOLDER_SIZE" -lt 1048576 ]; then
    echo "FATAL: bin/claude.exe is only $PLACEHOLDER_SIZE bytes; expected the native binary (>1 MiB)." >&2
    head -c 200 "$PLACEHOLDER" || true
    exit 1
fi
echo "[build-rootfs] bin/claude.exe = ${PLACEHOLDER_SIZE} bytes (OK)"

# Sanity check: the wrapper symlink lives at /usr/local/bin/claude.
if [ ! -e /usr/local/bin/claude ]; then
    echo "FATAL: /usr/local/bin/claude symlink missing" >&2
    exit 1
fi

# Final exec check — `claude --version` must run end-to-end.
claude --version

# Write DNS config — the runtime does this too, but having it in the image
# means the app's first proot exec works even if the post-extract hook is
# skipped.
printf 'nameserver 8.8.8.8\nnameserver 8.8.4.4\n' > /etc/resolv.conf

# Strip everything we don't need to reduce image size.
apt-get -y purge curl
apt-get -y autoremove
apt-get clean
rm -rf \
    /var/cache/apt/archives/* \
    /var/lib/apt/lists/* \
    /var/log/*.log /var/log/apt /var/log/dpkg.log \
    /usr/share/doc/* \
    /usr/share/man/* \
    /usr/share/locale/[a-d]* /usr/share/locale/[e-z]* \
    /root/.npm \
    /tmp/* /var/tmp/* \
    || true

# Leave a marker inside the rootfs so the runtime can detect bundled images.
printf 'version=%s\nsource=bundled\n' "$BUNDLED_VERSION" \
    > /.claudemobile-bundled-version

# Dump the rootfs as a tar stream — no xz yet, that's done outside. We
# exclude /proc, /sys, /dev which are bind-mounted at runtime.
#
# `--hard-dereference` materializes hard links as independent files inside
# the tarball. Termux/proot tar on Android private storage routinely fails
# to recreate hard links (the kernel layer above /data/data refuses
# linkat()), and our extractor swallows those errors as warnings — leaving
# any path expressed as a hard link MISSING after extraction. claude-code's
# install.cjs hard-links bin/claude.exe to the ~230 MB native binary, and
# losing that link manifests as a dangling /usr/bin/claude symlink on the
# device. Producing two independent files in the tarball costs a few MB of
# image size but makes extraction order-independent and immune to the
# Android hard-link restriction.
tar \
    --numeric-owner \
    --hard-dereference \
    --exclude=./proc \
    --exclude=./sys \
    --exclude=./dev \
    --exclude=./run \
    --exclude=./out \
    -cf /out/rootfs.tar -C / .

ls -lh /out/rootfs.tar
CONTAINER_SH

    # Compress outside docker where xz can use host CPU at full speed.
    local out_tar="$OUT_DIR/rootfs-$abi.tar.xz"
    mkdir -p "$OUT_DIR"
    echo "[build-rootfs] compressing -> $out_tar"
    xz -T0 -9e < "$build_dir/rootfs.tar" > "$out_tar.part"
    mv "$out_tar.part" "$out_tar"

    # Record metadata: sha256 + size for the manifest.
    local sha size uncompressed files
    if command -v sha256sum >/dev/null 2>&1; then
        sha="$(sha256sum "$out_tar" | awk '{print $1}')"
    else
        sha="$(shasum -a 256 "$out_tar" | awk '{print $1}')"
    fi
    size="$(stat -f %z "$out_tar" 2>/dev/null || stat -c %s "$out_tar")"
    # Uncompressed size + entry count drive the runtime extraction progress
    # bar. We list the tarball once after compression — slow for big images
    # but only happens at build time, never on device.
    uncompressed="$(xz --robot --list "$out_tar" 2>/dev/null \
        | awk '$1=="totals"{print $5; exit}')"
    files="$(tar -tJf "$out_tar" 2>/dev/null | wc -l | tr -d ' ')"
    echo "$abi"$'\t'"$BUNDLED_VERSION"$'\t'"$sha"$'\t'"$size"$'\t'"$uncompressed"$'\t'"$files" \
        >> "$OUT_DIR/manifest.tsv.tmp"

    echo "[build-rootfs] done $abi ($(du -h "$out_tar" | awk '{print $1}'))"
    rm -rf "$build_dir"
}

main() {
    if [ "$#" -lt 1 ]; then
        echo "usage: $0 <abi>|all" >&2
        echo "       valid abis: ${APK_ABIS[*]}" >&2
        exit 2
    fi
    mkdir -p "$OUT_DIR"
    : > "$OUT_DIR/manifest.tsv.tmp"

    if [ "$1" = "all" ]; then
        for abi in "${APK_ABIS[@]}"; do
            build_one "$abi"
        done
    else
        build_one "$1"
    fi

    # Atomic manifest swap so a half-written manifest never ships.
    mv "$OUT_DIR/manifest.tsv.tmp" "$OUT_DIR/manifest.tsv"
    cat "$OUT_DIR/manifest.tsv"
}

main "$@"
