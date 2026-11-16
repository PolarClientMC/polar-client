"""Revon's set: a code-only collection in black and violet with a four-point star, purple fire and drifting pixels.

    Revon's Cape   (with its own elytra)       capes.ITEMS
    Revon's Crown                              heads.ITEMS
    Revon's Wings                              wings.ITEMS
    Revon's Trail                              auras.ITEMS

Painted as pixel art: every colour comes from one violet ramp, so the pieces match each other and read crisply in game.
Importing this module adds the items to their kinds (build.py does that).
"""
import math

import numpy as np
from PIL import Image

from lib import rgb, rgba, smoothstep, blur, over, add_light, Model, Bone, Cube, to_u8

COLLECTION = {'id': 'revon', 'name': "Revon"}

# the one ramp everything is painted from, darkest to brightest
RAMP = ['#06030b', '#100818', '#1a0c28', '#25103a', '#361455', '#4d1886', '#6a1fc4', '#8a2cff', '#a752ff',
        '#c47dff', '#dfa9ff', '#f2d8ff', '#ffffff']
_RAMP = np.stack([rgb(c) for c in RAMP])
STAR = '#f6e6ff'


def ramp(v):
    """0..1 -> the nearest ramp colour (HxWx3), banded like hand-placed pixels."""
    i = np.clip(np.round(np.asarray(v, np.float32) * (len(RAMP) - 1)), 0, len(RAMP) - 1).astype(int)
    return _RAMP[i]


def grid(W, H):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    return x + 0.5, y + 0.5


def hashn(x, y, seed=0):
    """A per-pixel random value 0..1 (stable)."""
    h = np.sin(x * 12.9898 + y * 78.233 + seed * 37.719) * 43758.5453
    return h - np.floor(h)


def star_field(X, Y, cx, cy, a, b, p=0.55):
    """The four-point star: < 1 inside. a, b: half width and half height in pixels."""
    return (np.abs(X - cx) / a) ** p + (np.abs(Y - cy) / b) ** p


def _erode(m):
    e = m.copy()
    e[1:, :] &= m[:-1, :]
    e[:-1, :] &= m[1:, :]
    e[:, 1:] &= m[:, :-1]
    e[:, :-1] &= m[:, 1:]
    return e


def paint_star(img, X, Y, cx, cy, a, b, glow=1.0, core=True, halo=True, gem=False):
    """The Revon star as pixel art: a pale outline, a dark inside with a violet inner star, a white centre, and a
    banded violet halo (glow 0..1 breathes it). gem=True fills it instead, violet brightening to white at the centre
    (reads better at small sizes)."""
    col = img[..., :3]
    P = 0.62
    f = star_field(X, Y, cx, cy, a, b, P)
    body = f <= 1
    if halo:
        h = np.clip((1.75 - f) / 0.75, 0, 1) * glow     # a star-shaped glow round the outline
        band = np.floor(h * 3) / 3                      # banded, like hand-placed pixels
        col[:] = np.where(((band > 0) & ~body)[..., None], np.clip(col + rgb('#6a1fc4') * band[..., None] * 0.75, 0, 1), col)
    if gem:
        lv = np.floor((1 - np.clip(f, 0, 1)) * 4) / 4      # four flat steps of colour, like hand-placed pixels
        col[:] = np.where(body[..., None], ramp(0.5 + 0.42 * lv), col)
        rim = body & ~_erode(body)
        col[:] = np.where(rim[..., None], rgb(STAR), col)
        if core:
            col[:] = np.where(((np.abs(X - cx) <= 1.0) & (np.abs(Y - cy) <= 1.0))[..., None], rgb('#ffffff'), col)
        return img
    col[:] = np.where(body[..., None], ramp(0.24), col)
    inner = star_field(X, Y, cx, cy, a * 0.56, b * 0.56, P) <= 1
    col[:] = np.where((inner & ~_erode(inner))[..., None], ramp(0.7 + 0.2 * glow), col)
    col[:] = np.where(_erode(inner)[..., None], ramp(0.36), col)
    rim = body & ~_erode(body)
    col[:] = np.where(rim[..., None], rgb(STAR), col)
    if core:
        dot = (np.abs(X - cx) <= 1.0) & (np.abs(Y - cy) <= 1.0)
        col[:] = np.where(dot[..., None], rgb('#ffffff'), col)
    return img


def tongues(e, y, scroll, period, reach, seed=0, lean=0.0):
    """Flame tongues growing from an edge: e = distance from the edge (px), y = along the edge (px, up is -y).
    Returns heat 0..1 (1 at the hottest core). Tongues rise by `scroll` px; widest at their base, pointed at the top."""
    s = ((y + scroll) / period) % 1.0                   # 0 at a tongue's tip, 1 at its base
    k = np.floor((y + scroll) / period)
    jitter = 0.55 + 0.45 * hashn(k, 3.0, seed)          # each tongue its own size
    width = reach * jitter * np.clip(s, 0, 1) ** 0.75
    e2 = e - lean * (1 - s) * reach
    heat = np.clip(1 - e2 / np.maximum(width, 1e-3), 0, 1) * (width > 0.6)
    return heat


