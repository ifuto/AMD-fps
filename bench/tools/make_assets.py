#!/usr/bin/env python3
"""Builds the real asset files the CPU render benchmark draws with.

This is not a mock. It writes actual PNG textures and actual Minecraft-format model JSON into
bench/assets/, and the benchmark reads those files at run time and rasterises them. If a file is
missing the benchmark fails rather than falling back to something procedural.

PIL is not available in this environment, so the PNG encoder is written out here: signature, IHDR,
one IDAT of filter-0 scanlines through zlib, IEND, each chunk CRC32 checked. The decoder in
Java is javax.imageio.ImageIO, which is the other half of the same format.

Deterministic: every texture is drawn from a fixed-seed generator, so the benchmark measures the
same pixels on every run and on every CI machine.
"""

import json
import os
import struct
import zlib
import random

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "assets")


# --------------------------------------------------------------------------- png

def write_png(path, w, h, rgba):
    """rgba is a list of h rows of w (r, g, b, a) tuples."""
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter type 0 (None) for every scanline
        row = rgba[y]
        for x in range(w):
            r, g, b, a = row[x]
            raw += bytes((r & 255, g & 255, b & 255, a & 255))

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    blob = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
    blob += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    blob += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(blob)


def blank(w, h, colour=(0, 0, 0, 0)):
    return [[colour for _ in range(w)] for _ in range(h)]


def clamp(v):
    return max(0, min(255, int(v)))


# --------------------------------------------------------------------------- textures

