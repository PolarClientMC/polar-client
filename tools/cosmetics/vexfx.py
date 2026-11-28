"""VexCraft's trail and aura (format: auras.py, layers: dev.polarclient.cosmetics.Aura). Both share one sprite strip,
drawn in code in white and greys so the game tints them with each layer's colours.

VexCraft Trail - what you leave behind on a lifesteal server:
    sigils    VexCraft's V in a broken ring flashes on the ground at every step, then cracks and fades
    sparks    violet lightning crackling round your feet
    chips     crystal chips kicked up as you walk, tumbling back down
    smoke     low dark-violet smoke curling off the ground
    motes     crystal motes drifting up off your steps
    hearts    little stolen hearts rising from your steps

VexCraft Aura - a lifesteal storm round you:
    spiral    wisps of violet energy spiralling up round you
    hearts    red hearts circling you at chest height, beating
    arcs      lightning flicking across your body
    rune      a ring of VexCraft V runes slowly turning on the ground under you
    motes     crystal motes drifting up
"""
import math

import numpy as np
from PIL import Image, ImageDraw

from lib import rgba, blur

S = 32   # texels per sprite
SS = 4


def _grid():
    y, x = np.mgrid[0:S, 0:S].astype(np.float32)
    return (x + 0.5) / S - 0.5, (y + 0.5) / S - 0.5     # -0.5 .. 0.5


def _px(a):
    """Crisp pixel-art edges: alpha snapped to a few levels."""
    return np.round(np.clip(a, 0, 1) * 4) / 4


def _draw(fn):
    im = Image.new('L', (S * SS, S * SS), 0)
    fn(ImageDraw.Draw(im), S * SS)
    return np.asarray(im.resize((S, S), Image.LANCZOS), np.float32) / 255


def _v(d, s, cx, cy, size, width):
    """VexCraft's V as two blades meeting at the bottom (centred on cx, cy; size across)."""
    for sign in (-1, 1):
        d.polygon([((cx + sign * size * 0.5) * s, (cy - size * 0.42) * s), ((cx + sign * (size * 0.5 - width)) * s, (cy - size * 0.42) * s),
                   (cx * s, (cy + size * 0.38 - width * 0.4) * s), ((cx + sign * width * 0.15) * s, (cy + size * 0.42) * s)], fill=255)


def sigil(k):
    """VexCraft's V inside a broken ring, frame k of 4: a bright flash, then the ring breaks up and fades."""
    x, y = _grid()
    r = np.hypot(x, y) * 2
    ang = np.arctan2(y, x)
    ring = np.clip(1 - np.abs(r - 0.82) / (0.09 - 0.01 * k), 0, 1)
    gaps = np.cos(ang * 6 + 0.5) > (-0.6 + 0.35 * k)                # the ring breaks into more pieces as it fades
    ring = ring * gaps
    ticks = (np.abs(r - 0.66) < 0.05) & (np.cos(ang * 12) > 0.86)    # rune marks inside the ring
    v = _draw(lambda d, s: _v(d, 1, s * 0.5, s * 0.52, s * (0.56 - 0.03 * k), s * 0.14))
    flash = np.clip(1 - r / 0.9, 0, 1) ** 2 * (0.5 if k == 0 else 0.0)
    a = np.maximum.reduce([ring, ticks * (1 - 0.25 * k), v * (1 - 0.18 * k), flash])
    shade = np.ones((S, S, 3), np.float32) * (0.82 + 0.18 * v)[..., None]
    return rgba(shade, _px(a))


def spark(seed):
    """A forked lightning spark."""
    rng = np.random.default_rng(seed)

    def draw(d, s):
        for arm in range(3):
            a = rng.random() * 2 * math.pi
            px = py = s / 2
            line = [(px, py)]
            for _ in range(3):
                a += rng.normal(0, 0.65)
                px += math.cos(a) * s * 0.14
                py += math.sin(a) * s * 0.14
                line.append((px, py))
            d.line(line, fill=255, width=int(s * 0.06))
    a = _draw(draw)
    x, y = _grid()
    core = np.clip(1 - np.hypot(x, y) / 0.12, 0, 1)
    return rgba(np.ones((S, S, 3), np.float32), _px(np.clip(a + blur(a, 1.2) * 0.4 + core, 0, 1)))


