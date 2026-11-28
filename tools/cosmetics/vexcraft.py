"""VexCraft's set (partner server): a code-only collection in VexCraft's void violets, from the crystal V in their logo.

    VexCraft Wings   crystal blade wings (HazeSMP's bird wing, new art)   wings.ITEMS   (vexwings.py)
    VexCraft Crown   a crown of void crystal with an orbit of shards        heads.ITEMS   (vexcrown.py)
    VexCraft Trail   V sigils at every step, sparks, crystal chips, hearts  auras.ITEMS   (vexfx.py)
    VexCraft Aura    a lifesteal storm: hearts, lightning, a spiral         auras.ITEMS   (vexfx.py)

Everything is drawn in code: no images from VexCraft are used. All four are mythic and special (given out, not
bought). Importing this module adds the items to their kinds (build.py does that).
"""

COLLECTION = {'id': 'vexcraft', 'name': 'VexCraft'}


def register():
    """Adds VexCraft's set to the kinds build.py builds."""
    import auras
    import heads
    import wings
    import vexcrown
    import vexfx
    import vexwings
    if all(i['id'] != vexwings.WINGS['id'] for i in wings.ITEMS):
        wings.ITEMS.append(vexwings.WINGS)
    wings.PARTICLES[vexwings.WINGS['id']] = vexwings.PARTICLES
    if all(i['id'] != vexcrown.CROWN['id'] for i in heads.ITEMS):
        heads.ITEMS.append(vexcrown.CROWN)
    for item in (vexfx.TRAIL, vexfx.AURA):
        if all(i['id'] != item['id'] for i in auras.ITEMS):
            auras.ITEMS.append(item)
