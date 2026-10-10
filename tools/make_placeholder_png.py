"""Writes a solid-colour placeholder PNG (no third-party libraries).

Usage: python tools/make_placeholder_png.py <out.png> <size>
"""
import struct
import sys
import zlib


def png(path, size, rgb=(110, 20, 20)):
    row = b"\x00" + bytes(rgb) * size
    raw = row * size

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", header))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


if __name__ == "__main__":
    png(sys.argv[1], int(sys.argv[2]))
