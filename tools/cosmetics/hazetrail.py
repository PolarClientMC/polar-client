"""HazeSMP's trail: the water and the haze follow you. Sprites and settings for the aura (format: auras.py, layers:
dev.polarclient.cosmetics.Aura).

    ripples   glowing rings spreading out on the ground at every step, and now and then while standing
    bubbles   rising and wobbling from your feet and around you, popping at the top
    haze      soft blue mist curling up off the ground where you walk
    fish      a little school of HazeSMP-blue fish circling you
    glints    specks of light flickering in the water around you, like the light in their banner

All drawn as pixel art in the logo's colours (white sprites, tinted in game).
"""
import math

import numpy as np

from lib import rgba

S = 32   # texels per sprite


def _grid():
    y, x = np.mgrid[0:S, 0:S].astype(np.float32)
    return (x + 0.5) / S - 0.5, (y + 0.5) / S - 0.5     # -0.5 .. 0.5


def _px(a):
    """Crisp pixel-art edges: alpha snapped to a few levels."""
    return np.round(np.clip(a, 0, 1) * 4) / 4


def ripple(k):
    """A ring of water, frame k of 4: thick and bright when it starts, thin and broken as it spreads."""
    x, y = _grid()
    r = np.hypot(x, y) * 2
    width = 0.16 - 0.03 * k
    ring = np.clip(1 - np.abs(r - 0.86) / width, 0, 1)
    ang = np.arctan2(y, x)
    ring *= np.clip(0.55 + 0.45 * np.cos(ang * 3 + k * 1.3) + 0.25 * k * np.sin(ang * 7), 0, 1) if k else 1
    inner = np.clip(1 - np.abs(r - 0.55) / 0.08, 0, 1) * (0.45 - 0.12 * k)       # a second, fainter ring
    col = np.ones((S, S, 3), np.float32)
    shade = 0.75 + 0.25 * np.clip(-y * 2, 0, 1)                                   # lighter on the far side
    return rgba(col * shade[..., None], _px(np.maximum(ring, inner)))


def bubble(k, pop=0):
    """A bubble (k: a slight squash for the wobble) or its pop (1, 2: a broken ring with droplets flying)."""
    x, y = _grid()
    if pop:
        r = np.hypot(x, y) * 2
        ring = np.clip(1 - np.abs(r - (0.55 + 0.3 * pop)) / 0.09, 0, 1)
        ang = np.arctan2(y, x)
        ring *= (np.cos(ang * 4 + pop) > -0.2)
        drops = np.zeros_like(x)
        for i in range(6):
            a = i * math.pi / 3 + 0.4
            d = 0.36 + 0.07 * pop
            drops = np.maximum(drops, np.clip(1 - np.hypot(x - math.cos(a) * d, y - math.sin(a) * d) / 0.045, 0, 1))
        return rgba(np.ones((S, S, 3), np.float32), _px(np.maximum(ring * (1 - 0.3 * pop), drops)))
    sx, sy = 1 + 0.06 * math.sin(k * 1.6), 1 - 0.06 * math.sin(k * 1.6)
    r = np.hypot(x / sx, y / sy) * 2
    rim = np.clip(1 - np.abs(r - 0.8) / 0.12, 0, 1)
    body = (r < 0.8) * 0.22
    spec = np.clip(1 - np.hypot((x + 0.17) / 0.09, (y + 0.17) / 0.07), 0, 1)
    spec2 = np.clip(1 - np.hypot((x - 0.16) / 0.05, (y - 0.18) / 0.04), 0, 1) * 0.6
    a = np.clip(rim + body + spec + spec2, 0, 1)
    col = np.ones((S, S, 3), np.float32) * (0.82 + 0.18 * np.clip(spec + spec2, 0, 1)[..., None])
    return rgba(col, _px(a))


def mist():
    """A soft puff of haze (left soft: it's mist), a little uneven."""
    x, y = _grid()
    r = np.hypot(x, y * 1.15) * 2
    swirl = 0.82 + 0.18 * np.sin(x * 9 + np.sin(y * 7) * 1.5) * np.sin(y * 8 - 1)
    a = np.clip(1 - r, 0, 1) ** 1.7 * swirl
    return rgba(np.ones((S, S, 3), np.float32), np.clip(a, 0, 1) * 0.9)