# ================================================================ the cape and its elytra

def cape(W, H, f, N):
    """The outside of Revon's Cape (W x H texels), frame f of N."""
    X, Y = grid(W, H)
    u, v = X / W, Y / H
    t = f / N
    # black-violet cloth with a faint chevron weave
    chev = ((Y - np.abs(X - W / 2) * 0.9) % 10 < 2).astype(np.float32)
    base = 0.16 + 0.05 * chev + 0.04 * (1 - v)
    img = rgba(ramp(base))
    col = img[..., :3]

    # the star, breathing
    glow = 0.55 + 0.45 * (0.5 - 0.5 * math.cos(2 * math.pi * t))
    paint_star(img, X, Y, W * 0.5 - 0.5, H * 0.4, W * 0.27, H * 0.2, glow)

    # side flames: two rows of tongues licking up and inwards from both edges, rising
    period = H * 0.15
    scroll = t * period * 2
    reach = 3.0 + (W * 0.34) * smoothstep(0.2, 1.0, v)
    heat = np.zeros_like(X)
    for (pp, off, rr, sd) in ((period, 0.0, 1.0, 1), (period * 0.62, 0.31, 0.62, 7)):
        sc = t * pp * 3
        heat = np.maximum(heat, tongues(X, Y + off * pp, sc, pp, reach * rr, sd, lean=0.35))
        heat = np.maximum(heat, tongues(W - X, Y + (off + 0.5) * pp, sc, pp, reach * rr, sd + 1, lean=0.35))
    # bottom: a row of tongues rising from the jagged hem
    hem_tooth = np.abs(((X + 0.5) % 8) - 4) / 4               # 0 at a tooth's point, 1 between teeth
    hem = H - 2 - (1 - hem_tooth) * 5                           # the hem dips to a point under each tooth
    rise = tongues(hem - Y, X * 0.9, 0, 4, 9 + 4 * hashn(np.floor(X / 4), 1.0, 5), 3) * (Y < hem)
    flick = 0.75 + 0.25 * np.cos(2 * math.pi * (t * 2 + np.floor(X / 4) * 0.37))
    heat = np.maximum(heat, rise * flick)
    fire = heat > 0.05
    col[:] = np.where(fire[..., None], ramp(0.47 + 0.42 * heat ** 1.8 + 0.06 * (heat > 0.92)), col)

    # the collar: a violet V across the shoulders with a jagged lower edge
    vy = 2 + (1 - np.abs(2 * u - 1)) * H * 0.14
    teeth = (np.abs(((X + 1) % 6) - 3) / 3) * 2.2
    band = (Y >= vy - 3.2) & (Y <= vy + 0.8 + teeth)
    shade = np.clip((Y - (vy - 3.2)) / 6, 0, 1)
    col[:] = np.where(band[..., None], ramp(0.86 - 0.36 * shade), col)
    top = Y < 2
    col[:] = np.where(top[..., None], ramp(0.62), col)

    # below the hem: the black outline of the cut
    cut = Y >= hem + 0.5
    col[:] = np.where(cut[..., None], rgb(RAMP[0]), col)
    edge_line = (Y >= hem - 0.5) & (Y < hem + 0.5)
    col[:] = np.where(edge_line[..., None] & ~cut[..., None], ramp(0.72), col)

    # drifting pixel sparks that twinkle on and off
    for i in range(9):
        sx = int(hashn(i, 1.0, 9) * (W - 6)) + 3
        sy0 = hashn(i, 2.0, 9) * H
        sy = int((sy0 - t * H * 0.5 * (0.5 + hashn(i, 3.0, 9))) % H)
        on = 0.5 + 0.5 * math.cos(2 * math.pi * (t * 2 + hashn(i, 4.0, 9)))
        if on > 0.35 and 0 <= sy < H - 6:
            col[sy, sx] = rgb('#f2d8ff') if on > 0.75 else rgb('#a752ff')
    # a 1-texel dark frame so the cape reads as one shape
    col[0, :] = col[-1, :] = col[:, 0] = col[:, -1] = rgb(RAMP[1])
    return img


