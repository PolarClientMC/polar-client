"""HazeSMP's wings: big feathered wings in HazeSMP's ocean blues, built like a real bird's wing so they move like one.

Each wing is a chain of bones - shoulder, forearm, hand - with every flight feather on a bone of its own: five
primaries fanning from the hand and four secondaries along the forearm, under rows of coverts. So the wing can fold
against the back the way a bird's does (forearm down, hand tucked, feathers stacked), spread to glide (feathers
fanned), and flap with a real stroke: a straight, closed wing on the way down, then the wrist bends and the
feathers twist open on the way up.

The motion is the keyframe clips (one per state, ANIMATIONS.md) with springs on every bone on top: each bone chases
its clip with a little lag and overshoot, the hand and feathers bend against the arm's swing, and walking, turning,
jumping and landing push the whole wing about, so nothing moves stiffly.

The art follows HazeSMP's logo: deep blues with lighter pixel squares, a dark violet-navy outline, cloud-white tips,
and a cyan light that runs out along the feathers. Bubbles drift up off them.
"""
import json
import math
import os

import numpy as np
from PIL import Image

from lib import rgb, rgba, smoothstep, blur, to_u8
from keyframes import key, clip

D = 8          # texels per model pixel: twice the usual, so the feathers stay sharp up close
N = 12         # animation frames of the texture (the light running along the feathers)
FRAME_MS = 100

OUTLINE = rgb('#1c1240')
NAVY = rgb('#0d1f5c')
DEEP = rgb('#123f9e')
BLUE = rgb('#1f6fe0')
SKY = rgb('#38b6ff')
ICE = rgb('#9fe6ff')
CLOUD = rgb('#eefbff')
CYAN = rgb('#5ee0ff')


# ================================================================ the wing's build (left wing; the right mirrors it)

SHOULDER = (2.2, 6.6, 3.6)    # between the shoulder blades, a little off the back: clear of the head however it turns
UPPER, FORE, HAND = 7.0, 6.5, 4.0   # bone lengths (model px)
SCALE = 0.92

# primaries: (where along the hand, length, width, fan angle when spread, angle when folded); outermost last
PRIMARIES = [(0.3, 12.6, 3.7, 54, 16), (1.1, 13.8, 3.7, 43, 13), (1.9, 14.9, 3.7, 32, 10), (2.7, 15.8, 3.6, 21, 7),
             (3.5, 16.4, 3.5, 10, 4), (4.2, 15.9, 3.3, 0, 1)]
# secondaries on the forearm (where, length, width, spread, folded); innermost first
SECONDARIES = [(0.3, 10.6, 3.8, 100, 12), (1.7, 11.0, 3.8, 91, 9), (3.1, 11.4, 3.8, 82, 6), (4.5, 11.8, 3.7, 73, 4),
               (5.9, 12.2, 3.7, 64, 2)]
# tertials hanging from the upper arm, filling the gap by the body
TERTIALS = [(3.0, 8.8, 3.8, 112, 152), (5.2, 9.8, 3.8, 104, 146)]


# ================================================================ painting

