"""Copia os arquivos exportados do site (assets-src/) para o app (app/src/main/assets/).
- céus 360°: suaviza a emenda que o shader de céu do site tem na direção -X (coluna W/4);
- fotos do interior e o carro (car.glb, já compactado pelo gltfpack).
   python prepare_assets.py"""
import os
import shutil

from PIL import Image, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "assets-src")
DST = os.path.join(HERE, "..", "app", "src", "main", "assets")
os.makedirs(DST, exist_ok=True)


def fix_sky(path_in, path_out):
    im = Image.open(path_in).convert("RGB")
    w, h = im.size
    x = w // 4                      # direção -X no equiretangular (u = 0.25)
    band = 24
    box = (x - band, 0, x + band, h // 2 + 40)
    blurred = im.crop(box).filter(ImageFilter.BoxBlur(10))
    mask = Image.linear_gradient("L").rotate(90, expand=True).resize((band * 2, box[3]))
    # máscara em "telhado": 0 nas bordas da faixa, 255 no centro
    ramp = Image.new("L", (band * 2, box[3]))
    for i in range(band * 2):
        v = int(255 * (1 - abs(i - band) / band))
        ramp.paste(v, (i, 0, i + 1, box[3]))
    im.paste(blurred, box[:2], ramp)
    im.save(path_out, quality=88)


for name in sorted(os.listdir(SRC)):
    src = os.path.join(SRC, name)
    if name.startswith("sky-") and name.endswith(".jpg") and name != "sky-test.jpg":
        fix_sky(src, os.path.join(DST, name))
    # interior 3D simplificado (gltfpack -si 0.15); car-mirror = carro do reflexo (make_mirror_lod.py)
    # hubcap = calota da versão Pro (make_hubcap.py)
    elif name in ("car.glb", "interior.glb", "car-mirror.glb", "hubcap.glb"):
        shutil.copyfile(src, os.path.join(DST, name))
shutil.copyfile(os.path.join(HERE, "..", "..", "ex2-3d", "FE-FONT.TTF"), os.path.join(DST, "FE-FONT.TTF"))


def plate_base():
    """atlas da placa (material "plate" do car.glb) sem os caracteres de exemplo"""
    import io
    import json
    import struct
    d = open(os.path.join(SRC, "car.glb"), "rb").read()
    n = struct.unpack("<I", d[12:16])[0]
    g = json.loads(d[20:20 + n])
    b0 = 20 + n + 8
    m = next(m for m in g["materials"] if m.get("name") == "plate")
    im = g["images"][g["textures"][m["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]]
    bv = g["bufferViews"][im["bufferView"]]
    o = b0 + bv.get("byteOffset", 0)
    img = Image.open(io.BytesIO(d[o:o + bv["byteLength"]])).convert("RGB")
    img.paste((255, 255, 255), (20, 90, 202, 947))      # GLYPH_ZONE do plate.js
    img.save(os.path.join(DST, "plate-base.jpg"), quality=92)


plate_base()
total = 0
for n in sorted(os.listdir(DST)):
    s = os.path.getsize(os.path.join(DST, n))
    total += s
    print("%8.1f KB  %s" % (s / 1024, n))
print("total %.1f MB" % (total / 1e6))
