#!/usr/bin/env python3
"""Build the reproduction scene v7 (the frame this lane reads) into a copy of the user's world.

Usage: python3 make-scene.py <corpus-with-client-world>     # edits in place

The scene: a grass platform at y=80 in the sky, cleared above, and seven 2-tall specimen columns
(x=-30, y=81..82) so one frame holds a cutout block of each family next to solid controls:

    z=48 oak_leaves   z=49 grass_block   z=50 iron_bars   z=51 dandelion
    z=52 glass        z=53 stone         z=54 dirt

plus the camera at (-27, 81, 51) facing -X, level, with a grass block in hotbar slot 0.
Every edited cell was sky-lit air, so the stale light data stays plausible; nothing below y=80 is touched.
"""
import os
import shutil
import sys
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import nbt
import region

SPECS = [(48, 'minecraft:oak_leaves'), (49, 'minecraft:grass_block'), (50, 'minecraft:iron_bars'),
         (51, 'minecraft:dandelion'), (52, 'minecraft:glass'), (53, 'minecraft:stone'),
         (54, 'minecraft:dirt')]


def main(corpus):
    world = os.path.join(corpus, 'client-world', 'W7Client')
    mca = os.path.join(world, 'region', 'r.-1.0.mca')
    chunks, _, timestamps, _ = region.read_chunks(mca)
    key = (30, 3)                                   # chunk (-2,3): x -32..-17, z 48..63
    cname, root = region.load_chunk_nbt(*chunks[key])

    edits = {}
    for x in range(-31, -25):                       # platform
        for z in range(47, 57):
            edits[(x, 80, z)] = 'minecraft:grass_block'
    for x in range(-30, -25):                       # clear the air the camera looks through
        for z in range(46, 58):
            for y in range(81, 90):
                edits[(x, y, z)] = 'minecraft:air'
    for z, block in SPECS:                          # specimen columns
        for y in (81, 82):
            edits[(-30, y, z)] = block
    for (x, y, z), block in edits.items():
        region.set_block(root, x, y, z, block)
    chunks[key] = (2, zlib.compress(region.dump_chunk_nbt(cname, root), 6))
    region.write_chunks(mca, chunks, timestamps)

    pd = os.path.join(world, 'playerdata', '00000000-0000-0000-0000-000000000000.dat')
    name, player = nbt.load(pd)
    player['Inventory'] = (9, (10, [{'Slot': (1, 0), 'id': (8, 'minecraft:grass_block'), 'count': (3, 1)}]))
    player['SelectedItemSlot'] = (3, 0)
    player['Pos'] = (9, (6, [-27.0, 81.0, 51.0]))
    player['Rotation'] = (9, (5, [90.0, 0.0]))
    nbt.save(pd, name, player)
    print('scene v7 written into', world)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '/private/tmp/repro-corpus')
