"""Blockbench <-> Polar cosmetic models.

Artists can build and animate cosmetics in Blockbench (free, https://www.blockbench.net) and bring them into Polar, and
any Polar cosmetic can be opened in Blockbench to edit.

    # a Blockbench project -> a Polar model + texture (in server/public/cosmetics)
    python tools/cosmetics/blockbench.py import wings.bbmodel --id wings_hazesmp --anchor body

    # a Polar model -> a Blockbench project (open it in Blockbench: File > Open Model)
    python tools/cosmetics/blockbench.py export wings_revon --out wings_revon.bbmodel

How to make one in Blockbench (see ANIMATIONS.md):
  - File > New > "Modded Entity" (Java). Build around a player: feet at y = 0, neck at y = 24 (the Polar player
    template that `export` writes has a reference body, which is left out on import).
  - Bones (groups) can turn; cubes can't (Minecraft's rule): put a turned cube in its own bone.
  - One texture per model, any resolution. A second texture named "glow" glows in the dark.
  - Animations named idle, walk, sprint, sneak, fall, fly, swim, ride, sleep, plus "always" (plays on top). Only
    numbers in keyframes (no Molang).

Spaces: Blockbench works y up with the feet at 0; Polar models (like Minecraft's Java models) are y down from the
neck. A point maps as (x, y, z) -> (-x, 24 - y, z) (a half turn round z, as Blockbench's own Java export does),
bone turns as (x, y, z) -> (-x, -y, z), animation turns as they are and animation moves as (x, -y, z) (Blockbench
plays them in Bedrock's convention).
"""
import argparse
import base64
import io
import json
import os
import sys
import uuid as uuidlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, 'server', 'public', 'cosmetics')

# Blockbench face -> Polar face (after the half turn: east (+x) lands on -x, which Polar calls "right")
FACE_IN = {'east': 'right', 'west': 'left', 'north': 'front', 'south': 'back', 'up': 'top', 'down': 'bottom'}
FACE_OUT = {v: k for k, v in FACE_IN.items()}
REFERENCE = 'polar_reference'  # the stand-in player body `export` adds; never imported


def r4(v):
    return round(float(v), 4) + 0.0


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


# ---------------------------------------------------------------- import

