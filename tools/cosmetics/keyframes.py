"""Keyframe animations and springs for cosmetic models (the format: ANIMATIONS.md). The mod plays them
(dev.polarclient.cosmetics.Keyframes) and so does the launcher's wardrobe (launcher/src/renderer/keyframes.js).

    from keyframes import clip, curve, spring, wing_motion
    model['animations'] = wing_motion(['wing_l', 'mid_l', 'tip_l'], ['wing_r', 'mid_r', 'tip_r'])
    model['physics'] = [spring('tail', stiffness=55, damping=6)]

Values are in the model's own space: degrees, pixels, y down; a left wing has its pivot at x >= 0.
"""
import math


def key(value, smooth=True, pre=None):
    """One keyframe value: smooth (Catmull-Rom) or straight to the next key; pre: a different value arriving here."""
    k = {'post': [round(float(v), 4) for v in value], 'lerp_mode': 'catmullrom' if smooth else 'linear'}
    if pre is not None:
        k['pre'] = [round(float(v), 4) for v in pre]
    return k


def curve(fn, length, keys=12, smooth=True):
    """Samples fn(t) -> [x, y, z] at `keys` evenly spaced times over one loop (the last key repeats the first)."""
    out = {}
    for i in range(keys + 1):
        t = length * i / keys
        out[f'{t:.4f}'.rstrip('0').rstrip('.') or '0'] = key(fn(t % length if i < keys else 0), smooth)
    return out


def clip(length, bones, loop=True, speed=1.0, stride=None, blend=None):
    """A clip: bones = {bone: {'rotation': keys, 'position': keys, 'scale': keys}}. blend: seconds it takes to take over."""
    c = {'length': round(length, 4), 'loop': loop, 'bones': bones}
    if blend is not None:
        c['blend'] = blend
    if speed != 1:
        c['speed'] = speed
    if stride is not None:
        c['stride'] = stride
    return c


def spring(bone, stiffness=70, damping=7, inertia=1.0, limit=50, swing=(1, 1)):
    """A part that swings on its own (tails, ribbons, chains, banners): a damped spring pulled by how the player moves.
    Lower stiffness = slower and floppier; lower damping = more bounce; swing flips or scales the two directions."""
    return {'bone': bone, 'stiffness': stiffness, 'damping': damping, 'inertia': inertia, 'limit': limit, 'swing': list(swing)}


def beat(p, down=0.38):
    """A real wing beat over one cycle (p in cycles): a quick power stroke from +1 to -1, then a slower recovery."""
    p -= math.floor(p)
    return math.cos(math.pi * p / down) if p < down else -math.cos(math.pi * (p - down) / (1 - down))


def wing_motion(left, right, size=1.0, lag=0.12):
    """A full set of reactive clips for a pair of wings: breathing while idle, an easy beat while walking, a hard one
    sprinting, folded while sneaking (and swimming, riding, sleeping), spread to glide while falling and powerful
    strokes when flying. left/right: the bones from the shoulder outwards (each one follows the one before it a
    little late, so the wing bends like a real one). size scales how far everything moves."""
    def side_bones(names, s, fn):
        out = {}
        for i, b in enumerate(names):
            share = 1.0 if i == 0 else 0.55 / i
            out[b] = {'rotation': fn(s, share, i * lag)}
        return out

    def both(fn):
        bones = side_bones(left, 1, fn)
        bones.update(side_bones(right, -1, fn))
        return bones

    def flap(length, yaw, roll, down, keys=16):
        def fn(s, share, delay):
            return curve(lambda t: [0, s * share * size * yaw * beat((t - delay) / length, down), s * share * size * roll * beat((t - delay) / length, down)], length, keys)
        return fn

    def held(length, base_yaw, base_roll, wobble):
        def fn(s, share, delay):
            return curve(lambda t: [0, s * (base_yaw * (1 if share == 1 else 0.4) + share * size * wobble * math.sin(2 * math.pi * (t - delay) / length)),
                                    s * base_roll * (1 if share == 1 else 0.4)], length, 8)
        return fn

    return {
        'idle': clip(3.2, both(lambda s, share, d: curve(lambda t: [0, s * share * size * 6 * math.sin(2 * math.pi * (t - d) / 3.2),
                                                                  s * share * size * 2.5 * math.sin(2 * math.pi * (t - d) / 3.2 + 0.6)], 3.2, 8))),
        'walk': clip(1.25, both(flap(1.25, 11, 4, 0.42)), stride=True),
        'sprint': clip(0.8, both(flap(0.8, 17, 6, 0.38)), stride=True),
        'sneak': clip(2.4, both(held(2.4, -38, 10, 1.5))),
        'fall': clip(1.6, both(held(1.6, 14, -7, 2.5))),
        'fly': clip(0.62, both(flap(0.62, 24, 9, 0.34, keys=20))),
    }
