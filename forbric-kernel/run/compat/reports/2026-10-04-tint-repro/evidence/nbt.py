"""Minimal typed NBT reader/writer (big-endian, gzip), enough to edit Minecraft playerdata.

Values are represented as (tag_id, value):
  1 byte(int) 2 short 3 int 4 long 5 float 6 double 7 byte[](list) 8 string
  9 list -> (element_tag_id, [payload...])  10 compound -> {name: (tag_id, value)}
  11 int[] 12 long[]
"""
import gzip
import struct

T_BYTE, T_SHORT, T_INT, T_LONG, T_FLOAT, T_DOUBLE, T_BYTES, T_STRING, T_LIST, T_COMPOUND, T_INTS, T_LONGS = range(1, 13)


class Reader:
    def __init__(self, data):
        self.d = data
        self.i = 0

    def take(self, n):
        v = self.d[self.i:self.i + n]
        self.i += n
        return v

    def u1(self):
        return struct.unpack('>B', self.take(1))[0]

    def i2(self):
        return struct.unpack('>h', self.take(2))[0]

    def i4(self):
        return struct.unpack('>i', self.take(4))[0]

    def i8(self):
        return struct.unpack('>q', self.take(8))[0]

    def f4(self):
        return struct.unpack('>f', self.take(4))[0]

    def f8(self):
        return struct.unpack('>d', self.take(8))[0]

    def s(self):
        n = struct.unpack('>H', self.take(2))[0]
        return self.take(n).decode('utf-8')

    def payload(self, t):
        if t == T_BYTE:
            return struct.unpack('>b', self.take(1))[0]
        if t == T_SHORT:
            return self.i2()
        if t == T_INT:
            return self.i4()
        if t == T_LONG:
            return self.i8()
        if t == T_FLOAT:
            return self.f4()
        if t == T_DOUBLE:
            return self.f8()
        if t == T_BYTES:
            n = self.i4()
            return list(self.take(n))
        if t == T_STRING:
            return self.s()
        if t == T_LIST:
            et = self.u1()
            n = self.i4()
            return (et, [self.payload(et) for _ in range(n)])
        if t == T_COMPOUND:
            out = {}
            while True:
                tt = self.u1()
                if tt == 0:
                    break
                name = self.s()
                out[name] = (tt, self.payload(tt))
            return out
        if t == T_INTS:
            n = self.i4()
            return [self.i4() for _ in range(n)]
        if t == T_LONGS:
            n = self.i4()
            return [self.i8() for _ in range(n)]
        raise ValueError(f'tag {t}')


def load(path):
    raw = gzip.decompress(open(path, 'rb').read())
    r = Reader(raw)
    t = r.u1()
    assert t == T_COMPOUND
    name = r.s()
    return name, r.payload(T_COMPOUND)


class Writer:
    def __init__(self):
        self.b = bytearray()

    def u1(self, v):
        self.b += struct.pack('>B', v)

    def i2(self, v):
        self.b += struct.pack('>h', v)

    def i4(self, v):
        self.b += struct.pack('>i', v)

    def i8(self, v):
        self.b += struct.pack('>q', v)

    def f4(self, v):
        self.b += struct.pack('>f', v)

    def f8(self, v):
        self.b += struct.pack('>d', v)

    def s(self, v):
        raw = v.encode('utf-8')
        self.b += struct.pack('>H', len(raw)) + raw

    def payload(self, t, v):
        if t == T_BYTE:
            self.b += struct.pack('>b', v)
        elif t == T_SHORT:
            self.i2(v)
        elif t == T_INT:
            self.i4(v)
        elif t == T_LONG:
            self.i8(v)
        elif t == T_FLOAT:
            self.f4(v)
        elif t == T_DOUBLE:
            self.f8(v)
        elif t == T_BYTES:
            raw = bytes(v) if not isinstance(v, (bytes, bytearray)) else bytes(v)
            self.i4(len(raw)); self.b += raw
        elif t == T_STRING:
            self.s(v)
        elif t == T_LIST:
            et, items = v
            self.u1(et)
            self.i4(len(items))
            for el in items:
                self.payload(et, el)
        elif t == T_COMPOUND:
            for name, (ct, cv) in v.items():
                self.u1(ct)
                self.s(name)
                self.payload(ct, cv)
            self.u1(0)
        elif t == T_INTS:
            self.i4(len(v))
            for x in v:
                self.i4(x)
        elif t == T_LONGS:
            self.i4(len(v))
            for x in v:
                self.i8(x)
        else:
            raise ValueError(t)


def save(path, name, root):
    w = Writer()
    w.u1(T_COMPOUND)
    w.s(name)
    w.payload(T_COMPOUND, root)
    with gzip.open(path, 'wb') as f:
        f.write(bytes(w.b))