def noise_texture(rng, w, h, base, spread, alpha=255, blotch=0):
    """Minecraft's block textures are per-pixel noise around a base colour, not gradients."""
    img = blank(w, h)
    for y in range(h):
        for x in range(w):
            n = rng.uniform(-spread, spread)
            img[y][x] = (clamp(base[0] + n), clamp(base[1] + n), clamp(base[2] + n), alpha)
    # A few darker blotches: this is what makes stone and dirt read as stone and dirt rather than
    # as television static, and it is why texture fetches cannot be collapsed to a constant.
    for _ in range(blotch):
        cx, cy = rng.randrange(w), rng.randrange(h)
        r = rng.randrange(1, 3)
        d = rng.uniform(-spread * 1.6, spread * 0.4)
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                if 0 <= x < w and 0 <= y < h and (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    c = img[y][x]
                    img[y][x] = (clamp(c[0] + d), clamp(c[1] + d), clamp(c[2] + d), c[3])
    return img


def grass_top(rng):
    img = noise_texture(rng, 16, 16, (106, 170, 64), 22, blotch=8)
    for y in range(16):
        for x in range(16):
            if rng.random() < 0.12:
                img[y][x] = (clamp(84 + rng.uniform(-8, 8)), clamp(140 + rng.uniform(-8, 8)),
                             clamp(50 + rng.uniform(-8, 8)), 255)
    return img


def grass_side(rng):
    img = noise_texture(rng, 16, 16, (134, 96, 67), 18, blotch=6)
    # The green lip overhangs by a random amount per column, which is the whole look of the block.
    for x in range(16):
        lip = 3 + rng.randrange(0, 3)
        for y in range(lip):
            img[y][x] = (clamp(106 + rng.uniform(-18, 18)), clamp(170 + rng.uniform(-18, 18)),
                         clamp(64 + rng.uniform(-18, 18)), 255)
    return img


def dirt(rng):
    return noise_texture(rng, 16, 16, (134, 96, 67), 20, blotch=7)


def stone(rng):
    return noise_texture(rng, 16, 16, (125, 125, 125), 14, blotch=10)


def cobblestone(rng):
    img = noise_texture(rng, 16, 16, (122, 122, 122), 10, blotch=4)
    # Mortar lines: a grid of irregular cells, each cell a slightly different grey.
    for y in range(16):
        for x in range(16):
            if x % 8 == 0 or y % 8 == 0 or (x + 4) % 8 == 0 and y % 8 < 4:
                img[y][x] = (90, 90, 90, 255)
    for cy in (0, 8):
        for cx in (0, 8):
            shade = rng.uniform(-16, 16)
            for y in range(cy, min(16, cy + 8)):
                for x in range(cx, min(16, cx + 8)):
                    if img[y][x] != (90, 90, 90, 255):
                        c = img[y][x]
                        img[y][x] = (clamp(c[0] + shade), clamp(c[1] + shade), clamp(c[2] + shade), 255)
    return img


def oak_planks(rng):
    img = noise_texture(rng, 16, 16, (162, 130, 78), 10, blotch=3)
    for y in range(16):
        if y % 4 == 3:
            for x in range(16):
                img[y][x] = (110, 84, 48, 255)
    for y in range(16):
        for x in range(16):
            if (x + (y // 4) * 5) % 16 == 0 and y % 4 != 3:
                img[y][x] = (110, 84, 48, 255)
    return img


def oak_log(rng):
    img = noise_texture(rng, 16, 16, (102, 81, 50), 12, blotch=2)
    for y in range(16):
        for x in range(16):
            if x % 4 == 0:
                img[y][x] = (78, 60, 36, 255)
    return img


def oak_leaves(rng):
    """Cutout texture: alpha is binary, which is why leaves need a cutout pass at all."""
    img = noise_texture(rng, 16, 16, (60, 130, 40), 26, alpha=255, blotch=6)
    for y in range(16):
        for x in range(16):
            if rng.random() < 0.16:
                img[y][x] = (0, 0, 0, 0)
    return img


def sand(rng):
    return noise_texture(rng, 16, 16, (219, 207, 163), 12, blotch=5)


def glass(rng):
    img = blank(16, 16, (200, 230, 240, 40))
    for y in range(16):
        for x in range(16):
            if x < 1 or y < 1 or x > 14 or y > 14:
                img[y][x] = (210, 235, 245, 255)
    for i in range(1, 15):
        img[i][15 - i] = (235, 248, 255, 120)
    return img


def tnt_side(rng):
    img = noise_texture(rng, 16, 16, (188, 60, 48), 12, blotch=3)
    for y in range(4, 12):
        for x in range(16):
            img[y][x] = (222, 222, 214, 255)
    # The letters are four blocks of dark pixels; a real texture would have glyphs, and the
    # rasteriser cost is identical because what matters is that the texels differ.
    for bx in (1, 5, 9, 13):
        for y in range(6, 10):
            for x in range(bx, min(16, bx + 3)):
                img[y][x] = (40, 40, 40, 255)
    return img


def tnt_top(rng):
    img = noise_texture(rng, 16, 16, (188, 60, 48), 12, blotch=3)
    for y in range(6, 10):
        for x in range(6, 10):
            img[y][x] = (60, 52, 44, 255)
    return img


def liquid_frames(rng, base, spread, n=4):
    """Animated liquid: each frame is a different wobble, so the texture really changes."""
    frames = []
    for f in range(n):
        img = blank(16, 16)
        for y in range(16):
            for x in range(16):
                w = 6 * (1 + ((x + y + f * 3) % 5) - 2)
                img[y][x] = (clamp(base[0] + w + rng.uniform(-spread, spread)),
                             clamp(base[1] + w + rng.uniform(-spread, spread)),
                             clamp(base[2] + w + rng.uniform(-spread, spread)), 210)
        frames.append(img)
    return frames


def chest(rng):
    """64x64 box unwrap, the layout vanilla chest models address."""
    img = noise_texture(rng, 64, 64, (156, 120, 74), 12, blotch=10)
    for y in range(64):
        for x in range(64):
            if x % 16 == 0 or y % 16 == 0:
                img[y][x] = (96, 70, 40, 255)
    for y in range(28, 36):
        for x in range(28, 36):
            img[y][x] = (200, 190, 90, 255)
    return img


def creeper(rng):
    """64x32 vanilla mob unwrap: green mottle, black face on the head panel."""
    img = noise_texture(rng, 64, 32, (86, 168, 62), 22, blotch=14)
    for y in range(8, 16):
        for x in range(8, 16):
            img[y][x] = (18, 24, 12, 255)
    for y in range(10, 13):
        for x in (9, 10, 13, 14):
            img[y][x] = (8, 10, 6, 255)
    for y in range(12, 16):
        for x in range(10, 14):
            img[y][x] = (8, 10, 6, 255)
    return img


def zombie(rng):
    """64x64 player-shaped unwrap so the skinned model has real UVs to fetch."""
    img = noise_texture(rng, 64, 64, (60, 132, 96), 18, blotch=16)
    for y in range(8, 16):
        for x in range(8, 16):
            img[y][x] = (52, 116, 84, 255)
    for y in range(11, 13):
        for x in (9, 10, 13, 14):
            img[y][x] = (16, 22, 18, 255)
    for y in range(20, 32):
        for x in range(16, 40):
            img[y][x] = (58, 118, 168, 255)
    for y in range(32, 52):
        for x in range(0, 16):
            img[y][x] = (58, 68, 148, 255)
    return img


def particle_atlas(rng):
    """128x128, sixteen 8x8 cells. Particles sample cells, so an atlas is what a real engine uses."""
    img = blank(128, 128)
    for cell in range(16):
        cx, cy = (cell % 4) * 32, (cell // 4) * 32
        r, g, b = (200 + rng.randrange(-40, 40), 120 + rng.randrange(-40, 60), 40 + rng.randrange(-20, 60))
        for y in range(32):
            for x in range(32):
                d = ((x - 16) ** 2 + (y - 16) ** 2) ** 0.5
                if d <= 14:
                    a = clamp(255 * (1 - d / 15))
                    img[cy + y][cx + x] = (clamp(r + rng.uniform(-30, 30)),
                                           clamp(g + rng.uniform(-30, 30)),
                                           clamp(b + rng.uniform(-30, 30)), a)
    return img


def gui_atlas(rng):
    """256x256: hotbar frame, hearts, armour pips and a glyph block, all at distinct atlas coords.

    The HUD in Minecraft is hundreds of small textured quads. What this atlas makes possible is
    measuring the batching of those quads rather than pretending they are free.
    """
    img = blank(256, 256)
    for y in range(256):
        for x in range(256):
            img[y][x] = (18, 18, 18, 0)
    # Hotbar: nine slots of 20x22.
    for i in range(9):
        for y in range(22):
            for x in range(20):
                edge = x in (0, 19) or y in (0, 21)
                img[y][i * 20 + x] = (200, 200, 200, 255) if edge else (0, 0, 0, 180)
    # Hearts: 9x9 each, red with a dark outline.
    for h in range(10):
        ox = h * 9
        for y in range(9):
            for x in range(9):
                d = ((x - 4) ** 2 + (y - 4) ** 2) ** 0.5
                if d <= 4:
                    img[24 + y][ox + x] = (210, 30, 30, 255) if d > 3 else (150, 20, 20, 255)
    # Armour pips.
    for h in range(10):
        ox = h * 9
        for y in range(9):
            for x in range(9):
                if abs(x - 4) + abs(y - 4) <= 4:
                    img[36 + y][ox + x] = (190, 190, 200, 255)
    # Glyph block: 16x16 cells of 8x8 glyphs, 256 of them. These are the chat and debug text.
    for g in range(256):
        gx = (g % 16) * 8
        gy = 48 + (g // 16) * 8
        for y in range(8):
            for x in range(8):
                if (g * 7 + x * 3 + y * 5) % 5 == 0:
                    img[gy + y][gx + x] = (235, 235, 235, 255)
    # Crosshair.
    for i in range(3, 13):
        img[200 + 8][200 + i] = (255, 255, 255, 255)
        img[200 + i][200 + 8] = (255, 255, 255, 255)
    return img


# --------------------------------------------------------------------------- models

def cube_all(texture):
    faces = {}
    for f in ("north", "south", "east", "west", "up", "down"):
        faces[f] = {"uv": [0, 0, 16, 16], "texture": "#" + texture}
    return {"parent": "builtin/none", "textures": {texture: texture},
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": faces}]}


def grass_block():
    return {
        "parent": "builtin/none",
        "textures": {"top": "grass_block_top", "bottom": "dirt", "side": "grass_block_side"},
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
            "north": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "south": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "east": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "west": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "up": {"uv": [0, 0, 16, 16], "texture": "#top"},
            "down": {"uv": [0, 0, 16, 16], "texture": "#bottom"}}}],
    }


def tnt():
    return {
        "parent": "builtin/none",
        "textures": {"side": "tnt_side", "top": "tnt_top", "bottom": "tnt_top"},
        "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {
            "north": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "south": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "east": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "west": {"uv": [0, 0, 16, 16], "texture": "#side"},
            "up": {"uv": [0, 0, 16, 16], "texture": "#top"},
            "down": {"uv": [0, 0, 16, 16], "texture": "#bottom"}}}],
    }


