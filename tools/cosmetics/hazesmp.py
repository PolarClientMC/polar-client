"""HazeSMP's set (partner server): a code-only collection in HazeSMP's underwater blues.

    HazeSMP Cape   (HD, with its own elytra)     capes.ITEMS     (elytra: hazeelytra.py)
    HazeSMP Wings  (articulated, with physics)   wings.ITEMS     (hazewings.py)
    HazeSMP Trail  (layered particles)           auras.ITEMS     (hazetrail.py)
    HazeSMP Tide Crown                           heads.ITEMS     (hazecrown.py)

Painted from HazeSMP's own art (assets/hazesmp: their banner, a kelp forest under water with light coming down, and
their HAZE SMP logo): the banner fills the cape, the logo sits on it, and the water moves - light shafts sway,
caustics ripple, bubbles rise, the logo glows and bobs, and a shine sweeps across it.
The cape is HD: 8 texels per pixel (a 4x cape is built too, for older Polar versions).
Importing this module adds the items to their kinds (build.py does that).
"""
import math
import os

import numpy as np
from PIL import Image, ImageFilter

from lib import rgb, rgba, smoothstep, blur, over, to_u8

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, 'assets', 'hazesmp')
COLLECTION = {'id': 'hazesmp', 'name': 'HazeSMP'}

OUTLINE = '#1c1240'   # the logo's dark outline: the cape's border and seams
DEEP = '#04141f'
LIGHT = rgb('#bff4ff')


def _img(name):
    return Image.open(os.path.join(ASSETS, name))


def grid(W, H):
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    return x + 0.5, y + 0.5


def hashn(x, y, seed=0):
    h = np.sin(x * 12.9898 + y * 78.233 + seed * 37.719) * 43758.5453
    return h - np.floor(h)


def _banner(W, H, cx=0.5, top=0.0, zoom=1.0):
    """The banner, cropped to W:H around cx (0..1 across it) and resized to W x H texels."""
    b = _img('banner.png').convert('RGB')
    ch = b.height / zoom
    cw = ch * W / H
    x0 = min(max(0, b.width * cx - cw / 2), b.width - cw)
    y0 = top * (b.height - ch)
    crop = b.resize((W, H), Image.LANCZOS, box=(x0, y0, x0 + cw, y0 + ch))
    crop = crop.filter(ImageFilter.UnsharpMask(radius=1.2, percent=60, threshold=1))
    return np.asarray(crop, np.float32) / 255


_LOGO = {}


def _logo(width, name='logo.png'):
    """The HAZE SMP logo at `width` texels, crisp (alpha cut to hard edges like pixel art)."""
    key = (width, name)
    if key not in _LOGO:
        l = _img(name).convert('RGBA')
        h = round(l.height * width / l.width)
        s = l.resize((width, h), Image.LANCZOS).filter(ImageFilter.UnsharpMask(radius=0.8, percent=80, threshold=0))
        a = np.asarray(s, np.float32) / 255
        a[..., 3] = (a[..., 3] > 0.45).astype(np.float32)
        _LOGO[key] = a
    return _LOGO[key]


def _paste(dst, src, x, y):
    """src (h x w x 4) over dst at (x, y), clipped."""
    H, W = dst.shape[:2]
    h, w = src.shape[:2]
    x0, y0, x1, y1 = max(0, x), max(0, y), min(W, x + w), min(H, y + h)
    if x0 >= x1 or y0 >= y1:
        return
    s = src[y0 - y:y1 - y, x0 - x:x1 - x]
    a = s[..., 3:4]
    dst[y0:y1, x0:x1, :3] = dst[y0:y1, x0:x1, :3] * (1 - a) + s[..., :3] * a
    dst[y0:y1, x0:x1, 3:4] = np.maximum(dst[y0:y1, x0:x1, 3:4], a)


