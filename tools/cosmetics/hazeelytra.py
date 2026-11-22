"""The HazeSMP cape's elytra: what Minecraft draws from the cape's texture when you wear an elytra.

Each wing is a feathered bird's wing in HazeSMP's blues, matching the HazeSMP Wings: rows of small scale-like
coverts under a crest at the shoulder, long secondaries below them and the primaries sweeping out along the outer
edge, all with dark outlines, light rims and shafts, and cloud-white tips. It moves all the time (24 looping frames):
a wave of light rolls down the feathers from the shoulder, the feathers breathe one after another, water shimmers
over them, glints twinkle and bubbles drift up. The crest is HazeSMP's H on a gold-rimmed pearl that glows and
catches a shine. The underside (the face towards your back) is the same wing, darker and calmer.

Painted at any size: 10 x 20 elytra pixels, at the cape texture's scale (8 texels a pixel for the HD cape).
"""
import math

import numpy as np

from lib import rgb, rgba, smoothstep, blur

OUTLINE = rgb('#1c1240')
NAVY = rgb('#0d1f5c')
DEEP = rgb('#123f9e')
BLUE = rgb('#1f6fe0')
SKY = rgb('#38b6ff')
ICE = rgb('#9fe6ff')
CLOUD = rgb('#eefbff')
GOLD_HI = rgb('#fff0b8')
GOLD = rgb('#f2c46a')
GOLD_DK = rgb('#7a4a1c')
LETTER_TURN = 66


def _grid(w, h):
    y, x = np.mgrid[0:h, 0:w].astype(np.float32)
    return x + 0.5, y + 0.5


def _rand(seed):
    return np.random.RandomState(seed)


def _outline_of(W, H):
    """The wing's outer edge (texels): straight down from the shoulder, then slanting in to the tip at the bottom
    left. left(v) for v = y / H; right(v) is the straight inner edge along your back."""
    def left(v):
        return 0.5 + W * 0.2 * smoothstep(0.42, 1.0, np.clip(v, 0, 1)) ** 1.2

    def right(v):
        return W - 0.5 + 0 * v

    return left, right


def _trailing(W, H, x):
    """Where the long feathers end: a line from the inner edge half way down to the tip at the bottom left."""
    return H * (0.5 + 0.5 * np.clip((W - x) / (0.78 * W), 0, 1))


def _feathers(W, H):
    """The feathers, painted back to front: (root x, root y, length, width, angle, tier, phase). Angles in radians
    from straight down (positive leans the tip out to the left, towards the wing's outer edge)."""
    rng = _rand(11)
    out = []
    # the flight feathers: a fan from under the coverts, their tips making the trailing edge (longest at the outer
    # tip), the outermost first
    n = 9
    for k in range(n):
        q = k / (n - 1)
        rx, ry = W * (0.08 + 0.86 * q), H * 0.2
        tx = W * (0.24 + 0.72 * q)
        ty = float(_trailing(W, H, tx)) - H * 0.005
        ang = math.atan2(rx - tx, ty - ry)
        length = math.hypot(rx - tx, ty - ry)
        out.append((rx, ry, length, W * (0.2 - 0.03 * q), ang, 'primary' if q < 0.45 else 'secondary', rng.uniform()))
    # coverts: rows of short rounded feathers over the roots, the lower rows first so the rows above overlap them
    left, _ = _outline_of(W, H)
    rows = 3
    for r in reversed(range(rows)):
        v0 = 0.17 + r * 0.075
        lo, hi = float(left(v0)) - W * 0.02, W * 1.04
        cols = 4 if r % 2 == 0 else 5
        for c in range(cols):
            x = lo + (hi - lo) * (c + (0.5 if r % 2 == 0 else 0.0) + 0.1) / (cols + 0.3)
            out.append((x, H * v0, H * (0.15 + 0.015 * r), W * 0.3, 0.12 - 0.12 * x / W, 'covert', rng.uniform()))
    return out


