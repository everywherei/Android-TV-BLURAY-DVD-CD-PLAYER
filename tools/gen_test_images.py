#!/usr/bin/env python3
"""Genera imágenes de prueba para el núcleo (ISO9660, Joliet, UDF 2.60 'bridge' con pycdlib, y un UDF 2.50
con partición de metadatos construido a mano, que es el caso de los Blu-ray).
Uso: gen_test_images.py <dir_salida>
Escribe <img>.manifest con líneas 'ruta<TAB>tamaño<TAB>sha256' de los ficheros esperados."""
import hashlib, io, os, random, struct, sys

out = sys.argv[1]
os.makedirs(out, exist_ok=True)

def blob(seed, n):
    r = random.Random(seed)
    return bytes(r.getrandbits(8) for _ in range(n))

FILES = {  # ruta -> contenido
    "/README.TXT": b"Hola disco\n",
    "/EMPTY.BIN": b"",
    "/DATA/BIG.BIN": blob(1, 3_000_001),        # cruza muchos bloques y no es múltiplo de 2048
    "/DATA/SUB/DEEP.DAT": blob(2, 5000),
    "/VIDEO_TS/VIDEO_TS.IFO": blob(3, 2048),
}
DIRS = ["/DATA", "/DATA/SUB", "/VIDEO_TS"]

def manifest(name, files):
    with open(os.path.join(out, name + ".manifest"), "w") as f:
        for p, d in sorted(files.items()):
            f.write(f"{p}\t{len(d)}\t{hashlib.sha256(d).hexdigest()}\n")

# ---- pycdlib ----
import pycdlib

def iso9660_name(p):
    parts = p.strip("/").split("/")
    return "/" + "/".join(x.upper() for x in parts)

def build(name, **kw):
    iso = pycdlib.PyCdlib()
    iso.new(vol_ident="TESTDISC", **kw)
    joliet = kw.get("joliet")
    udf = kw.get("udf")
    for d in DIRS:
        k = {}
        if joliet: k["joliet_path"] = d
        if udf: k["udf_path"] = d
        iso.add_directory(d.upper(), **k)
    for p, d in FILES.items():
        k = {}
        if joliet: k["joliet_path"] = p
        if udf: k["udf_path"] = p
        iso.add_fp(io.BytesIO(d), len(d), iso9660_name(p) + ";1", **k)
    iso.write(os.path.join(out, name))
    iso.close()
    manifest(name, FILES)

build("iso_plain.iso", interchange_level=3)
build("iso_joliet.iso", interchange_level=3, joliet=3)
build("udf_bridge.iso", interchange_level=3, udf="2.60")

# ---- UDF 2.50 con partición de metadatos, hecho a mano ----
SS = 2048
img = bytearray(SS * 400)
PART_START = 272                      # sector físico donde empieza la partición 0

def tag(ident, loc, body_len=0):
    t = bytearray(16)
    struct.pack_into("<HH", t, 0, ident, 3)
    struct.pack_into("<I", t, 12, loc)
    return t

def finish(buf, ident, loc):
    """Rellena la etiqueta (checksum) de un descriptor ya construido."""
    struct.pack_into("<HH", buf, 0, ident, 3)
    struct.pack_into("<I", buf, 12, loc)
    buf[4] = sum(buf[i] for i in range(16) if i != 4) & 0xFF
    return buf

def put(sector, buf):
    img[sector * SS: sector * SS + len(buf)] = buf

def regid(ident, suffix=b""):
    r = bytearray(32); r[1:1 + len(ident)] = ident
    r[24:24 + len(suffix)] = suffix
    return r

def dstring128(s):
    b = bytearray(128); b[0] = 8; b[1:1 + len(s)] = s.encode(); b[127] = len(s) + 1; return b

def ps(lbn):            # sector físico de un lbn de la partición 0
    return PART_START + lbn

def long_ad(length, lbn, ref):
    return struct.pack("<IIH6x", length, lbn, ref)

def file_entry(ftype, info_len, alloc, ads=b"", embedded=b"", lbn=0):
    b = bytearray(SS)
    struct.pack_into("<HH", b, 0, 261, 3)
    b[27] = ftype
    struct.pack_into("<H", b, 34, alloc)
    struct.pack_into("<Q", b, 56, info_len)
    body = embedded if alloc == 3 else ads
    struct.pack_into("<II", b, 168, 0, len(body))
    b[176:176 + len(body)] = body
    return b

def short_ad(length, lbn, t=0):
    return struct.pack("<II", (t << 30) | length, lbn)

