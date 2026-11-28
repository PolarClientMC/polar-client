"""VexCraft's wings: crystal blade wings in VexCraft's void violets, built on HazeSMP's bird wing.

The build and the motion are HazeSMP's wing (hazewings.py): the same bones, feathers, poses, clips and springs, so the
wings fold, glide and beat the same way and stay clear of the player exactly as that wing was checked to. Only the
art is new: every flight feather is a faceted amethyst blade like the shards of VexCraft's V, and the coverts are
rows of cut crystal under a bar of black glass with a silver rim.

    blades     a ridge down the middle splits each blade into a lit face and a shadowed one, cut again into facets;
               dark void violet at the root, through violet and amethyst to a lilac point
    energy     a magenta vein runs down every blade with lightning cracks branching off it; a pulse of light races
               from root to point and the cracks flicker (the glow layer, 12 frames)
    particles  violet sparks and tiny crystal chips falling off the wings

All code, no images. Painted 8 texels per pixel like the HazeSMP wing.
"""
import math

import numpy as np
from PIL import Image, ImageDraw

from lib import rgb, rgba, blur
import hazewings
from hazewings import D, N

OUTLINE = rgb('#14041f')    # the void between the shards
VOID = rgb('#2a0b4f')
VIOLET = rgb('#5b1fb0')
PURPLE = rgb('#7c3aed')
AMETHYST = rgb('#a35cff')
LILAC = rgb('#d2b0ff')
PALE = rgb('#f3e8ff')
MAGENTA = rgb('#e04cff')
SPARK = rgb('#f6e6ff')
SILVER = rgb('#e6e1f2')
GLASS = rgb('#1a0a2e')

SS = 4   # shapes drawn 4x finer, then sampled down: straight crystal edges without jaggies


def _hash(x, y, seed):
    h = np.sin(x * 12.9898 + y * 78.233 + seed * 37.719) * 43758.5453
    return h - np.floor(h)


def _erode(m):
    e = m.copy()
    e[1:] &= m[:-1]
    e[:-1] &= m[1:]
    e[:, 1:] &= m[:, :-1]
    e[:, :-1] &= m[:, 1:]
    return e


def _mask(w, h, draw):
    im = Image.new('L', (w * SS, h * SS), 0)
    draw(ImageDraw.Draw(im), SS * D)
    return np.asarray(im.resize((w, h), Image.BOX), np.float32) / 255 > 0.5


def _lines(w, h, draw):
    im = Image.new('L', (w * SS, h * SS), 0)
    draw(ImageDraw.Draw(im), SS * D)
    return np.asarray(im.resize((w, h), Image.BOX), np.float32) / 255


def _blade_outline(L, W, kind, seed):
    """The blade's corners (model px; x along the blade, y across, leading edge at y < 0). It widens fast from the
    root and stays full most of the way, so the blades overlap into one wing, then two straight cuts run down to a
    point swept a little back, like a shard of the V."""
    rng = np.random.default_rng(seed)
    lead = 0.40 * W
    trail = 0.49 * W
    if kind == 'primary':
        lead *= 0.86
    hold = 0.56 + 0.06 * rng.random()          # where the cuts down to the point start
    tip_y = 0.12 * W
    pts = [(0.0, -0.1 * W), (L * 0.12, -lead * 0.85), (L * hold, -lead), (L * 0.995, tip_y),
           (L * (hold + 0.08), trail), (L * 0.14, trail * 0.92), (0.0, 0.1 * W)]
    return pts, tip_y, hold


