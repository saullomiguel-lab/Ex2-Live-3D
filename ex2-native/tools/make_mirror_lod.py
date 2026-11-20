"""Carro simplificado para o REFLEXO no lago (car-mirror.glb).
O reflexo é fraco e desfocado: não precisa de todos os triângulos nem de texturas.
- tira as texturas (o app usa os materiais do carro de verdade no reflexo);
- simplifica para ~20% dos triângulos mantendo nomes de nós e materiais (o app casa as peças pelo nome).
   python make_mirror_lod.py [proporção]   (padrão 0.2)"""
import json
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "assets-src")
GLTFPACK = os.path.join(HERE, "..", "..", "ex2-wallpaper", "tools", "node_modules", ".bin", "gltfpack.cmd")
RATIO = sys.argv[1] if len(sys.argv) > 1 else "0.2"


def read_glb(path):
    d = open(path, "rb").read()
    jl = struct.unpack("<I", d[12:16])[0]
    j = json.loads(d[20:20 + jl])
    rest = d[20 + jl:]          # chunk binário (comprimento + tipo + dados)
    return j, rest


def write_glb(path, j, rest):
    js = json.dumps(j, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    total = 12 + 8 + len(js) + len(rest)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, total))
        f.write(struct.pack("<II", len(js), 0x4E4F534A))
        f.write(js)
        f.write(rest)


def strip_textures(j):
    def walk(o):
        if isinstance(o, dict):
            for k in list(o.keys()):
                if k.endswith("Texture") and isinstance(o[k], dict) and "index" in o[k]:
                    del o[k]
                else:
                    walk(o[k])
        elif isinstance(o, list):
            for v in o:
                walk(v)
    for m in j.get("materials", []):
        walk(m)
    for k in ("textures", "images", "samplers"):
        j.pop(k, None)


j, rest = read_glb(os.path.join(SRC, "car-raw.glb"))
strip_textures(j)
tmp = os.path.join(SRC, "car-raw-notex.glb")
write_glb(tmp, j, rest)
out = os.path.join(SRC, "car-mirror.glb")
# mesmos parâmetros do car.glb (nomes, materiais, extras, posição/UV/normal em float) + simplificação;
# -kv mantém UV/tangentes: os materiais do carro de verdade esperam esses atributos
# 2.8.0: SEM -sa (simplificação agressiva): ela ignorava as bordas das peças e abria buracos no
# reflexo (o usuário viu o reflexo "cheio de buracos"); sem ela as bordas ficam fechadas
# (-se: erro permitido; o reflexo é desfocado, 2% não aparece e reduz bem mais que o padrão de 1%)
ERR = os.environ.get("LOD_ERR", "0.02")
cmd = [GLTFPACK, "-i", tmp, "-o", out, "-kn", "-km", "-ke", "-kv", "-vpf", "-vtf", "-vnf", "-si", RATIO, "-se", ERR]
if len(sys.argv) > 2 and sys.argv[2] == "agressivo":
    # -slb: trava as bordas entre peças vizinhas (sem frestas entre elas)
    cmd += ["-sa", "-slb"]
print(" ".join(cmd))
subprocess.run(cmd, check=True, shell=False)
os.remove(tmp)
print("ok: %s (%.1f MB)" % (out, os.path.getsize(out) / 1e6))