def elytra(W, H, f, N):
    """One elytra wing (the back of the wing box, as Minecraft lays it out: W x H texels, the shoulder at the top,
    the straight inner edge on the right). Transparent outside its shape."""
    X, Y = grid(W, H)
    u, v = X / W, Y / H
    t = f / N
    # the silhouette: full width at the shoulder, tapering on the outer (left) edge, a pointed tip at the bottom
    left = 1.5 + W * 0.38 * v ** 1.15
    right = W - 0.5 - W * 0.42 * smoothstep(0.72, 1.0, v) ** 1.4
    saw = (np.abs(((Y + 0.5) % 7) - 3.5) / 3.5) * 3.2       # feather tips along the outer edge
    inside = (X >= left - saw) & (X <= right) & (Y < H - 1)
    # long feathers sweeping down and out from the inner edge: dark, with a violet lower edge and bright tips
    band = (Y * 0.8 + (W - X) * 0.6) / 10.0
    k, s = np.floor(band), band % 1.0
    out = np.clip(1 - (X - (left - saw)) / (W * 0.55), 0, 1)        # 1 at the outer edge
    tone = 0.14 + 0.06 * hashn(k, 1.0, 2) + 0.5 * smoothstep(0.62, 1.0, s) * (0.35 + 0.65 * out) + 0.18 * out ** 2
    img = rgba(ramp(tone), inside.astype(np.float32))
    col = img[..., :3]
    seam = (s < 0.1) & inside
    col[:] = np.where(seam[..., None], ramp(0.06), col)
    # the outer feather tips glow, and a thin violet line runs down the inner edge
    tip = inside & (X < left - saw + 3.0)
    col[:] = np.where(tip[..., None], ramp(0.72 + 0.16 * ((left - saw + 3.0 - X) / 3.0)), col)
    spine = inside & (X > right - 1.2)
    col[:] = np.where(spine[..., None], ramp(0.6), col)
    # purple fire running up the outer edge
    period = H * 0.14
    heat = tongues(X - (left - saw), Y, t * period * 2, period, 3 + W * 0.2 * v, 4, lean=-0.2) * inside
    col[:] = np.where((heat > 0.05)[..., None], ramp(0.5 + 0.48 * heat), col)
    # the star near the shoulder. Closed, each wing is in front only on its own side (the left part of this art; the
    # other wing covers the rest), so the star sits there to be seen whole.
    glow = 0.55 + 0.45 * (0.5 - 0.5 * math.cos(2 * math.pi * t))
    star = rgba(col.copy(), 1)
    paint_star(star, X, Y, W * 0.3, H * 0.19, W * 0.16, H * 0.095, glow, gem=True)
    col[:] = np.where(inside[..., None], star[..., :3], col)
    # a dark outline round the whole shape
    m = inside.astype(np.float32)
    outline = (blur(m, 0.7) < 0.92) & inside
    col[:] = np.where(outline[..., None], ramp(0.04), col)
    return img


# ================================================================ the crown

CROWN_N = 16
AW, AH = 36, 26          # the art of one crown wall, in art pixels (2 texels each)


def _wall_profile(kind):
    """Height of the wall (art px above the band) for each of the AW columns."""
    if kind == 'front':
        # side towers, steps, and the tall centre spire
        prof = [2, 2, 4, 4, 8, 8, 8, 8, 5, 5, 3, 3, 6, 6, 8, 11, 13, 15, 15, 13, 11, 8, 6, 6, 3, 3, 5, 5, 8, 8, 8, 8, 4, 4, 2, 2]
    elif kind == 'back':
        prof = [2, 2, 4, 4, 7, 7, 7, 7, 4, 4, 3, 3, 5, 5, 7, 9, 11, 12, 12, 11, 9, 7, 5, 5, 3, 3, 4, 4, 7, 7, 7, 7, 4, 4, 2, 2]
    else:
        prof = [2, 2, 3, 3, 5, 5, 5, 5, 3, 3, 2, 2, 4, 4, 6, 8, 10, 10, 10, 10, 8, 6, 4, 4, 2, 2, 3, 3, 5, 5, 5, 5, 3, 3, 2, 2]
    return np.array(prof, np.float32)


