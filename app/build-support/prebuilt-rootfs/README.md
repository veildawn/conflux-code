# Prebuilt rootfs tarballs

Place ABI-specific Ubuntu rootfs images here (`rootfs-<abi>.tar.xz`). Build
them with:

```sh
app/build-support/build-rootfs.sh all          # every ABI
app/build-support/build-rootfs.sh arm64-v8a    # single ABI
```

The script requires `docker` with multi-arch (binfmt_misc) support; Docker
Desktop on macOS handles this automatically. Expect ~15 minutes per ABI
under qemu emulation and ~100-140 MB per compressed tarball.

Files produced:

| Path                            | Purpose                                    |
|---------------------------------|--------------------------------------------|
| `rootfs-<abi>.tar.xz`           | Ubuntu 24.04 + Node.js 18 + Claude CLI.    |
| `manifest.tsv`                  | `<abi>\t<version>\t<sha256>\t<bytes>\t<uncompressed_bytes>\t<file_count>` |

These artifacts are `.gitignore`d because each one is 100+ MB. Regenerate
before every release, or host them in object storage if you prefer not to
ship them in-tree.

The `bundlePrebuiltRootfs` Gradle task copies every `rootfs-<abi>.tar.xz`
present here into `assets/rootfs/` at APK build time. The `ndk.abiFilters`
list in `app/build.gradle.kts` should cover the ABIs you build tarballs for
— otherwise the APK's rootfs will ship without a matching native-binary
bridge (bash, proot, etc.) and the runtime extraction will be skipped.

If a tarball is missing for a given ABI, the runtime fails fast at the
`INSTALL_ROOTFS` bootstrap step with a clear error message — there is no
online fallback. Generate a tarball for every ABI you ship before building
the APK.
