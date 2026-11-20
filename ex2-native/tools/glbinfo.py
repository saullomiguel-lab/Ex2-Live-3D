"""Resumo de um GLB: triângulos, nós especiais (X_*), materiais, imagens e animações.
   python glbinfo.py arquivo.glb"""
import collections
import json
import struct
import sys

d = open(sys.argv[1], "rb").read()
jl = struct.unpack("<I", d[12:16])[0]
j = json.loads(d[20:20 + jl])
acc = j["accessors"]
tris = sum(acc[p["indices"]]["count"] // 3 for m in j["meshes"] for p in m["primitives"] if "indices" in p)
special = collections.Counter(n["name"].split("|")[0] + "|" + n["name"].split("|")[1]
                              for n in j["nodes"] if n.get("name", "").startswith("X_"))
print("arquivo %.1f MB | nós %d | malhas %d | primitivas %d | triângulos %d | materiais %d | imagens %d"
      % (len(d) / 1e6, len(j["nodes"]), len(j["meshes"]), sum(len(m["primitives"]) for m in j["meshes"]),
         tris, len(j.get("materials", [])), len(j.get("images", []))))
print("especiais:", dict(special))
for a in j.get("animations", []):
    print("animação", a["name"], "canais", len(a["channels"]))
print("extensões:", j.get("extensionsUsed"))