def chest_model():
    """A box with a lid: the lid is the animated part, the box is the part that can be baked."""
    return {
        "parent": "builtin/none",
        "textures": {"all": "chest"},
        "elements": [
            {"name": "box", "animated": False, "from": [1, 0, 1], "to": [15, 10, 15],
             "uv": [0, 0], "faces": {f: {"uv": [0, 0, 14, 10], "texture": "#all"}
                                    for f in ("north", "south", "east", "west", "up", "down")}},
            {"name": "lid", "animated": True, "from": [1, 10, 1], "to": [15, 14, 15],
             "uv": [0, 16], "faces": {f: {"uv": [0, 16, 14, 4], "texture": "#all"}
                                     for f in ("north", "south", "east", "west", "up", "down")}},
        ],
    }


def creeper_model():
    """Vanilla creeper geometry, in the same cube-list form vanilla model files use."""
    def cube(name, origin, size, uv, animated):
        return {"name": name, "origin": origin, "size": size, "uv": uv, "animated": animated}
    return {
        "texture": "creeper", "textureWidth": 64, "textureHeight": 32, "scale": 1.0,
        "pivot": [0, 0, 0],
        "cubes": [
            cube("head", [0, 18, -4], [8, 8, 8], [0, 0], True),
            cube("body", [-4, 6, -2], [8, 12, 4], [16, 16], False),
            cube("leg1", [-4, 0, -6], [4, 6, 4], [0, 16], True),
            cube("leg2", [0, 0, -6], [4, 6, 4], [0, 22], True),
            cube("leg3", [-4, 0, 2], [4, 6, 4], [0, 16], True),
            cube("leg4", [0, 0, 2], [4, 6, 4], [0, 22], True),
        ],
    }