class Importer:
    def __init__(self, project, anchor):
        self.p = project
        self.anchor = anchor
        self.warn = []
        self.elements = {e['uuid']: e for e in project.get('elements', []) if e.get('type', 'cube') == 'cube'}
        self.names = set()
        self.bone_of_uuid = {}

    def unique(self, name):
        base = (name or 'bone').strip().replace(' ', '_') or 'bone'
        n, i = base, 2
        while n in self.names:
            n = f'{base}_{i}'
            i += 1
        self.names.add(n)
        return n

    def cube(self, e, origin):
        """A Blockbench cube as a Polar cube, relative to the bone pivot `origin` (Blockbench space)."""
        f, t = e['from'], e['to']
        ox, oy, oz = origin
        c = {'from': [r4(ox - t[0]), r4(oy - t[1]), r4(f[2] - oz)], 'to': [r4(ox - f[0]), r4(oy - f[1]), r4(t[2] - oz)]}
        if e.get('inflate'):
            c['inflate'] = r4(e['inflate'])
        faces = {}
        if e.get('box_uv'):
            u, v = e.get('uv_offset', [0, 0])
            w, h, d = t[0] - f[0], t[1] - f[1], t[2] - f[2]
            if e.get('mirror_uv'):
                self.warn.append(f"cube '{e.get('name')}': mirrored box UV isn't supported, it's imported unmirrored")
            faces = {
                'top': [u + d, v, u + d + w, v + d], 'bottom': [u + d + w, v, u + d + w + w, v + d],
                'right': [u, v + d, u + d, v + d + h], 'front': [u + d, v + d, u + d + w, v + d + h],
                'left': [u + d + w, v + d, u + d + w + d, v + d + h], 'back': [u + d + w + d, v + d, u + d + w + d + w, v + d + h],
            }
        else:
            for bb, face in (e.get('faces') or {}).items():
                # a face with no texture is left out (Blockbench's "null" texture)
                if bb not in FACE_IN or not face or face.get('texture') is None or 'uv' not in face:
                    continue
                if face.get('enabled') is False:
                    continue
                if face.get('rotation'):
                    self.warn.append(f"cube '{e.get('name')}': turned face UVs ({bb}) aren't supported")
                faces[FACE_IN[bb]] = [r4(x) for x in face['uv']]
        c['faces'] = {k: [r4(x) for x in v] for k, v in faces.items()}
        return c

    def bone(self, node, parent_origin):
        """A Blockbench group (and everything in it) as a Polar bone."""
        origin = node.get('origin', [0, 0, 0])
        rot = node.get('rotation', [0, 0, 0])
        b = {'name': self.unique(node.get('name')), 'pivot': self.pivot(origin, parent_origin), 'rot': [r4(-rot[0]), r4(-rot[1]), r4(rot[2])], 'cubes': [], 'children': []}
        self.bone_of_uuid[node.get('uuid')] = b['name']
        for ch in node.get('children', []):
            if isinstance(ch, str):
                self.add_cube(b, ch, origin)
            elif isinstance(ch, dict) and ch.get('name') != REFERENCE:
                b['children'].append(self.bone(ch, origin))
        if not b['children']:
            del b['children']
        return b

    def pivot(self, origin, parent_origin):
        if parent_origin is None:  # top level: from the feet-up Blockbench space to the neck-down body space
            return [r4(-origin[0]), r4(24 - origin[1]), r4(origin[2])]
        return [r4(-(origin[0] - parent_origin[0])), r4(-(origin[1] - parent_origin[1])), r4(origin[2] - parent_origin[2])]

    def add_cube(self, bone, uid, origin):
        e = self.elements.get(uid)
        if e is None:
            return
        rot = e.get('rotation') or [0, 0, 0]
        if any(abs(x) > 1e-6 for x in rot):
            # Minecraft can't turn a cube: give it a bone of its own, turned round the cube's origin
            co = e.get('origin', origin)
            sub = {'name': self.unique(f"{e.get('name', 'cube')}_turn"), 'pivot': self.pivot(co, origin), 'rot': [r4(-rot[0]), r4(-rot[1]), r4(rot[2])],
                   'cubes': [self.cube(e, co)]}
            bone.setdefault('children', []).append(sub)
        else:
            bone['cubes'].append(self.cube(e, origin))

    def bones(self):
        out = []
        loose = []
        for node in self.p.get('outliner', []):
            if isinstance(node, str):
                loose.append(node)
            elif node.get('name') != REFERENCE:
                out.append(self.bone(node, None))
        if loose:  # cubes outside any group: a bone at the neck holds them
            b = {'name': self.unique('root'), 'pivot': [0, 0, 0], 'rot': [0, 0, 0], 'cubes': []}
            for uid in loose:
                self.add_cube(b, uid, [0, 24, 0])
            out.append(b)
        return out

    def animations(self):
        out = {}
        for a in self.p.get('animations', []):
            name = a.get('name', 'animation').split('.')[-1]
            loop = a.get('loop', 'loop')
            clip = {'length': r4(a.get('length') or 0), 'loop': loop == 'loop'}
            if loop == 'hold':
                clip['loop'] = False
            bones = {}
            for uid, an in (a.get('animators') or {}).items():
                if an.get('type', 'bone') != 'bone':
                    continue
                bone = self.bone_of_uuid.get(uid)
                if bone is None:
                    continue
                chans = {}
                for kf in an.get('keyframes', []):
                    ch = kf.get('channel')
                    if ch not in ('rotation', 'position', 'scale'):
                        continue
                    pts = kf.get('data_points') or [{}]
                    def vec(dp):
                        v = [num(dp.get(k, 0)) for k in 'xyz']
                        if any(x is None for x in v):
                            self.warn.append(f"animation '{name}', bone '{bone}': a Molang expression was read as 0")
                            v = [x if x is not None else (1 if ch == 'scale' else 0) for x in v]
                        if ch == 'position':
                            v[1] = -v[1]
                        return [r4(x) for x in v]
                    post = vec(pts[-1])
                    k = {'post': post, 'lerp_mode': {'catmullrom': 'catmullrom', 'step': 'step', 'bezier': 'catmullrom'}.get(kf.get('interpolation'), 'linear')}
                    if len(pts) > 1:
                        k['pre'] = vec(pts[0])
                    if kf.get('interpolation') == 'bezier':
                        self.warn.append(f"animation '{name}': bezier keyframes are played as smooth (Catmull-Rom)")
                    chans.setdefault(ch, {})[f"{r4(kf.get('time', 0)):g}"] = k
                if chans:
                    bones[bone] = chans
            clip['bones'] = bones
            out[name] = clip
        return out

    def textures(self):
        texs = self.p.get('textures', [])
        if not texs:
            raise SystemExit('The project has no texture.')
        main = next((t for t in texs if 'glow' not in t.get('name', '').lower()), texs[0])
        glow = next((t for t in texs if t is not main and 'glow' in t.get('name', '').lower()), None)
        if len([t for t in texs if t not in (main, glow)]):
            self.warn.append('only one texture (plus one named "glow") is used; the others are ignored')
        return main, glow