def feather(L, W, seed, phase, kind='primary'):
    """One crystal blade pointing along +x from its root at x = 0 (the card spans x 0..L, y -W/2..W/2), for every
    frame: (base frames, glow frames)."""
    w, h = int(round(L * D)), int(round(W * D))
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    X, Y = (x + 0.5) / D, (y + 0.5) / D - W / 2
    pts, tip_y, wide = _blade_outline(L, W, kind, seed)
    m = _mask(w, h, lambda d, s: d.polygon([(px * s, (py + W / 2) * s) for px, py in pts], fill=255))
    u = np.clip(X / L, 0, 1)
    ridge = tip_y * u ** 1.6                                          # the ridge runs from the root to the point
    lit_side = Y < ridge
    # facets: cuts running diagonally out from the ridge to both edges; each facet gets its own brightness
    cut_pos = np.array([0.18, 0.36, 0.55, 0.74]) + np.random.default_rng(seed + 5).uniform(-0.03, 0.03, 4)
    facet = np.zeros_like(X)
    across = np.abs(Y - ridge) / (W * 0.45)
    for i, c in enumerate(cut_pos):
        facet += (u - across * 0.12 * (1 if i % 2 else -0.6) > c)
    shade = _hash(facet, lit_side.astype(np.float32), seed)          # 0..1 per facet
    t = u
    stops = [(0.0, GLASS), (0.18, VOID), (0.4, VIOLET), (0.62, PURPLE), (0.8, AMETHYST), (0.93, LILAC), (1.0, PALE)]
    col = np.zeros((h, w, 3), np.float32) + VOID
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        k = np.clip((t - t0) / (t1 - t0), 0, 1)[..., None]
        col = np.where((t >= t0)[..., None], c0 + (c1 - c0) * k, col)
    light = np.where(lit_side, 1.12 + 0.18 * shade, 0.62 + 0.16 * shade)
    col = col * light[..., None]
    # a sheen across the lit face, brightest near the leading edge
    sheen = np.clip(1 - np.abs((Y - ridge) / (W * 0.4) + 0.55) * 2.2, 0, 1) * lit_side * (0.25 + 0.75 * u)
    col = col + (PALE - col) * (sheen * 0.35)[..., None]
    # facet cuts: thin darker lines, and the ridge a bright line
    cuts = np.zeros_like(X, bool)
    for i, c in enumerate(cut_pos):
        cu = u - across * 0.12 * (1 if i % 2 else -0.6)
        cuts |= np.abs(cu - c) * L < 0.07
    col = np.where((cuts & m)[..., None], col * 0.6, col)
    ridge_line = (np.abs(Y - ridge) < 0.06 + 0.06 * (1 - u)) & (u > 0.04) & (u < 0.97)
    col = np.where(ridge_line[..., None], col * 0.4 + LILAC * 0.6, col)
    col = np.clip(col, 0, 1)
    edge = m & ~_erode(m)
    lead_rim = m & ~_erode(_erode(m)) & ~edge & (Y < ridge) & (u > 0.08)
    col = np.where(lead_rim[..., None], col * 0.3 + PALE * 0.7, col)
    cut_rim = m & ~_erode(_erode(m)) & ~edge & (u > wide - 0.02)          # the cuts down to the point: charged edges
    col = np.where(edge[..., None], OUTLINE, col)
    base = rgba(col, m.astype(np.float32))
    # the energy: a vein along the ridge with lightning cracks branching off it (new cracks every few frames)
    vein = np.clip(1 - np.abs(Y - ridge) / (0.12 + 0.05 * u), 0, 1) * (u > 0.06) * (u < 0.96)
    rng = np.random.default_rng(seed * 31 + 7)
    crack_sets = []
    for c in range(3):
        def draw(d, s, c=c):
            for k in range(4):
                u0 = rng.uniform(0.15, 0.85)
                px, py = u0 * L, tip_y * u0 ** 1.6
                side = -1 if (k + c) % 2 else 1
                a = side * rng.uniform(0.6, 1.1)
                line = [(px * s, (py + W / 2) * s)]
                for j in range(4):
                    a += rng.normal(0, 0.45)
                    px += math.cos(a) * 0.55 + 0.25
                    py += math.sin(a) * 0.55
                    line.append((px * s, (py + W / 2) * s))
                d.line(line, fill=255, width=max(1, int(0.12 * s)))
        crack_sets.append(_lines(w, h, draw) * m)
    tip_glow = np.clip(1 - np.hypot(X - L * 0.97, Y - tip_y) / (W * 0.35), 0, 1) * m
    bases, glows = [], []
    for f in range(N):
        tt = f / N
        run = np.clip(1 - np.abs(((u - (tt - phase) * 1.6) % 1.6) - 0.5) * 4.5, 0, 1)   # a pulse racing root -> point
        flick = 0.5 + 0.5 * math.sin(2 * math.pi * (tt * 3 + phase * 7))
        cracks = crack_sets[(f // 4 + int(phase * 10)) % 3] * (0.35 + 0.65 * flick)
        light = (vein * (0.45 + 0.55 * run) + cracks * (0.3 + 0.7 * run) + tip_glow * (0.45 + 0.5 * flick) + lead_rim * run * 0.45
                 + cut_rim * (0.3 + 0.5 * run))
        g = np.zeros((h, w, 4), np.float32)
        hot = np.clip(light - 0.75, 0, 1)[..., None] * 3
        g[..., :3] = np.clip(MAGENTA + (SPARK - MAGENTA) * np.clip(hot, 0, 1), 0, 1)
        g[..., 3] = np.clip(light, 0, 1) * m
        bases.append(base)
        glows.append(g)
    return bases, glows


def coverts(L, W, rows, seed, phase, arch=0.0):
    """Rows of cut crystals over a bone (the card spans x 0..L, y -1..W-1): a bar of black glass along the top with a
    silver rim, and below it diamond-cut amethyst scales, lightest at the top."""
    w, h = int(round(L * D)), int(round(W * D))
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    X, Y = (x + 0.5) / D, (y + 0.5) / D - 1
    m = np.zeros((h, w), bool)
    col = np.zeros((h, w, 3), np.float32)
    lines = np.zeros((h, w), bool)
    for r in reversed(range(rows)):
        ry = (r + 0.7) * (W - 2.2) / rows
        size = 1.7 + 0.35 * r / max(1, rows - 1)
        n = int(math.ceil(L / (size * 1.05))) + 1
        for i in range(n):
            cx = (i + (0.5 if r % 2 else 0)) * size * 1.05 - 0.2
            cy = ry + arch * (cx / L) ** 2
            # a diamond pointing down-and-back
            dx, dy = (X - cx) / (size * 0.62), (Y - cy) / (size * 1.0)
            dia = np.abs(dx) + np.abs(dy) < 1
            dia &= Y > cy - size * 0.75
            left = dx < 0
            tone = 0.45 + 0.4 * (1 - r / max(1, rows - 1))
            k = _hash(np.float32(i), np.float32(r), seed)
            c = PURPLE + (AMETHYST - PURPLE) * tone
            c = c * np.where(left, 1.15 + 0.1 * k, 0.7 + 0.1 * k)[..., None]
            m |= dia
            col = np.where(dia[..., None], c, col)
            lines = np.where(dia, (np.abs(np.abs(dx) + np.abs(dy) - 1) < 0.16) | (np.abs(dx) < 0.07), lines)
    col = np.where(lines[..., None] & m[..., None], col * 0.55, col)
    # the top bar: black glass with a silver rim and violet glints
    bar = (Y > -1) & (Y < 1.35 + 0.15 * np.sin(X * 1.7)) & (X > -0.05)
    gl = GLASS + (VIOLET - GLASS) * np.clip((Y + 1) / 2.3, 0, 1)[..., None] * 0.6
    col = np.where(bar[..., None], gl, col)
    rim = bar & (Y < -0.45)
    col = np.where(rim[..., None], LILAC * 0.55 + SILVER * 0.45, col)
    glint = bar & ~rim & (_hash(np.floor(X * 1.2), np.floor(Y * 1.2), seed + 99) > 0.9)
    col = np.where(glint[..., None], AMETHYST * 0.6 + gl * 0.4, col)
    m |= bar
    edge = m & ~_erode(m)
    col = np.where(edge[..., None], OUTLINE, col)
    base = rgba(np.clip(col, 0, 1), m.astype(np.float32))
    bases, glows = [], []
    for f in range(N):
        tt = f / N
        run = np.clip(1 - np.abs(((X / L - (tt - phase) * 1.6) % 1.6) - 0.5) * 4, 0, 1)
        g = np.zeros((h, w, 4), np.float32)
        g[..., :3] = np.where(rim[..., None], LILAC * 0.5 + MAGENTA * 0.5, MAGENTA)
        g[..., 3] = ((rim & ~edge) * (0.15 + 0.55 * run) + (glint & ~edge) * (0.25 + 0.7 * run)).astype(np.float32)
        bases.append(base)
        glows.append(g)
    return bases, glows


def skeleton():
    return hazewings.skeleton(feather=feather, coverts=coverts)


# ---------------------------------------------------------------- sparks and crystal chips falling off the wings

def _spark(size, seed):
    rng = np.random.default_rng(seed)
    im = Image.new('L', (size * 4, size * 4), 0)
    d = ImageDraw.Draw(im)
    for arm in range(3):
        a = rng.random() * 2 * math.pi
        px = py = size * 2
        line = [(px, py)]
        for k in range(3):
            a += rng.normal(0, 0.7)
            px += math.cos(a) * size * 0.6
            py += math.sin(a) * size * 0.6
            line.append((px, py))
        d.line(line, fill=255, width=max(1, size // 4))
    a = np.asarray(im.resize((size, size), Image.LANCZOS), np.float32) / 255
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    core = np.clip(1 - np.hypot(xx - size / 2 + 0.5, yy - size / 2 + 0.5) / (size * 0.16), 0, 1)
    return rgba(np.ones((size, size, 3), np.float32), np.clip(a + blur(a, size / 16) * 0.5 + core, 0, 1))


def _chip(size, k):
    """A tiny crystal chip: a diamond with a lit half."""
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    u, v = (xx + 0.5) / size - 0.5, (yy + 0.5) / size - 0.5
    stretch = 1.0 + 0.5 * k
    dia = np.abs(u) * 2.4 + np.abs(v) * 2.4 / stretch < 1
    col = np.where((u < 0)[..., None], np.ones(3) * 1.0, np.ones(3) * 0.62) * np.ones((size, size, 3), np.float32)
    return rgba(col, dia.astype(np.float32))


PARTICLES = (lambda: np.concatenate([_spark(16, 3), _spark(16, 8), _chip(16, 0), _chip(16, 1)], 1), dict(
    rate=6, life=[0.6, 1.4], size=[0.04, 0.08], radius=[0.35, 0.95], height=[0.7, 1.7], vx=[-0.06, 0.06], vy=[-0.35, -0.12],
    vz=[-0.06, 0.06], spin=[-240, 240], shrink=True, colors=['#e04cff', '#c084fc', '#f3e8ff', '#a35cff'], glow=True))


def _make():
    return hazewings.HazeWings(skeleton), None


WINGS = dict(id='wings_vexcraft', name='VexCraft Wings', collection='vexcraft', rarity='mythic', make=_make, special=True,
             description="VexCraft's partner wings: blades of cut amethyst that fold, spread, glide and beat like a bird's, "
                         'with a magenta vein of energy down every blade, lightning crackling through the crystal and sparks falling off.')
