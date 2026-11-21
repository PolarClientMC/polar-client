"""HazeSMP's headgear: the Tide Crown, an Atlantis crown. A band in the logo's blues between gold trims, set with a
row of pearls and HazeSMP's H; above it scallop shells and rolling waves tipped with pearls, a golden trident rising
from the big shell at the front, shells with pearls on the corners, and a little school of fish circling it (the
same fish as the trail).

    the band       the HAZE letters' blues with lighter squares, gold trims, glowing pearls, the H on the front
    the shells     ribbed scallops in sea-glass blues with gold hinges; light runs round the crown through them
    the waves      rounded breakers rolling in towards the middle of each side, white foam and a pearl on each
    the trident    gold, three barbed prongs, a pearl where they meet
    the fish       five fish circling above, bobbing, tails wagging; they lag and swing when you move or turn
                   (springs), and settle again

Drawn at 8 texels per pixel, 16 frames. Sits outside the head and its hat layer, so it never cuts into it.
"""
import math

import numpy as np
from PIL import Image

from lib import rgb, rgba, Model, Bone, Cube, to_u8
from keyframes import key, clip

N = 16
FRAME_MS = 85
D = 8

OUTLINE = rgb('#1c1240')
NAVY = rgb('#0d1f5c')
DEEP = rgb('#123f9e')
BLUE = rgb('#1f6fe0')
SKY = rgb('#38b6ff')
ICE = rgb('#9fe6ff')
CLOUD = rgb('#eefbff')
CYAN = rgb('#5ee0ff')

R = 4.85                # half the crown's width (the head is 4, its hat layer 4.5)
YB = -6.3               # the crown's bottom edge: it sits over the top of the head (which is at -8)
H = 7.25                # wall height


