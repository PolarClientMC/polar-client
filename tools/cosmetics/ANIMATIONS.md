# Animated cosmetics

Polar's cosmetic models (wings, hats, back items) move in three ways, all of which can be mixed in one model:

| What | Use it for | Where it lives |
|---|---|---|
| **Keyframe clips** | Hand-made motion: wings beating, folding, gliding; a crown's gems pulsing | `animations` in the model |
| **Springs** | Parts that swing by themselves: tails, ribbons, chains, banners | `physics` in the model |
| Simple loops (older) | A plain flap, sway or spin | `anims` in the model |

The mod (`mod/common/.../cosmetics/Keyframes.java`) and the launcher's wardrobe (`launcher/src/renderer/keyframes.js`)
play them with the same maths, so the wardrobe shows exactly what players see in game. The website's previews use
the wardrobe's code too.

## Clips follow what the player does

A model can have a clip for each of these. The one that fits what the player is doing plays, and changing state
blends smoothly over a fifth of a second.

| Clip | When | If the model has no such clip |
|---|---|---|
| `idle` | standing still | (nothing moves) |
| `walk` | walking | `idle` |
| `sprint` | sprinting | `walk`, `idle` |
| `sneak` | sneaking | `idle` |
| `fall` | in the air (jumping, falling) | `idle` |
| `fly` | flying (creative) | `fall`, `sprint`, `walk`, `idle` |
| `glide` | gliding with an elytra | `fly`, `fall`, `sprint`, `walk`, `idle` |
| `swim` | swimming | `sneak`, `idle` |
| `ride` | riding | `sneak`, `idle` |
| `sleep` | in bed | `sneak`, `idle` |
| `always` | all the time, on top of the others | - |

`walk` and `sprint` play a little slower while the player is barely moving, so the beat matches the steps (turn that
off with `"stride": false`). Wings with clips skip the old automatic fold/glide, because the clips do it.

## The format

The same shape Blockbench exports for Bedrock (so its animations can be pasted in), in the model's own space:
pixels, degrees, y pointing down from the neck, the face at -z; a left wing has its pivot at x >= 0.

```json
"animations": {
  "idle": {
    "length": 3.2,
    "loop": true,
    "bones": {
      "wing_l": {
        "rotation": { "0": [0, 0, 0], "1.6": { "post": [0, 6, 2], "lerp_mode": "catmullrom" }, "3.2": [0, 0, 0] },
        "position": { "0": [0, 0, 0], "1.6": [0, -0.5, 0], "3.2": [0, 0, 0] },
        "scale":    { "0": 1, "1.6": 1.05, "3.2": 1 }
      }
    }
  },
  "sneak": { "length": 0.3, "loop": "hold_on_last_frame", "bones": { "...": {} } }
},
"physics": [
  { "bone": "tail", "stiffness": 55, "damping": 6, "inertia": 1, "limit": 45, "swing": [1, 1] }
]
```

- **Keys:** time in seconds -> `[x, y, z]`, a single number (all three), or `{"post": [...], "pre": [...], "lerp_mode": ...}`.
  `lerp_mode`: `linear` (default), `catmullrom` (smooth), `step` (jump). `pre` is the value arriving at the key, for
  sudden changes.
- **Rotation** and **position** add to the bone's rest pose; **scale** multiplies (1 = as made) around the bone's pivot.
- **loop**: `true` (default) repeats; `false`, `"once"` or `"hold_on_last_frame"` plays once and holds the end (it
  starts over each time the state comes back).
- **length**: seconds (default: the last key). **speed**: playback speed (default 1). **blend**: how quickly the clip
  takes over when its state comes on (seconds, default 0.2).
- Only numbers: Blockbench's Molang expressions (`math.sin(...)`) are read as 0.
- **Full springs** (for wings, capes, anything that should feel alive) say per axis `[x, y, z]` how each push moves
  the bone: `move` (walking forward; 16 walking, 28 sprinting), `turn` (turning; about -0.06 x the turn speed),
  `jump` (a kick when leaving the ground and when landing) and `air` (in the air: -10 falling, 40 flying or gliding).
  `follow` (0..1) makes the bone chase its own clip motion instead of copying it, so it lags, overshoots and settles
  (follow-through); `limit` then counts from the clip. `drag: {"bone": "arm_l", "amount": [x, y, z]}` bends it
  against how fast another bone turns (degrees per degree a second): a hand that whips behind its arm, feathers that
  bend in the stroke. Springs run in list order, so put a parent before the bones that drag on it.

  ```json
  { "bone": "hand_l", "stiffness": 110, "damping": 8, "limit": 30, "follow": 1,
    "drag": { "bone": "arm_l", "amount": [0, 0.05, 0.08] }, "move": [0, 0.4, 0], "jump": [0, 0, 0.6] }
  ```
- **Springs:** the bone is pulled to where the player's movement would leave it (trailing behind a walk or a turn,
  lifted when jumping) and overshoots and settles like something hanging. `stiffness` (default 70): lower is slower
  and floppier. `damping` (7): lower bounces more. `inertia` (1): how strongly it reacts. `limit` (50): the most it
  ever swings, in degrees. `swing`: `[x, z]` multipliers for forward/back and side to side (-1 flips a direction).

## Making them

**In Python** (how Polar's own cosmetics are made), with `tools/cosmetics/keyframes.py`:

```python
from keyframes import wing_motion, spring, clip, curve
model['animations'] = wing_motion(['wing_l', 'mid_l', 'tip_l'], ['wing_r', 'mid_r', 'tip_r'])  # all 6 states
model['physics'] = [spring('tail', stiffness=55, damping=6)]
```

`wing_motion` gives any pair of wings the full set: breathing while idle, an easy beat walking, a hard one sprinting,
folded when sneaking, spread to glide when falling and powerful strokes flying, each bone following the one before
it a little late so the wing bends.

**In Blockbench** (free, https://www.blockbench.net), with `tools/cosmetics/blockbench.py`:

1. Start from an existing cosmetic so the scale and position are right:
   `python tools/cosmetics/blockbench.py export wings_revon --out wings_revon.bbmodel`, then File > Open Model.
   The grey "polar_reference" body is a stand-in player (feet at y 0, neck at y 24); it's left out on import.
2. Build with **bones (groups)** - they can turn; cubes can't (Minecraft's rule; a turned cube is put in a bone of
   its own on import). One texture (any resolution); a second one named `glow` glows in the dark.
3. Animate in the Animate tab. Name the animations `idle`, `walk`, `sprint`, `sneak`, `fall`, `fly`, ... (any
   prefix like `animation.mymodel.idle` is fine). Keyframes: linear, smooth (Catmull-Rom) or step.
4. Save the project, then
   `python tools/cosmetics/blockbench.py import wings_haze.bbmodel --id wings_hazesmp --anchor body`.
   It writes `server/public/cosmetics/models/wings_hazesmp.json` and its texture, and says what it couldn't bring in.
5. Add the item to the catalogue (see `revon.py` for a set that's given by code), run `build.py`, and look at it in
   the wardrobe.

Blockbench's space is y up with the feet at 0; Polar's is y down from the neck: points map as
(x, y, z) -> (-x, 24 - y, z), bone turns as (-x, -y, z), animation turns as they are and animation moves as
(x, -y, z). `export` and `import` are exact inverses (checked by a round trip of every model).