def chip(k):
    """A crystal chip, turned a little differently in each variant: a lit half and a shadowed half."""
    x, y = _grid()
    a = math.radians(25 * k - 20)
    u = x * math.cos(a) + y * math.sin(a)
    v = -x * math.sin(a) + y * math.cos(a)
    dia = np.abs(u) / 0.16 + np.abs(v) / (0.36 - 0.05 * k) < 1
    shade = np.where(u < 0, 1.0, 0.6) * np.where(np.abs(u) < 0.025, 1.15, 1.0)
    return rgba(np.ones((S, S, 3), np.float32) * np.clip(shade, 0, 1)[..., None], dia.astype(np.float32))


def smoke():
    """A soft, uneven puff of smoke."""
    x, y = _grid()
    r = np.hypot(x, y * 1.1) * 2
    curl = 0.8 + 0.2 * np.sin(x * 11 + np.sin(y * 9) * 1.6) * np.sin(y * 10 + 1)
    a = np.clip(1 - r, 0, 1) ** 1.6 * curl
    return rgba(np.ones((S, S, 3), np.float32) * (0.85 + 0.15 * np.clip(-y * 2, 0, 1))[..., None], np.clip(a, 0, 1) * 0.9)


HEART = [
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
HEART_SHADE = {'o': 0.32, 'h': 1.0, 'x': 0.85, 'd': 0.6}


def heart(k):
    """A pixel heart: k = 0 resting, 1 swelling on the beat (a pixel bigger), 2 a cracked heart (stolen)."""
    a = np.zeros((16, 16), np.float32)
    shade = np.zeros((16, 16), np.float32)
    for yy, row in enumerate(HEART):
        for xx, c in enumerate(row):
            if c != '.':
                a[yy + 3, xx + 2] = 1
                shade[yy + 3, xx + 2] = HEART_SHADE[c]
    if k == 2:   # a crack down the middle
        for yy, xx in ((4, 7), (5, 8), (6, 7), (7, 8), (8, 7), (9, 8), (10, 7)):
            shade[yy, xx] = 0.32
    a = np.kron(a, np.ones((2, 2), np.float32))
    shade = np.kron(shade, np.ones((2, 2), np.float32))
    img = rgba(np.repeat(shade[..., None], 3, 2), a)
    if k == 1:   # the beat: drawn a little bigger
        big = Image.fromarray((img * 255).astype(np.uint8), 'RGBA').resize((36, 36), Image.NEAREST).crop((2, 2, 34, 34))
        img = np.asarray(big, np.float32) / 255
    return img


def wisp():
    """A tall wisp of energy, brightest in a thin core (drawn upright: the layer keeps it pointing up)."""
    x, y = _grid()
    w = 0.13 * np.clip(1 - np.abs(y) / 0.5, 0, 1) ** 0.7 * (1 - 0.4 * np.clip(-y * 2, 0, 1))
    bend = 0.06 * np.sin(y * 7)
    d = np.abs(x - bend) / np.maximum(w, 1e-3)
    a = np.clip(1 - d, 0, 1) ** 1.3 * np.clip(1 - np.abs(y) * 2, 0, 1) ** 0.5
    core = np.clip(1 - np.abs(x - bend) / 0.025, 0, 1) * np.clip(1 - np.abs(y) * 2.2, 0, 1)
    return rgba(np.ones((S, S, 3), np.float32) * (0.75 + 0.25 * core)[..., None], np.clip(a + core * 0.5, 0, 1))


def rune_ring():
    """A ring of V runes for the ground under you, with a thin circle inside and out."""
    x, y = _grid()
    r = np.hypot(x, y) * 2
    ang = np.arctan2(y, x)
    a = np.clip(1 - np.abs(r - 0.92) / 0.05, 0, 1) + np.clip(1 - np.abs(r - 0.6) / 0.04, 0, 1) * 0.8

    def runes(d, s):
        for i in range(8):
            t = i / 8 * 2 * math.pi
            cx, cy = s / 2 + math.cos(t) * s * 0.39, s / 2 + math.sin(t) * s * 0.39
            # each V points at the centre
            size, wd = s * 0.15, s * 0.05
            pts_l = [(-size * 0.5, -size * 0.42), (-size * 0.5 + wd, -size * 0.42), (0, size * 0.36), (wd * 0.15, size * 0.42)]
            for sign in (-1, 1):
                pts = []
                for px, py in pts_l:
                    px *= sign
                    rot = t + math.pi / 2
                    pts.append((cx + px * math.cos(rot) - py * math.sin(rot), cy + px * math.sin(rot) + py * math.cos(rot)))
                d.polygon(pts, fill=255)
    v = _draw(runes)
    return rgba(np.ones((S, S, 3), np.float32), np.clip(a + v, 0, 1))


def mote(k):
    """A tiny crystal mote with a glint (two sizes)."""
    x, y = _grid()
    s = 0.22 if k else 0.15
    dia = np.abs(x) / (s * 0.7) + np.abs(y) / s < 1
    glint = np.clip(1 - np.hypot(x, y) / (s * 0.5), 0, 1)
    return rgba(np.ones((S, S, 3), np.float32) * np.where(x < 0, 1.0, 0.7)[..., None], np.clip(dia + glint, 0, 1))


# the strip: what each layer's "frame": [first, count] points at
FRAMES = ([sigil(k) for k in range(4)]            # 0..3   sigils (a flipbook)
          + [spark(s) for s in (3, 8, 13)]        # 4..6   sparks
          + [chip(k) for k in range(3)]           # 7..9   crystal chips
          + [smoke()]                             # 10     smoke
          + [heart(k) for k in (0, 1, 0, 0)]      # 11..14 a heart beating (a flipbook: rest, beat, rest, rest)
          + [heart(2)]                            # 15     a cracked heart
          + [wisp()]                              # 16     energy wisp
          + [rune_ring()]                         # 17     the rune ring
          + [mote(0), mote(1)])                   # 18..19 motes


def sprites():
    return np.concatenate(FRAMES, 1)


VIOLETS = ['#a35cff', '#7c3aed', '#c084fc', '#e04cff']
HEARTS = ['#ff2e63', '#ff4f7b', '#e11d48']

TRAIL_PARTICLES = dict(
    rate=1, glow=False, shrink=False,
    layers=[
        dict(frame=[0, 4], anim=True, flat=True, glow=True, rate=0.4, perBlock=1.2, life=[0.9, 1.2], size=[0.3, 0.36], grow=1.6,
             radius=[0.0, 0.08], height=[0.03, 0.04], spin=[-25, 25], fade=[0.03, 0.55], colors=['#c084fc', '#e04cff', '#a35cff']),
        dict(frame=[4, 3], glow=True, rate=5, perBlock=4, life=[0.12, 0.3], size=[0.08, 0.15], radius=[0.1, 0.55],
             height=[0.0, 0.5], spin=[-500, 500], colors=['#f3e8ff', '#e04cff', '#c084fc', '#ffffff']),
        dict(frame=[7, 3], rate=0.5, perBlock=3.5, life=[0.7, 1.1], size=[0.05, 0.09], radius=[0.05, 0.35], height=[0.02, 0.15],
             vx=[-0.6, 0.6], vy=[1.4, 2.2], vz=[-0.6, 0.6], gravity=6.5, spin=[-360, 360], fade=[0.0, 0.25],
             colors=['#a35cff', '#c084fc', '#7c3aed', '#d2b0ff']),
        dict(frame=[10, 1], rate=1.5, perBlock=1.8, life=[1.4, 2.2], size=[0.28, 0.42], grow=2.0, radius=[0.05, 0.45],
             height=[0.0, 0.15], vy=[0.06, 0.16], spin=[-25, 25], fade=[0.2, 0.6], alpha=0.55, colors=['#2a0b4f', '#3b1470', '#1a0a2e']),
        dict(frame=[18, 2], glow=True, rate=2, perBlock=3, life=[1.0, 1.8], size=[0.04, 0.07], radius=[0.05, 0.45], height=[0.05, 0.5],
             vx=[-0.05, 0.05], vy=[0.45, 0.85], vz=[-0.05, 0.05], spin=[-120, 120], wobble=[0.04, 1.8], shrink=True,
             colors=['#d2b0ff', '#e04cff', '#f3e8ff']),
        dict(frame=[15, 1], rate=0.1, perBlock=0.45, life=[1.4, 2.0], size=[0.09, 0.12], radius=[0.05, 0.3], height=[0.1, 0.4],
             vx=[-0.04, 0.04], vy=[0.45, 0.7], vz=[-0.04, 0.04], wobble=[0.06, 1.6], fade=[0.1, 0.35], upright=True, colors=HEARTS),
    ])

AURA_PARTICLES = dict(
    rate=1, glow=False, shrink=False,
    layers=[
        dict(frame=[16, 1], glow=True, upright=True, rate=22, life=[1.0, 1.6], size=[0.2, 0.32], radius=[0.5, 0.66],
             height=[0.0, 0.3], vy=[0.9, 1.3], swirl=[2.2, 2.8], fade=[0.15, 0.4], colors=VIOLETS),
        dict(frame=[11, 4], anim=True, upright=True, rate=1.6, life=[2.4, 3.2], size=[0.11, 0.14], radius=[0.72, 0.85],
             height=[0.75, 1.55], vy=[-0.03, 0.03], swirl=[1.0, 1.3], fade=[0.15, 0.2], colors=HEARTS),
        dict(frame=[4, 3], glow=True, rate=14, life=[0.12, 0.26], size=[0.16, 0.28], radius=[0.3, 0.6], height=[0.2, 1.9],
             spin=[-600, 600], colors=['#f3e8ff', '#e04cff', '#ffffff', '#c084fc']),
        dict(frame=[17, 1], flat=True, glow=True, rate=0.45, life=[2.6, 3.0], size=[0.8, 0.85], grow=1.12, radius=[0.0, 0.0],
             height=[0.03, 0.03], spin=[28, 34], fade=[0.3, 0.35], colors=['#c084fc', '#e04cff', '#a35cff']),
        dict(frame=[18, 2], glow=True, rate=6, life=[1.6, 2.4], size=[0.04, 0.07], radius=[0.2, 0.85], height=[0.0, 1.2],
             vx=[-0.04, 0.04], vy=[0.3, 0.55], vz=[-0.04, 0.04], spin=[-120, 120], shrink=True, colors=['#d2b0ff', '#f3e8ff', '#e04cff']),
    ])

TRAIL = dict(id='aura_vexcraft_trail', name='VexCraft Trail', collection='vexcraft', rarity='mythic', sprites=sprites, special=True,
             description="VexCraft's partner trail: their V flashes on the ground at every step, violet lightning crackles at "
                         'your feet, crystal chips fly up, dark smoke curls behind you and stolen hearts float away.',
             particles=TRAIL_PARTICLES)

AURA = dict(id='aura_vexcraft_aura', name='VexCraft Lifesteal Aura', collection='vexcraft', rarity='mythic', sprites=sprites, special=True,
            description="VexCraft's partner aura, a lifesteal storm: violet energy spirals up round you, red hearts circle "
                        'you beating, lightning flicks across you and a ring of V runes turns under your feet.',
            particles=AURA_PARTICLES)