def _grid(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    return x + 0.5, y + 0.5


def _hash(x, y, seed):
    v = np.sin(x * 12.9898 + y * 78.233 + seed * 37.719) * 43758.5453
    return v - np.floor(v)


def _erode(m):
    e = m.copy()
    e[1:] &= m[:-1]
    e[:-1] &= m[1:]
    e[:, 1:] &= m[:, :-1]
    e[:, :-1] &= m[:, 1:]
    return e


def _up(art, w, h):
    """Art pixels -> the face's texels (nearest, so pixels stay crisp)."""
    return np.asarray(Image.fromarray(to_u8(art), 'RGBA').resize((w, h), Image.NEAREST), np.float32) / 255


PHASE = {'front': 0.0, 'right': 0.25, 'back': 0.5, 'left': 0.75}

# Atlantis gold, from the sand at the bottom of HazeSMP's logo warmed into metal
GOLD_HI = rgb('#fff0b8')
GOLD = rgb('#f2c46a')
GOLD_MID = rgb('#c98e3c')
GOLD_DK = rgb('#7a4a1c')
PEARL = rgb('#f4fbff')
SS = 4                  # shapes are drawn 4x finer and then sampled, so curves stay smooth but crisp


def _h_letter(width):
    """HazeSMP's H from their logo, as pixel art `width` px wide (h x w x 4)."""
    import hazesmp
    full = hazesmp._logo(round(width / 0.27))
    return full[:round(full.shape[0] * 0.43), :round(full.shape[1] * 0.27)]


class _Canvas:
    """Draws shapes (polygons, circles, thick lines) as masks at the face's texel size."""

    def __init__(self, w, h):
        self.w, self.h = w, h

    def mask(self, draw):
        im = Image.new('L', (self.w * SS, self.h * SS), 0)
        from PIL import ImageDraw
        d = ImageDraw.Draw(im)
        draw(d, SS)
        return np.asarray(im.resize((self.w, self.h), Image.BOX), np.float32) / 255 > 0.45


def _wave(d, s, x0, x1, base, h, curl):
    """A rounded wave from x0 to x1 (texels): a soft swell h above base whose top rolls over towards `curl` (+1
    right) in a round curl, like the waves on old maps."""
    pts = []
    n = 32
    for i in range(n + 1):
        u = i / n
        k = u if curl > 0 else 1 - u
        y = base - h * 0.78 * math.sin(math.pi * min(1.0, k / 0.9)) ** 0.55 if k < 0.9 else base - h * 0.78 * math.sin(math.pi * 0.9 / 0.9) ** 0.55
        pts.append(((x0 + (x1 - x0) * u) * s, y * s))
    pts += [(x1 * s, base * s), (x0 * s, base * s)]
    d.polygon(pts, fill=255)
    # the rolling top: a round curl a little past the middle
    r = h * 0.3
    cx = x0 + (x1 - x0) * (0.58 if curl > 0 else 0.42)
    d.ellipse(((cx - r) * s, (base - h + 0.2 * r - r) * s, (cx + r) * s, (base - h + 0.2 * r + r) * s), fill=255)


def _disc(d, s, x, y, r, fill=255):
    d.ellipse(((x - r) * s, (y - r) * s, (x + r) * s, (y + r) * s), fill=fill)


def atlantis_wall(kind, side, f, w, h, glow=False, inner=False):
    """One wall of the crown (h x w x 4 texels). A blue band with gold trims and a row of pearls; above it curling
    waves with foam pearls, a scallop shell (a big one on the front, with a golden trident rising from it and the H
    on the band below)."""
    t = f / N
    cv = _Canvas(w, h)
    X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
    band_h = 18
    bt = h - band_h                                  # the band's top
    cx = w / 2
    col = np.zeros((h, w, 3), np.float32)
    lit = np.zeros((h, w), np.float32)
    solid = np.zeros((h, w), bool)
    u = (X / w + PHASE[side] * 4) / 4                # 0..1 round the crown, for the light that runs round it
    run = np.clip(1 - np.abs(((u - t + 0.5) % 1.0) - 0.5) / 0.08, 0, 1)
    pulse = 0.6 + 0.4 * math.sin(2 * math.pi * t)

    def put(m, c, light=None, outline=True, edge_col=OUTLINE):
        nonlocal col, lit, solid
        if outline:
            e = m & ~_erode(m)
            c = np.where(e[..., None], edge_col, c)
        col = np.where(m[..., None], c, col)
        lit = np.where(m, 0 if light is None else light, lit)
        solid |= m

    # ---- waves along the wall, curling in towards the middle
    big = kind == 'front'
    wave_h = {'front': 13, 'back': 12, 'side': 11}[kind]
    gap = 16 if big else 13
    for (x0, x1, curl) in ((1.0, cx - gap, 1), (w - 1 - (cx - gap) + 1.0, w - 1.0, -1)):
        crest_x = x0 + (x1 - x0) * (0.58 if curl > 0 else 0.42)
        # a smaller wave behind first, then the big one
        x2a, x2b = (x0 + (x1 - x0) * 0.35, x1 + 3) if curl > 0 else (x0 - 3, x1 - (x1 - x0) * 0.35)
        m2 = cv.mask(lambda d, s: _wave(d, s, x2a, x2b, bt + 1, wave_h * 0.6, curl))
        v2 = np.clip((bt - Y) / (wave_h * 0.6), 0, 1)
        put(m2, BLUE + (SKY - BLUE) * v2[..., None] * 0.7, edge_col=NAVY)
        m = cv.mask(lambda d, s: _wave(d, s, x0, x1, bt + 1, wave_h, curl))
        v = np.clip((bt - Y) / wave_h, 0, 1)
        c = DEEP + (SKY - DEEP) * v[..., None]
        inner_m = _erode(_erode(_erode(m)))
        foam = m & ~inner_m & (v > 0.35)
        c = np.where(foam[..., None], CLOUD, c)
        rr = np.hypot(X - crest_x, Y - (bt - wave_h + wave_h * 0.06))
        swirl = m & (np.abs(rr - wave_h * 0.17) < 0.75) & (Y > bt - wave_h + wave_h * 0.06 - 0.3)
        c = np.where(swirl[..., None], DEEP, c)
        line = m & (np.abs(((X - x0) * 0.5 + Y) % 7 - 3.5) < 0.6) & ~foam & (v > 0.15) & ~swirl
        c = np.where(line[..., None], c * 0.55 + ICE * 0.45, c)
        put(m, c, light=np.where(foam, 0.2 + 0.55 * run, 0.0), edge_col=NAVY)
        # a pearl of foam where it breaks
        pm = cv.mask(lambda d, s: _disc(d, s, crest_x, bt - wave_h - 0.8, 2.3))
        pc = PEARL * (0.75 + 0.25 * np.clip(1 - np.hypot(X - crest_x + 0.8, Y - (bt - wave_h - 1.6)) / 1.8, 0, 1))[..., None]
        put(pm, pc * np.ones_like(col), light=np.where(pm, 0.45 + 0.35 * pulse, 0), edge_col=NAVY)
    # ---- the shell (a scallop) in the middle
    sr = {'front': 25, 'back': 19, 'side': 18}[kind]
    scy = bt + 2
    ribs = 9 if big else 7
    ang = np.arctan2(scy - Y, X - cx)                      # 0 right .. pi left (upper half)
    rad = np.hypot(X - cx, Y - scy)
    rib = np.clip(ang / math.pi, 0, 1) * ribs
    edge_r = sr - 2.2 * np.abs(np.sin(rib * math.pi))      # scalloped rim
    shell = (rad < edge_r) & (Y < scy) & (rad > 2.5)
    groove = np.abs((rib % 1.0) - 0.5) > 0.42
    c = np.where((np.floor(rib) % 2 == 0)[..., None], ICE * 0.7 + SKY * 0.3, SKY * 0.7 + BLUE * 0.3)
    c = c * (0.75 + 0.25 * np.clip(rad / sr, 0, 1))[..., None]
    c = np.where((groove & shell)[..., None], BLUE * 0.8, c)
    c = np.where((shell & (rad > edge_r - 2.5))[..., None], c * 0.6 + CLOUD * 0.4, c)
    put(shell, c, light=np.where(shell & ~groove, 0.12 + 0.45 * np.clip(1 - np.abs(((u - t + 0.5) % 1.0) - 0.5) / 0.12, 0, 1), 0))
    # the hinge of the shell, gold
    hinge = cv.mask(lambda d, s: d.polygon([((cx - 6) * s, (bt + 1) * s), ((cx + 6) * s, (bt + 1) * s), ((cx + 3) * s, (bt - 4) * s), ((cx - 3) * s, (bt - 4) * s)], fill=255))
    put(hinge, GOLD_MID + (GOLD - GOLD_MID) * np.clip((bt - Y) / 5, 0, 1)[..., None], edge_col=GOLD_DK)
    if kind != 'front':
        # a pearl resting on top of the shell
        pm = cv.mask(lambda d, s: _disc(d, s, cx, scy - sr - 1.2, 3.0))
        pc = PEARL * (0.78 + 0.22 * np.clip(1 - np.hypot(X - cx + 1, Y - (scy - sr - 2.2)) / 2.5, 0, 1))[..., None]
        put(pm, pc * np.ones_like(col), light=np.where(pm, 0.4 + 0.4 * pulse, 0))
    # ---- the trident (front): a gold shaft from the shell, three barbed prongs
    if big:
        top = 2

        def trident(d, s):
            fork = bt - 21
            d.rectangle(((cx - 1.6) * s, fork * s, (cx + 1.6) * s, (bt - 2) * s), fill=255)                   # the shaft
            # the fork: a curved bar turning up into the side prongs
            d.line([((cx - 9) * s, (top + 9) * s), ((cx - 9) * s, (fork - 2) * s), ((cx - 6) * s, (fork + 1.5) * s),
                    ((cx + 6) * s, (fork + 1.5) * s), ((cx + 9) * s, (fork - 2) * s), ((cx + 9) * s, (top + 9) * s)],
                   fill=255, width=int(3.2 * s), joint='curve')
            d.rectangle(((cx - 1.6) * s, (top + 5) * s, (cx + 1.6) * s, fork * s), fill=255)                  # the middle prong
            for px, tip in ((cx, top), (cx - 9, top + 5), (cx + 9, top + 5)):                               # barbed heads
                d.polygon([((px - 3.6) * s, (tip + 7) * s), (px * s, tip * s), ((px + 3.6) * s, (tip + 7) * s),
                           ((px + 1.6) * s, (tip + 5.6) * s), ((px - 1.6) * s, (tip + 5.6) * s)], fill=255)
            d.ellipse(((cx - 3.2) * s, (fork - 1) * s, (cx + 3.2) * s, (fork + 5.4) * s), fill=255)        # a boss where they meet
        cross = bt - 21 + 1.6
        tm = cv.mask(trident)
        gx = (X - cx) / 12
        c = GOLD_MID + (GOLD - GOLD_MID) * np.clip(0.6 - gx * 0.8, 0, 1)[..., None]
        hi = tm & ~_erode(_erode(tm)) & (X < cx + 0.5)
        c = np.where(hi[..., None], GOLD_HI, c)
        put(tm, c, light=np.where(tm, 0.18 + 0.5 * run, 0), edge_col=GOLD_DK)
        # a pearl in the boss
        pm = cv.mask(lambda d, s: _disc(d, s, cx, cross + 0.8, 1.8))
        put(pm, PEARL * np.ones_like(col), light=np.where(pm, 0.6 + 0.3 * pulse, 0), outline=False)
    # ---- the band: blue with lighter squares, gold trims, a row of pearls
    band = Y >= bt
    vb = (Y - bt) / band_h
    bc = DEEP + (BLUE - DEEP) * np.clip(1.1 - vb * 1.3, 0, 1)[..., None]
    sq = band & (_hash(np.floor(X / 2), np.floor(Y / 2), 5) > 0.8) & (vb > 0.25) & (vb < 0.78)
    bc = np.where(sq[..., None], bc * 0.55 + ICE * 0.45, bc)
    trim_t = band & (Y < bt + 3)
    trim_b = band & (Y >= h - 4)
    bc = np.where(trim_t[..., None], np.where((Y < bt + 1)[..., None], GOLD_HI, GOLD), bc)
    bc = np.where(trim_b[..., None], np.where((Y >= h - 1)[..., None], GOLD_DK, GOLD_MID), bc)
    put(band, bc, outline=False)
    lit = np.where(trim_t, 0.12 + 0.45 * run, lit)
    # pearls along the band
    spacing = 9.6
    for i in range(int(w / spacing) + 1):
        px = (i + 0.5) * spacing
        if big and abs(px - cx) < 9:
            continue
        pm = cv.mask(lambda d, s: _disc(d, s, px, bt + 9.5, 2.1))
        pc = PEARL * (0.72 + 0.28 * np.clip(1 - np.hypot(X - px + 0.8, Y - (bt + 8.7)) / 1.8, 0, 1))[..., None]
        put(pm, pc * np.ones_like(col), light=np.where(pm, 0.3 + 0.35 * pulse, 0), edge_col=NAVY)
    # HazeSMP's H on the front of the band
    if big and not inner:
        hl = _h_letter(15)
        hh, hw = hl.shape[:2]
        x0, y0 = int(round(cx - hw / 2)), bt + 3 + (band_h - 7 - hh) // 2
        a = hl[..., 3] > 0.5
        sub = (slice(y0, y0 + hh), slice(x0, x0 + hw))
        col[sub] = np.where(a[..., None], hl[..., :3], col[sub])
        lit[sub] = np.where(a, 0.25 + 0.3 * pulse, lit[sub])
    if inner:
        col = col * 0.42
        lit[:] = 0
    if glow:
        g = np.zeros((h, w, 4), np.float32)
        g[..., :3] = np.where((lit > 0.5)[..., None], CLOUD, np.clip(col * 0.6 + CYAN * 0.4, 0, 1))
        g[..., 3] = lit * solid
        return g
    return rgba(np.clip(col, 0, 1), solid.astype(np.float32))


def wall_paint(kind, side, glow=False, inner=False):
    memo = {}

    def p(face, w, h, f):
        if (face, f, w, h) not in memo:
            img = atlantis_wall(kind, side, f, w, h, glow, inner)
            if face in ('back', 'left'):
                img = img[:, ::-1]
            memo[(face, f, w, h)] = img
        return memo[(face, f, w, h)]
    return p


def finial_paint(seed, glow=False):
    """The corners: a small gold-rimmed shell with a pearl on top (on crossed cards)."""
    def p(face, w, h, f):
        t = f / N
        cv = _Canvas(w, h)
        X, Y = np.meshgrid(np.arange(w) + 0.5, np.arange(h) + 0.5)
        cx, base = w / 2, h - 1
        sr = w * 0.42
        ang = np.arctan2(base - Y, X - cx)
        rad = np.hypot(X - cx, Y - base)
        rib = np.clip(ang / math.pi, 0, 1) * 5
        edge_r = sr - 1.6 * np.abs(np.sin(rib * math.pi))
        shell = (rad < edge_r) & (Y < base)
        groove = np.abs((rib % 1.0) - 0.5) > 0.4
        c = np.where((np.floor(rib) % 2 == 0)[..., None], ICE * 0.7 + SKY * 0.3, SKY * 0.7 + BLUE * 0.3)
        c = np.where((groove & shell)[..., None], BLUE * 0.8, c)
        e = shell & ~_erode(shell)
        c = np.where(e[..., None], GOLD_DK, c)
        pm = cv.mask(lambda d, s: _disc(d, s, cx, base - sr - 2.2, 2.6))
        pc = PEARL * (0.75 + 0.25 * np.clip(1 - np.hypot(X - cx + 0.8, Y - (base - sr - 3)) / 2.2, 0, 1))[..., None]
        pe = pm & ~_erode(pm)
        col = np.where(pm[..., None], np.where(pe[..., None], NAVY, pc), c)
        solid = shell | pm
        lit = np.where(pm & ~pe, 0.45 + 0.35 * (0.5 + 0.5 * math.sin(2 * math.pi * (t + seed))), np.where(shell & ~groove & ~e, 0.12, 0))
        if glow:
            g = np.zeros((h, w, 4), np.float32)
            g[..., :3] = np.where((lit > 0.4)[..., None], CLOUD, CYAN)
            g[..., 3] = lit * solid
            img = g
        else:
            img = rgba(col, solid.astype(np.float32))
        return img[:, ::-1] if face in ('back', 'left') else img
    return p


FISH = [
    '................',
    '................',
    '................',
    '........ooo.....',
    '......oolllo....',
    'o....olllllloo..',
    'oo..ollllllllloo',
    'olooollllllllewo',
    'olloddlllllllllo',
    'oo..oddddddddoo.',
    'o....ooddddoo...',
    '.......oo.oo....',
    '................',
    '................',
    '................',
    '................',
]
FISH_COL = {'o': OUTLINE, 'l': SKY * 0.5 + ICE * 0.5, 'd': BLUE, 'e': OUTLINE, 'w': CLOUD}


def fish_paint(colour, phase, glow=False):
    """A little fish in HazeSMP's colours, its tail wagging over the frames; it faces +x on the front face."""
    def p(face, w, h, f):
        a = np.zeros((16, 16), np.float32)
        col = np.zeros((16, 16, 3), np.float32)
        for yy, row in enumerate(FISH):
            for xx, c in enumerate(row):
                if c != '.':
                    a[yy, xx] = 1
                    col[yy, xx] = FISH_COL[c] if c not in 'l' else colour
        wag = round(math.sin(2 * math.pi * (f / N * 2 + phase)))
        if wag:
            a[:, :4] = np.roll(a[:, :4], wag, 0)
            col[:, :4] = np.roll(col[:, :4], wag, 0)
        art = rgba(col, a)
        if glow:
            g = np.zeros_like(art)
            g[..., :3] = CLOUD
            g[..., 3] = (np.array([[c == 'w' for c in row] for row in FISH]) * 0.9).astype(np.float32)
            art = g
        if face == 'back':
            art = art[:, ::-1]
        return _up(art, w, h)
    return p


FISH_ORBIT = [  # (angle round the crown, height, size, colour)
    (0, -11.2, 2.8, SKY), (72, -12.6, 2.5, ICE), (144, -10.8, 2.9, CYAN), (216, -13.0, 2.6, SKY), (288, -11.8, 2.7, ICE * 0.6 + BLUE * 0.4)]
ORBIT_R = 8.0


def tide_crown():
    cubes = []
    for kind, side, frm, size in (('front', 'front', (-R, YB - H, -R), (2 * R, H, 0)), ('back', 'back', (-R, YB - H, R), (2 * R, H, 0)),
                                  ('side', 'left', (-R, YB - H, -R), (0, H, 2 * R)), ('side', 'right', (R, YB - H, -R), (0, H, 2 * R))):
        cubes.append(Cube(frm, size, wall_paint(kind, side), wall_paint(kind, side, glow=True)))
        # the inside, a little in (just outside the hat layer), darker
        ins = 0.28
        f2 = (frm[0] + (ins if size[0] == 0 and frm[0] < 0 else -ins if size[0] == 0 else 0), frm[1],
              frm[2] + (ins if size[2] == 0 and frm[2] < 0 else -ins if size[2] == 0 else 0))
        cubes.append(Cube(f2, size, wall_paint(kind, side, inner=True)))
    # shells with a pearl on the corners (crossed cards)
    for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        x, z = sx * R, sz * R
        ch, cw = 3.4, 3.2
        y0 = YB - 18 / D - ch + 0.4
        cubes += [Cube((x - cw / 2, y0, z), (cw, ch, 0), finial_paint(i * 0.27), finial_paint(i * 0.27, True)),
                  Cube((x, y0, z - cw / 2), (0, ch, cw), finial_paint(i * 0.27 + 0.5), finial_paint(i * 0.27 + 0.5, True))]
    # the fish, each on its own bone round the orbit (turned to swim round it)
    fish = []
    for i, (ang, y, s, colour) in enumerate(FISH_ORBIT):
        a = math.radians(ang)
        fb = Bone(f'fish{i + 1}', (ORBIT_R * math.cos(a), y, ORBIT_R * math.sin(a)), (0, 90 - ang, 0),
                  [Cube((-s / 2, -s / 2, 0), (s, s, 0), fish_paint(colour, i * 0.23), fish_paint(colour, i * 0.23, True))])
        fish.append(fb)
    orbit = Bone('orbit', (0, 0, 0), (0, 0, 0), [], fish)       # turns (the clip)
    school = Bone('school', (0, -12, 0), (0, 0, 0), [], [Bone('school_in', (0, 12, 0), (0, 0, 0), [], [orbit])])   # tilts (the spring)
    root = Bone('crown', (0, 0, 0), (0, 0, 0), cubes, [school])
    return Model('head', [root], density=D, frames=N, frame_time=FRAME_MS, translucent=False, tight=True)


def animations():
    """The fish swim round (a full lap in 8 s), each bobbing and rolling a little on its own. Plus a spring: the
    school lags and swings when you move or turn, and settles again."""
    L = 8.0
    orbit = {'rotation': {'0': [0, 0, 0], str(L): [0, 360, 0]}}
    bones = {'orbit': orbit}
    for i in range(len(FISH_ORBIT)):
        ph = i * 0.37
        pos, rot = {}, {}
        for k in range(17):
            t = L * k / 16
            s = math.sin(2 * math.pi * (t / L * 4 + ph))
            pos[f'{t:.3f}'] = key([0, 0.55 * s, 0])
            rot[f'{t:.3f}'] = key([4 * math.cos(2 * math.pi * (t / L * 4 + ph)), 0, -6 * s])
        bones[f'fish{i + 1}'] = {'position': pos, 'rotation': rot}
    return {'always': clip(L, bones)}


PHYSICS = [
    {'bone': 'school', 'stiffness': 45, 'damping': 4.5, 'limit': 22, 'follow': 1, 'move': [0.55, 0, 0], 'turn': [0, 0, 0.35],
     'jump': [0.25, 0, 0], 'air': [0.2, 0, 0]},
]


class TideCrown:
    """heads.build() calls item['model']().build(out, id): builds the model, then adds the clips and springs."""

    def build(self, out, name):
        import json
        import os
        model = tide_crown().build(out, name)
        model['animations'] = animations()
        model['physics'] = PHYSICS
        with open(os.path.join(out, 'models', f'{name}.json'), 'w') as fh:
            json.dump(model, fh, separators=(',', ':'))
        return model


CROWN = dict(id='head_hazesmp_crown', name='HazeSMP Tide Crown', collection='hazesmp', rarity='mythic', model=TideCrown,
             special=True,
             description="HazeSMP's partner crown, made for Atlantis: gold, pearls and their H on a band of blue, shells and "
                         'rolling waves, a golden trident at the front and a school of little fish circling it.')
