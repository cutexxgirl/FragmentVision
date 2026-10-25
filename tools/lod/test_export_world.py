import gzip
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

import export_world as exporter


def text(value):
    data = value.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def tag(kind, name, payload):
    return bytes([kind]) + text(name) + payload


def compound(*tags):
    return b"".join(tags) + b"\0"


def list_tag(kind, items):
    return bytes([kind]) + struct.pack(">i", len(items)) + b"".join(items)


def packed(indices, bits):
    per_long = 64 // bits
    longs = []
    for offset in range(0, len(indices), per_long):
        longs.append(sum(value << (i * bits) for i, value in enumerate(indices[offset:offset + per_long])))
    return struct.pack(">i", len(longs)) + b"".join(struct.pack(">Q", value) for value in longs)


def chunk(version=3955, status="minecraft:full", chunk_x=0, chunk_z=0):
    names = ["minecraft:air", "minecraft:grass_block"] + sorted(exporter.GROUND - {"minecraft:grass_block"})[:15]
    palette = []
    for name in names:
        properties = tag(10, "Properties", compound(tag(8, "snowy", text("false")))) if name.endswith(":grass_block") else b""
        palette.append(compound(tag(8, "Name", text(name)), properties))
    indices = []
    for y in range(16):
        for z in range(16):
            for x in range(16):
                indices.append((16 if x == 15 else 1) if y == 0 else 0)
    states = compound(tag(9, "palette", list_tag(10, palette)), tag(12, "data", packed(indices, 5)))
    biomes = compound(tag(9, "palette", list_tag(8, [text("minecraft:plains"), text("minecraft:swamp")])),
                      tag(12, "data", packed([i % 2 for i in range(64)], 1)))
    section = compound(tag(1, "Y", b"\0"), tag(10, "block_states", states), tag(10, "biomes", biomes),
                       tag(7, "BlockLight", struct.pack(">i", 2048) + bytes([0x33]) * 2048))
    root = compound(tag(3, "DataVersion", struct.pack(">i", version)), tag(3, "xPos", struct.pack(">i", chunk_x)),
                    tag(3, "zPos", struct.pack(">i", chunk_z)), tag(8, "Status", text(status)),
                    tag(9, "sections", list_tag(10, [section])))
    return b"\x0a\0\0" + root


def region(path, data):
    compressed = zlib.compress(data)
    payload = struct.pack(">I", len(compressed) + 1) + b"\x02" + compressed
    sectors = (len(payload) + 4095) // 4096
    header = struct.pack(">I", (2 << 8) | sectors) + bytes(8192 - 4)
    (path / "r.0.0.mca").write_bytes(header + payload + bytes(sectors * 4096 - len(payload)))