def crown_wall(kind, f, glow=False, inner=False):
    """One wall of the crown as pixel art (AH x AW x 4): obsidian battlements with a violet outline over a band that
    lightens to a lavender rim; the front carries the star and a gem on its spire."""
    X, Y = grid(AW, AH)
    t = f / CROWN_N
    pulse = 0.55 + 0.45 * (0.5 - 0.5 * math.cos(2 * math.pi * t))
    band_top = AH - 9
    prof = _wall_profile(kind)
    col_i = np.clip(X.astype(int), 0, AW - 1)
    top = band_top - prof[col_i]
    solid = Y >= top
    out = np.zeros((AH, AW, 4), np.float32)
    # outline: the top pixel of each column and the step sides
    left_h = np.concatenate([[0], prof[:-1]])[col_i]
    right_h = np.concatenate([prof[1:], [0]])[col_i]
    step_side = ((Y < band_top - np.minimum(left_h, prof[col_i]) + 0) & (Y >= top)) & (left_h < prof[col_i])
    step_side |= ((Y < band_top - np.minimum(right_h, prof[col_i])) & (Y >= top)) & (right_h < prof[col_i])
    rim_top = solid & (Y < top + 1)
    edge = (rim_top | step_side) & solid
    hgt = np.clip((band_top - Y) / 15.0, 0, 1)                 # 0 at the band, 1 at the spire's top
    body = 0.24 + 0.1 * hgt + 0.05 * hashn(np.floor(X / 2), np.floor(Y / 2), 3) + 0.06 * ((Y - top) < 2)
    v_band = (Y - band_top) / 9.0
    band = Y >= band_top
    dither = 0.04 * ((np.floor(X) + np.floor(Y)) % 2)
    tone = np.where(band, 0.22 + 0.5 * np.clip(v_band, 0, 1) ** 1.3 + dither, body)
    tone = np.where(band & (Y >= AH - 2), 0.8, tone)
    tone = np.where(band & (Y >= AH - 3) & (Y < AH - 2), 0.66, tone)
    tone = np.where(band & (Y < band_top + 1), 0.5, tone)
    tone = np.where(edge, 0.62 + 0.12 * pulse, tone)
    if inner:
        tone = np.where(solid, np.where(edge, 0.3, np.minimum(tone, 0.2) * 0.8), tone)
    rgbv = ramp(tone)
    lit = np.zeros((AH, AW), np.float32)
    lit = np.where(edge, 0.4 * pulse, lit)
    lit = np.where(band & (Y >= AH - 2), 0.15 + 0.12 * pulse, lit)
    if not inner:
        if kind == 'front':
            img = rgba(rgbv.copy(), solid.astype(np.float32))
            SA, SB, SY = 7.0, 9.0, band_top - 1.0
            paint_star(img, X, Y, AW / 2, SY, SA, SB, pulse, gem=True)
            star_m = star_field(X, Y, AW / 2, SY, SA * 1.75, SB * 1.75, 0.62) <= 1
            rgbv = np.where((star_m & solid)[..., None], img[..., :3], rgbv)
            body_s = star_field(X, Y, AW / 2, SY, SA, SB, 0.62) <= 1
            rim_s = body_s & ~_erode(body_s)
            fs = star_field(X, Y, AW / 2, SY, SA, SB, 0.62)
            centre = (np.abs(X - AW / 2) <= 1.0) & (np.abs(Y - SY) <= 1.0)
            lit = np.where(star_m & solid & ~body_s, 0.3 * pulse, lit)
            lit = np.where(body_s, (0.12 + 0.4 * np.floor((1 - np.clip(fs, 0, 1)) * 4) / 4) * pulse, lit)
            lit = np.where(rim_s, 0.5, lit)
            lit = np.where(centre, 0.8, lit)
        # gems on the spire and the side towers
        gems = []
        if kind == 'front':
            gems = [(AW / 2, band_top - 15 - 2.4, 2.4, 3.2), (6.0, band_top - 8 - 1.8, 1.6, 2.2), (AW - 6.0, band_top - 8 - 1.8, 1.6, 2.2)]
        elif kind == 'back':
            gems = [(AW / 2, band_top - 12 - 2.0, 2.0, 2.6)]
        else:
            gems = [(AW / 2, band_top - 10 - 1.8, 1.8, 2.4)]
        for gx, gy, ga, gb in gems:
            d = np.abs(X - gx) / ga + np.abs(Y - gy) / gb
            gm = d <= 1
            g_tone = np.where(X < gx, 0.92, 0.78) * np.ones_like(X)
            g_tone = np.where(d > 0.7, 0.66, g_tone)
            g_tone = np.where((np.abs(X - gx + 0.6) < 0.6) & (np.abs(Y - gy + gb * 0.35) < 0.6), 1.0, g_tone)
            rgbv = np.where(gm[..., None], ramp(g_tone), rgbv)
            solid = solid | gm
            lit = np.where(gm, 0.75 + 0.25 * pulse, lit)
    out[..., :3] = rgbv
    out[..., 3] = solid.astype(np.float32)
    if inner:
        lit[:] = 0                                              # the inside stays dark: no glowing outlines in there
    if glow:
        g = np.zeros((AH, AW, 4), np.float32)
        g[..., :3] = rgbv
        g[..., 3] = lit * solid
        return g
    return out


def _up(art, w, h):
    """Art pixels -> the card's texels (nearest, so pixels stay crisp)."""
    return np.asarray(Image.fromarray(to_u8(art), 'RGBA').resize((w, h), Image.NEAREST), np.float32) / 255


def wall_paint(kind, glow=False, inner=False):
    def p(face, w, h, f):
        art = crown_wall(kind, f, glow, inner)
        if face == 'back':
            art = art[:, ::-1]
        return _up(art, w, h)
    return p


def pillar_paint(glow=False):
    def p(face, w, h, f):
        X, Y = grid(w, h)
        pulse = 0.55 + 0.45 * (0.5 - 0.5 * math.cos(2 * math.pi * f / CROWN_N))
        edge = (X < 1.5) | (X > w - 1.5) | (Y < 1.5)
        if face == 'top':
            edge = np.ones_like(X, bool)
        tone = np.where(edge, 0.6, 0.26 + 0.05 * hashn(np.floor(X / 2), np.floor(Y / 2), 5))
        if glow:
            return rgba(ramp(tone), edge * 0.35 * pulse)
        return rgba(ramp(tone))
    return p