def _grid(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    return (x + 0.5) / D, (y + 0.5) / D     # model px


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


def feather(L, W, seed, phase, kind='primary'):
    """One flight feather pointing along +x from its quill at x = 0 (the card spans x 0..L, y -W/2..W/2), for every
    frame: (base frames, glow frames). Leading edge on top (y < 0), the tip rounded and curving back a little; navy
    at the root through the logo's blues to a cloud-white tip behind a dark band, like the logo's letters."""
    w, h = int(round(L * D)), int(round(W * D))
    X, Y = _grid(w, h)
    Y = Y - W / 2
    u = X / L
    yc = Y - 0.12 * W * u ** 2.4                            # the tip curves back (towards the trailing side)
    taper = np.sqrt(np.clip(1 - ((u - 0.70) / 0.30).clip(0, None) ** 2.2, 0, 1))   # a rounded tip
    root = smoothstep(0.0, 0.16, u)
    lead = 0.34 * W * root * taper
    trail = 0.49 * W * smoothstep(0.0, 0.22, u) * taper
    if kind == 'primary':                                   # the outer vane narrows past the notch
        lead = lead * (1 - 0.35 * smoothstep(0.5, 0.6, u))
    inside = ((yc < 0) & (-yc < lead)) | ((yc >= 0) & (yc < trail))
    inside |= (np.abs(yc) < 0.2) & (u < 0.2) & (u > 0)      # the quill
    inside &= (u > 0) & (u < 1.0)
    # a few splits in the trailing vane, like a real feather's
    for k in range(2):
        su = 0.3 + 0.25 * k + 0.08 * math.sin(seed * 1.7 + k)
        split = (yc > trail * 0.45) & (np.abs((u - su) * L - (yc - trail * 0.45) * 0.9) < 0.11)
        inside &= ~split
    across = np.where(yc < 0, -yc / np.maximum(lead, 1e-3), yc / np.maximum(trail, 1e-3))
    t = np.clip(u, 0, 1)
    stops = [(0.0, NAVY), (0.32, DEEP), (0.6, BLUE), (0.77, SKY)]
    col = np.zeros(t.shape + (3,), np.float32) + NAVY
    for (t0, c0), (t1, c1) in zip(stops, stops[1:]):
        k = np.clip((t - t0) / (t1 - t0), 0, 1)[..., None]
        col = np.where((t >= t0)[..., None], c0 + (c1 - c0) * k, col)
    wav = 0.025 * np.sin(Y * 3.3 + seed * 2.1)
    band = (t > 0.775 + wav) & (t < 0.82 + wav)              # the dark band before the white tip
    tip = t >= 0.82 + wav
    col = np.where(band[..., None], OUTLINE * 0.5 + NAVY * 0.5, col)
    puffs = 0.5 + 0.5 * np.sin(X * 4.0 + seed) * np.sin(Y * 4.6 + seed * 3)   # cloud puffs in the white
    col = np.where(tip[..., None], CLOUD * (0.9 + 0.1 * puffs[..., None]) - (CLOUD - ICE) * (puffs < 0.25)[..., None], col)
    # barbs: fine lines sweeping back from the shaft, and soft shading across the vane
    barbs = 0.5 + 0.5 * np.sin((X * 3.2 - np.abs(yc) * 2.2) * math.pi)
    col = col * (0.9 + 0.1 * barbs[..., None])
    col = np.where((yc < 0)[..., None], col * 1.1 + 0.02, col * (1 - 0.22 * smoothstep(0.5, 1.0, across)[..., None]))
    # pixel squares like the logo's letters
    sq = (_hash(np.floor(X * 1.4), np.floor(Y * 1.4), seed) > 0.88) & (t > 0.12) & (t < 0.72) & (across < 0.85)
    col = np.where(sq[..., None], col * 0.45 + ICE * 0.55, col)
    # the shaft: light, thinning out towards the tip
    shaft = (np.abs(yc) < 0.09 + 0.12 * (1 - t)) & (t < 0.86)
    col = np.where(shaft[..., None], col * 0.35 + ICE * 0.65, col)
    col = np.clip(col, 0, 1)
    m = inside
    edge = m & ~_erode(m)
    col = np.where(edge[..., None], OUTLINE, col)
    base = rgba(col, m.astype(np.float32))
    rim = m & ~_erode(_erode(m)) & ~edge & (yc < 0)
    tipg = m & tip & ~edge
    bases, glows = [], []
    for f in range(N):
        tt = f / N
        wave = 0.5 + 0.5 * math.cos(2 * math.pi * (tt - phase))
        run = np.clip(1 - np.abs(((u - (tt - phase) * 1.6) % 1.6) - 0.5) * 5, 0, 1)   # a pulse travelling root -> tip
        g = np.zeros((h, w, 4), np.float32)
        light = (rim * (0.3 + 0.55 * run) + tipg * (0.15 + 0.45 * wave) + (shaft & m) * 0.6 * run).astype(np.float32)
        g[..., :3] = np.where(tipg[..., None], CLOUD, CYAN)
        g[..., 3] = np.clip(light, 0, 1)
        bases.append(base)
        glows.append(g)
    return bases, glows


def coverts(L, W, rows, seed, phase, arch=0.0):
    """Rows of small rounded feathers over a bone (the card spans x 0..L, y -1..W-1): the wing's top edge is a thick
    rounded bar in the logo's blue, with scale-like coverts below it, lightest at the top."""
    w, h = int(round(L * D)), int(round(W * D))
    X, Y = _grid(w, h)
    Y = Y - 1
    m = np.zeros((h, w), bool)
    col = np.zeros((h, w, 3), np.float32)
    shade = np.zeros((h, w), np.float32)
    # rows from the bottom (drawn first, under) to the top
    for r in reversed(range(rows)):
        ry = (r + 0.6) * (W - 2.2) / rows
        size = 1.6 + 0.35 * r / max(1, rows - 1)
        n = int(math.ceil(L / (size * 1.1))) + 1
        for i in range(n):
            cx = (i + (0.5 if r % 2 else 0)) * size * 1.1 - 0.3
            cy = ry + arch * (cx / L) ** 2
            # a round-ended scale pointing down-and-back
            d = np.hypot((X - cx) / (size * 0.62), (Y - cy) / (size * 0.95))
            sc = (d < 1) & (Y > cy - size * 0.6)
            k = np.clip(1 - d, 0, 1)
            tone = 0.35 + 0.4 * (1 - r / max(1, rows - 1)) + 0.25 * k
            m |= sc
            col = np.where(sc[..., None], BLUE * (1 - tone[..., None] * 0.2) + (SKY - BLUE) * tone[..., None] * 0.6, col)
            shade = np.where(sc, np.where(d > 0.78, 1.0, 0.0), shade)
            pix = sc & (_hash(np.floor(X * 1.5), np.floor(Y * 1.5), seed + r * 7 + i) > 0.86) & (d < 0.7)
            col = np.where(pix[..., None], ICE, col)
    # the leading edge: a thick bar along the top
    bar = (Y > -1) & (Y < 1.3 + 0.2 * np.sin(X * 1.3)) & (X > -0.05)
    col = np.where(bar[..., None], DEEP + (SKY - DEEP) * smoothstep(1.2, -0.6, Y)[..., None], col)
    hi = bar & (Y < -0.2)
    col = np.where(hi[..., None], ICE, col)
    bar_sq = bar & (_hash(np.floor(X * 1.5), np.floor(Y * 1.5), seed + 99) > 0.82) & ~hi
    col = np.where(bar_sq[..., None], col * 0.5 + ICE * 0.5, col)
    m |= bar
    col = np.where((shade > 0.5)[..., None] & ~bar[..., None], OUTLINE * 0.6 + col * 0.4, col)
    edge = m & ~_erode(m)
    col = np.where(edge[..., None], OUTLINE, col)
    base = rgba(np.clip(col, 0, 1), m.astype(np.float32))
    rim = hi & ~edge
    bases, glows = [], []
    for f in range(N):
        tt = f / N
        run = np.clip(1 - np.abs(((X / L - (tt - phase) * 1.6) % 1.6) - 0.5) * 4, 0, 1)
        g = np.zeros((h, w, 4), np.float32)
        g[..., :3] = CYAN
        g[..., 3] = (rim * (0.3 + 0.6 * run)).astype(np.float32)
        bases.append(base)
        glows.append(g)
    return bases, glows


# ================================================================ the model

class _Card:
    def __init__(self, frames, glow, x, y, w, h, z):
        self.frames, self.glow, self.x, self.y, self.w, self.h, self.z = frames, glow, x, y, w, h, z


class _Bone:
    def __init__(self, name, pivot, rot=(0, 0, 0)):
        self.name, self.pivot, self.rot = name, list(pivot), list(rot)
        self.cards, self.children = [], []

    def add(self, b):
        self.children.append(b)
        return b


def skeleton(feather=feather, coverts=coverts):
    """The left wing's bones with their cards (bone-local model px; rest pose = straight out, everything at 0).
    feather / coverts paint the cards (another set can reuse this wing's build and motion with its own art)."""
    s = SCALE
    base = _Bone('base', SHOULDER)            # tilts the whole wing back (outermost, so it's a plain tilt)
    wing = base.add(_Bone('wing', (0, 0, 0)))
    arm = wing.add(_Bone('arm', (UPPER * s, 0, 0)))
    hand = arm.add(_Bone('hand', (FORE * s, 0, 0)))
    cards = {}

    def card(bone, kind, args, x, y, z):
        b, g = (feather if kind != 'coverts' else coverts)(*args)
        L, W = args[0], args[1]
        bone.cards.append(_Card(b, g, x * s, y * s, L * s, W * s, z))

    # coverts over each bone, in front of the feathers (z: further from the back = in front when seen from behind)
    card(wing, 'coverts', (UPPER + 0.6, 5.2, 3, 1, 0.0, 0.6), -0.4, -1.0, 0.42)
    card(arm, 'coverts', (FORE + 0.8, 4.6, 3, 2, 0.15, 0.3), -0.3, -1.0, 0.40)
    card(hand, 'coverts', (HAND + 1.6, 3.6, 2, 3, 0.3, 0.0), -0.3, -1.0, 0.38)
    # secondaries on the forearm, primaries on the hand, each on its own bone
    for i, (at, L, W, sp, fo) in enumerate(TERTIALS):
        fb = wing.add(_Bone(f'ter{i + 1}', (at * s, 0.6 * s, 0)))
        b, g = feather(L, W, 10 + i, 0.05 + 0.05 * i, 'secondary')
        fb.cards.append(_Card(b, g, 0, -W / 2 * s, L * s, W * s, 0.33 - 0.015 * i))
    for i, (at, L, W, sp, fo) in enumerate(SECONDARIES):
        fb = arm.add(_Bone(f'sec{i + 1}', (at * s, 0.6 * s, 0)))
        b, g = feather(L, W, 20 + i, 0.15 + 0.05 * i, 'secondary')
        fb.cards.append(_Card(b, g, 0, -W / 2 * s, L * s, W * s, 0.30 - 0.015 * i))
    for i, (at, L, W, sp, fo) in enumerate(PRIMARIES):
        fb = hand.add(_Bone(f'pri{i + 1}', (at * s, 0.3 * s, 0)))
        b, g = feather(L, W, 40 + i, 0.42 + 0.05 * i, 'primary')
        fb.cards.append(_Card(b, g, 0, -W / 2 * s, L * s, W * s, 0.21 - 0.025 * i))
    return base


# a wing pose, in degrees for the left wing (the right one mirrors it)
def pose(tilt=0.0, rise=0.0, sweep=0.0, elbow=0.0, wrist=0.0, fan=1.0, ftwist=0.0, droop=0.0, ebend=0.0, wbend=0.0, drop=0.0, back=0.0):
    """Rotations for every bone. tilt: + leans the whole wing back, its lower part away from the player; rise: + lifts
    it; sweep: + swings it back behind the player; elbow/wrist: + folds the forearm/hand down within the wing's plane;
    ebend/wbend: + bends the forearm/hand back behind the player (the upstroke's bent wrist); fan: 0 feathers
    stacked, 1 spread; ftwist: feathers turned on their shafts, opening slots between them; drop/back: moves the
    wing's root down/back (px), for sneaking, when the leaning body brings the head closer."""
    out = {'base': [tilt, 0, 0], 'wing': [0, -sweep, -rise], 'arm': [0, -ebend, elbow], 'hand': [0, -wbend, wrist]}
    # folded, the feathers hang: each points (nearly) straight down whatever the bones above it do; spread, they fan
    # out from their bone. a1..a3: how far below straight out the upper arm, forearm and hand point
    a1 = -rise
    a2 = a1 + elbow
    a3 = a2 + wrist
    for i, (at, L, W, sp, fo) in enumerate(TERTIALS):
        out[f'ter{i + 1}'] = [ftwist * 0.3, 0, 97 - 7 * i - a1 + droop * 0.3]   # tertials always hang
    for i, (at, L, W, sp, fo) in enumerate(SECONDARIES):
        hang = 92 + 2.5 * i - a2
        a = hang + (sp - hang) * fan
        out[f'sec{i + 1}'] = [ftwist * 0.5, 0, a + droop * 0.5]
    for i, (at, L, W, sp, fo) in enumerate(PRIMARIES):
        hang = max(fo, 96 + 2.5 * i - a3) if a3 < 96 else fo   # along the hand, or hanging if the hand points up
        a = hang + (sp - hang) * fan
        out[f'pri{i + 1}'] = [ftwist * (0.6 + 0.1 * i), 0, a + droop]
    return out


# The poses are fitted to stay clear of the player (every frame checked against where the head, body, arms and legs
# can be): never in front of the back, never inside the body, never where the head can turn or look (standing or
# sneaking), and when standing still or gliding not where the arms and legs are either.
FOLDED = dict(tilt=7.6, rise=-10.9, sweep=16.6, elbow=-53.4, wrist=153.8, droop=2.1, fan=0.0)   # resting on the back
RELAXED = dict(tilt=7.6, rise=-9, sweep=15, elbow=-42, wrist=134, fan=0.18)
TUCKED = dict(tilt=5.4, rise=4.6, sweep=15.1, elbow=-66.6, wrist=151.2, droop=9.0, drop=4.8, back=2.2, fan=0.0)  # sneaking
OPEN = dict(tilt=12.2, rise=-3.3, sweep=14, elbow=-20.3, wrist=-18.8, ebend=6, wbend=8, fan=1.0)


def _mix(a, b, k):
    keys = set(a) | set(b)
    base = dict(tilt=0, rise=0, sweep=0, elbow=0, wrist=0, fan=1, ftwist=0, droop=0, ebend=0, wbend=0, drop=0, back=0)
    return {kk: a.get(kk, base[kk]) + (b.get(kk, base[kk]) - a.get(kk, base[kk])) * k for kk in keys}


REST = None   # the pose built into the bones (clips move from it): set below


def _clip_from(fn, length, keys, loop=True, speed=1.0, stride=None, blend=None):
    """A clip sampled from fn(t) -> pose params; each bone's keys are the pose minus the rest pose built into it."""
    names = list(pose().keys())
    rest = pose(**REST)
    bones = {n: {'rotation': {}} for n in names}
    bones['base']['position'] = {}
    for i in range(keys + 1):
        t = length * i / keys
        q = fn(t if (i < keys or not loop) else 0)
        p = pose(**q)
        tk = f'{t:.4f}'.rstrip('0').rstrip('.') or '0'
        for n in names:
            bones[n]['rotation'][tk] = key([a - b for a, b in zip(p[n], rest[n])], True)
        bones['base']['position'][tk] = key([0, q.get('drop', 0) - REST.get('drop', 0), q.get('back', 0) - REST.get('back', 0)], True)
    return clip(length, bones, loop=loop, speed=speed, stride=stride, blend=blend)


def _ease(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


REST = _mix(FOLDED, RELAXED, 0.35)


def clips_left():
    """The clips for the left wing (degrees; mirrored for the right)."""
    two_pi = 2 * math.pi

    def idle(t):            # resting folded, breathing; every few seconds a little shake-out of the feathers
        b = math.sin(two_pi * t / 4.0)
        ruffle = math.exp(-((t - 2.9) / 0.18) ** 2)
        p = _mix(FOLDED, RELAXED, 0.3 + 0.2 * b)
        p['fan'] += 0.1 * ruffle
        p['ftwist'] = 8 * ruffle * math.sin(two_pi * t * 6)
        return p

    def walk(t):            # folded, rocking with the steps
        s = math.sin(two_pi * t / 1.0)
        p = _mix(FOLDED, RELAXED, 0.25)
        p['rise'] += 1.5 * s
        p['sweep'] += 3 * math.sin(two_pi * t / 1.0 + 0.8)
        return p

    def sprint(t):          # pulled back for speed, quick shivers
        p = dict(FOLDED, sweep=26)
        p['rise'] += 1.2 * math.sin(two_pi * t / 0.6)
        p['ftwist'] = 4 * math.sin(two_pi * t / 0.3)
        return p

    def sneak(t):           # tucked tight
        p = dict(TUCKED)
        p['sweep'] += 1.5 * math.sin(two_pi * t / 3.0)
        return p

    def fall(t):            # half open at once for balance; open all the way if the fall goes on, rocking
        k = 0.55 * _ease(t / 0.25) + 0.45 * _ease((t - 0.45) / 0.4)
        p = _mix(RELAXED, OPEN, k)
        p['elbow'] += -6 * k * math.sin(two_pi * t / 0.9)
        p['ftwist'] = 6 * math.sin(two_pi * t / 0.45) * k
        return p

    def fly(t):             # a full wing beat
        return stroke(t / 0.9)

    def glide(t):           # soaring: wide open, riding the air; one strong beat every few seconds
        c = t / 4.0
        p = dict(OPEN)
        p['elbow'] += -3 * math.sin(two_pi * c) - 1.5 * math.sin(two_pi * c * 3)
        p['ftwist'] = 4 * math.sin(two_pi * c * 2)
        beat_at = 0.62
        if beat_at <= c < beat_at + 0.225:
            q = (c - beat_at) / 0.225
            s = stroke(q)
            w = math.sin(math.pi * q)
            p = {k: p.get(k, 0) + (s.get(k, 0) - p.get(k, 0)) * w for k in set(p) | set(s)}
        return p

    return {
        'idle': _clip_from(idle, 4.0, 32),
        'walk': _clip_from(walk, 1.0, 12, stride=True),
        'sprint': _clip_from(sprint, 0.6, 12, stride=True),
        'sneak': _clip_from(sneak, 3.0, 12, blend=0.004),  # crouching moves the body at once: the wings go with it
        'fall': _clip_from(fall, 1.8, 30),
        'fly': _clip_from(fly, 0.9, 30),
        'glide': _clip_from(glide, 4.0, 56),
    }


def stroke(q):
    """One wing beat, q in cycles: the downstroke (45% of the beat) with the wing straight, wide and closed, sweeping
    down and forward; the upstroke with the wrist bent, the hand tucked back and the feathers twisted open. The upper
    arm stays low (the head can turn into the space above the shoulders); the forearm and hand make the stroke."""
    q -= math.floor(q)
    down = 0.45
    if q < down:
        k = _ease(q / down)
        f = math.sin(math.pi * q / down)
        return dict(tilt=10, rise=-3 - 18 * k, sweep=18 - 6 * k, elbow=-32 + 40 * k, wrist=-14 + 18 * k, fan=1.0, ebend=10, wbend=10,
                    ftwist=-6 * f, droop=-5 * f)
    k = (q - down) / (1 - down)
    e = _ease(k)
    lift = math.sin(math.pi * k)
    return dict(tilt=10 + 4 * lift, rise=-21 + 18 * e, sweep=12 + 6 * e + 6 * lift, elbow=8 - 40 * e + 10 * lift, wrist=4 - 18 * e + 12 * lift,
                ebend=10 + 24 * lift, wbend=10 + 32 * lift, fan=1.0 - 0.3 * lift, ftwist=28 * lift, droop=4 * lift)


def physics_left():
    """Springs for the left wing, parents first: the wing trails the player's moves (leaning and sweeping back as they
    speed up), each part chases its clip (keeping up with it, overshooting where it turns round), and the hand and
    feathers bend against the arm's swing. The limits keep every push inside the clear space around the player."""
    sp = [
        {'bone': 'base', 'stiffness': 220, 'damping': 17, 'limit': 6, 'follow': 1, 'air': [0.05, 0, 0]},
        {'bone': 'wing', 'stiffness': 240, 'damping': 17, 'limit': 12, 'follow': 1,
         'move': [0, -0.25, 0], 'turn': [0, 0.08, 0.06], 'jump': [0, 0, 0.05]},
        {'bone': 'arm', 'stiffness': 200, 'damping': 13, 'limit': 16, 'follow': 1,
         'drag': {'bone': 'wing', 'amount': [0, 0, 0.02]}, 'jump': [0, 0, 0.06], 'move': [0, -0.15, 0]},
        {'bone': 'hand', 'stiffness': 160, 'damping': 10, 'limit': 22, 'follow': 1,
         'drag': {'bone': 'arm', 'amount': [0, 0, 0.024]}, 'jump': [0, 0, 0.1], 'move': [0, -0.2, 0]},
    ]
    for i in range(len(TERTIALS)):
        sp.append({'bone': f'ter{i + 1}', 'stiffness': 120, 'damping': 7.5, 'limit': 20, 'follow': 1,
                   'drag': {'bone': 'wing', 'amount': [0.012, 0, 0.018]}, 'move': [0.22, 0, 0.06], 'jump': [0, 0, 0.1]})
    for i in range(len(SECONDARIES)):
        sp.append({'bone': f'sec{i + 1}', 'stiffness': 125 - 5 * i, 'damping': 7.2, 'limit': 24, 'follow': 1,
                   'drag': {'bone': 'arm', 'amount': [0.016, 0, 0.02]}, 'move': [0.25, 0, 0.1], 'jump': [0, 0, 0.12], 'air': [0, 0, -0.08]})
    for i in range(len(PRIMARIES)):
        sp.append({'bone': f'pri{i + 1}', 'stiffness': 115 - 5 * i, 'damping': 6.6, 'limit': 26, 'follow': 1,
                   'drag': {'bone': 'hand', 'amount': [0.02, 0, 0.022 + 0.003 * i]}, 'move': [0.28, 0, 0.08], 'jump': [0, 0, 0.14], 'air': [0, 0, -0.1]})
    return sp


# ================================================================ writing it out

class HazeWings:
    """wings.build() calls build(out, name); returns the model dict. skeleton: the left wing's bones with their
    painted cards (another set can pass its own painters to skeleton() and keep this wing's build and motion)."""

    def __init__(self, skeleton_fn=None):
        self.skeleton_fn = skeleton_fn

    def build(self, out_dir, name):
        left = (self.skeleton_fn or skeleton)()
        cards = []

        def collect(b):
            for c in b.cards:
                cards.append(c)
            for ch in b.children:
                collect(ch)
        collect(left)
        # pack the atlas: every card's picture once (the right wing and the back faces reuse it, flipped)
        order = sorted(range(len(cards)), key=lambda i: -cards[i].frames[0].shape[0])

        def pack(width):
            x = y = shelf = 0
            place = {}
            for i in order:
                h, w = cards[i].frames[0].shape[:2]
                if x + w + 2 > width:
                    x, y, shelf = 0, y + shelf, 0
                place[i] = (x + 1, y + 1, w, h)
                x += w + 2
                shelf = max(shelf, h + 2)
            height = 1
            while height < y + shelf:
                height *= 2
            return width * height, width, height, place
        widest = max(c.frames[0].shape[1] for c in cards) + 2
        _, width, height, place = min((pack(wd) for wd in (128, 256, 512, 1024) if wd >= widest), key=lambda p: (p[0], p[1]))
        tex = np.zeros((height * N, width, 4), np.float32)
        gtex = np.zeros_like(tex)
        for i, c in enumerate(cards):
            px, py, w, h = place[i]
            for f in range(N):
                _blit(tex, px, py + f * height, c.frames[f])
                _blit(gtex, px, py + f * height, c.glow[f])
        os.makedirs(os.path.join(out_dir, 'models'), exist_ok=True)
        os.makedirs(os.path.join(out_dir, 'textures'), exist_ok=True)
        Image.fromarray(to_u8(tex), 'RGBA').save(os.path.join(out_dir, 'textures', f'{name}.png'), optimize=True)
        Image.fromarray(to_u8(gtex), 'RGBA').save(os.path.join(out_dir, 'textures', f'{name}_glow.png'), optimize=True)
        index = {id(c): i for i, c in enumerate(cards)}

        rest = pose(**REST)

        def emit(b, side):
            sg = 1 if side == 'l' else -1
            b.rot = rest.get(b.name, b.rot)
            cubes = []
            for c in b.cards:
                ix, iy, w, h = place[index[id(c)]]
                x0, x1 = c.x, c.x + c.w
                if side == 'r':
                    x0, x1 = -x1, -x0
                plain, flipped = [ix, iy, ix + w, iy + h], [ix + w, iy, ix, iy + h]
                faces = {'front': plain, 'back': flipped} if side == 'l' else {'front': flipped, 'back': plain}
                cubes.append({'from': [r4(x0), r4(c.y), r4(c.z)], 'to': [r4(x1), r4(c.y + c.h), r4(c.z)], 'faces': faces})
            px, py, pz = b.pivot
            rx, ry, rz = b.rot
            return {'name': f'{b.name}_{side}', 'pivot': [r4(px * sg), r4(py), r4(pz)], 'rot': [r4(rx), r4(ry * sg), r4(rz * sg)],
                    'cubes': cubes, 'children': [emit(ch, side) for ch in b.children]}

        animations = {}
        for st, c in clips_left().items():
            bones = {}
            for bone, ch in c['bones'].items():
                for side, sg in (('l', 1), ('r', -1)):
                    rot = {t: dict(k, post=[r2(k['post'][0]), r2(k['post'][1] * sg), r2(k['post'][2] * sg)]) for t, k in ch['rotation'].items()}
                    bones[f'{bone}_{side}'] = {'rotation': rot}
                    if 'position' in ch and any(any(k['post']) for k in ch['position'].values()):
                        bones[f'{bone}_{side}']['position'] = {t: dict(k, post=[r2(k['post'][0] * sg), r2(k['post'][1]), r2(k['post'][2])]) for t, k in ch['position'].items()}
            animations[st] = dict(c, bones=bones)
        physics = []
        for side, sg in (('l', 1), ('r', -1)):
            for s in physics_left():
                s2 = dict(s, bone=f"{s['bone']}_{side}")
                for k in ('move', 'turn', 'jump', 'air'):
                    if k in s2:
                        s2[k] = [s2[k][0], s2[k][1] * sg, s2[k][2] * sg]
                if 'drag' in s2:
                    s2['drag'] = {'bone': f"{s['drag']['bone']}_{side}", 'amount': s['drag']['amount']}
                physics.append(s2)
        model = {'format': 1, 'anchor': 'body', 'texture': f'textures/{name}.png', 'size': [width, height], 'frames': N,
                 'frameTime': FRAME_MS, 'glow': f'textures/{name}_glow.png', 'translucent': False,
                 'bones': [emit(left, 'l'), emit(left, 'r')], 'animations': animations, 'physics': physics,
                 # fitted to clear the player at their own size: the wing size setting leaves them as they are
                 'scaleAbout': [0, SHOULDER[1], SHOULDER[2]], 'sizeRange': [1, 1]}
        with open(os.path.join(out_dir, 'models', f'{name}.json'), 'w') as fh:
            json.dump(model, fh, separators=(',', ':'))
        return model


def r2(v):
    v = round(float(v), 2)
    return 0.0 if v == 0 else v


def r4(v):
    v = round(float(v), 4)
    return 0.0 if v == 0 else v


def _blit(dst, x, y, img):
    h, w = img.shape[:2]
    dst[y:y + h, x:x + w] = img
    dst[y - 1, x:x + w] = img[0]
    dst[y + h, x:x + w] = img[-1]
    dst[y:y + h, x - 1] = img[:, 0]
    dst[y:y + h, x + w] = img[:, -1]


# ================================================================ bubbles drifting up off the wings

def bubble_sprite(size, i):
    y, x = np.mgrid[0:size, 0:size].astype(np.float32)
    c = (size - 1) / 2
    d = np.hypot(x - c, y - c) / (size / 2)
    ring = np.clip(1 - np.abs(d - (0.78 - 0.08 * i)) * 6, 0, 1)
    spec = np.clip(1 - np.hypot(x - c * 0.65, y - c * 0.65) / (size * 0.14), 0, 1)
    body = np.where(d < 0.75, 0.15, 0)
    a = np.clip(ring + spec + body, 0, 1)
    return rgba(np.ones((size, size, 3), np.float32), a)


PARTICLES = (lambda: np.concatenate([bubble_sprite(16, i % 3) for i in range(3)], 1), dict(
    rate=5, life=[1.6, 2.6], size=[0.035, 0.075], radius=[0.35, 0.9], height=[0.7, 1.6], vx=[-0.05, 0.05], vy=[0.25, 0.5],
    vz=[-0.05, 0.05], swirl=[0.1, 0.3], shrink=False, colors=['#bff4ff', '#e8fbff', '#7fdcff'], glow=True))