def _water(img, W, H, t, sun=(0.5, -0.08), rays=7, seed=1):
    """Moving water on top of the banner: light shafts from the surface that sway, rippling caustics near the top,
    and a slow brightening pulse. t: 0..1 through the loop (everything loops)."""
    X, Y = grid(W, H)
    sx, sy = sun[0] * W, sun[1] * H
    ang = np.arctan2(X - sx, Y - sy)                      # 0 straight down
    dist = np.hypot(X - sx, Y - sy) / H
    tau = 2 * math.pi * t
    shafts = np.zeros_like(X)
    for k in range(rays):
        a0 = (k - (rays - 1) / 2) * 0.16 + 0.05 * math.sin(tau + k * 1.7)
        width = 0.035 + 0.02 * (0.5 + 0.5 * math.sin(tau * 2 + k * 2.3))
        bright = 0.55 + 0.45 * math.sin(tau + k * 0.9)
        shafts += bright * np.exp(-((ang - a0) / width) ** 2)
    shafts *= np.exp(-dist * 1.6) * smoothstep(0.0, 0.12, dist)
    # caustics: two sets of waves crossing, strongest just under the surface
    c = (np.sin(X * 0.32 + Y * 0.11 + tau) + np.sin(X * 0.17 - Y * 0.27 - tau * 2) + np.sin((X + Y) * 0.21 + tau)) / 3
    caustic = np.clip(c, 0, 1) ** 3 * np.exp(-Y / (H * 0.3))
    pulse = 0.85 + 0.15 * math.sin(tau)
    light = (shafts * 0.5 + caustic * 0.35) * pulse
    img[..., :3] = np.clip(img[..., :3] + light[..., None] * LIGHT, 0, 1)
    return img


def _bubbles(img, W, H, t, n, seed, size=(1.2, 2.6)):
    """Bubbles rising and wobbling; each goes up a whole number of times per loop, so the loop is seamless."""
    X, Y = grid(W, H)
    rng = np.random.RandomState(seed)
    col = img[..., :3]
    for i in range(n):
        x0, p0, r = rng.uniform(4, W - 4), rng.uniform(0, 1), rng.uniform(*size) * W / 80
        laps = rng.choice([1, 1, 2])
        p = (p0 + t * laps) % 1.0
        y = H + 6 - p * (H + 12)
        x = x0 + math.sin(2 * math.pi * (p * 3 + p0)) * 2.2 * W / 80
        d = np.hypot(X - x, Y - y)
        ring = smoothstep(r + 0.9, r, d) * smoothstep(r - 1.4, r - 0.4, d)        # the rim
        body = smoothstep(r, r - 1, d) * 0.18                                      # faint inside
        spec = smoothstep(r * 0.45, 0, np.hypot(X - (x - r * 0.4), Y - (y - r * 0.4)))  # a highlight
        fade = smoothstep(1.0, 0.85, p) * smoothstep(0.0, 0.08, p)
        a = np.clip(ring * 0.75 + body + spec * 0.9, 0, 1) * fade
        col[:] = col * (1 - a[..., None]) + np.array([0.86, 0.98, 1.0]) * a[..., None]
    return img


def _shine(src, t, width=0.12, strength=0.85):
    """A diagonal shine crossing src (h x w x 4) once in the first part of the loop, inside its alpha only."""
    h, w = src.shape[:2]
    X, Y = grid(w, h)
    pos = -0.3 + (t / 0.45) * 1.6 if t < 0.45 else 9
    d = (X / w + Y / h * 0.5) / 1.5 - pos
    band = np.exp(-(d / width) ** 2) * src[..., 3]
    out = src.copy()
    out[..., :3] = np.clip(out[..., :3] + band[..., None] * strength, 0, 1)
    return out


def _border(img, W, H):
    """A thin outline in the logo's dark blue round the cape, with a lighter inner line."""
    X, Y = grid(W, H)
    e = np.minimum(np.minimum(X, W - X), np.minimum(Y, H - Y))
    s = W / 80
    img[..., :3] = np.where((e < 1.6 * s)[..., None], rgb(OUTLINE), img[..., :3])
    inner = (e >= 1.6 * s) & (e < 2.6 * s)
    img[..., :3] = np.where(inner[..., None], img[..., :3] * 0.55 + rgb('#5fd4ff') * 0.45, img[..., :3])
    return img


