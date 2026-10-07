"""Place a wall of grass blocks in an Anvil region file, without a full world editor.

Reads the region, decodes one chunk's NBT, sets blocks in `sections[].block_states`, re-encodes, writes the
region back preserving every other chunk's raw compressed bytes.
"""
import struct
import sys
import zlib

sys.path.insert(0, '/private/tmp/repro-tools')
import nbt


def read_chunks(path):
    """-> dict[(cx, cz)] = raw bytes (compressed payload incl. compression byte prefix stripped), plus header."""
    data = open(path, 'rb').read()
    assert len(data) >= 8192
    locations = data[:4096]
    timestamps = data[4096:8192]
    out = {}
    for i in range(1024):
        off = struct.unpack('>I', b'\x00' + locations[i * 4:i * 4 + 3])[0]
        cnt = locations[i * 4 + 3]
        if off == 0 or cnt == 0:
            continue
        start = off * 4096
        length = struct.unpack('>I', data[start:start + 4])[0]
        comp = data[start + 4]
        payload = data[start + 5:start + 4 + length]
        out[(i % 32, i // 32)] = (comp, payload)
    return out, locations, timestamps, len(data)


def write_chunks(path, chunks, timestamps):
    body = bytearray(8192)
    body[4096:8192] = timestamps
    current_sector = 2
    for (cx, cz), (comp, payload) in sorted(chunks.items()):
        raw = struct.pack('>I', len(payload) + 1) + bytes([comp]) + payload
        pad = (-len(raw)) % 4096
        raw += b'\x00' * pad
        idx = (cx % 32) + (cz % 32) * 32
        sectors = len(raw) // 4096
        assert sectors <= 255
        loc = (current_sector << 8) | sectors
        body[idx * 4:idx * 4 + 4] = struct.pack('>I', loc)
        body += raw
        current_sector += sectors
    open(path, 'wb').write(bytes(body))


def load_chunk_nbt(comp, payload):
    if comp == 1:
        raw = gzip_decompress(payload)
    elif comp == 2:
        raw = zlib.decompress(payload)
    elif comp == 3:
        raw = payload
    else:
        raise ValueError(comp)
    r = nbt.Reader(raw)
    t = r.u1()
    assert t == 10
    name = r.s()
    return name, r.payload(10)


def gzip_decompress(payload):
    import gzip
    return gzip.decompress(payload)


def dump_chunk_nbt(name, root):
    w = nbt.Writer()
    w.u1(10)
    w.s(name)
    w.payload(10, root)
    return bytes(w.b)


def pack_bits(values, bits, per_long=64):
    """Pack a list of ints, `bits` each, LSB-first into big-endian longs (Minecraft's format)."""
    per = per_long // bits
    longs = []
    for start in range(0, len(values), per):
        group = values[start:start + per]
        v = 0
        for i, x in enumerate(group):
            v |= (x & ((1 << bits) - 1)) << (i * bits)
        longs.append(v)
    return longs


def unpack_bits(longs, bits, count, per_long=64):
    per = per_long // bits
    out = []
    for v in longs:
        for i in range(per):
            out.append((v >> (i * bits)) & ((1 << bits) - 1))
        if len(out) >= count:
            break
    return out[:count]


def set_block(root, x, y, z, block_name):
    """Set one block. x,z,y absolute; only the low 4 bits of x/z and y matter inside a chunk."""
    sections = root['sections'][1][1]  # (elem_type, items) -> items
    sy = y >> 4
    section = None
    for s in sections:
        if s['Y'][1] == sy:
            section = s
            break
    if section is None:
        raise KeyError(f'no section Y={sy}')
    if 'block_states' not in section:
        raise KeyError('section has no block_states')
    bs = section['block_states'][1]
    palette = bs['palette'][1][1]   # list of compounds
    names = [p['Name'][1] for p in palette]
    n_old = len(names)
    # section-local coordinates: Python modulo so -1 -> 15
    idx = (x & 15) + (z & 15) * 16 + (y & 15) * 256
    if 'data' in bs:
        cur = unpack_bits(bs['data'][1], max(4, (n_old - 1).bit_length()), 4096)
    else:
        cur = [0] * 4096
    if block_name in names:
        pi = names.index(block_name)
    else:
        palette.append({'Name': (8, block_name)})
        pi = n_old
    bits_old = max(4, (n_old - 1).bit_length())
    bits_new = max(4, (len(palette) - 1).bit_length())
    cur[idx] = pi
    # A palette with more than one entry needs the packed array; a single-entry palette is
    # "non-zero storage" without it only if some value is non-zero, which cannot happen here.
    if len(palette) > 1:
        bs['data'] = (12, pack_bits(cur, bits_new))
    return pi


if __name__ == '__main__':
    pass
