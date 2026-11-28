"""VexCraft's headgear: the VexCraft Crown. Chunky pixel gold round a band of dark violet, the gold rising in steps
and points all the way round, a big amethyst in a tall gold setting at the front, small amethysts on the sides and
back, and three violet banners with VexCraft's V hanging from it.

    the gold      outlined and bevelled like pixel art: lit on its top edges, darker underneath
    the band      dark violet in mixed pixels, between gold trims; violet fills the steps above it
    the gems      cut amethysts: a big one at the front in a diamond-shaped gold setting, small ones round the crown
    the banners   violet pennants edged in gold with a white V: two by the front corners, one at the back; they sway

Light catches it: a shine sweeps across the gold all the way round, the amethysts glow and pulse, and sparkles
flash on the gems (the glow layer, 32 frames).

It sits outside the head and its hat layer (R 4.85). The banners are kept short so they stay clear of the body at
every head angle (looking straight up or down included), and they sway in the crown's own plane, never in towards
the head. Code only: drawn as pixel art at 2 texels per art pixel (8 texels per model pixel).
"""
import json
import math
import os

import numpy as np
from PIL import Image

from lib import rgb, rgba, Model, Bone, Cube, to_u8
from keyframes import key, clip

N = 32
FRAME_MS = 90
D = 8

GOLD_OUT = rgb('#4a2a0c')
GOLD_DK = rgb('#9a6418')
GOLD_MID = rgb('#d8962a')
GOLD = rgb('#f2bf45')
GOLD_HI = rgb('#fff0a8')
PURPLES = [rgb('#24103d'), rgb('#2f1450'), rgb('#3b1766'), rgb('#4a1f80')]
GEM_DK = rgb('#4a148c')
GEM = rgb('#8a2be2')
GEM_MID = rgb('#a855f7')
GEM_HI = rgb('#d8b4fe')
GEM_SHINE = rgb('#f5e8ff')
BANNER = rgb('#5b21b6')
BANNER_DK = rgb('#3b1475')
WHITE = rgb('#f4eeff')
SHINE = rgb('#fff6d6')
GLOW = rgb('#d17bff')

R = 4.85                # half the crown's width (the head is 4, its hat layer 4.5)
YB = -4.6               # the band's bottom edge
H = 8.5                 # wall height (its top at -13.1)
AW, AH = 39, 34         # a wall in art pixels (2 texels each)
BAND_TOP, BAND_BOT = 20, 33   # art rows: the band's top trim starts at 20, the bottom trim ends at 33 (each trim 4 rows)

START = {'front': 0.0, 'right': 0.25, 'back': 0.5, 'left': 0.75}   # where each wall is round the crown


def _erode(m):
    e = m.copy()
    e[1:] &= m[:-1]
    e[:-1] &= m[1:]
    e[:, 1:] &= m[:, :-1]
    e[:, :-1] &= m[:, 1:]
    return e


def _hash(x, y, seed):
    v = np.sin(np.asarray(x, np.float32) * 12.9898 + np.asarray(y, np.float32) * 78.233 + seed * 37.719) * 43758.5453
    return v - np.floor(v)


def _up(img, w, h):
    """Art pixels -> texels (nearest, so the pixels stay crisp)."""
    return np.asarray(Image.fromarray(to_u8(img), 'RGBA').resize((w, h), Image.NEAREST), np.float32) / 255


def _profile(side):
    """The top edge of the gold above the band, per art column (the row it starts at): points at the corners,
    blocks and dips between, a peak in the middle of the sides and back (the front has its gem setting there)."""
    half = [1, 3, 6, 13, 13, 13, 13, 8, 8, 8, 8, 8, 13, 13, 13, 11, 9, 7, 5, 3]   # columns 0..19, then mirrored
    if side == 'front':
        half = half[:15] + [13, 13, 13, 13, 13]
    top = half + half[::-1][1:]
    return np.array(top[:AW])


def _shift(m, dy, dx):
    out = np.zeros_like(m)
    h, w = m.shape
    out[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)] = m[max(0, -dy):h + min(0, -dy), max(0, -dx):w + min(0, -dx)]
    return out


