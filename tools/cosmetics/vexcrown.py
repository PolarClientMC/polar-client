"""VexCraft's headgear: the Void Crown. A band of black glass between silver trims, set with cut amethysts; above it
a ring of violet crystal shards, VexCraft's crystal V rising at the front, and an orbit of floating shards with a
lifesteal heart among them.

    the band     black-violet glass, silver trims, diamond-cut amethysts that pulse; a line of energy runs round it
    the shards   clusters of crystal prisms, a lit face and a shadowed face each, lightning flickering up them
    the V        two crystal blades meeting at a point over the front, silver-edged, with magenta energy inside
    the orbit    four shards and a red heart crystal circling the crown, bobbing and turning; they lag and swing when
                 you move or turn (a spring), and settle again

Built like HazeSMP's Tide Crown (hazecrown.py): the same walls just outside the head and its hat layer, and the same
orbit, so it never cuts into the head. Code only, 8 texels per pixel, 16 frames.
"""
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw

from lib import rgb, rgba, Model, Bone, Cube, to_u8
from keyframes import key, clip

N = 16
FRAME_MS = 85
D = 8

OUTLINE = rgb('#14041f')
GLASS = rgb('#1a0a2e')
VOID = rgb('#2a0b4f')
VIOLET = rgb('#5b1fb0')
PURPLE = rgb('#7c3aed')
AMETHYST = rgb('#a35cff')
LILAC = rgb('#d2b0ff')
PALE = rgb('#f3e8ff')
MAGENTA = rgb('#e04cff')
SILVER = rgb('#e6e1f2')
SILVER_MID = rgb('#a9a2bf')
SILVER_DK = rgb('#5c5573')
HEART = rgb('#ff2e63')
HEART_DK = rgb('#8c0f3a')
HEART_HI = rgb('#ffb3c8')

R = 4.85                # half the crown's width (the head is 4, its hat layer 4.5)
YB = -6.3               # the bottom edge, over the top of the head (which is at -8)
H = 7.25                # wall height
SS = 4

PHASE = {'front': 0.0, 'right': 0.25, 'back': 0.5, 'left': 0.75}


def _hash(x, y, seed):
    v = np.sin(np.asarray(x, np.float32) * 12.9898 + np.asarray(y, np.float32) * 78.233 + seed * 37.719) * 43758.5453
    return v - np.floor(v)


def _erode(m):
    e = m.copy()
    e[1:] &= m[:-1]
    e[:-1] &= m[1:]
    e[:, 1:] &= m[:, :-1]
    e[:, :-1] &= m[:, 1:]
    return e


def _mask(w, h, draw):
    im = Image.new('L', (w * SS, h * SS), 0)
    draw(ImageDraw.Draw(im), SS)
    return np.asarray(im.resize((w, h), Image.BOX), np.float32) / 255 > 0.45


def _soft(w, h, draw):
    im = Image.new('L', (w * SS, h * SS), 0)
    draw(ImageDraw.Draw(im), SS)
    return np.asarray(im.resize((w, h), Image.BOX), np.float32) / 255


def _shard_poly(cx, base, height, width, lean=0.0):
    """A crystal prism standing on `base` (texels): straight sides, a pointed top cut at two angles."""
    hw = width / 2
    top_x = cx + lean * height
    shoulder = base - height * 0.78
    return [(cx - hw, base), (cx - hw + lean * height * 0.78, shoulder), (top_x - hw * 0.15, base - height),
            (top_x + hw * 0.25, base - height + height * 0.06), (cx + hw + lean * height * 0.78, shoulder - height * 0.04), (cx + hw, base)]