def cape(W, H, f, N):
    """The outside of the HazeSMP cape (W x H texels), frame f of N."""
    t = f / N
    img = rgba(np.zeros((H, W, 3), np.float32))
    img[..., :3] = _banner(W, H, cx=0.5, zoom=1.0)
    # deepen towards the bottom, so the logo and the light stand out
    _, Y = grid(W, H)
    img[..., :3] *= (1 - 0.35 * smoothstep(0.45, 1.0, Y / H))[..., None]
    _water(img, W, H, t)
    _bubbles(img, W, H, t, 16, 7)
    # the logo, bobbing gently, with a soft glow behind it and a shine sweeping across
    lw = round(W * 0.8)
    logo = _logo(lw)
    lh = logo.shape[0]
    bob = round(math.sin(2 * math.pi * t) * W / 80)
    lx, ly = (W - lw) // 2, round(H * 0.12) + bob
    glow = np.zeros((H, W), np.float32)
    glow[max(0, ly):ly + lh, lx:lx + lw] = logo[:min(lh, H - ly), :, 3] if ly >= 0 else logo[-ly:, :, 3]
    glow = blur(glow, W / 14) * (0.55 + 0.25 * math.sin(2 * math.pi * t))
    img[..., :3] = np.clip(img[..., :3] + glow[..., None] * rgb('#3fc6ff') * 0.9, 0, 1)
    _paste(img, _shine(logo, t), lx, ly)
    return _border(img, W, H)


def lining(W, H, f, N):
    """The inside of the cape: the deep water, darker, with bubbles."""
    t = f / N
    img = rgba(np.zeros((H, W, 3), np.float32))
    img[..., :3] = _banner(W, H, cx=0.3, zoom=1.15) * 0.45
    _water(img, W, H, t, rays=4)
    _bubbles(img, W, H, t, 6, 11)
    return _border(img, W, H)


def elytra(W, H, f, N):
    """The elytra's outside: a feathered wing with HazeSMP's H on a crest at the shoulder (hazeelytra.py)."""
    import hazeelytra
    full = _logo(max(64, round(W * 2.4)))
    letter = full[:round(full.shape[0] * 0.43), :round(full.shape[1] * 0.27)]   # the H: the first quarter of HAZE
    return hazeelytra.elytra(W, H, f, N, letter)


def elytra_inside(W, H, f, N):
    """The elytra's underside (towards your back): the same wing, darker."""
    import hazeelytra
    return hazeelytra.underside(W, H, f, N)


CAPE = dict(id='cape_hazesmp', name='HazeSMP Cape', collection='hazesmp', rarity='mythic', paint=cape, back=lining, elytra=elytra,
            elytra_inside=elytra_inside,
            description="HazeSMP's partner cape: their logo over a kelp forest under water, with swaying light, rising bubbles and a "
                        'shine across the logo. Wear an elytra and it turns into feathered ocean wings with a shimmering light and HazeSMP\'s crest.',
            frames=24, frameTime=90, edge=OUTLINE, special=True, hd=True)


def _wings():
    import hazewings
    return hazewings.HazeWings(), None


WINGS = dict(id='wings_hazesmp', name='HazeSMP Wings', collection='hazesmp', rarity='mythic', make=_wings, special=True,
             description="HazeSMP's partner wings: big ocean-blue feathered wings that fold, spread, glide and beat like a "
                         "real bird's, with cloud-white tips, a light running along the feathers and bubbles drifting up.")


def register():
    """Adds HazeSMP's set to the kinds build.py builds."""
    import auras
    import capes
    import heads
    import wings
    import hazecrown
    import hazetrail
    import hazewings
    if all(i['id'] != CAPE['id'] for i in capes.ITEMS):
        capes.ITEMS.append(CAPE)
    if all(i['id'] != WINGS['id'] for i in wings.ITEMS):
        wings.ITEMS.append(WINGS)
    wings.PARTICLES[WINGS['id']] = hazewings.PARTICLES
    if all(i['id'] != hazetrail.TRAIL['id'] for i in auras.ITEMS):
        auras.ITEMS.append(hazetrail.TRAIL)
    if all(i['id'] != hazecrown.CROWN['id'] for i in heads.ITEMS):
        heads.ITEMS.append(hazecrown.CROWN)