def fid(name, is_dir, ref, lbn, is_parent=False):
    nm = b"" if is_parent else bytes([8]) + name.encode("latin-1")
    b = bytearray(38 + len(nm))
    struct.pack_into("<H", b, 16, 1)
    b[18] = (0x02 if is_dir else 0) | (0x08 if is_parent else 0)
    b[19] = len(nm)
    b[20:36] = long_ad(SS, lbn, ref)
    b[38:38 + len(nm)] = nm
    while len(b) % 4: b.append(0)
    struct.pack_into("<H", b, 0, 257)
    return b

# datos del fichero regular en la partición FÍSICA (lbn 100..)
INDEX = blob(7, 5000)
img[ps(100) * SS: ps(100) * SS + len(INDEX)] = INDEX
README = b"embebido en el ICB\n"

# Metadatos: 8 bloques lógicos, fragmentados en 2 tramos físicos (lbn 10..13 y 20..23)
meta_phys = [10, 11, 12, 13, 20, 21, 22, 23]
def meta_put(mlbn, buf):
    put(ps(meta_phys[mlbn]), buf)

# fichero de metadatos (FE tipo 250) y su espejo en la partición física
meta_ads = short_ad(4 * SS, 10) + short_ad(4 * SS, 20)
put(ps(0), file_entry(250, 8 * SS, 0, meta_ads))
put(ps(1), file_entry(251, 8 * SS, 0, meta_ads))

REF_META = 1
# FSD (meta lbn 0)
fsd = bytearray(SS)
fsd[400:416] = long_ad(SS, 1, REF_META)
meta_put(0, finish(fsd, 256, 0))
# raíz: FE (meta 1) -> FIDs en meta lbn 2
root_fids = fid("", True, REF_META, 1, is_parent=True) + fid("BDMV", True, REF_META, 3) + fid("readme.txt", False, REF_META, 6)
meta_put(1, file_entry(4, len(root_fids), 0, short_ad(len(root_fids), 2)))
meta_put(2, bytearray(root_fids))
# BDMV: FE (meta 3) -> FIDs en meta 4
bd_fids = fid("", True, REF_META, 1, is_parent=True) + fid("index.bdmv", False, REF_META, 5)
meta_put(3, file_entry(4, len(bd_fids), 0, short_ad(len(bd_fids), 4)))
meta_put(4, bytearray(bd_fids))
# index.bdmv: FE (meta 5), short_ad -> datos en la partición física, lbn 100
meta_put(5, file_entry(5, len(INDEX), 0, short_ad(len(INDEX), 100)))
# readme.txt: embebido
meta_put(6, file_entry(5, len(README), 3, embedded=README))

# --- descriptores de volumen en el sector 32.. ---
pd = bytearray(SS)
struct.pack_into("<IHH", pd, 16, 1, 1, 0)           # VDSN, flags, partición 0
pd[24:56] = regid(b"+NSR03")
struct.pack_into("<III", pd, 184, 1, PART_START, 120)
put(32, finish(pd, 5, 32))

lv = bytearray(SS)
struct.pack_into("<I", lv, 16, 2)
lv[84:212] = dstring128("BDTEST")
struct.pack_into("<I", lv, 212, SS)
lv[216:248] = regid(b"*OSTA UDF Compliant", struct.pack("<H", 0x0250))
lv[248:264] = long_ad(SS, 0, REF_META)             # FSD en meta lbn 0, mapa 1
maps = bytearray()
maps += struct.pack("<BBHH", 1, 6, 1, 0)           # mapa 0: tipo 1, partición 0
m2 = bytearray(64); m2[0] = 2; m2[1] = 64
m2[4:36] = regid(b"*UDF Metadata Partition", struct.pack("<H", 0x0250))
struct.pack_into("<HHIII", m2, 36, 1, 0, 0, 1, 0xFFFFFFFF)   # volseq, partNum, metaLoc, mirrorLoc, bitmapLoc
maps += m2
struct.pack_into("<II", lv, 264, len(maps), 2)
lv[440:440 + len(maps)] = maps
put(33, finish(lv, 6, 33))
put(34, finish(bytearray(SS), 8, 34))

avdp = bytearray(SS)
struct.pack_into("<IIII", avdp, 16, 3 * SS, 32, 3 * SS, 32)
put(256, finish(avdp, 2, 256))

with open(os.path.join(out, "udf250_metadata.img"), "wb") as f:
    f.write(img)
manifest("udf250_metadata.img", {"/BDMV/index.bdmv": INDEX, "/readme.txt": README})
print("ok", sorted(os.listdir(out)))