def flame_card(seed, glow=False):
    """An animated purple flame on a flat card."""
    def p(face, w, h, f):
        X, Y = grid(w, h)
        t = f / CROWN_N
        rise = Y / h                                          # 0 at the top (tip), 1 at the base
        sway = 0.1 * math.sin(2 * math.pi * (t * 2 + seed)) + 0.07 * np.sin(2 * math.pi * (rise * 1.4 - t * 3 + seed))
        width = (0.06 + 0.4 * rise ** 0.8) * (0.85 + 0.15 * math.cos(2 * math.pi * (t * 3 + seed)))
        d = np.abs(X / w - 0.5 - sway * (1 - rise)) / np.maximum(width, 1e-3)
        heat = np.clip(1 - d, 0, 1) * np.clip(rise * 4, 0, 1)
        lick = (rise < 0.5) & (np.abs(X / w - 0.28 - 0.16 * math.sin(2 * math.pi * (t * 2 + seed + 0.5))) < 0.07 * (rise / 0.5))
        heat = np.maximum(heat, lick * 0.5)
        heat = np.floor(heat * 4) / 4                          # banded, pixel-art fire
        a = (heat > 0.1).astype(np.float32)
        img = rgba(ramp(0.5 + 0.36 * heat), a)
        if glow:
            img[..., :3] = ramp(0.55 + 0.3 * heat)
            img[..., 3] = a * (0.35 + 0.4 * heat)
        return img
    return p


def spark_paint(glow=False):
    def p(face, w, h, f):
        tone = 0.74 + 0.1 * math.cos(2 * math.pi * f / CROWN_N)
        img = rgba(ramp(np.full((h, w), tone)))
        if glow:
            img[..., 3] = 0.35
        return img
    return p


def revon_crown():
    """An obsidian crown: battlement walls with a violet outline over a lavender-rimmed band, a tall front spire with
    a gem, the Revon star on the front, purple fire on the corner towers and pixels drifting round it."""
    R = 4.45                                  # half size (the head is 4)
    yb, H = -7.0, 6.5                         # bottom of the crown, its height
    top = yb - H
    cubes = []
    for kind, frm, size in (('front', (-R, top, -R), (2 * R, H, 0)), ('back', (-R, top, R), (2 * R, H, 0)),
                            ('side', (-R, top, -R), (0, H, 2 * R)), ('side', (R, top, -R), (0, H, 2 * R))):
        cubes.append(Cube(frm, size, wall_paint(kind), wall_paint(kind, glow=True)))
        # a darker inner face a little inside, so the walls have some thickness
        ins = 0.35
        f2 = (frm[0] + (ins if size[0] == 0 and frm[0] < 0 else -ins if size[0] == 0 else 0),
              frm[1], frm[2] + (ins if size[2] == 0 and frm[2] < 0 else -ins if size[2] == 0 else 0))
        cubes.append(Cube(f2, size, wall_paint(kind, inner=True)))
    # corner towers with fire on top
    flames = []
    for i, (sx, sz) in enumerate(((-1, -1), (1, -1), (-1, 1), (1, 1))):
        x, z = sx * R, sz * R
        cubes.append(Cube((x - 0.55, yb - 3.6, z - 0.55), (1.1, 3.6, 1.1), pillar_paint(), pillar_paint(True)))
        fy = yb - 3.6 - 2.4
        p, g = flame_card(i * 1.7), flame_card(i * 1.7, glow=True)
        flames += [Cube((x - 0.9, fy, z), (1.8, 2.4, 0), p, g), Cube((x, fy, z - 0.9), (0, 2.4, 1.8), p, g)]
    # pixels circling slowly, bobbing
    sparks = []
    for i in range(6):
        a = i / 6 * 2 * math.pi + 0.4
        r = 6.2 + 0.7 * (i % 2)
        x, z, y = math.cos(a) * r, math.sin(a) * r, yb - 2.5 - 2.6 * ((i * 0.37) % 1)
        sz = 0.55 if i % 2 else 0.4
        sparks.append(Cube((x - sz / 2, y - sz / 2, z - sz / 2), (sz, sz, sz), spark_paint(), spark_paint(True)))
    orbit = Bone('orbit', (0, 0, 0), (0, 0, 0), sparks)
    root = Bone('crown', (0, 0, 0), (0, 0, 0), cubes + flames, [orbit])
    anims = [{'bone': 'orbit', 'type': 'spin', 'rot': [0, 36, 0]},
             {'bone': 'orbit', 'type': 'wave', 'pos': [0, 0.6, 0], 'speed': 0.5, 'speedMove': 0.5}]
    return Model('head', [root], density=8, anims=anims, frames=CROWN_N, frame_time=70, translucent=False)


# ================================================================ the wings

WING_N = 16