def png_bytes(tex):
    src = tex.get('source', '')
    if src.startswith('data:image/png;base64,'):
        return base64.b64decode(src.split(',', 1)[1])
    path = tex.get('path')
    if path and os.path.exists(path):
        return open(path, 'rb').read()
    raise SystemExit(f"Texture '{tex.get('name')}' isn't saved inside the project: in Blockbench, right-click it > Save, then File > Save Project.")


def do_import(args):
    project = json.load(open(args.file, encoding='utf-8'))
    imp = Importer(project, args.anchor)
    res = project.get('resolution', {'width': 64, 'height': 64})
    model = {'format': 1, 'anchor': args.anchor, 'texture': f'textures/{args.id}.png', 'size': [int(res['width']), int(res['height'])], 'translucent': bool(args.translucent)}
    model['bones'] = imp.bones()
    anims = imp.animations()
    if anims:
        model['animations'] = anims
    main, glow = imp.textures()
    out = args.out or OUT
    os.makedirs(os.path.join(out, 'textures'), exist_ok=True)
    os.makedirs(os.path.join(out, 'models'), exist_ok=True)
    open(os.path.join(out, 'textures', f'{args.id}.png'), 'wb').write(png_bytes(main))
    if glow:
        model['glow'] = f'textures/{args.id}_glow.png'
        open(os.path.join(out, 'textures', f'{args.id}_glow.png'), 'wb').write(png_bytes(glow))
    json.dump(model, open(os.path.join(out, 'models', f'{args.id}.json'), 'w'), indent=1)
    cubes = sum(1 for _ in walk(model['bones']) for _c in _['cubes'])
    print(f"imported {args.id}: {len(list(walk(model['bones'])))} bones, {cubes} cubes, clips: {', '.join(anims) or 'none'}")
    for w in sorted(set(imp.warn)):
        print('  note:', w)
    return model


def walk(bones):
    for b in bones:
        yield b
        yield from walk(b.get('children', []))


# ---------------------------------------------------------------- export