def _gold(m, shape, X, Y):
    """Pixel-art gold for the pixels in m: a dark outline where it meets the air, lit along its top and left edges,
    a darker line underneath and on the right, a few hammered pixels in the middle."""
    col = np.zeros(m.shape + (3,), np.float32) + GOLD
    air = shape & ~_erode(shape)
    body = m & ~air
    lit = body & (~_shift(body, 1, 0) | ~_shift(body, 0, 1))             # nothing gold above or to the left
    shade = body & (~_shift(body, -1, 0) | ~_shift(body, 0, -1))         # nothing gold below or to the right
    col = np.where(shade[..., None], GOLD_DK, col)
    col = np.where((lit & ~shade)[..., None], GOLD_HI, col)
    mid = body & ~lit & ~shade
    col = np.where((mid & (_hash(X, Y, 7) > 0.8))[..., None], GOLD_MID, col)
    col = np.where((m & air)[..., None], GOLD_OUT, col)
    return col, lit


def _gem(cx, cy, rx, ry, X, Y):
    """A cut amethyst (a diamond): mask, colours, and where its highlight is."""
    dx, dy = (X - cx) / rx, (Y - cy) / ry
    m = np.abs(dx) + np.abs(dy) <= 1.0
    col = np.where((dx < 0)[..., None], np.where((dy < 0)[..., None], GEM_HI, GEM_MID), np.where((dy < 0)[..., None], GEM, GEM_DK))
    table = np.abs(dx) + np.abs(dy) <= 0.42
    col = np.where(table[..., None], np.where((dx + dy < 0)[..., None], GEM_MID, GEM), col)
    hi = (np.abs(dx + 0.45) < 0.2) & (np.abs(dy + 0.35) < 0.18)
    col = np.where(hi[..., None], GEM_SHINE, col)
    edge = m & ~_erode(m)
    col = np.where(edge[..., None], GEM_DK * 0.6, col)
    return m, col, (cx - rx * 0.45, cy - ry * 0.35)