class _Feather:
    """A jagged blade (model px): base point, angle (0 = outwards, 90 = down), length, width, teeth on the trailing
    edge. Gives its polygon and, for any texel, how far along (t) and across (s) the blade it is."""

    def __init__(self, base, ang, length, width, teeth=3, tooth=0.45, layer=0, flame=False, seed=0):
        self.b, self.L, self.w, self.layer, self.flame, self.seed = base, length, width, layer, flame, seed
        a = math.radians(ang)
        self.d = (math.cos(a), math.sin(a))
        self.n = (-self.d[1], self.d[0])          # points to the trailing side (below a feather pointing outwards)
        self.teeth, self.tooth = teeth, tooth

    def poly(self):
        (bx, by), (dx, dy), (nx, ny), L, w = self.b, self.d, self.n, self.L, self.w
        P = lambda t, s: (bx + dx * L * t + nx * w * 0.5 * s, by + dy * L * t + ny * w * 0.5 * s)
        # the leading edge: long, with one lightning kink; the tip a sharp point
        lead = [P(0, -0.9), P(0.42, -1.0), P(0.5, -0.62), P(0.78, -0.78), P(1.0, 0.05)]
        trail = []
        k = self.teeth
        for i in range(k, 0, -1):                                         # saw teeth back along the trailing edge
            t0 = 0.16 + 0.74 * (i / (k + 0.4))
            trail += [P(t0 + 0.02, 0.35 + self.tooth), P(t0 - 0.1, 0.55)]
        trail += [P(0.05, 1.0)]
        return lead + trail

    def ts(self, mx, my):
        (bx, by), (dx, dy), (nx, ny) = self.b, self.d, self.n
        px, py = mx - bx, my - by
        return (px * dx + py * dy) / self.L, (px * nx + py * ny) / (self.w * 0.5)


def _raster(cv, polys):
    """Binary coverage of polygons in model px on a wing canvas."""
    im = Image.new('L', (cv.w * 2, cv.h * 2), 0)
    from PIL import ImageDraw
    d = ImageDraw.Draw(im)
    for poly in polys:
        d.polygon([((x * 4) * 2, ((y - cv.top) * 4) * 2) for x, y in poly], fill=255)
    return np.asarray(im.resize((cv.w, cv.h), Image.BOX), np.float32) / 255 > 0.5


def _revon_feathers():
    """The left wing's feathers, back to front: a big fan round the shoulder like the reference art. Lower feathers
    hang down with fire at their tips, the middle ones sweep outwards, the top ones rise up and out from the wrist,
    and short coverts lie over the arm."""
    S, E, Wr, T = (0.8, 0.2), (6.5, -4.0), (12.0, -8.0), (16.5, -11.5)
    arm = [S, E, Wr, T]
    lead = lambda k: _along_pts(arm, k)
    F = []
    # lower feathers, hanging (back layer)
    for i in range(6):
        k = i / 5
        b = lead(0.03 + 0.36 * k)
        F.append(_Feather((b[0] + 0.6, b[1] + 0.6), 90 - 34 * k, 12.0 + 2.6 * math.sin(math.pi * k), 3.1, 4, 0.9, 0, flame=True, seed=i))
    # the middle fan, sweeping outwards (middle layer)
    for i in range(6):
        k = i / 5
        b = lead(0.34 + 0.38 * k)
        F.append(_Feather((b[0], b[1] + 0.6), 54 - 46 * k, 15.0 - 1.4 * k, 3.0, 4, 0.9, 1, flame=(i < 2), seed=10 + i))
    # the top feathers, rising up and out from the wrist (front layer)
    for i in range(5):
        k = i / 4
        b = lead(0.66 + 0.32 * k)
        F.append(_Feather((b[0] - 0.4, b[1] + 0.5), 4 - 36 * k, 10.5 - 1.6 * k, 2.8 - 0.3 * k, 3, 0.85, 2, seed=20 + i))
    # coverts over the arm (front layer, drawn after the top feathers)
    for i in range(6):
        k = i / 5
        b = lead(0.06 + 0.64 * k)
        F.append(_Feather((b[0], b[1] + 0.2), 70 - 40 * k, 6.0 - 1.0 * k, 2.8, 3, 0.8, 2, seed=30 + i))
    return F, arm


def _along_pts(pts, k):
    """The point a fraction k of the way along a polyline."""
    seg = [math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:])]
    d = k * sum(seg)
    for (a, b), L in zip(zip(pts, pts[1:]), seg):
        if d <= L:
            u = d / L if L else 0
            return (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u)
        d -= L
    return pts[-1]