FISH = [   # 16 x 16, drawn at twice the size: a sleek little fish facing right with a forked tail
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
SHADE = {'o': 0.28, 'l': 1.0, 'd': 0.68, 'e': 0.12, 'w': 1.0}


def fish(k):
    """A little fish swimming right (k: its tail, 0 middle, 1 up, 2 down), in white and greys for the tint: a light
    back, a darker belly, a dark outline and eye."""
    a = np.zeros((16, 16), np.float32)
    shade = np.zeros((16, 16), np.float32)
    for yy, row in enumerate(FISH):
        for xx, c in enumerate(row):
            if c != '.':
                a[yy, xx], shade[yy, xx] = 1, SHADE[c]
    if k:   # the tail (the first 4 columns) flicks up or down a pixel
        dy = -1 if k == 1 else 1
        a[:, :4], shade[:, :4] = np.roll(a[:, :4], dy, 0), np.roll(shade[:, :4], dy, 0)
    a = np.kron(a, np.ones((2, 2), np.float32))
    shade = np.kron(shade, np.ones((2, 2), np.float32))
    return rgba(np.repeat(shade[..., None], 3, 2), a)


def glint(k):
    """A speck of light: frame k of 4 (small, big, small, tiny) as it flickers."""
    x, y = _grid()
    size = [0.5, 1.0, 0.6, 0.3][k]
    cross = np.maximum(np.clip(1 - np.abs(x) / 0.035, 0, 1) * np.clip(1 - np.abs(y) / (0.42 * size), 0, 1),
                       np.clip(1 - np.abs(y) / 0.035, 0, 1) * np.clip(1 - np.abs(x) / (0.42 * size), 0, 1))
    core = np.clip(1 - np.hypot(x, y) / (0.1 + 0.08 * size), 0, 1)
    return rgba(np.ones((S, S, 3), np.float32), _px(np.maximum(cross, core)))


# the strip: what each layer's "frame": [first, count] points at
FRAMES = ([ripple(k) for k in range(4)]                                   # 0..3   ripples (a flipbook)
          + [bubble(k) for k in (0, 1, 2, 1, 0, 1, 2)] + [bubble(0, 1), bubble(0, 2)]   # 4..12  bubble, wobbling, then popping
          + [mist()]                                                      # 13     haze
          + [fish(k) for k in (0, 1, 0, 2) * 2]                           # 14..21 fish, tail wagging
          + [glint(k) for k in range(4)])                                 # 22..25 glints


def sprites():
    return np.concatenate(FRAMES, 1)


PARTICLES = dict(
    rate=1, glow=False, shrink=False,
    layers=[
        dict(frame=[0, 4], anim=True, flat=True, glow=True, rate=0.9, perBlock=1.5, life=[1.0, 1.4], size=[0.26, 0.34],
             grow=3.3, radius=[0.0, 0.12], height=[0.03, 0.04], fade=[0.04, 0.8], colors=['#5ee0ff', '#38b6ff', '#9fe6ff']),
        dict(frame=[4, 9], anim=True, rate=4, perBlock=3.0, life=[1.6, 2.6], size=[0.045, 0.1], radius=[0.15, 0.75],
             height=[0.0, 1.2], vx=[-0.03, 0.03], vy=[0.35, 0.7], vz=[-0.03, 0.03], wobble=[0.045, 1.3], fade=[0.1, 0.12],
             alpha=0.95, colors=['#e8fbff', '#bff4ff', '#9fe6ff']),
        dict(frame=[13, 1], glow=True, rate=2.2, perBlock=1.6, life=[1.8, 2.8], size=[0.34, 0.52], grow=2.2, radius=[0.05, 0.55],
             height=[0.02, 0.3], vy=[0.03, 0.1], spin=[-20, 20], fade=[0.25, 0.6], alpha=0.6,
             colors=['#9fe6ff', '#bff4ff', '#5ee0ff']),
        dict(frame=[14, 8], anim=True, face=True, rate=1.4, life=[3.0, 4.4], size=[0.11, 0.16], radius=[0.75, 1.1],
             height=[0.35, 1.65], swirl=[0.85, 1.25], vy=[-0.04, 0.04], fade=[0.15, 0.15], upright=True,
             colors=['#38b6ff', '#5ee0ff', '#4f9dff', '#9fe6ff']),
        dict(frame=[22, 4], anim=True, glow=True, rate=7, life=[0.9, 1.6], size=[0.06, 0.11], radius=[0.3, 0.9],
             height=[0.1, 2.0], vx=[-0.05, 0.05], vy=[0.04, 0.16], vz=[-0.05, 0.05], fade=[0.15, 0.3],
             colors=['#bff4ff', '#5ee0ff', '#ffffff']),
    ])

TRAIL = dict(id='aura_hazesmp_trail', name='HazeSMP Trail', collection='hazesmp', rarity='mythic', sprites=sprites, special=True,
             description="HazeSMP's partner trail: glowing ripples spread from every step, bubbles rise and pop, blue haze "
                         'curls off the ground, a school of little fish circles you and light glints in the water.',
             particles=PARTICLES)