def wall_art(side, f, inner=False):
    """One wall as art (AH x AW x 4) and its light (AH x AW x 4), frame f."""
    t = f / N
    X, Y = np.meshgrid(np.arange(AW), np.arange(AH))
    top = _profile(side)
    shape = (Y >= top[None, :]) & (Y <= BAND_BOT)              # the gold steps and everything under them
    # the violet: the band and the fill inside the steps, in mixed pixels
    k = _hash(X, Y, 3 + START[side] * 10)
    idx = np.clip((k * 3.2 + (Y > BAND_TOP) * 0.6).astype(int), 0, 3)
    col = np.stack(PURPLES)[idx]
    # the gold: the steps' rim (2 pixels thick) and the two trims round the band
    e = shape
    for _ in range(4):
        e = _erode(e)
    rim = shape & ~e & (Y < BAND_TOP)                          # a gold bar a model pixel thick along the steps
    trims = shape & (((Y >= BAND_TOP) & (Y < BAND_TOP + 4)) | (Y > BAND_BOT - 4))
    gold = rim | trims
    gems, gem_cols, glints = [], [], []
    if side == 'front':
        # the big setting: a tall gold diamond with a finial on top, the amethyst inside
        sx, sy = 19, 17
        setting = (np.abs(X - sx) / 8.5 + np.abs(Y - sy) / 16 <= 1.0) | ((np.abs(X - sx) <= 1) & (Y <= 3))
        setting &= Y <= BAND_BOT
        shape |= setting
        gold |= setting
        spots = [(sx, sy, 5.0, 9.4)]
    else:
        # a small gem in a gold frame in the middle of the band
        sx, sy = 19, 26.5
        frame = np.abs(X - sx) / 3.8 + np.abs(Y - sy) / 5.0 <= 1.0
        shape |= frame
        gold |= frame
        spots = [(sx, sy, 2.2, 3.2)]
    spots += [(8, 26.5, 1.7, 2.5), (30, 26.5, 1.7, 2.5)]          # small gems on the band either side
    for gx, gy, rx, ry in spots:
        gm, gc, gl = _gem(gx, gy, rx, ry, X, Y)
        gems.append(gm)
        gem_cols.append(gc)
        glints.append(gl)
    gcol, gold_lit = _gold(gold, shape, X, Y)
    col = np.where(gold[..., None], gcol, col)
    gem_any = np.zeros_like(shape)
    for gm, gc in zip(gems, gem_cols):
        col = np.where(gm[..., None], gc, col)
        gem_any |= gm
    out = shape & ~_erode(shape)
    col = np.where((out & ~gem_any)[..., None], np.where(gold[..., None], GOLD_OUT, PURPLES[0] * 0.6), col)
    base = rgba(np.clip(col, 0, 1), shape.astype(np.float32))
    if inner:
        base[..., :3] = base[..., :3] * 0.6 + PURPLES[0] * 0.12
        return base, None
    # ---- light: a shine sweeping round the crown across the gold, glowing gems, sparkles
    u = START[side] + X / AW * 0.25
    s = u * 4 * AW + (AH - Y) * 0.55
    pos = (t * 1.25 - 0.1) * 4 * AW
    sweep = np.clip(1 - np.abs(s - pos) / 6.0, 0, 1) ** 1.2
    sweep2 = np.clip(1 - np.abs(s - pos + 10) / 2.0, 0, 1) * 0.7     # a thinner streak following it
    shine = np.maximum(sweep, sweep2)
    light = np.where(gold & ~out, shine, np.where(shape & ~out, shine * 0.22, 0.0))
    light = np.where(gold_lit & gold, np.maximum(light, 0.16), light)
    lcol = np.zeros((AH, AW, 3), np.float32) + SHINE
    pulse = 0.5 + 0.5 * math.sin(2 * math.pi * (t * 2 + START[side]))
    for i, (gm, (gx, gy)) in enumerate(zip(gems, glints)):
        light = np.where(gm, np.maximum(light, 0.22 + 0.25 * pulse + 0.6 * sweep), light)
        lcol = np.where(gm[..., None], GLOW, lcol)
        ph = (t * 2 + i * 0.31 + START[side] * 1.7) % 1.0             # a four-point sparkle flashing on the highlight
        flash = max(0.0, 1 - abs(ph - 0.5) / 0.12)
        if flash > 0:
            arm = 1 + round(2 * flash) + (1 if i == 0 else 0)
            gxi, gyi = int(round(gx)), int(round(gy))
            star = ((X == gxi) & (np.abs(Y - gyi) <= arm)) | ((Y == gyi) & (np.abs(X - gxi) <= arm))
            light = np.where(star, np.maximum(light, flash), light)
            lcol = np.where(star[..., None], WHITE, lcol)
    glow = np.zeros((AH, AW, 4), np.float32)
    glow[..., :3] = lcol
    glow[..., 3] = np.clip(light, 0, 1)
    return base, glow


def wall_paint(side, glow=False, inner=False):
    memo = {}

    def p(face, w, h, f):
        k = (face, w, h, f)
        if k not in memo:
            base, g = wall_art(side, f, inner)
            img = _up(g if glow else base, w, h)
            memo[k] = img[:, ::-1] if face in ('back', 'left') else img
        return memo[k]
    return p


BW, BH = 9, 10          # a banner in art pixels


def banner_art(f, glow=False):
    t = f / N
    X, Y = np.meshgrid(np.arange(BW), np.arange(BH))
    point = BH - 1 - np.abs(X - (BW - 1) / 2) * 0.6             # a pointed bottom
    m = Y <= point
    col = np.where((X < BW / 2)[..., None], BANNER, BANNER_DK) * np.ones((BH, BW, 3), np.float32)
    edge = (m & ~_erode(m)) | (m & (Y == 0))
    col = np.where(edge[..., None], GOLD, col)
    col = np.where((edge & ((X == 0) | (Y == 0)))[..., None], GOLD_HI, col)
    col = np.where((edge & ((X == BW - 1) | (Y >= point - 0.6)) & (Y > 0))[..., None], GOLD_MID, col)
    V = ['x...x', 'x...x', '.x.x.', '.x.x.', '..x..']
    vm = np.zeros((BH, BW), bool)
    for yy, row in enumerate(V):
        for xx, c in enumerate(row):
            if c == 'x':
                vm[2 + yy, 2 + xx] = True
    col = np.where(vm[..., None], WHITE, col)
    if glow:
        g = np.zeros((BH, BW, 4), np.float32)
        g[..., :3] = SHINE
        shine = np.clip(1 - np.abs((X + Y * 0.6) - (t * 2.7 % 1.0) * 20 + 3) / 1.6, 0, 1)
        g[..., 3] = (edge * shine * 0.9 + vm * (0.15 + 0.15 * math.sin(2 * math.pi * t))).astype(np.float32)
        return g
    return rgba(col, m.astype(np.float32))


