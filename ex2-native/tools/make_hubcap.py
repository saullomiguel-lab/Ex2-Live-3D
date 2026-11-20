"""Calota da versão Pro (hubcap.glb) a partir do arquivo enviado pelo usuário (assets-src/calota/).
O modelo é uma foto recortada da calota real sobre um disco escuro (642 triângulos).
Aqui só troca a transparência da foto de "mistura" para "recorte" (MASK): sem problemas de
ordem de desenho e mais leve para a placa de vídeo.
   python make_hubcap.py"""
import json
import os
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
# 2ª versão enviada pelo usuário (foto mais limpa, disco de raio 0,5 m e 4,5 cm de espessura);
# a 1ª (geely_wheel_cover.glb, raio 1) continua guardada na mesma pasta
SRC = os.path.join(HERE, "..", "assets-src", "calota", "geely_hubcap_3d.glb")
OUT = os.path.join(HERE, "..", "assets-src", "hubcap.glb")

d = open(SRC, "rb").read()
jl = struct.unpack("<I", d[12:16])[0]
j = json.loads(d[20:20 + jl])
rest = d[20 + jl:]
for m in j.get("materials", []):
    if m.get("alphaMode") == "BLEND":
        m["alphaMode"] = "MASK"
        m["alphaCutoff"] = 0.5
js = json.dumps(j, separators=(",", ":")).encode("utf-8")
js += b" " * ((4 - len(js) % 4) % 4)
with open(OUT, "wb") as f:
    f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + len(rest)))
    f.write(struct.pack("<II", len(js), 0x4E4F534A))
    f.write(js)
    f.write(rest)
print("ok: %s (%.2f MB)" % (OUT, os.path.getsize(OUT) / 1e6))