def _paint_feathers(W, H, t, dark):
    """The feathered wing (rgba), frame at time t (0..1 through the loop)."""
    X, Y = _grid(W, H)
    v = Y / H
    left, right = _outline_of(W, H)
    inside = (X >= left(v)) & (X <= right(v)) & (Y < H - 0.5) & (Y <= _trailing(W, H, X) + H * 0.04)
    col = np.zeros((H, W, 3), np.float32)
    col[:] = NAVY
    alpha = np.zeros((H, W), np.float32)
    owner = np.full((H, W), -1, np.int32)
    tau = 2 * math.pi * t
    feathers = _feathers(W, H)
    # the shoulder: solid under the coverts, so no gaps show at the top
    shoulder = inside & (v < 0.2)
    col[shoulder] = NAVY * 0.9 + DEEP * 0.1
    alpha[shoulder] = 1
    for i, (cx, cy, length, width, ang, tier, phase) in enumerate(feathers):
        ca, sa = math.cos(ang), math.sin(ang)
        dx, dy = X - cx, Y - cy
        u = (dx * -sa + dy * ca) / length          # 0 at the root, 1 at the tip
        w = (dx * ca + dy * sa) / (width / 2)      # -1 .. 1 across
        hw = np.sqrt(np.clip(1 - (2 * u - 1) ** 2, 0, 1)) ** 0.8
        hw = np.where(u < 0.5, np.maximum(hw, 0.85), hw)   # a broad root, rounded tip
        m = (u >= -0.05) & (u <= 1) & (np.abs(w) <= hw) & (Y < H - 0.5) & inside
        if not m.any():
            continue
        uu, ww = np.clip(u, 0, 1), np.abs(w) / np.maximum(hw, 1e-3)
        if tier == 'covert':
            base = DEEP + (BLUE - DEEP) * uu[..., None] * 0.9
        elif tier == 'secondary':
            base = DEEP + (SKY - DEEP) * smoothstep(0.1, 0.9, uu)[..., None]
        else:
            base = BLUE + (SKY - BLUE) * smoothstep(0.0, 0.8, uu)[..., None]
        c = base.copy()
        # the barbs: fine diagonal lines
        if tier != 'covert':
            barbs = 0.5 + 0.5 * np.sin(uu * 18 * math.pi + ww * 3.0)
            c *= (0.9 + 0.1 * barbs)[..., None]
            # the shaft down the middle, lighter
            c = np.where((ww < 0.11)[..., None] & (uu > 0.05)[..., None], c * 0.6 + ICE * 0.4, c)
        else:
            # coverts are soft and rounded: lighter towards the middle of their tip
            c = c * (0.85 + 0.25 * (1 - ww) * uu)[..., None]
        # a light rim on the outer side, the cloud-white tips
        rim = (ww > 0.72) & (np.sign(w) * np.sign(-ang + 0.3) > 0)
        c = np.where(rim[..., None], c * 0.45 + ICE * 0.55, c)
        tip0 = 0.8 if tier == 'covert' else 0.72
        tipm = smoothstep(tip0, tip0 + 0.14, uu)
        c = c * (1 - tipm[..., None]) + CLOUD * tipm[..., None]
        # the feathers breathe one after another, from the shoulder out
        c *= (0.93 + 0.09 * np.sin(tau - cy / H * 5 + phase * 6))
        # the dark outline round each feather
        edge = (ww > 0.86) | (u > 0.93) | (u < 0.0)
        c = np.where(edge[..., None], OUTLINE, c)
        col[m] = c[m]
        alpha[m] = 1
        owner[m] = i
    # the outline round the whole wing
    sil = alpha > 0.5
    outer = sil & (blur(sil.astype(np.float32), max(0.6, W / 110)) < 0.93)
    col[outer] = OUTLINE

    # ---- the motion on top
    feathered = sil & ~outer
    # a wave of light rolling down from the shoulder to the tips, once a loop
    pos = -0.25 + t * 1.5
    d = v + (W - X) / W * 0.12
    wave = np.exp(-((d - pos) / 0.07) ** 2) + 0.5 * np.exp(-((d - pos + 0.16) / 0.05) ** 2)
    # water shimmering over the feathers
    sh = (np.sin(X * 0.42 / (W / 80) + Y * 0.13 / (W / 80) + tau) + np.sin(X * 0.21 / (W / 80) - Y * 0.29 / (W / 80) - 2 * tau)
          + np.sin((X + Y) * 0.17 / (W / 80) + tau)) / 3
    shimmer = np.clip(sh, 0, 1) ** 3
    k = (0.55 if dark else 1.0)
    light = (wave * 0.45 + shimmer * 0.22) * k
    col = np.where(feathered[..., None], np.clip(col + light[..., None] * ICE, 0, 1), col)
    if dark:
        col = np.where(sil[..., None], col * 0.58 + NAVY * 0.12, col)
    else:
        _glints(col, feathered, W, H, t)
        _bubbles(col, feathered, W, H, t)
    out = rgba(col, sil.astype(np.float32))
    return out


def _glints(col, where, W, H, t):
    """Little four-pointed glints that flash for a few frames each, at fixed places through the loop."""
    X, Y = _grid(W, H)
    rng = _rand(5)
    s = W / 80
    for i in range(9):
        x, y = rng.uniform(0.2, 0.95) * W, rng.uniform(0.18, 0.92) * H
        t0 = rng.uniform()
        life = ((t - t0) % 1.0) / 0.12
        if life > 1:
            continue
        a = math.sin(math.pi * life)
        r = (2.2 + 1.6 * a) * s
        dx, dy = np.abs(X - x), np.abs(Y - y)
        star = ((dx < 0.6 * s) & (dy < r)) | ((dy < 0.6 * s) & (dx < r)) | ((dx + dy) < 1.4 * s * a + 0.5 * s)
        m = star & where
        col[m] = col[m] * (1 - a) + CLOUD * a