def do_export(args):
    src = args.out_dir or OUT
    path = args.model if args.model.endswith('.json') else os.path.join(src, 'models', f'{args.model}.json')
    model = json.load(open(path, encoding='utf-8'))
    elements, ids = [], {}

    def uid():
        return str(uuidlib.uuid4())

    def group(b, parent_origin):
        if parent_origin is None:
            origin = [-b['pivot'][0], 24 - b['pivot'][1], b['pivot'][2]]
        else:
            origin = [parent_origin[0] - b['pivot'][0], parent_origin[1] - b['pivot'][1], parent_origin[2] + b['pivot'][2]]
        rot = b.get('rot', [0, 0, 0])
        g = {'name': b['name'], 'origin': [r4(x) for x in origin], 'rotation': [r4(-rot[0]), r4(-rot[1]), r4(rot[2])], 'uuid': uid(), 'export': True, 'isOpen': True, 'children': []}
        ids[b['name']] = g['uuid']
        for c in b.get('cubes', []):
            f, t = c['from'], c['to']
            e = {'name': 'cube', 'type': 'cube', 'uuid': uid(), 'box_uv': False, 'rescale': False, 'origin': g['origin'],
                 'from': [r4(origin[0] - t[0]), r4(origin[1] - t[1]), r4(origin[2] + f[2])],
                 'to': [r4(origin[0] - f[0]), r4(origin[1] - f[1]), r4(origin[2] + t[2])],
                 'faces': {bb: ({'uv': c['faces'][pf], 'texture': 0} if pf in c.get('faces', {}) else {'uv': [0, 0, 0, 0], 'texture': None}) for bb, pf in FACE_IN.items()}}
            if c.get('inflate'):
                e['inflate'] = c['inflate']
            elements.append(e)
            g['children'].append(e['uuid'])
        for ch in b.get('children', []):
            g['children'].append(group(ch, origin))
        return g

    outliner = [group(b, None) for b in model['bones']]
    tex_path = os.path.join(src, model['texture'])
    texs = []
    for name, p in (('texture', tex_path), ('glow', os.path.join(src, model['glow']) if model.get('glow') else None)):
        if p and os.path.exists(p):
            data = open(p, 'rb').read()
            if model.get('frames', 1) > 1:  # animated strip: Blockbench gets the first frame
                from PIL import Image
                im = Image.open(io.BytesIO(data))
                im = im.crop((0, 0, im.width, im.height // model['frames']))
                buf = io.BytesIO()
                im.save(buf, 'PNG')
                data = buf.getvalue()
            texs.append({'name': name, 'id': str(len(texs)), 'uuid': uid(), 'source': 'data:image/png;base64,' + base64.b64encode(data).decode(), 'saved': True, 'particle': False})
    animations = []
    for name, clip in (model.get('animations') or {}).items():
        animators = {}
        for bone, chans in clip.get('bones', {}).items():
            if bone not in ids:
                continue
            kfs = []
            for ch, keys in chans.items():
                if not isinstance(keys, dict):
                    keys = {'0': keys}
                for t, v in keys.items():
                    post = v.get('post', v.get('value')) if isinstance(v, dict) else v
                    post = post if isinstance(post, list) else [post] * 3
                    if ch == 'position':
                        post = [post[0], -post[1], post[2]]
                    kfs.append({'channel': ch, 'data_points': [{'x': post[0], 'y': post[1], 'z': post[2]}], 'uuid': uid(), 'time': float(t),
                                'interpolation': (v.get('lerp_mode', 'linear') if isinstance(v, dict) else 'linear')})
            animators[ids[bone]] = {'name': bone, 'type': 'bone', 'keyframes': kfs}
        animations.append({'uuid': uid(), 'name': f'animation.{args.model}.{name}', 'loop': 'loop' if clip.get('loop', True) else 'hold',
                           'length': clip.get('length', 0), 'snapping': 60, 'animators': animators})
    project = {'meta': {'format_version': '4.10', 'model_format': 'modded_entity', 'box_uv': False}, 'name': args.model,
               'resolution': {'width': model['size'][0], 'height': model['size'][1]}, 'elements': elements, 'outliner': outliner,
               'textures': texs, 'animations': animations}
    if not args.no_reference:  # a grey stand-in body (8x12x4) so the model can be placed around a player
        body = {'name': 'body', 'type': 'cube', 'uuid': uid(), 'box_uv': False, 'origin': [0, 24, 0], 'from': [-4, 12, -2], 'to': [4, 24, 2],
                'faces': {k: {'uv': [0, 0, 0, 0], 'texture': None} for k in FACE_IN}}
        elements.append(body)
        outliner.append({'name': REFERENCE, 'origin': [0, 24, 0], 'rotation': [0, 0, 0], 'uuid': uid(), 'export': False, 'children': [body['uuid']]})
    out = args.out or f'{args.model}.bbmodel'
    json.dump(project, open(out, 'w', encoding='utf-8'))
    print(f'wrote {out}: {len(elements)} cubes, {len(animations)} animations')
    return project


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest='cmd', required=True)
    i = sub.add_parser('import', help='a Blockbench project -> a Polar model and texture')
    i.add_argument('file')
    i.add_argument('--id', required=True, help='the cosmetic id, e.g. wings_hazesmp')
    i.add_argument('--anchor', choices=['body', 'head'], default='body', help='what it moves with')
    i.add_argument('--translucent', action='store_true', help='the texture has see-through parts (glass, ghostly)')
    i.add_argument('--out', help='output folder (default: server/public/cosmetics)')
    e = sub.add_parser('export', help='a Polar model -> a Blockbench project')
    e.add_argument('model', help='a model id (e.g. wings_revon) or a path to a model .json')
    e.add_argument('--out', help='the .bbmodel to write')
    e.add_argument('--out-dir', help='where the models and textures are (default: server/public/cosmetics)')
    e.add_argument('--no-reference', action='store_true', help="leave out the stand-in player body")
    args = ap.parse_args(argv)
    return do_import(args) if args.cmd == 'import' else do_export(args)


if __name__ == '__main__':
    main()