def region_grid(path):
    header, body = bytearray(8192), bytearray()
    for z in range(3):
        for x in range(3):
            data = zlib.compress(chunk(chunk_x=x, chunk_z=z))
            payload = struct.pack(">I", len(data) + 1) + b"\x02" + data
            sectors = (len(payload) + 4095) // 4096
            struct.pack_into(">I", header, (z * 32 + x) * 4, ((2 + len(body) // 4096) << 8) | sectors)
            body.extend(payload + bytes(sectors * 4096 - len(payload)))
    (path / "r.0.0.mca").write_bytes(header + body)


class AnvilChecks(unittest.TestCase):
    def test_real_layout_snapshot(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            region(root, chunk())
            output = root / "snapshot.fvvol.gz"
            report = exporter.export(root, output, (0, 0, 0), (18, 3, 3), [(0, 0, 0, 8, 1, 3)])
            self.assertEqual(report["unknown_voxels"], 18)
            self.assertEqual(report["natural_voxels"], 24)
            raw = io.BytesIO(gzip.decompress(output.read_bytes()))
            self.assertEqual(raw.read(8), b"FVOL0001")
            ox, oy, oz, sx, sy, sz, count = struct.unpack(">7i", raw.read(28))
            palette = []
            for _ in range(count):
                pair = []
                for _ in range(2):
                    length = struct.unpack(">I", raw.read(4))[0]
                    pair.append(raw.read(length).decode("utf-8"))
                palette.append(pair)
            cells = sx * sy * sz
            ids = struct.unpack(">" + "i" * cells, raw.read(cells * 4))
            lights, mask = raw.read(cells), raw.read(cells)
            self.assertEqual(palette[ids[0]][0], "minecraft:grass_block[snowy=false]")
            self.assertEqual(palette[ids[4]][1], "minecraft:swamp")
            self.assertNotEqual(ids[15], ids[14])  # 5-bit palette values do not straddle long boundaries
            self.assertEqual(ids[16], -1)
            self.assertEqual(ids[18 * 3], 0)  # known saved air
            self.assertEqual(lights[0], 3)  # missing sky light is not fabricated as 15
            self.assertEqual(mask[7], 1)
            self.assertEqual(mask[8], 0)
            self.assertEqual(raw.read(), b"")
            # An immutable input must not be silently overwritten.
            with self.assertRaises(FileExistsError):
                exporter.export(root, output, (0, 0, 0), (18, 3, 3), [])

    def test_missing_negative_chunk_is_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            region(root, chunk())
            report = exporter.export(root, root / "negative.gz", (-2, 0, -2), (3, 3, 3), [])
            self.assertEqual(report["unknown_voxels"], 24)
            self.assertEqual(report["natural_voxels"], 0)

    def test_known_empty_section(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            region(root, chunk())
            report = exporter.export(root, root / "empty.gz", (0, 32, 0), (3, 3, 3), [])
            self.assertEqual(report["unknown_voxels"], 0)
            self.assertEqual(report["palette"], 1)

    def test_author_protection_overrides_natural_box(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            region(root, chunk())
            report = exporter.export(root, root / "protected.gz", (0, 0, 0), (3, 3, 3),
                                     [(0, 0, 0, 3, 1, 3)], [(1, 0, 0, 2, 1, 3)])
            self.assertEqual(report["natural_voxels"], 6)

    def test_bad_palette(self):
        with self.assertRaisesRegex(ValueError, "palette array"):
            exporter.unpack_palette({"palette": [1, 2], "data": []}, 4096, 4)
        with self.assertRaisesRegex(ValueError, "out of bounds"):
            exporter.unpack_palette({"palette": [1, 2], "data": [15] + [0] * 255}, 4096, 4)

    def test_wrong_world_version_and_partial_chunk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            region(root, chunk(version=3953))
            with self.assertRaisesRegex(ValueError, "DataVersion"):
                exporter.Anvil(root).chunk(0, 0)
            region(root, chunk(status="minecraft:noise"))
            with self.assertRaisesRegex(ValueError, "incomplete chunk"):
                exporter.Anvil(root).chunk(0, 0)

    def test_truncated_region(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "r.0.0.mca").write_bytes(struct.pack(">I", (3 << 8) | 1) + bytes(8192 - 4))
            with self.assertRaisesRegex(ValueError, "sector bounds"):
                exporter.Anvil(root).chunk(0, 0)

    def test_nbt_depth_limit(self):
        data = b"\x0a\0\0" + (b"\x0a\0\0" * 66) + bytes(67)
        with self.assertRaisesRegex(ValueError, "nesting limit"):
            exporter.nbt(io.BytesIO(data))

    def test_java_modified_utf8_in_non_geometry_data(self):
        value = "A\0🌿"
        utf16 = value.encode("utf-16-be")
        raw = b"".join(chr(int.from_bytes(utf16[i:i + 2], "big")).encode("utf-8", errors="surrogatepass")
                       for i in range(0, len(utf16), 2)).replace(b"\0", b"\xc0\x80")
        data = b"\x0a\0\0" + compound(tag(8, "label", struct.pack(">H", len(raw)) + raw))
        self.assertEqual(exporter.nbt(io.BytesIO(data))["label"], value)


if __name__ == "__main__":
    unittest.main()
