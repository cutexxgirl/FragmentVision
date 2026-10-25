#!/usr/bin/env python3
"""Read a *closed copy* of a 1.21.1 Anvil world into a bounded block-state proof snapshot.

No world generation, registry IDs, heightmap sampling or resource-pack assumptions.
Only explicitly authorised natural boxes permit smoothing. All other blocks stay exact.
"""
import argparse
from array import array
from collections import OrderedDict
import gzip
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import zlib

MAGIC = b"FVOL0001"
MAX_CHUNK_BYTES = 16 * 1024 * 1024
MAX_CELLS = 8 * 1024 * 1024
AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
# Full opaque cubes only. This is an eligibility filter, never a nature/building classifier.
GROUND = {"minecraft:" + name for name in (
    "stone granite diorite andesite deepslate tuff calcite dirt coarse_dirt "
    "grass_block podzol mycelium moss_block sand red_sand gravel sandstone "
    "red_sandstone snow_block terracotta"
).split()}


def read_exact(stream, count):
    data = stream.read(count)
    if len(data) != count:
        raise ValueError("truncated NBT/region data")
    return data


def nbt(stream):
    """Bounded modern NBT reader; lists/arrays cannot allocate more than the chunk limit."""
    def number(fmt):
        return struct.unpack(fmt, read_exact(stream, struct.calcsize(fmt)))[0]

    def length():
        n = number(">i")
        if n < 0 or n > MAX_CHUNK_BYTES:
            raise ValueError("invalid NBT collection length")
        return n

    def string():
        # Java DataInput.readUTF uses modified UTF-8. Signs/books may contain
        # supplementary characters even though block/biome identifiers are ASCII.
        raw = read_exact(stream, number(">H")).replace(b"\xc0\x80", b"\0")
        value = raw.decode("utf-8", errors="surrogatepass")
        return value.encode("utf-16-le", errors="surrogatepass").decode("utf-16-le", errors="surrogatepass")

    def payload(kind, depth=0):
        if depth > 64:
            raise ValueError("NBT nesting limit")
        if kind in (1, 2, 3, 4, 5, 6):
            return number({1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}[kind])
        if kind == 7:
            return read_exact(stream, length())
        if kind == 8:
            return string()
        if kind == 9:
            element, count = number(">B"), length()
            if count > MAX_CHUNK_BYTES // 8 or (element == 0 and count):
                raise ValueError("invalid NBT list")
            return [payload(element, depth + 1) for _ in range(count)]
        if kind == 10:
            value = {}
            while (element := number(">B")) != 0:
                name = string()
                if name in value:
                    raise ValueError("duplicate NBT compound key")
                value[name] = payload(element, depth + 1)
            return value
        if kind in (11, 12):
            count, width = length(), 4 if kind == 11 else 8
            if count > MAX_CHUNK_BYTES // width:
                raise ValueError("NBT array limit")
            raw = read_exact(stream, count * width)
            return struct.unpack(">" + ("i" if kind == 11 else "Q") * count, raw)
        raise ValueError(f"unsupported NBT tag {kind}")

    if number(">B") != 10:
        raise ValueError("chunk NBT root must be a compound")
    string()
    result = payload(10)
    if stream.read(1):
        raise ValueError("trailing chunk NBT bytes")
    return result