class _Wall:
    """Shapes painted one over the other, each with its colour, light (for the glow) and outline."""

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.X, self.Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
        self.col = np.zeros((h, w, 3), np.float32)
        self.lit = np.zeros((h, w), np.float32)
        self.glow_col = np.zeros((h, w, 3), np.float32) + MAGENTA
        self.solid = np.zeros((h, w), bool)

    def put(self, m, c, light=None, edge=OUTLINE, glow_col=None):
        e = m & ~_erode(m)
        c = np.where(e[..., None], edge, c) if edge is not None else c
        self.col = np.where(m[..., None], c, self.col)
        self.lit = np.where(m, 0 if light is None else light, self.lit)
        if glow_col is not None:
            self.glow_col = np.where(m[..., None], glow_col, self.glow_col)
        self.solid |= m

    def shard(self, poly, seed, run, flick, cracks=True):
        """A crystal prism: the left face lit, the right in shadow, a bright ridge, lightning flickering inside."""
        X, Y = self.X, self.Y
        m = _mask(self.w, self.h, lambda d, s: d.polygon([(x * s, y * s) for x, y in poly], fill=255))
        if not m.any():
            return
        xs = [p[0] for p in poly]
        top = min(p[1] for p in poly)
        base = max(p[1] for p in poly)
        ridge_x = (poly[2][0] + poly[3][0]) / 2
        cx = (min(xs) + max(xs)) / 2
        v = np.clip((base - Y) / max(1, base - top), 0, 1)                  # 0 at the base, 1 at the point
        ridge = cx + (ridge_x - cx) * v
        lit_face = X < ridge
        c = VIOLET + (AMETHYST - VIOLET) * v[..., None]
        c = c + (LILAC - c) * (np.clip(v - 0.65, 0, 1) * 1.6)[..., None]
        c = c * np.where(lit_face, 1.15, 0.66)[..., None]
        k = _hash(seed, 1, 3)
        band = (np.abs(((Y + X * 0.3 + seed * 5) % (6 + 3 * k)) - 1) < 0.5) & ~lit_face
        c = np.where(band[..., None], c * 0.8, c)
        ridge_line = np.abs(X - ridge) < 0.6
        c = np.where(ridge_line[..., None], c * 0.35 + PALE * 0.65, c)
        rim = m & ~_erode(_erode(m)) & lit_face
        c = np.where(rim[..., None], c * 0.4 + PALE * 0.6, c)
        light = ridge_line * (0.15 + 0.6 * run) + np.clip(v - 0.8, 0, 1) * 3 * (0.4 + 0.5 * flick)
        if cracks:
            rng = np.random.default_rng(seed)
            cr = _soft(self.w, self.h, lambda d, s: [d.line([((cx + rng.normal(0, 1.2)) * s, (base - 1) * s),
                                                               ((cx + rng.normal(0, 1.5)) * s, (base - (base - top) * 0.4) * s),
                                                               ((ridge_x + rng.normal(0, 1.0)) * s, (top + (base - top) * 0.2) * s)],
                                                              fill=255, width=max(1, SS // 2)) for _ in range(1)])
            light = light + cr * m * flick * 0.45
        self.put(m, np.clip(c, 0, 1), light=np.clip(light, 0, 1) * m)


def wall(kind, side, f, w, h, glow=False, inner=False):
    """One wall of the crown (h x w x 4 texels)."""
    t = f / N
    wl = _Wall(w, h)
    X, Y = wl.X, wl.Y
    band_h = 18
    bt = h - band_h
    cx = w / 2
    u = (X / w + PHASE[side] * 4) / 4                                  # 0..1 round the crown
    run = np.clip(1 - np.abs(((u - t + 0.5) % 1.0) - 0.5) / 0.08, 0, 1)
    flick = 0.5 + 0.5 * math.sin(2 * math.pi * (t * 2 + PHASE[side]))
    pulse = 0.6 + 0.4 * math.sin(2 * math.pi * t)
    seed0 = {'front': 1, 'right': 11, 'back': 21, 'left': 31}[side]
    # ---- shards along the wall: a cluster each side of the middle, small ones towards the corners
    if kind == 'front':
        clusters = [(9, 13, 6, -0.12), (17, 18, 6.5, -0.06), (w - 17, 18, 6.5, 0.06), (w - 9, 13, 6, 0.12)]
    else:
        clusters = [(8, 12, 6, -0.1), (17, 19, 6.5, -0.05), (cx, 27, 8, 0.0), (w - 17, 19, 6.5, 0.05), (w - 8, 12, 6, 0.1)]
    for i, (sx, sh, sw, lean) in enumerate(clusters):
        wl.shard(_shard_poly(sx, bt + 1, sh, sw, lean), seed0 + i, run, flick)
    # ---- the V on the front: two blades meeting at a point just above the band, silver-edged, energy inside
    if kind == 'front':
        top, vb = 1.5, bt - 1.0

        def blade(d, s, sign):
            d.polygon([((cx + sign * 17.5) * s, top * s), ((cx + sign * 8.5) * s, top * s), ((cx + sign * 1.0) * s, (vb - 8) * s),
                       (cx * s, (vb - 7) * s), (cx * s, vb * s), ((cx + sign * 2.5) * s, (vb - 1) * s)], fill=255)
        for sign in (-1, 1):
            m = _mask(w, h, lambda d, s, sign=sign: blade(d, s, sign))
            v = np.clip((vb - Y) / (vb - top), 0, 1)
            outer = (X - cx) * sign
            mid = 1.0 + v * 12.0                       # the middle of the blade at this height
            lit_face = (outer < mid) if sign < 0 else (outer > mid)
            c = VIOLET + (AMETHYST - VIOLET) * (0.2 + 0.8 * v)[..., None]
            c = c * np.where(lit_face, 1.2, 0.5)[..., None]
            ridge = np.abs(outer - mid) < 0.7
            c = np.where(ridge[..., None], LILAC * 0.8 + c * 0.2, c)
            core = m & _erode(_erode(_erode(m))) & ~ridge
            vein = core & (np.abs(outer - mid - (1.8 if sign > 0 else -1.8)) < 0.8)
            c = np.where(vein[..., None], MAGENTA * 0.7 + PALE * 0.3, c)
            wl.put(m, np.clip(c, 0, 1), light=vein * (0.45 + 0.45 * pulse) + ridge * m * (0.2 + 0.5 * run), edge=OUTLINE)
        # a spark where the blades meet
        sp = _mask(w, h, lambda d, s: d.polygon([(cx * s, (vb - 4.5) * s), ((cx + 1.6) * s, (vb - 2) * s), (cx * s, (vb + 0.5) * s),
                                                ((cx - 1.6) * s, (vb - 2) * s)], fill=255))
        wl.put(sp, PALE * np.ones_like(wl.col), light=sp * (0.7 + 0.3 * pulse), edge=None, glow_col=np.ones_like(wl.col))
    # ---- the band: black glass, silver trims, a row of cut amethysts
    band = Y >= bt
    vb_ = (Y - bt) / band_h
    bc = GLASS + (VOID - GLASS) * np.clip(1 - vb_ * 1.4, 0, 1)[..., None]
    streak = band & (np.abs(((X * 0.6 - Y) % 11) - 5.5) < 0.45)
    bc = np.where(streak[..., None], bc * 0.6 + VIOLET * 0.4, bc)
    trim_t = band & (Y < bt + 3)
    trim_b = band & (Y >= h - 3)
    bc = np.where(trim_t[..., None], np.where((Y < bt + 1)[..., None], SILVER, SILVER_MID), bc)
    bc = np.where(trim_b[..., None], np.where((Y >= h - 1)[..., None], SILVER_DK, SILVER_MID), bc)
    wl.put(band, bc, edge=None)
    wl.lit = np.where(trim_t, 0.1 + 0.5 * run, wl.lit)
    wl.glow_col = np.where(trim_t[..., None], PALE * 0.5 + MAGENTA * 0.5, wl.glow_col)
    # gems: diamonds along the band, a big one in the middle of the front
    spacing = 13.0
    gy = bt + 3 + (band_h - 6) / 2
    spots = [(i + 0.5) * spacing for i in range(int(w / spacing) + 1)]
    if kind == 'front':
        spots = [g for g in spots if abs(g - cx) > spacing] + [cx]
    for i, gx in enumerate(spots):
        big = kind == 'front' and gx == cx
        rx, ry = (4.2, 5.2) if big else (2.6, 3.4)
        gm = _mask(w, h, lambda d, s: d.polygon([(gx * s, (gy - ry) * s), ((gx + rx) * s, gy * s), (gx * s, (gy + ry) * s), ((gx - rx) * s, gy * s)], fill=255))
        dx, dy = X - gx, Y - gy
        facet = np.where(dx < 0, np.where(dy < 0, 1.3, 0.95), np.where(dy < 0, 1.0, 0.6))
        gc = (HEART if big else AMETHYST) * facet[..., None]
        hi = np.hypot(dx + rx * 0.35, dy + ry * 0.35) < (0.9 if big else 0.7)
        gc = np.where(hi[..., None], PALE, gc)
        ph = i * 0.37
        wl.put(gm, np.clip(gc, 0, 1), light=gm * (0.15 + 0.35 * (0.5 + 0.5 * math.sin(2 * math.pi * (t + ph)))), edge=SILVER_DK,
               glow_col=(HEART if big else MAGENTA) * np.ones_like(wl.col))
    col, lit, solid = wl.col, wl.lit, wl.solid
    if inner:
        col = col * 0.4
        lit = lit * 0
    if glow:
        g = np.zeros((h, w, 4), np.float32)
        hot = np.clip(lit - 0.7, 0, 1)[..., None] * 3
        g[..., :3] = np.clip(wl.glow_col + (PALE - wl.glow_col) * np.clip(hot, 0, 1), 0, 1)
        g[..., 3] = np.clip(lit, 0, 1) * solid
        return g
    return rgba(np.clip(col, 0, 1), solid.astype(np.float32))


def wall_paint(kind, side, glow=False, inner=False):
    memo = {}

    def p(face, w, h, f):
        k = (face, f, w, h)
        if k not in memo:
            img = wall(kind, side, f, w, h, glow, inner)
            if face in ('back', 'left'):
                img = img[:, ::-1]
            memo[k] = img
        return memo[k]
    return p


def finial_paint(seed, glow=False):
    """The corners: a little cluster of three crystals (on crossed cards)."""
    memo = {}

    def p(face, w, h, f):
        k = (face, f, w, h)
        if k not in memo:
            t = f / N
            wl = _Wall(w, h)
            run = np.zeros((h, w), np.float32) + 0.5 + 0.5 * math.sin(2 * math.pi * (t + seed))
            flick = 0.5 + 0.5 * math.sin(2 * math.pi * (t * 2 + seed))
            base = h - 0.5
            for i, (dx, sh, sw, lean) in enumerate(((-0.24, 0.55, 0.3, -0.12), (0.24, 0.6, 0.3, 0.12), (0.0, 0.95, 0.38, 0.0))):
                wl.shard(_shard_poly(w / 2 + dx * w, base, sh * h, sw * w, lean), int(seed * 100) + i, run, flick, cracks=False)
            if glow:
                img = np.zeros((h, w, 4), np.float32)
                img[..., :3] = MAGENTA
                img[..., 3] = np.clip(wl.lit, 0, 1) * wl.solid
            else:
                img = rgba(np.clip(wl.col, 0, 1), wl.solid.astype(np.float32))
            memo[k] = img[:, ::-1] if face in ('back', 'left') else img
        return memo[k]
    return p


# ---------------------------------------------------------------- the orbit: floating shards and a heart

HEART_ART = [
    '..oo...oo..',
    '.ohho.oxxo.',
    'ohhxxoxxxxo',
    'ohxxxxxxxxo',
    'oxxxxxxxxdo',
    '.oxxxxxxdo.',
    '..oxxxxdo..',
    '...oxxdo...',
    '....odo....',
    '.....o.....',
]


def heart_paint(glow=False):
    def p(face, w, h, f):
        t = f / N
        beat = max(0.0, math.sin(2 * math.pi * t * 2)) ** 3        # two beats a loop
        a = np.zeros((11, 11), np.float32)
        col = np.zeros((11, 11, 3), np.float32)
        lit = np.zeros((11, 11), np.float32)
        for yy, row in enumerate(HEART_ART):
            for xx, c in enumerate(row):
                if c == '.':
                    continue
                a[yy + 1, xx] = 1
                col[yy + 1, xx] = {'o': OUTLINE, 'h': HEART_HI, 'x': HEART, 'd': HEART_DK}[c]
                lit[yy + 1, xx] = {'o': 0, 'h': 0.9, 'x': 0.35 + 0.55 * beat, 'd': 0.15}[c]
        if glow:
            img = np.zeros((11, 11, 4), np.float32)
            img[..., :3] = HEART_HI
            img[..., 3] = lit * a
        else:
            img = rgba(col, a)
        if face in ('back', 'left'):
            img = img[:, ::-1]
        return np.asarray(Image.fromarray(to_u8(img), 'RGBA').resize((w, h), Image.NEAREST), np.float32) / 255
    return p


def orbit_shard_paint(seed, glow=False):
    """A floating shard: a long diamond, lit on one side, with a bright core."""
    memo = {}

    def p(face, w, h, f):
        k = (face, f, w, h)
        if k not in memo:
            t = f / N
            X, Y = np.meshgrid((np.arange(w) + 0.5) / w - 0.5, (np.arange(h) + 0.5) / h - 0.5)
            dia = np.abs(X) * 2.3 + np.abs(Y) * 2.05 < 1
            lit_face = X < -Y * 0.15
            v = np.clip(0.5 - Y, 0, 1)
            c = VIOLET + (LILAC - VIOLET) * v[..., None]
            c = c * np.where(lit_face, 1.15, 0.65)[..., None]
            ridge = np.abs(X + Y * 0.15) < 0.05
            c = np.where(ridge[..., None], PALE, c)
            e = dia & ~_erode(dia)
            c = np.where(e[..., None], OUTLINE, c)
            pulse = 0.5 + 0.5 * math.sin(2 * math.pi * (t * 2 + seed))
            core = np.clip(1 - np.hypot(X / 0.12, Y / 0.3), 0, 1)
            if glow:
                img = np.zeros((h, w, 4), np.float32)
                img[..., :3] = MAGENTA + (PALE - MAGENTA) * core[..., None]
                img[..., 3] = (ridge * 0.6 + core * (0.4 + 0.5 * pulse)) * dia * ~e
            else:
                img = rgba(np.clip(c, 0, 1), dia.astype(np.float32))
            memo[k] = img[:, ::-1] if face in ('back', 'left') else img
        return memo[k]
    return p


ORBIT = [  # (angle round the crown, height, width, height of the piece, what)
    (0, -12.2, 1.7, 3.6, 'shard'), (72, -13.4, 1.4, 3.0, 'shard'), (144, -11.6, 2.8, 2.6, 'heart'), (216, -13.6, 1.5, 3.2, 'shard'),
    (288, -12.0, 1.6, 3.4, 'shard')]
ORBIT_R = 8.0


def void_crown():
    cubes = []
    for kind, side, frm, size in (('front', 'front', (-R, YB - H, -R), (2 * R, H, 0)), ('back', 'back', (-R, YB - H, R), (2 * R, H, 0)),
                                  ('side', 'left', (-R, YB - H, -R), (0, H, 2 * R)), ('side', 'right', (R, YB - H, -R), (0, H, 2 * R))):
        cubes.append(Cube(frm, size, wall_paint(kind, side), wall_paint(kind, side, glow=True)))
        ins = 0.28
        f2 = (frm[0] + (ins if size[0] == 0 and frm[0] < 0 else -ins if size[0] == 0 else 0), frm[1],
              frm[2] + (ins if size[2] == 0 and frm[2] < 0 else -ins if size[2] == 0 else 0))
        cubes.append(Cube(f2, size, wall_paint(kind, side, inner=True)))
    for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        x, z = sx * R, sz * R
        ch, cw = 3.6, 3.0
        y0 = YB - 18 / D - ch + 0.5
        cubes += [Cube((x - cw / 2, y0, z), (cw, ch, 0), finial_paint(i * 0.27), finial_paint(i * 0.27, True)),
                  Cube((x, y0, z - cw / 2), (0, ch, cw), finial_paint(i * 0.27 + 0.5), finial_paint(i * 0.27 + 0.5, True))]
    pieces = []
    for i, (ang, y, w, h, what) in enumerate(ORBIT):
        a = math.radians(ang)
        if what == 'heart':
            paint, glow = heart_paint(), heart_paint(True)
        else:
            paint, glow = orbit_shard_paint(i * 0.21), orbit_shard_paint(i * 0.21, True)
        # crossed cards, so the piece reads from every side as it turns
        spin = Bone(f'spin{i + 1}', (0, 0, 0), (0, 0, 0),
                    [Cube((-w / 2, -h / 2, 0), (w, h, 0), paint, glow), Cube((0, -h / 2, -w / 2), (0, h, w), paint, glow)])
        pieces.append(Bone(f'piece{i + 1}', (ORBIT_R * math.cos(a), y, ORBIT_R * math.sin(a)), (0, 90 - ang, 0), [], [spin]))
    orbit = Bone('orbit', (0, 0, 0), (0, 0, 0), [], pieces)
    swarm = Bone('swarm', (0, -12, 0), (0, 0, 0), [], [Bone('swarm_in', (0, 12, 0), (0, 0, 0), [], [orbit])])
    root = Bone('crown', (0, 0, 0), (0, 0, 0), cubes, [swarm])
    return Model('head', [root], density=D, frames=N, frame_time=FRAME_MS, translucent=False, tight=True)


def animations():
    """The pieces circle the crown (a lap in 9 s) the other way to HazeSMP's fish, each bobbing and slowly turning on
    its own axis; the heart turns slower so it faces out more of the time."""
    L = 9.0
    bones = {'orbit': {'rotation': {'0': [0, 0, 0], str(L): [0, -360, 0]}}}
    for i, (_, _, _, _, what) in enumerate(ORBIT):
        ph = i * 0.29
        pos, rot = {}, {}
        for k in range(19):
            t = L * k / 18
            s = math.sin(2 * math.pi * (t / L * 3 + ph))
            pos[f'{t:.3f}'] = key([0, 0.6 * s, 0])
            rot[f'{t:.3f}'] = key([0, 0, 5 * math.cos(2 * math.pi * (t / L * 3 + ph))])
        bones[f'piece{i + 1}'] = {'position': pos, 'rotation': rot}
        turns = 1 if what == 'heart' else 3
        bones[f'spin{i + 1}'] = {'rotation': {'0': [0, 0, 0], str(L): [0, 360 * turns * (1 if i % 2 else -1), 0]}}
    return {'always': clip(L, bones)}


PHYSICS = [
    {'bone': 'swarm', 'stiffness': 45, 'damping': 4.5, 'limit': 22, 'follow': 1, 'move': [0.55, 0, 0], 'turn': [0, 0, 0.35],
     'jump': [0.25, 0, 0], 'air': [0.2, 0, 0]},
]


class VoidCrown:
    """heads.build() calls item['model']().build(out, id): builds the model, then adds the clips and springs."""

    def build(self, out, name):
        model = void_crown().build(out, name)
        model['animations'] = animations()
        model['physics'] = PHYSICS
        with open(os.path.join(out, 'models', f'{name}.json'), 'w') as fh:
            json.dump(model, fh, separators=(',', ':'))
        return model


CROWN = dict(id='head_vexcraft_crown', name='VexCraft Void Crown', collection='vexcraft', rarity='mythic', model=VoidCrown,
             special=True,
             description="VexCraft's partner crown: black glass and silver set with amethysts, a ring of violet crystal with "
                         "VexCraft's V rising at the front, and floating shards and a stolen heart circling it.")