def banner_paint(glow=False):
    memo = {}

    def p(face, w, h, f):
        k = (face, w, h, f)
        if k not in memo:
            img = _up(banner_art(f, glow), w, h)
            memo[k] = img[:, ::-1] if face in ('back', 'left') else img
        return memo[k]
    return p


BANNER_W, BANNER_H = 2.25, 2.5   # model px: short enough to stay in front of the body looking down, behind it looking up
BANNERS = [  # (bone, x, z, plane): two by the front corners on the sides, one at the back
    ('banner_l', R + 0.03, -2.9, 'x'), ('banner_r', -R - 0.03, -2.9, 'x'), ('banner_b', 0, R + 0.03, 'z')]


def vexcraft_crown():
    cubes = []
    for side, frm, size in (('front', (-R, YB - H, -R), (2 * R, H, 0)), ('back', (-R, YB - H, R), (2 * R, H, 0)),
                            ('left', (R, YB - H, -R), (0, H, 2 * R)), ('right', (-R, YB - H, -R), (0, H, 2 * R))):
        cubes.append(Cube(frm, size, wall_paint(side), wall_paint(side, glow=True)))
        ins = 0.25
        f2 = (frm[0] + (ins if size[0] == 0 and frm[0] < 0 else -ins if size[0] == 0 else 0), frm[1],
              frm[2] + (ins if size[2] == 0 and frm[2] < 0 else -ins if size[2] == 0 else 0))
        cubes.append(Cube(f2, size, wall_paint(side, inner=True)))
    bones = []
    for name, x, z, plane in BANNERS:
        if plane == 'x':
            cube = Cube((0, 0, -BANNER_W / 2), (0, BANNER_H, BANNER_W), banner_paint(), banner_paint(True))
        else:
            cube = Cube((-BANNER_W / 2, 0, 0), (BANNER_W, BANNER_H, 0), banner_paint(), banner_paint(True))
        bones.append(Bone(name, (x, YB - 0.05, z), (0, 0, 0), [cube]))
    root = Bone('crown', (0, 0, 0), (0, 0, 0), cubes, bones)
    return Model('head', [root], density=D, frames=N, frame_time=FRAME_MS, translucent=False, tight=True)


def animations():
    """The banners sway gently, each in the crown's own plane (never in towards the head), out of step."""
    L = 4.0
    bones = {}
    for i, (name, x, z, plane) in enumerate(BANNERS):
        rot = {}
        for k in range(17):
            t = L * k / 16
            a = 6 * math.sin(2 * math.pi * (t / L * 2 + i * 0.3)) + 2.5 * math.sin(2 * math.pi * (t / L * 3 + i * 0.7))
            rot[f'{t:.3f}'] = key([a, 0, 0] if plane == 'x' else [0, 0, a])
        bones[name] = {'rotation': rot}
    return {'always': clip(L, bones)}


class VexCraftCrown:
    """heads.build() calls item['model']().build(out, id): builds the model, then adds the clips."""

    def build(self, out, name):
        model = vexcraft_crown().build(out, name)
        model['animations'] = animations()
        with open(os.path.join(out, 'models', f'{name}.json'), 'w') as fh:
            json.dump(model, fh, separators=(',', ':'))
        return model


CROWN = dict(id='head_vexcraft_crown', name='VexCraft Crown', collection='vexcraft', rarity='mythic', model=VexCraftCrown,
             special=True,
             description="VexCraft's partner crown: chunky gold round a band of violet, a big amethyst at the front, small "
                         'ones all round and VexCraft banners swaying from it. Light sweeps across the gold and the gems sparkle.')