def _wing_layer(cv, feathers, layer, f, glow=False, arm=None, emblem=None):
    mx, my = cv.mx(), cv.my()
    t = f / WING_N
    out = np.zeros((cv.h, cv.w, 4), np.float32)
    lit = np.zeros((cv.h, cv.w), np.float32)
    wave = 0.5 + 0.5 * np.cos(2 * math.pi * (mx / cv.W * 1.2 - t))          # light running out along the wing
    pulse = 0.55 + 0.45 * (0.5 - 0.5 * math.cos(2 * math.pi * t))
    for fe in [x for x in feathers if x.layer == layer]:
        m = _raster(cv, [fe.poly()])
        if not m.any():
            continue
        tt, ss = fe.ts(mx, my)
        tt = np.clip(tt, 0, 1)
        tone = 0.13 + 0.14 * tt + 0.03 * hashn(np.floor(mx * 2), np.floor(my * 2), fe.seed)
        lead = ss < -0.35
        tone = np.where(lead, 0.42 + 0.42 * tt ** 1.2, tone)
        tone = np.where(lead & (ss < -0.72), 0.62 + 0.3 * tt, tone)
        tone = np.where((tt > 0.84) & (ss < 0.2), np.maximum(tone, 0.5 + 0.34 * (tt - 0.84) / 0.16), tone)
        tone = np.where(ss > 0.62, 0.08, tone)                                  # shadow under the trailing teeth
        inner = _erode(m)
        tone = np.where(m & ~inner, 0.03, tone)                                 # a dark outline per feather
        col = ramp(tone)
        out[..., :3] = np.where(m[..., None], col, out[..., :3])
        out[..., 3] = np.where(m, 1.0, out[..., 3])
        g = np.where(lead & inner, (0.25 + 0.45 * tt) * (0.5 + 0.5 * wave), 0.0)
        g = np.where((tt > 0.8) & inner, np.maximum(g, 0.45 * pulse), g)
        lit = np.where(m, g, lit)
    if arm is not None:
        # the arm: a dark band along the leading edge with a violet rim
        from wingpaint import lines_mask
        band = lines_mask(cv, [[cv.p(*p) for p in arm]], 1.2 * 4) > 0.5
        rim = band & ~_erode(_erode(band))
        tone = np.where(rim, 0.6, 0.2 + 0.06 * hashn(np.floor(mx * 2), np.floor(my * 2), 31))
        out[..., :3] = np.where(band[..., None], ramp(tone), out[..., :3])
        out[..., 3] = np.where(band, 1.0, out[..., 3])
        lit = np.where(band, np.where(rim, 0.4 * pulse, 0.0), lit)
    if emblem is not None:
        ex, ey, ea, eb = emblem
        X, Y = cv.X, cv.Y
        cx, cy = cv.p(ex, ey)
        img = out.copy()
        img[..., 3] = 1
        paint_star(img, X, Y, cx, cy, ea * 4, eb * 4, pulse)
        reach = star_field(X, Y, cx, cy, ea * 4 * 1.75, eb * 4 * 1.75, 0.62) <= 1
        body = star_field(X, Y, cx, cy, ea * 4, eb * 4, 0.62) <= 1
        sel = reach & ((out[..., 3] > 0) | body)
        out[..., :3] = np.where(sel[..., None], img[..., :3], out[..., :3])
        out[..., 3] = np.where(body, 1.0, out[..., 3])
        rim = body & ~_erode(body)
        lit = np.where(sel, 0.25 * pulse, lit)
        lit = np.where(body, 0.0, lit)
        lit = np.where(rim, 0.6, lit)
    if glow:
        g = np.zeros_like(out)
        g[..., :3] = out[..., :3]
        g[..., 3] = lit * out[..., 3]
        return g
    return out


def _flames(cv, feathers, f):
    """Purple fire hanging from the lower feathers' tips and licking upwards, for the glow layer (and the base)."""
    mx, my = cv.mx(), cv.my()
    t = f / WING_N
    heat = np.zeros((cv.h, cv.w), np.float32)
    for fe in feathers:
        if not fe.flame:
            continue
        tx = fe.b[0] + fe.d[0] * fe.L
        ty = fe.b[1] + fe.d[1] * fe.L
        ph = fe.seed * 0.37
        for j, (dx, ln, wd) in enumerate(((0.0, 4.2, 1.5), (-1.3, 3.0, 1.0), (1.2, 2.6, 0.9))):
            # a tongue rising from just below the tip, swaying and flickering in length
            sway = 0.6 * math.sin(2 * math.pi * (t * 2 + ph + j * 0.3))
            L = ln * (0.8 + 0.25 * math.sin(2 * math.pi * (t * 3 + ph * 2 + j * 0.5)))
            bx, by = tx + dx * 0.6, ty + 1.2
            v = (by - my) / L                                   # 0 at the base, 1 at the tip (rising)
            off = mx - bx - sway * np.clip(v, 0, 1) ** 1.5
            width = wd * np.clip(1 - v, 0, 1) ** 0.8 * np.clip(v * 4 + 0.6, 0, 1)
            h = np.clip(1 - np.abs(off) / np.maximum(width, 1e-3), 0, 1) * ((v > -0.15) & (v < 1))
            heat = np.maximum(heat, h)
    heat = np.floor(heat * 4) / 4
    return heat


def revon_wings():
    from wingpaint import Canvas
    from wingkit import WingModel, Rig, Layer
    W, H, top = 27, 35, -19
    cv = Canvas(W, H, top)
    feathers, arm = _revon_feathers()

    def layer(i, glow=False):
        def p(f):
            img = _wing_layer(cv, feathers, i, f, glow, arm if i == 2 else None, (3.0, -1.1, 2.6, 3.4) if i == 2 else None)
            if i == 0 or i == 1:
                heat = _flames(cv, [x for x in feathers if x.layer == i], f)
                fire = heat > 0.1
                if glow:
                    img[..., :3] = np.where(fire[..., None], ramp(0.55 + 0.32 * heat), img[..., :3])
                    img[..., 3] = np.where(fire, 0.35 + 0.45 * heat, img[..., 3])
                else:
                    show = fire & (img[..., 3] < 0.5)
                    img[..., :3] = np.where(show[..., None], ramp(0.5 + 0.36 * heat), img[..., :3])
                    img[..., 3] = np.where(show, 1.0, img[..., 3])
            return img
        memo = {}

        def get(f):
            if f not in memo:
                memo[f] = p(f)
            return memo[f]
        return get
    layers = [Layer(0.0, layer(0), layer(0, True)), Layer(0.35, layer(1), layer(1, True)), Layer(0.7, layer(2), layer(2, True))]
    rig = Rig(layers, (W, H), top, cuts=[6.5, 12.0], flap=dict(amp=14, speed=0.3, speedMove=0.85, down=0.34), scale=0.88)
    return WingModel([rig], frames=WING_N, frame_time=70, translucent=False), None


