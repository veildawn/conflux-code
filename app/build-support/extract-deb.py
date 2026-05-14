#!/usr/bin/env python3
"""Extract a .deb archive's data.tar.xz into a directory.

Called by `prepareTermuxBootstrap` in build.gradle.kts to unpack .deb
packages fetched from the Termux apt repo. macOS's BSD `ar` emits entry
names with a trailing slash (e.g. `debian-binary/`) which confuses
`tar -xf` and `ar xo`, so we roll our own parser.
"""
from __future__ import annotations

import os
import sys


def extract(deb_path: str, out_dir: str) -> None:
    os.makedirs(out_dir, exist_ok=True)
    with open(deb_path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise RuntimeError(f"Not an ar archive: {deb_path}")
        while True:
            hdr = f.read(60)
            if len(hdr) < 60:
                break
            name = hdr[:16].decode("ascii").rstrip(" /")
            size = int(hdr[48:58].decode("ascii").strip())
            data = f.read(size)
            if size % 2:
                f.read(1)  # pad byte
            out_path = os.path.join(out_dir, name)
            with open(out_path, "wb") as o:
                o.write(data)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: extract-deb.py <deb-file> <out-dir>", file=sys.stderr)
        return 2
    extract(argv[1], argv[2])
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
