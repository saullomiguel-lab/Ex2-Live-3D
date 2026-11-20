"""Carro leve (assets-src/car.glb) a partir do original (car-raw.glb).
A lataria pintada, vidros, lanternas, emblemas, placa e calotas ficam com TODO o detalhe
(simplificar a lataria deu "amassados" no passado); o resto (vedações e forro das portas, miolo
dos faróis, palhetas, suspensão, discos de freio, plásticos) vem de uma versão simplificada.
As peças são casadas pelo nome do nó (gltfpack -kn mantém os nomes).
   python make_light_car.py [proporção]   (padrão 0.3)
Gera também car-full.glb (o carro completo de antes, para comparação)."""
import json
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "assets-src")
GLTFPACK = os.path.join(HERE, "..", "..", "ex2-wallpaper", "tools", "node_modules", ".bin", "gltfpack.cmd")
RATIO = sys.argv[1] if len(sys.argv) > 1 else "0.2"        # peças escondidas (forte)
GENTLE = sys.argv[2] if len(sys.argv) > 2 else "0.5"       # peças visíveis não protegidas (suave)
FLAGS = ["-kn", "-km", "-ke", "-vpf", "-vtf", "-vnf"]       # os mesmos do car.glb de sempre

# 1ª tentativa (tudo forte) serrilhou vedações pretas, caixas de roda e grade. Agora:
#  FORTE (-sa, pode mudar o contorno) só no que fica escondido ou quase não aparece;
#  SUAVE (sem -sa: preserva bordas e silhueta) no resto que não é protegido.
import re
# (suspensão fica no nível suave: aparece um pouco dentro das caixas de roda)
HIDDEN = re.compile(r"^E22H_(Door_(FL|FR|RL|RR)_Body_(?!2$)\d+|shachepian(_\d+)?|EXT_12|"
                    r"Windshield_wiper_\d+|qiankou|Interior.*)$")

# materiais que NUNCA são simplificados (o que aparece de perto e reflete)
KEEP_MATERIALS = ("CarPaint", "Glass", "LOGO", "plate", "Light", "light", "Lamp", "lamp", "Chrome", "mat",
                  "Tire", "Window")
# nós que nunca são simplificados (lanternas, emblema, rodas/calotas, placa)
KEEP_NODES = ("X_LAMP", "X_WHEEL", "GEELY", "Object_4", "X_CAMLENS", "X_CAMBLANK", "X_STRIP", "X_CONTRAST")


def read_glb(path):
    d = open(path, "rb").read()
    jl = struct.unpack("<I", d[12:16])[0]
    j = json.loads(d[20:20 + jl])
    bl = struct.unpack("<I", d[20 + jl:24 + jl])[0]
    b = d[28 + jl:28 + jl + bl]
    return j, b


def write_glb(path, j, b):
    while len(b) % 4:
        b += b"\0"
    j["buffers"] = [{"byteLength": len(b)}]
    js = json.dumps(j, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(b)))
        f.write(struct.pack("<II", len(js), 0x4E4F534A)); f.write(js)
        f.write(struct.pack("<II", len(b), 0x004E4942)); f.write(b)


def pack(out, extra):
    cmd = [GLTFPACK, "-i", os.path.join(SRC, "car-raw.glb"), "-o", out] + FLAGS + extra
    print(" ".join(os.path.basename(c) for c in cmd))
    subprocess.run(cmd, check=True)