# ================================================================ the particles (wings and trail)

# pixel sprites, drawn on a 9 x 9 grid and doubled: '#' white, '+' light grey, '-' mid grey (the colour tints them)
_SPRITES = {
    'star': ['....#....',
             '....#....',
             '...+#+...',
             '..+###+..',
             '#########',
             '..+###+..',
             '...+#+...',
             '....#....',
             '....#....'],
    'pixel': ['.........',
              '.........',
              '..#####..',
              '..####+..',
              '..####+..',
              '..####+..',
              '..++++-..',
              '.........',
              '.........'],
    'bit': ['.........',
            '.........',
            '.........',
            '...##....',
            '...#+....',
            '.........',
            '.........',
            '.........',
            '.........'],
    'flame0': ['....-....',
               '...-+....',
               '...-+-...',
               '..-+#+-..',
               '..-+#+-..',
               '.-+###+-.',
               '.-+###+-.',
               '..-+#+-..',
               '...---...'],
    'flame1': ['.....-...',
               '....-+...',
               '...-+-...',
               '...-+#-..',
               '..-+#+-..',
               '..-+##+-.',
               '.-+###+-.',
               '..-+#+-..',
               '...---...'],
    'flame2': ['...-.....',
               '...+-....',
               '...-+-...',
               '..-#+-...',
               '..-+#+-..',
               '.-+##+-..',
               '.-+###+-.',
               '..-+#+-..',
               '...---...'],
}
_SHADE = {'#': 1.0, '+': 0.78, '-': 0.55}


def pixel_sprite(name, size=18):
    rows = _SPRITES[name]
    n = len(rows)
    a = np.array([[1.0 if c in _SHADE else 0.0 for c in r] for r in rows], np.float32)
    v = np.array([[_SHADE.get(c, 0.0) for c in r] for r in rows], np.float32)
    art = rgba(np.repeat(v[..., None], 3, 2), a)
    k = size // n
    return np.kron(art, np.ones((k, k, 1), np.float32))


def _strip(names):
    return np.concatenate([pixel_sprite(n) for n in names], 1)


# violet pixels and little stars drifting up off the wings
WING_PARTICLES = (lambda: _strip(['pixel', 'pixel', 'bit', 'bit', 'star']), dict(
    rate=7, life=[1.4, 2.4], size=[0.05, 0.09], radius=[0.3, 0.8], height=[0.9, 1.7], vx=[-0.06, 0.06], vy=[0.12, 0.3],
    vz=[-0.06, 0.06], shrink=True, colors=['#a752ff', '#8a2cff', '#dfa9ff', '#c47dff'], glow=True, upright=True))

# ================================================================ the items

CAPE = dict(id='cape_revon', name="Revon's Cape", collection='revon', rarity='mythic', paint=cape, elytra=elytra,
            description='Black and violet with the Revon star and purple fire. Wear an elytra and it changes to match.',
            frames=20, frameTime=60, edge='#1a0c28', special=True)
WINGS_ITEM = dict(id='wings_revon', name="Revon's Wings", collection='revon', rarity='mythic',
                  make=revon_wings, description='Jagged black-violet wings with the Revon star at the shoulder, purple fire and drifting pixels.',
                  special=True)
CROWN = dict(id='head_revon_crown', name="Revon's Crown", collection='revon', rarity='mythic', model=revon_crown,
             description='An obsidian crown with the Revon star, burning violet and circled by drifting pixels.', special=True)
TRAIL = dict(id='aura_revon_trail', name="Revon's Trail", collection='revon', rarity='mythic',
             sprites=lambda: _strip(['flame0', 'flame1', 'flame2', 'flame0', 'pixel', 'bit', 'star']),
             description='Purple fire, pixels and Revon stars burning at your feet and left behind wherever you walk.',
             special=True, particles=dict(
                 rate=34, life=[0.8, 1.5], size=[0.07, 0.13], radius=[0.22, 0.5], height=[0.0, 0.12], vx=[-0.04, 0.04],
                 vy=[0.3, 0.6], vz=[-0.04, 0.04], shrink=True, colors=['#8a2cff', '#a752ff', '#c47dff', '#dfa9ff', '#6a1fc4'],
                 glow=True, upright=True))


def register():
    """Adds Revon's set to the kinds build.py builds."""
    import auras, capes, heads, wings
    for module, item in ((capes, CAPE), (heads, CROWN), (wings, WINGS_ITEM), (auras, TRAIL)):
        if all(i['id'] != item['id'] for i in module.ITEMS):
            module.ITEMS.append(item)
    wings.PARTICLES[WINGS_ITEM['id']] = WING_PARTICLES
