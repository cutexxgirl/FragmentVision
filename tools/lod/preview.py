#!/usr/bin/env python3
"""Diagnostic CPU geometry comparison (NumPy/Pillow). This is not a Minecraft screenshot."""
import argparse
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def read_obj(path):
    vertices, normals, faces, materials = [], [], [], []
    material = 1
    for line in Path(path).read_text().splitlines():
        fields = line.split()
        if not fields:
            continue
        if fields[0] == "v":
            vertices.append([float(x) for x in fields[1:4]])
        elif fields[0] == "vn":
            normals.append([float(x) for x in fields[1:4]])
        elif fields[0] == "g":
            material = int(fields[1].split("_")[1])
        elif fields[0] == "f":
            faces.append([int(x.split("/")[0]) - 1 for x in fields[1:4]])
            materials.append(material)
    return np.asarray(vertices), np.asarray(normals), np.asarray(faces), np.asarray(materials)


def raster(mesh, extent, width=460, height=325, wire=False):
    vertices, normals, triangles, materials = mesh
    # Fixed camera and framing for each row, shared by every compared level.
    right = np.array([.79, 0, -.61])
    up = np.array([.27, .90, .35]); up /= np.linalg.norm(up)
    forward = np.cross(right, up)
    projected = np.stack((vertices @ right, vertices @ up, vertices @ forward), axis=1)
    lo, hi = extent
    scale = min((width - 50) / (hi[0] - lo[0]), (height - 40) / (hi[1] - lo[1]))
    projected[:, 0] = (projected[:, 0] - (lo[0] + hi[0]) / 2) * scale + width / 2
    projected[:, 1] = height / 2 - (projected[:, 1] - (lo[1] + hi[1]) / 2) * scale
    pixels = np.full((height, width, 3), (228, 234, 239), dtype=np.uint8)
    depth = np.full((height, width), -np.inf)
    sun = np.array([-.4, .82, .4]); sun /= np.linalg.norm(sun)
    for ids, material in zip(triangles, materials):
        p = projected[ids]
        x0, y0 = np.maximum(np.floor(p[:, :2].min(axis=0)).astype(int), 0)
        x1, y1 = np.minimum(np.ceil(p[:, :2].max(axis=0)).astype(int), (width - 1, height - 1))
        if x0 > x1 or y0 > y1:
            continue
        x, y = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        denominator = (p[1, 1] - p[2, 1]) * (p[0, 0] - p[2, 0]) + (p[2, 0] - p[1, 0]) * (p[0, 1] - p[2, 1])
        if abs(denominator) < 1e-8:
            continue
        a = ((p[1, 1] - p[2, 1]) * (x - p[2, 0]) + (p[2, 0] - p[1, 0]) * (y - p[2, 1])) / denominator
        b = ((p[2, 1] - p[0, 1]) * (x - p[2, 0]) + (p[0, 0] - p[2, 0]) * (y - p[2, 1])) / denominator
        c = 1 - a - b
        z = a * p[0, 2] + b * p[1, 2] + c * p[2, 2]
        visible = (a >= 0) & (b >= 0) & (c >= 0) & (z > depth[y0:y1 + 1, x0:x1 + 1])
        # Face shading for every column: geometric changes, not interpolated normal smoothing.
        ns = np.cross(vertices[ids[1]] - vertices[ids[0]], vertices[ids[2]] - vertices[ids[0]])
        ns /= max(np.linalg.norm(ns), 1e-8)
        shade = .32 + .68 * max(0, ns @ sun)
        color = np.array([133, 154, 125] if material == 1 else [239, 243, 247])
        rgb = np.broadcast_to(np.clip(color * shade, 0, 255).astype(np.uint8), (*a.shape, 3)).copy()
        if wire:
            edge = np.minimum(np.minimum(a, b), c) < .025
            rgb[edge] = (rgb[edge] * .45).astype(np.uint8)
        pixels[y0:y1 + 1, x0:x1 + 1][visible] = rgb[visible]
        depth[y0:y1 + 1, x0:x1 + 1][visible] = z[visible]
    return Image.fromarray(pixels)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("hills", type=Path)
    parser.add_argument("arch", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    image = Image.new("RGB", (1440, 850), "#f7f9fa")
    draw = ImageDraw.Draw(image)
    try:
        title = ImageFont.truetype("DejaVuSans.ttf", 24)
        label = ImageFont.truetype("DejaVuSans.ttf", 17)
    except OSError:
        title = label = ImageFont.load_default()
    draw.text((24, 12), "Offline LOD comparison - synthetic fixtures", fill="#26363b", font=title)
    for row, directory in enumerate((args.hills, args.arch)):
        meshes = [read_obj(directory / ("tile-0-0-" + suffix + ".obj")) for suffix in ("reference", "candidate-lod2", "lod2")]
        right = np.array([.79, 0, -.61]); up = np.array([.27, .90, .35]); up /= np.linalg.norm(up)
        vertices = meshes[0][0]
        points = np.stack((vertices @ right, vertices @ up), axis=1)
        extent = (points.min(axis=0), points.max(axis=0))
        for column, (name, mesh) in enumerate(zip(("Exact merged source", "Direct candidate L2 + wire", "Selected level 2"), meshes)):
            x, y = 20 + column * 475, 60 + row * 375
            draw.text((x, y), f"{name} | {len(mesh[2]):,} triangles", fill="#26363b", font=label)
            image.paste(raster(mesh, extent, wire=column == 1), (x, y + 28))
    draw.text((24, 817), "No smoothing pass. Cost/error rejection keeps exact faces. Same face shading in all columns. No game GPU timing.", fill="#526167", font=label)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    image.save(args.output)


if __name__ == "__main__":
    main()