def tris(j):
    return sum(j["accessors"][p["indices"]]["count"] // 3 for m in j["meshes"] for p in m["primitives"] if "indices" in p)


def node_tags(j):
    """nome do nó + nomes dos pais (para achar X_LAMP etc. acima da peça)"""
    parent = {}
    for i, n in enumerate(j["nodes"]):
        for c in n.get("children", []):
            parent[c] = i
    out = {}
    for i, n in enumerate(j["nodes"]):
        names = [n.get("name", "")]
        k = i
        while k in parent:
            k = parent[k]
            names.append(j["nodes"][k].get("name", ""))
        out[i] = names
    return out


full = os.path.join(SRC, "car-full.glb")
simp = os.path.join(SRC, "car-simplified.tmp.glb")
soft = os.path.join(SRC, "car-gentle.tmp.glb")
pack(full, [])
pack(simp, ["-si", RATIO, "-sa"])
pack(soft, ["-si", GENTLE])
J, B = read_glb(full)
sources = {}
for key, path in (("forte", simp), ("suave", soft)):
    S_, SB_ = read_glb(path)
    q = {}
    for i, n in enumerate(S_["nodes"]):
        if "mesh" in n:
            q.setdefault(n.get("name", ""), []).append(n["mesh"])
    sources[key] = (S_, SB_, q)
tags = node_tags(J)
out = bytearray(B)
replaced = kept = 0
saved = 0
for i, n in enumerate(J["nodes"]):
    if "mesh" not in n:
        continue
    name = n.get("name", "")
    mesh = J["meshes"][n["mesh"]]
    mats = [J["materials"][p["material"]]["name"] if "material" in p else "" for p in mesh["primitives"]]
    hidden = HIDDEN.match(name) is not None or any("Interior" in m for m in mats)
    S, SB, sq = sources["forte" if hidden else "suave"]
    # (consome o nome nas duas fontes para manter a ordem dos nomes repetidos)
    picks = {k: (v[2].get(name) or [None]).pop(0) if v[2].get(name) else None for k, v in sources.items()}
    smesh = picks["forte" if hidden else "suave"]
    protect = any(k in m for m in mats for k in KEEP_MATERIALS) or any(t.startswith(KEEP_NODES) for t in tags[i])
    if protect or smesh is None:
        kept += 1
        continue
    sp = S["meshes"][smesh]["primitives"]
    if len(sp) != len(mesh["primitives"]):
        kept += 1
        continue
    before = sum(J["accessors"][p["indices"]]["count"] // 3 for p in mesh["primitives"])
    for p, q in zip(mesh["primitives"], sp):
        # copia os acessores da versão simplificada (dados + vista) para o arquivo final
        def take(ai):
            # o gltfpack guarda várias peças no mesmo bloco: copia só o trecho DESTE acessor
            a = dict(S["accessors"][ai])
            src_bv = S["bufferViews"][a["bufferView"]]
            comp = {5120: 1, 5121: 1, 5122: 2, 5123: 2, 5125: 4, 5126: 4}[a["componentType"]]
            ncomp = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4, "MAT4": 16}[a["type"]]
            elem = comp * ncomp
            stride = src_bv.get("byteStride", elem)
            start = src_bv.get("byteOffset", 0) + a.get("byteOffset", 0)
            length = (a["count"] - 1) * stride + elem
            while len(out) % 4:
                out.append(0)
            bv = {"buffer": 0, "byteOffset": len(out), "byteLength": length}
            if "byteStride" in src_bv:
                bv["byteStride"] = src_bv["byteStride"]
            if "target" in src_bv:
                bv["target"] = src_bv["target"]
            out.extend(SB[start:start + length])
            J["bufferViews"].append(bv)
            a["bufferView"] = len(J["bufferViews"]) - 1
            a["byteOffset"] = 0
            J["accessors"].append(a)
            return len(J["accessors"]) - 1
        q_attrs = q["attributes"]
        if set(q_attrs) != set(p["attributes"]):
            raise SystemExit("atributos diferentes em %s" % name)
        p["attributes"] = {k: take(v) for k, v in q_attrs.items()}
        p["indices"] = take(q["indices"])
    after = sum(J["accessors"][p["indices"]]["count"] // 3 for p in mesh["primitives"])
    saved += before - after
    replaced += 1

light = os.path.join(SRC, "car.glb")
merged = os.path.join(SRC, "car-merged.tmp.glb")
write_glb(merged, J, bytes(out))
os.remove(simp); os.remove(soft)
# compacta: o gltfpack regrava só o que é usado (sem simplificar de novo), com os mesmos parâmetros
cmd = [GLTFPACK, "-i", merged, "-o", light] + FLAGS
subprocess.run(cmd, check=True)
os.remove(merged)
J = read_glb(light)[0]
print("peças simplificadas: %d · mantidas com todo o detalhe: %d" % (replaced, kept))
print("triângulos: %d → %d (−%d)" % (tris(read_glb(full)[0]), tris(J), saved))
print("ok: %s (%.1f MB)" % (light, os.path.getsize(light) / 1e6))