def unpack_palette(container, count, minimum_bits):
    palette = container.get("palette", [])
    if not palette or len(palette) > count:
        raise ValueError("invalid section palette")
    if len(palette) == 1:
        return palette, [0] * count
    bits = max(minimum_bits, (len(palette) - 1).bit_length())
    per_long, mask = 64 // bits, (1 << bits) - 1
    data = container.get("data", ())
    if len(data) != (count + per_long - 1) // per_long:
        raise ValueError("invalid padded palette array")
    indices = [(data[i // per_long] >> (i % per_long * bits)) & mask for i in range(count)]
    if max(indices) >= len(palette):
        raise ValueError("section palette index out of bounds")
    return palette, indices


class Anvil:
    def __init__(self, region):
        self.region = Path(region)
        self.cache = OrderedDict()
        self.read_chunks = 0

    def chunk(self, x, z):
        key = (x, z)
        if key in self.cache:
            self.cache.move_to_end(key)
            return self.cache[key]
        path = self.region / f"r.{x // 32}.{z // 32}.mca"
        result = None
        if path.exists():
            with path.open("rb") as source:
                source.seek(((x % 32) + (z % 32) * 32) * 4)
                location = int.from_bytes(read_exact(source, 4), "big")
                offset, sectors = location >> 8, location & 255
                if offset:
                    if offset < 2 or not sectors or (offset + sectors) * 4096 > path.stat().st_size:
                        raise ValueError(f"invalid sector bounds: {path}, chunk {key}")
                    source.seek(offset * 4096)
                    length = int.from_bytes(read_exact(source, 4), "big")
                    if length < 1 or length > sectors * 4096 - 4:
                        raise ValueError("invalid region chunk length")
                    compression = read_exact(source, 1)[0]
                    if compression & 128:
                        raise ValueError("external .mcc chunks are not supported by this proof exporter")
                    data = read_exact(source, length - 1)
                    if compression in (1, 2):
                        decoder = zlib.decompressobj(31 if compression == 1 else 15)
                        data = decoder.decompress(data, MAX_CHUNK_BYTES + 1)
                        if len(data) > MAX_CHUNK_BYTES or not decoder.eof or decoder.unused_data:
                            raise ValueError("oversized/incomplete compressed chunk")
                    elif compression != 3:
                        raise ValueError(f"unsupported Anvil compression {compression}")
                    if len(data) > MAX_CHUNK_BYTES:
                        raise ValueError("chunk size limit")
                    root = nbt(io.BytesIO(data))
                    if root.get("DataVersion") != 3955:
                        raise ValueError("proof exporter requires Minecraft 1.21.1 DataVersion 3955")
                    if root.get("xPos") != x or root.get("zPos") != z:
                        raise ValueError("chunk position mismatch")
                    if root.get("Status") not in ("full", "minecraft:full"):
                        raise ValueError("incomplete chunk; use a saved, fully generated world copy")
                    if not isinstance(root.get("sections"), list):
                        raise ValueError("missing/invalid saved section list")
                    result = {}
                    for section in root["sections"]:
                        y = section["Y"]
                        if y in result:
                            raise ValueError("duplicate section Y")
                        if "block_states" not in section:
                            result[y] = None  # lighting-only data is not evidence of saved air
                            continue
                        states, blocks = unpack_palette(section["block_states"], 4096, 4)
                        biomes, biome_ids = unpack_palette(section["biomes"], 64, 1)
                        sky, block = section.get("SkyLight"), section.get("BlockLight")
                        if any(v is not None and len(v) != 2048 for v in (sky, block)):
                            raise ValueError("invalid light nibble array")
                        result[y] = (states, blocks, biomes, biome_ids, sky, block)
                    self.read_chunks += 1
        self.cache[key] = result
        if len(self.cache) > 8:
            self.cache.popitem(last=False)
        return result


def state_key(state):
    name = state["Name"]
    properties = state.get("Properties", {})
    return name + ("[" + ",".join(f"{k}={properties[k]}" for k in sorted(properties)) + "]" if properties else "")


def export(region, output, origin, size, boxes, protected_boxes=()):
    sx, sy, sz = size
    if min(size) < 1 or sx > 144 or sz > 144 or sy > 512 or sx * sy * sz > MAX_CELLS:
        raise ValueError("snapshot limit: 144 x 512 x 144, at most 8M voxels")
    if any(o < -(1 << 31) or o + n > (1 << 31) - 1 for o, n in zip(origin, size)):
        raise ValueError("snapshot origin/extent must fit signed 32-bit world coordinates")
    source = Anvil(region)
    ids = array("i", [-1]) * (sx * sy * sz)
    lights, natural = bytearray(len(ids)), bytearray(len(ids))
    palette, palette_ids = [("minecraft:air", "minecraft:plains")], {}
    ox, oy, oz = origin
    missing = 0
    for z in range(sz):
        wz = oz + z
        for x in range(sx):
            wx = ox + x
            chunk = source.chunk(wx // 16, wz // 16)
            for y in range(sy):
                wy, index = oy + y, (y * sz + z) * sx + x
                if chunk is None:
                    missing += 1
                    continue
                section = chunk.get(wy // 16)
                if section is None:
                    if wy // 16 in chunk:
                        missing += 1  # light-only section: no authoritative block payload
                        continue
                    # A missing section in a FULL chunk is air; light remains unknown/conservative zero.
                    ids[index] = 0
                    continue
                states, blocks, biomes, biome_ids, sky, block = section
                p = ((wy % 16) * 16 + wz % 16) * 16 + wx % 16
                state = states[blocks[p]]
                biome = biomes[biome_ids[((wy % 16 // 4) * 4 + wz % 16 // 4) * 4 + wx % 16 // 4]]
                if state["Name"] in AIR:
                    ids[index] = 0
                else:
                    key = state_key(state), biome
                    if key not in palette_ids:
                        if len(palette) >= 65536:
                            raise ValueError("snapshot palette limit")
                        palette_ids[key] = len(palette)
                        palette.append(key)
                    ids[index] = palette_ids[key]
                    natural[index] = state["Name"] in GROUND and any(
                        a <= wx < d and b <= wy < e and c <= wz < f for a, b, c, d, e, f in boxes
                    ) and not any(a <= wx < d and b <= wy < e and c <= wz < f
                                  for a, b, c, d, e, f in protected_boxes)
                # The snapshot records the saved light, never substitutes a bright outdoor value.
                lights[index] = ((sky[p // 2] >> (p % 2 * 4)) & 15 if sky else 0) << 4
                lights[index] |= ((block[p // 2] >> (p % 2 * 4)) & 15 if block else 0)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    # Refuse overwriting a previous snapshot; these are immutable inputs to the proof.
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=output.parent, prefix=".fvvol-", delete=False) as destination:
            temporary = Path(destination.name)
            with gzip.GzipFile(fileobj=destination, mode="wb", mtime=0, filename="") as out:
                out.write(MAGIC + struct.pack(">6iI", *origin, *size, len(palette)))
                for state, biome in palette:
                    for value in (state, biome):
                        encoded = value.encode("utf-8")
                        out.write(struct.pack(">I", len(encoded)) + encoded)
                if sys.byteorder == "little":
                    ids.byteswap()
                out.write(ids.tobytes())
                out.write(lights)
                out.write(natural)
        # Atomic publication without replacing an existing input, on the same filesystem.
        os.link(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    return {"snapshot": str(output), "origin": origin, "size": size,
            "palette": len(palette), "unknown_voxels": missing,
            "natural_voxels": sum(natural), "saved_chunks_read": source.read_chunks,
            "source": "saved Anvil blocks; no regeneration; protected cube preview is diagnostic"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("region", type=Path, help="region directory of a CLOSED world copy")
    parser.add_argument("output", type=Path)
    parser.add_argument("--origin", nargs=3, type=int, required=True, metavar=("X", "Y", "Z"))
    parser.add_argument("--size", nargs=3, type=int, required=True, metavar=("X", "Y", "Z"))
    parser.add_argument("--natural-box", nargs=6, type=int, action="append", default=[],
                        help="author-approved natural volume: minXYZ maxXYZ (exclusive); repeatable")
    parser.add_argument("--protect-box", nargs=6, type=int, action="append", default=[],
                        help="protected volume, takes priority over natural boxes; repeatable")
    args = parser.parse_args()
    if any(any(box[i] >= box[i + 3] for i in range(3)) for box in args.natural_box + args.protect_box):
        parser.error("boxes must have positive extents")
    print(json.dumps(export(args.region, args.output, args.origin, args.size, args.natural_box, args.protect_box), indent=2))


if __name__ == "__main__":
    main()
