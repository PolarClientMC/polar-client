"""Polar name tags: the badges shown next to a player's name (above their head and in the tab list), given by admins.

Each tag is a little pill drawn in code: a gradient in the tag's colours with a dark rim and a lit top edge, an icon
and the label in Poppins Bold. They're animated: a shine sweeps across every few seconds (LIVE blinks its dot,
LEGEND cycles through every colour).

    python tools/cosmetics/tags.py

writes everything from the TAGS list below:
    mod/common/.../textures/font/tags/<id>.png   16 frames side by side, drawn as glyphs of the polarclient:tags font
    mod/common/.../font/tags.json                 that font (one bitmap provider per tag, 9 units tall)
    mod/common/.../cosmetics/PolarTagList.java    the tags the mod knows: id, first glyph, how it animates, colour
    server/src/tags.js                            the tags the server can give (id, label, colour, what it means)
    server/public/cosmetics/tags/<id>.png         a still picture of each tag, for the launcher and the admin panel

To add a tag: add a line to TAGS (and an icon below if it needs a new one), run this, then deploy the server and
release (the mod needs the new glyphs). Ids never change once given out.
"""
import colorsys
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, 'mod', 'common', 'src', 'main', 'resources', 'assets', 'polarclient')
FONT_FILE = os.path.join(RES, 'font', 'poppins_bold.ttf')

# id, label, icon, top colour, bottom colour, animation, what it means (shown on profile cards)
TAGS = [
    ('owner', 'OWNER', 'crown', '#ffe27a', '#e0901a', 'shine', 'Owns Polar Client'),
    ('dev', 'DEV', 'code', '#6ee7ff', '#2563eb', 'shine', 'Makes Polar Client'),
    ('admin', 'ADMIN', 'bolt', '#ff7a7a', '#c81e3c', 'shine', 'Polar admin'),
    ('staff', 'STAFF', 'shield', '#ffd36b', '#ea7a12', 'shine', 'Polar staff'),
    ('mod', 'MOD', 'hammer', '#6cf2a8', '#0f9b55', 'shine', 'Keeps Polar safe'),
    ('helper', 'HELPER', 'plus', '#7ff0dc', '#0d9488', 'shine', 'Helps players out'),
    ('partner', 'PARTNER', 'handshake', '#d4a8ff', '#7c3aed', 'shine', 'Runs a partner server'),
    ('creator', 'CREATOR', 'play', '#ff6b6b', '#b4122b', 'shine', 'Makes videos about Polar'),
    ('live', 'LIVE', 'dot', '#ff5c7a', '#be123c', 'blink', 'Streams with Polar'),
    ('booster', 'BOOSTER', 'sparkle', '#ffb3f5', '#c026d3', 'shine', 'Boosts the Polar Discord'),
    ('vip', 'VIP', 'gem', '#ff9cc0', '#db2777', 'shine', 'Polar VIP'),
    ('og', 'OG', 'star', '#5ff09a', '#15803d', 'shine', 'One of the first players'),
    ('early', 'EARLY', 'snowflake', '#8fd6ff', '#2580d0', 'shine', 'Joined Polar early'),
    ('tester', 'TESTER', 'flask', '#d9f99d', '#4d8c0e', 'shine', 'Tests new Polar features'),
    ('builder', 'BUILDER', 'pickaxe', '#ffc58a', '#d9580b', 'shine', 'Builds for Polar'),
    ('artist', 'ARTIST', 'brush', '#ffa8e8', '#8b5cf6', 'shine', 'Designs for Polar'),
    ('polar', 'POLAR+', 'paw', '#a8e4ff', '#2f8fd8', 'shine', 'Supports Polar Client'),
    ('legend', 'LEGEND', 'trophy', '#ffffff', '#ffffff', 'rainbow', 'A Polar legend'),
    ('hazesmp', 'HAZESMP', 'wave', '#5fd4ff', '#1d4ed8', 'shine', 'Plays on HazeSMP'),
    ('vexcraft', 'VEXCRAFT', 'vee', '#c084fc', '#4c1d95', 'shine', 'Plays on VexCraft'),
]
BASE = 0xE100           # the first glyph; each tag gets FRAMES glyphs from BASE + index * FRAMES
FRAMES = 16
H = 18                  # the picture's height in pixels: 2 pixels per text unit (it shows 9 units tall)
SS = 4                  # drawn this much bigger, then scaled down, for smooth edges


def rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def _font(size):
    return ImageFont.truetype(FONT_FILE, size)


# ---------------------------------------------------------------- icons (drawn white in a box of s x s at x0, y0)

def icon(d, name, x0, y0, s, fill=(255, 255, 255, 255)):
    def P(pts):
        return [(x0 + x * s, y0 + y * s) for x, y in pts]

    def C(cx, cy, r):
        return (x0 + (cx - r) * s, y0 + (cy - r) * s, x0 + (cx + r) * s, y0 + (cy + r) * s)
    w = max(1, int(s * 0.13))
    if name == 'crown':
        d.polygon(P([(0.05, 0.3), (0.3, 0.55), (0.5, 0.15), (0.7, 0.55), (0.95, 0.3), (0.85, 0.85), (0.15, 0.85)]), fill=fill)
        for cx, cy in ((0.05, 0.27), (0.5, 0.12), (0.95, 0.27)):
            d.ellipse(C(cx, cy, 0.09), fill=fill)
    elif name == 'code':
        d.line(P([(0.32, 0.22), (0.08, 0.5), (0.32, 0.78)]), fill=fill, width=w, joint='curve')
        d.line(P([(0.68, 0.22), (0.92, 0.5), (0.68, 0.78)]), fill=fill, width=w, joint='curve')
        d.line(P([(0.58, 0.14), (0.42, 0.86)]), fill=fill, width=w)
    elif name == 'bolt':
        d.polygon(P([(0.6, 0.02), (0.15, 0.58), (0.45, 0.58), (0.38, 0.98), (0.85, 0.4), (0.55, 0.4)]), fill=fill)
    elif name == 'shield':
        d.polygon(P([(0.5, 0.04), (0.9, 0.18), (0.86, 0.55), (0.5, 0.96), (0.14, 0.55), (0.1, 0.18)]), fill=fill)
    elif name == 'hammer':   # a gavel, tilted
        d.polygon(P([(0.38, 0.02), (0.98, 0.62), (0.78, 0.82), (0.18, 0.22)]), fill=fill)
        d.line(P([(0.5, 0.5), (0.06, 0.94)]), fill=fill, width=int(w * 1.2))
    elif name == 'plus':
        d.rounded_rectangle((x0 + 0.38 * s, y0 + 0.08 * s, x0 + 0.62 * s, y0 + 0.92 * s), radius=s * 0.06, fill=fill)
        d.rounded_rectangle((x0 + 0.08 * s, y0 + 0.38 * s, x0 + 0.92 * s, y0 + 0.62 * s), radius=s * 0.06, fill=fill)
    elif name == 'handshake':
        d.polygon(P([(0.5, 0.06), (0.94, 0.5), (0.5, 0.94), (0.06, 0.5)]), fill=fill)
        d.polygon(P([(0.5, 0.3), (0.7, 0.5), (0.5, 0.7), (0.3, 0.5)]), fill=(0, 0, 0, 0))
    elif name == 'play':
        d.rounded_rectangle((x0, y0 + 0.14 * s, x0 + s, y0 + 0.86 * s), radius=s * 0.2, fill=fill)
        d.polygon(P([(0.4, 0.32), (0.7, 0.5), (0.4, 0.68)]), fill=(0, 0, 0, 0))
    elif name == 'dot':
        d.ellipse(C(0.5, 0.5, 0.3), fill=fill)
    elif name == 'sparkle':
        d.polygon(P([(0.5, 0.0), (0.62, 0.38), (1.0, 0.5), (0.62, 0.62), (0.5, 1.0), (0.38, 0.62), (0.0, 0.5), (0.38, 0.38)]), fill=fill)
    elif name == 'gem':
        d.polygon(P([(0.22, 0.12), (0.78, 0.12), (0.98, 0.38), (0.5, 0.94), (0.02, 0.38)]), fill=fill)
        d.line(P([(0.02, 0.38), (0.98, 0.38)]), fill=(0, 0, 0, 0), width=max(1, w // 2))
    elif name == 'star':
        pts = []
        for i in range(10):
            a = -math.pi / 2 + i * math.pi / 5
            r = 0.5 if i % 2 == 0 else 0.22
            pts.append((0.5 + r * math.cos(a), 0.54 + r * math.sin(a)))
        d.polygon(P(pts), fill=fill)
    elif name == 'snowflake':
        for k in range(3):
            a = k * math.pi / 3
            d.line(P([(0.5 - 0.46 * math.cos(a), 0.5 - 0.46 * math.sin(a)), (0.5 + 0.46 * math.cos(a), 0.5 + 0.46 * math.sin(a))]), fill=fill, width=w)
        d.ellipse(C(0.5, 0.5, 0.12), fill=fill)
    elif name == 'flask':
        d.polygon(P([(0.38, 0.06), (0.62, 0.06), (0.62, 0.38), (0.92, 0.9), (0.08, 0.9), (0.38, 0.38)]), fill=fill)
        d.polygon(P([(0.3, 0.6), (0.7, 0.6), (0.82, 0.8), (0.18, 0.8)]), fill=(0, 0, 0, 0))
    elif name == 'pickaxe':   # tilted, the head curving over the handle
        d.arc(C(0.62, 0.62, 0.52), 195, 345, fill=fill, width=int(w * 1.5))
        d.line(P([(0.66, 0.32), (0.08, 0.92)]), fill=fill, width=int(w * 1.1))
    elif name == 'brush':
        d.polygon(P([(0.82, 0.05), (0.95, 0.18), (0.48, 0.62), (0.38, 0.52)]), fill=fill)
        d.polygon(P([(0.3, 0.56), (0.44, 0.7), (0.3, 0.9), (0.05, 0.95), (0.12, 0.72)]), fill=fill)
    elif name == 'paw':
        d.ellipse(C(0.5, 0.66, 0.26), fill=fill)
        for cx, cy in ((0.17, 0.38), (0.38, 0.18), (0.62, 0.18), (0.83, 0.38)):
            d.ellipse(C(cx, cy, 0.12), fill=fill)
    elif name == 'trophy':
        d.polygon(P([(0.22, 0.08), (0.78, 0.08), (0.72, 0.5), (0.5, 0.62), (0.28, 0.5)]), fill=fill)
        d.arc(C(0.22, 0.28, 0.17), 90, 270, fill=fill, width=max(1, w // 2 + 1))
        d.arc(C(0.78, 0.28, 0.17), 270, 90, fill=fill, width=max(1, w // 2 + 1))
        d.rectangle((x0 + 0.44 * s, y0 + 0.6 * s, x0 + 0.56 * s, y0 + 0.8 * s), fill=fill)
        d.rounded_rectangle((x0 + 0.26 * s, y0 + 0.8 * s, x0 + 0.74 * s, y0 + 0.95 * s), radius=s * 0.04, fill=fill)
    elif name == 'wave':
        pts = [(0.04 + i / 20 * 0.92, 0.5 - 0.2 * math.sin(i / 20 * 2 * math.pi * 1.25)) for i in range(21)]
        d.line(P(pts), fill=fill, width=w, joint='curve')
        pts = [(0.04 + i / 20 * 0.92, 0.8 - 0.14 * math.sin(i / 20 * 2 * math.pi * 1.25)) for i in range(21)]
        d.line(P(pts), fill=fill, width=max(1, int(w * 0.7)), joint='curve')
    elif name == 'vee':
        d.polygon(P([(0.0, 0.08), (0.3, 0.08), (0.5, 0.62), (0.7, 0.08), (1.0, 0.08), (0.58, 0.95), (0.42, 0.95)]), fill=fill)
    else:
        raise KeyError(name)


# ---------------------------------------------------------------- the pill

def _measure(label):
    f = _font(13 * SS)
    box = f.getbbox(label)
    return box[2] - box[0], box


def pill(tag, frame):
    """The tag's picture (H px tall) for animation frame `frame` of FRAMES."""
    tid, label, ic, top, bot, anim, _ = tag
    tw, box = _measure(label)
    s = SS
    icon_box = 11
    pad, gap = 4, 3
    w = pad + icon_box + gap + int(math.ceil(tw / s)) + pad
    W, HH = w * s, H * s
    t = frame / FRAMES
    if anim == 'rainbow':
        hue = t
        c1 = tuple(int(v * 255) for v in colorsys.hsv_to_rgb(hue % 1, 0.55, 1.0))
        c2 = tuple(int(v * 255) for v in colorsys.hsv_to_rgb((hue + 0.18) % 1, 0.85, 0.85))
    else:
        c1, c2 = rgb(top), rgb(bot)
    # the shape: a rounded pill with a dark rim
    shape = Image.new('L', (W, HH), 0)
    ImageDraw.Draw(shape).rounded_rectangle((0, 0, W - 1, HH - 1), radius=5 * s, fill=255)
    inner = Image.new('L', (W, HH), 0)
    ImageDraw.Draw(inner).rounded_rectangle((s, s, W - 1 - s, HH - 1 - s), radius=4 * s, fill=255)
    y = np.linspace(0, 1, HH)[:, None]
    grad = np.array(c1, np.float32) * (1 - y[..., None]) + np.array(c2, np.float32) * y[..., None]
    grad = np.broadcast_to(grad, (HH, W, 3)).copy()
    rim = np.array(c2, np.float32) * 0.35
    img = np.zeros((HH, W, 4), np.float32)
    a_shape = np.asarray(shape, np.float32)[..., None] / 255
    a_in = np.asarray(inner, np.float32)[..., None] / 255
    img[..., :3] = grad * a_in + rim * (1 - a_in)
    img[..., 3:] = a_shape * 255
    # a lit band along the top, a darker one along the bottom
    yy = np.arange(HH)[:, None, None]
    img[..., :3] += a_in * np.clip(1 - (yy - 1.5 * s) / (2.5 * s), 0, 1) * 70 * (yy >= s)
    img[..., :3] -= a_in * np.clip((yy - (HH - 4 * s)) / (3 * s), 0, 1) * 40
    # the shine: a slanted band of light crossing in the first half of the loop
    if anim in ('shine', 'rainbow') and frame < FRAMES // 2:
        p = frame / (FRAMES // 2 - 1)
        xx = np.arange(W)[None, :, None]
        cx = -0.2 * W + p * 1.4 * W
        dist = np.abs((xx - cx) + (yy - HH / 2) * 0.6)
        band = np.clip(1 - dist / (2.6 * s), 0, 1) ** 1.4 * 150 + np.clip(1 - np.abs(dist - 4.5 * s) / (0.9 * s), 0, 1) * 70
        img[..., :3] += band * a_in
    img[..., :3] = np.clip(img[..., :3], 0, 255)
    out = Image.fromarray(img.astype(np.uint8), 'RGBA')
    shadow = tuple(int(v * 0.3) for v in c2) + (200,)
    # the icon (the LIVE dot blinks)
    ix, iy = pad * s, (H - icon_box) / 2 * s
    icol = (255, 255, 255, 255)
    if anim == 'blink':
        on = 0.5 + 0.5 * math.cos(2 * math.pi * t)
        icol = (255, int(255 - 120 * on), int(255 - 120 * on), 255)
    layer = Image.new('RGBA', (W, HH), (0, 0, 0, 0))
    icon(ImageDraw.Draw(layer), ic, ix, iy + s * 0.75, icon_box * s, shadow[:3] + (150,))
    icon(ImageDraw.Draw(layer), ic, ix, iy, icon_box * s, icol)
    out.alpha_composite(layer)
    if anim == 'blink':   # a ring pulsing out from the dot
        ring = Image.new('RGBA', (W, HH), (0, 0, 0, 0))
        r = (0.3 + 0.25 * t) * icon_box * s
        cxp, cyp = ix + icon_box * s / 2, iy + icon_box * s / 2
        ImageDraw.Draw(ring).ellipse((cxp - r, cyp - r, cxp + r, cyp + r), outline=(255, 255, 255, int(200 * (1 - t))), width=s)
        out.alpha_composite(ring)
    # the label, white with a thin shadow under it (drawn on its own layer so nothing punches through the pill)
    f = _font(13 * s)
    tx = (pad + icon_box + gap) * s - box[0]
    ty = (HH - (box[3] - box[1])) / 2 - box[1]
    text = Image.new('RGBA', (W, HH), (0, 0, 0, 0))
    td = ImageDraw.Draw(text)
    td.text((tx, ty + s * 0.75), label, font=f, fill=tuple(int(v * 0.3) for v in c2) + (150,))
    td.text((tx, ty), label, font=f, fill=(255, 255, 255, 255))
    out.alpha_composite(text)
    small = out.resize((w, H), Image.LANCZOS)
    # keep the alpha crisp at the edges (glyphs are drawn without smoothing)
    arr = np.asarray(small).copy()
    arr[..., 3] = np.where(arr[..., 3] > 110, 255, 0)
    return Image.fromarray(arr, 'RGBA')


def main():
    font_dir = os.path.join(RES, 'textures', 'font', 'tags')
    os.makedirs(font_dir, exist_ok=True)
    pub = os.path.join(ROOT, 'server', 'public', 'cosmetics', 'tags')
    os.makedirs(pub, exist_ok=True)
    providers, java, server = [], [], []
    for i, tag in enumerate(TAGS):
        tid, label, ic, top, bot, anim, means = tag
        frames = [pill(tag, f) for f in range(FRAMES)]
        w = frames[0].width
        strip = Image.new('RGBA', (w * FRAMES, H), (0, 0, 0, 0))
        for f, im in enumerate(frames):
            strip.paste(im, (f * w, 0))
        strip.save(os.path.join(font_dir, f'{tid}.png'), optimize=True)
        first = BASE + i * FRAMES
        providers.append({'type': 'bitmap', 'file': f'polarclient:font/tags/{tid}.png', 'ascent': 8, 'height': 9,
                          'chars': [''.join(chr(first + f) for f in range(FRAMES))]})
        # a still for the launcher and the admin panel: the frame without the shine, twice as big
        still = frames[FRAMES - 1].resize((w * 2, H * 2), Image.NEAREST)
        still.save(os.path.join(pub, f'{tid}.png'), optimize=True)
        colour = int(top.lstrip('#'), 16) if anim != 'rainbow' else 0xFFD36B
        java.append(f'\t\treg("{tid}", "{label}", 0x{first:04X}, {FRAMES}, "{anim}", 0x{colour:06X});')
        server.append({'id': tid, 'label': label, 'color': top if anim != 'rainbow' else '#ffd36b', 'means': means, 'image': f'tags/{tid}.png'})
    with open(os.path.join(RES, 'font', 'tags.json'), 'w', encoding='utf-8') as fh:
        json.dump({'providers': providers}, fh, indent=1, ensure_ascii=True)
    jpath = os.path.join(ROOT, 'mod', 'common', 'src', 'main', 'java', 'dev', 'polarclient', 'cosmetics', 'PolarTagList.java')
    with open(jpath, 'w', encoding='utf-8') as fh:
        fh.write('package dev.polarclient.cosmetics;\n\n'
                 '/** Generated by tools/cosmetics/tags.py from its TAGS list: do not edit by hand. */\n'
                 'final class PolarTagList {\n\tprivate PolarTagList() {\n\t}\n\n'
                 '\tstatic void register() {\n' + '\n'.join(java) + '\n\t}\n\n'
                 '\tprivate static void reg(String id, String label, int glyph, int frames, String anim, int colour) {\n'
                 '\t\tPolarTags.add(new PolarTags.Def(id, label, glyph, frames, anim, colour));\n\t}\n}\n')
    with open(os.path.join(ROOT, 'server', 'src', 'tags.js'), 'w', encoding='utf-8') as fh:
        fh.write('// Generated by tools/cosmetics/tags.py from its TAGS list: do not edit by hand.\n'
                 '// Name tags admins can give (pictures in public/cosmetics/tags; the mod draws them from its own font).\n')
        fh.write(f'export default {json.dumps(server, indent=1)};\n')
    print(f'{len(TAGS)} tags')


if __name__ == '__main__':
    main()