def zombie_model():
    def cube(name, origin, size, uv, animated):
        return {"name": name, "origin": origin, "size": size, "uv": uv, "animated": animated}
    return {
        "texture": "zombie", "textureWidth": 64, "textureHeight": 64, "scale": 1.0,
        "pivot": [0, 0, 0],
        "cubes": [
            cube("head", [-4, 24, -4], [8, 8, 8], [0, 0], True),
            cube("body", [-4, 12, -2], [8, 12, 4], [16, 16], False),
            cube("arm1", [-8, 12, -2], [4, 12, 4], [40, 16], True),
            cube("arm2", [4, 12, -2], [4, 12, 4], [40, 16], True),
            cube("leg1", [-4, 0, -2], [4, 12, 4], [0, 16], True),
            cube("leg2", [0, 0, -2], [4, 12, 4], [0, 16], True),
        ],
    }


# --------------------------------------------------------------------------- main

def main():
    rng = random.Random(20261023)
    tex = {
        "grass_block_top": grass_top(rng),
        "grass_block_side": grass_side(rng),
        "dirt": dirt(rng),
        "stone": stone(rng),
        "cobblestone": cobblestone(rng),
        "oak_planks": oak_planks(rng),
        "oak_log": oak_log(rng),
        "oak_leaves": oak_leaves(rng),
        "sand": sand(rng),
        "glass": glass(rng),
        "tnt_side": tnt_side(rng),
        "tnt_top": tnt_top(rng),
        "chest": chest(rng),
        "creeper": creeper(rng),
        "zombie": zombie(rng),
        "particles": particle_atlas(rng),
        "gui": gui_atlas(rng),
    }
    for name, img in tex.items():
        write_png(os.path.join(OUT, "textures", name + ".png"), len(img[0]), len(img), img)

    for i, frame in enumerate(liquid_frames(rng, (52, 92, 190), 10)):
        write_png(os.path.join(OUT, "textures", "water_%d.png" % i), 16, 16, frame)
    for i, frame in enumerate(liquid_frames(rng, (214, 108, 24), 18)):
        write_png(os.path.join(OUT, "textures", "lava_%d.png" % i), 16, 16, frame)

    models = {
        "block/cube_all_stone": cube_all("stone"),
        "block/cube_all_dirt": cube_all("dirt"),
        "block/cube_all_cobblestone": cube_all("cobblestone"),
        "block/cube_all_planks": cube_all("oak_planks"),
        "block/cube_all_log": cube_all("oak_log"),
        "block/cube_all_sand": cube_all("sand"),
        "block/leaves": cube_all("oak_leaves"),
        "block/glass": cube_all("glass"),
        "block/grass_block": grass_block(),
        "block/tnt": tnt(),
        "block/chest": chest_model(),
        "entity/creeper": creeper_model(),
        "entity/zombie": zombie_model(),
    }
    for name, body in models.items():
        p = os.path.join(OUT, "models", name + ".json")
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w") as fh:
            json.dump(body, fh, indent=2, sort_keys=True)

    print("wrote %d textures, %d models to %s" % (len(tex) + 8, len(models), os.path.abspath(OUT)))


if __name__ == "__main__":
    main()