def _bubbles(col, where, W, H, t):
    """A few small bubbles drifting up over the wing; each goes up a whole number of times a loop."""
    X, Y = _grid(W, H)
    rng = _rand(9)
    s = W / 80
    for i in range(6):
        x0, p0, r = rng.uniform(0.25, 0.9) * W, rng.uniform(), rng.uniform(1.6, 2.8) * s
        p = (p0 + t) % 1.0
        y = H * 1.02 - p * H * 1.05
        x = x0 + math.sin(2 * math.pi * (p * 2 + p0)) * 2 * s
        d = np.hypot(X - x, Y - y)
        ring = (d < r) & (d > r - 0.9 * s)
        spec = np.hypot(X - (x - r * 0.35), Y - (y - r * 0.35)) < r * 0.3
        fade = smoothstep(1.0, 0.85, p) * smoothstep(0.0, 0.1, p)
        m = (ring | spec) & where
        col[m] = col[m] * (1 - 0.8 * fade) + CLOUD * 0.8 * fade


def _crest(W, H, t, letter):
    """The crest at the shoulder: HazeSMP's H on a pearl in a gold ring, glowing, with a shine crossing it."""
    X, Y = _grid(W, H)
    s = W / 80
    cx, cy, R = W * 0.62, H * 0.115, 13.0 * s
    d = np.hypot(X - cx, Y - cy)
    img = np.zeros((H, W, 4), np.float32)
    ring = (d <= R) & (d > R - 2.6 * s)
    disc = d <= R - 2.6 * s
    shade = 0.75 + 0.25 * np.clip(1 - np.hypot(X - (cx - R * 0.35), Y - (cy - R * 0.35)) / R, 0, 1)
    img[disc, :3] = (NAVY * 0.6 + DEEP * 0.4) * shade[disc, None]
    gold = GOLD + (GOLD_HI - GOLD) * np.clip((cy - Y) / R, 0, 1)[..., None]
    img[ring, :3] = gold[ring]
    img[(d <= R) & (d > R - 0.9 * s), :3] = GOLD_DK
    img[d <= R, 3] = 1
    # the H, centred, in ice and white
    if letter is not None:
        lh, lw = letter.shape[:2]
        size = int(R * 1.25)
        from PIL import Image
        im = Image.fromarray((letter * 255).astype(np.uint8)).resize((max(1, int(size * lw / lh)), size), Image.LANCZOS)
        # turned so it stands upright while you fly (the wings spread out about 66 degrees then)
        L = np.asarray(im.rotate(LETTER_TURN, resample=Image.BICUBIC, expand=True), np.float32) / 255
        x0, y0 = int(cx - L.shape[1] / 2), int(cy - L.shape[0] / 2)
        a = L[..., 3:4]
        region = img[y0:y0 + L.shape[0], x0:x0 + L.shape[1]]
        lc = np.clip(L[..., :3] * 0.5 + ICE * 0.6, 0, 1)
        region[..., :3] = region[..., :3] * (1 - a) + lc * a
    # glow pulse round the ring and a shine across the pearl
    tau = 2 * math.pi * t
    glow = np.exp(-((d - R) / (2.2 * s)) ** 2) * (0.35 + 0.25 * math.sin(tau))
    pos = -0.4 + ((t * 2) % 1.0) * 1.8
    band = np.exp(-((((X - cx) + (Y - cy) * 0.6) / R - pos) / 0.18) ** 2) * (d <= R)
    img[..., :3] = np.clip(img[..., :3] + band[..., None] * 0.6, 0, 1)
    return img, glow


def elytra(W, H, f, N, letter=None):
    """The outside of one elytra wing (what you see from behind), W x H texels, frame f of N."""
    t = f / N
    img = _paint_feathers(W, H, t, dark=False)
    crest, glow = _crest(W, H, t, letter)
    sil = img[..., 3] > 0.5
    img[..., :3] = np.where(sil[..., None], np.clip(img[..., :3] + glow[..., None] * SKY, 0, 1), img[..., :3])
    a = crest[..., 3:4]
    img[..., :3] = img[..., :3] * (1 - a) + crest[..., :3] * a
    img[..., 3] = np.maximum(img[..., 3], crest[..., 3])
    return img


def underside(W, H, f, N):
    """The face towards your back: the same wing, mirrored so its edges meet the outside, darker and calmer."""
    t = f / N
    return _paint_feathers(W, H, t, dark=True)[:, ::-1].copy()
