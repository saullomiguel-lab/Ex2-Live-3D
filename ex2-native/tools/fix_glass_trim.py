"""Correções direto no modelo (car.glb e car-mirror.glb), sem mudar a geometria:

1) VIDROS dos dois lados (doubleSided): o para-brisa (E22H_Roof_4) e outros vidros vieram com as faces
   viradas para dentro; de fora a placa de vídeo descartava a face e via-se o carro vazio por dentro.
   Com doubleSided o Filament desenha os dois lados e acerta a luz do lado de trás.
2) ACABAMENTO PRETO de baixo das portas TRASEIRAS (Body_2): na porta da frente a borda de cima tem as
   normais um pouco para cima (pega a luz: brilho/sombra suave); na de trás estavam retas/para baixo
   (preto chapado). Copia o perfil de inclinação da porta da frente do mesmo lado, por altura.

   python fix_glass_trim.py arquivo.glb [arquivo2.glb ...]   (regrava no lugar)"""
import json
import math
import struct
import sys

GLASS = {"E22H_Glass", "E22H_Glass (no uv1)", "E22H_Glass.003"}
# lado esquerdo do carro = +x; o modelo tem as portas da frente com nomes trocados (FR = esquerda)
PAIRS = [("E22H_Door_FR_Body_2", "E22H_Door_RL_Body_2", 1.0), ("E22H_Door_FL_Body_2", "E22H_Door_RR_Body_2", -1.0)]
TRIM = False
# faixa da pintura (m, altura) onde entra a dobra igual à da porta da frente, e a inclinação no pé dela
P_LOW, P_HIGH, P_NY = .37, .48, -.5
PAINT_FIX = False
EDGE_MAX = .43      # borda da peça preta acima disso (curva perto da roda) = sem dobra
Y0, Y1, STEP = .25, .47, .01


def load(p):
    d = open(p, "rb").read()
    jl = struct.unpack("<I", d[12:16])[0]
    j = json.loads(d[20:20 + jl])
    bl = struct.unpack("<I", d[20 + jl:24 + jl])[0]
    return j, bytearray(d[28 + jl:28 + jl + bl])


def save(p, j, b):
    js = json.dumps(j, separators=(",", ":")).encode("utf-8")
    js += b" " * ((4 - len(js) % 4) % 4)
    while len(b) % 4:
        b.append(0)
    with open(p, "wb") as f:
        f.write(struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(js) + 8 + len(b)))
        f.write(struct.pack("<II", len(js), 0x4E4F534A)); f.write(js)
        f.write(struct.pack("<II", len(b), 0x004E4942)); f.write(b)


def view(j, i):
    a = j["accessors"][i]; bv = j["bufferViews"][a["bufferView"]]
    if a["componentType"] != 5126:
        raise SystemExit("normais/posições não são float (gerar com -vpf -vnf)")
    st = bv.get("byteStride", 12); off = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    return off, st, a["count"]


def mat(n):
    if "matrix" in n:
        m = n["matrix"]; return [[m[c * 4 + r] for c in range(4)] for r in range(4)]
    t = n.get("translation", [0, 0, 0]); q = n.get("rotation", [0, 0, 0, 1]); s = n.get("scale", [1, 1, 1]); x, y, z, w = q
    R = [[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)], [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
         [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]]
    return [[R[r][0] * s[0], R[r][1] * s[1], R[r][2] * s[2], t[r]] for r in range(3)] + [[0, 0, 0, 1]]


def mm(a, b):
    return [[sum(a[r][k] * b[k][c] for k in range(4)) for c in range(4)] for r in range(4)]


def norm(v):
    l = math.sqrt(sum(c * c for c in v)) or 1.0
    return [c / l for c in v]


def fix(path):
    j, b = load(path)
    n_glass = 0
    # vidro OPACO (preto brilhante): mesmo com opacidade total o material transparente deixava ver
    # os bancos; opaco, com o carro fechado nada de dentro aparece (aberto, vê-se pela porta)
    for m in j["materials"]:
        if m.get("name") in GLASS:
            m["doubleSided"] = True
            m.pop("alphaMode", None)
            pbr = m.setdefault("pbrMetallicRoughness", {})
            pbr["baseColorFactor"] = [.004, .0046, .0052, 1.0]
            pbr["metallicFactor"] = 0.0
            pbr["roughnessFactor"] = .3       # (.12 dava pontinhos brancos de reflexo na central)
            n_glass += 1
    parent = {}
    for i, n in enumerate(j["nodes"]):
        for c in n.get("children", []):
            parent[c] = i

    def world(i):
        M = mat(j["nodes"][i])
        while i in parent:
            i = parent[i]; M = mm(mat(j["nodes"][i]), M)
        return M

    byname = {n.get("name"): i for i, n in enumerate(j["nodes"]) if "mesh" in n}

    def verts(i):
        prim = j["meshes"][j["nodes"][i]["mesh"]]["primitives"][0]
        M = world(i)
        po, ps, cnt = view(j, prim["attributes"]["POSITION"])
        no, ns, _ = view(j, prim["attributes"]["NORMAL"])
        out = []
        for k in range(cnt):
            p = struct.unpack_from("<3f", b, po + k * ps)
            q = struct.unpack_from("<3f", b, no + k * ns)
            w = [sum(M[r][c] * p[c] for c in range(3)) + M[r][3] for r in range(3)]
            nw = norm([sum(M[r][c] * q[c] for c in range(3)) for r in range(3)])
            out.append((k, w, nw))
        return M, no, ns, out

    changed = 0
    # (2.8.0) a cópia das normais do acabamento criou pontos brancos na peça: desligada
    for front, rear, side in (PAIRS if TRIM else []):
        if front not in byname or rear not in byname:
            continue
        _, _, _, fv = verts(byname[front])
        # ponto a ponto: cada vértice de fora da porta traseira copia a inclinação do vértice da porta
        # da frente com a mesma altura e o mesmo afastamento lateral (mesma seção da peça); uma média
        # por altura deixava a peça com um cinza chapado
        cell = .004
        grid = {}
        for _, w, nw in fv:
            if Y0 - .03 <= w[1] < Y1 + .03 and w[0] * side > .65 and nw[0] * side > .2:
                grid.setdefault((int(w[1] / cell), int(w[0] * side / cell)), []).append((w, nw))
        M, no, ns, rv = verts(byname[rear])
        for k, w, nw in rv:
            if not (Y0 <= w[1] < Y1) or w[0] * side <= .7 or nw[0] * side <= .3:
                continue
            gy, gx = int(w[1] / cell), int(w[0] * side / cell)
            best = None; bd = 1e9
            for dy in range(-2, 3):
                for dx in range(-2, 3):
                    for fw, fn in grid.get((gy + dy, gx + dx), ()):
                        dd = (fw[1] - w[1]) ** 2 + (fw[0] * side - w[0] * side) ** 2
                        if dd < bd:
                            bd = dd; best = fn
            if best is None:
                continue
            ty = best[1]
            hf = math.sqrt(max(0.0, 1 - ty * ty))
            h = math.sqrt(max(1e-6, nw[0] ** 2 + nw[2] ** 2))
            nn = [nw[0] / h * hf, ty, nw[2] / h * hf]
            # de volta ao espaço da peça (rotação + escala uniforme: transposta)
            loc = norm([sum(M[r][c] * nn[r] for r in range(3)) for c in range(3)])
            struct.pack_into("<3f", b, no + k * ns, *loc)
            changed += 1
    # 3) SOMBRA NA PINTURA logo acima do acabamento preto: na porta da frente a pintura dobra para
    #    dentro mais forte e numa faixa mais alta (fica uma sombra suave em cima da peça); na de trás a
    #    dobra era fraca e curta. A pintura da porta traseira ganha a mesma dobra suave (só a direção da
    #    superfície muda; a forma continua igual)
    # a faixa segue a borda de cima da peça preta em cada ponto (perto da roda ela sobe): o perfil da
    # porta da frente é medido pela distância até a borda dela e aplicado do mesmo jeito atrás
    ZC, DC, DMAX, RAMP = .02, .01, .12, .09

    def edge(body, side):
        e = {}
        for _, w, nw in verts(byname[body])[3]:
            if w[0] * side > .7 and nw[0] * side > .2 and w[1] < .6:
                kz = int(math.floor(w[2] / ZC)); e[kz] = max(e.get(kz, -1.0), w[1])
        return e

    def edge_at(e, z):
        kz = int(math.floor(z / ZC))
        for d in (0, -1, 1, -2, 2):
            if kz + d in e:
                return e[kz + d]
        return None

    painted = 0
    # (desligado: na malha irregular da pintura traseira a dobra virava manchas serrilhadas)
    paint_pairs = [(("E22H_Door_FR_CarPaint", "E22H_Door_FR_Body_2"), ("E22H_Door_RL_CarPaint", "E22H_Door_RL_Body_2"), 1.0),
                   (("E22H_Door_FL_CarPaint", "E22H_Door_FL_Body_2"), ("E22H_Door_RR_CarPaint", "E22H_Door_RR_Body_2"), -1.0)]
    for (fpaint, fbody), (rpaint, rbody), side in (paint_pairs if PAINT_FIX else []):
        if not all(n in byname for n in (fpaint, fbody, rpaint, rbody)):
            continue
        fe = edge(fbody, side)
        nb = int(DMAX / DC)
        acc = [[] for _ in range(nb)]
        for _, w, nw in verts(byname[fpaint])[3]:
            if w[0] * side <= .7 or nw[0] * side <= .3:
                continue
            ey = edge_at(fe, w[2])
            if ey is None:
                continue
            d = w[1] - ey
            if 0 <= d < DMAX:
                acc[int(d / DC)].append(nw[1])
        prof = [sum(a) / len(a) if a else None for a in acc]
        # completa buracos e suaviza (média com os vizinhos)
        for i in range(nb):
            if prof[i] is None:
                near = [prof[j] for j in range(nb) if prof[j] is not None]
                prof[i] = near[0] if near else 0.0
        prof = [(prof[max(0, i - 1)] + 2 * prof[i] + prof[min(nb - 1, i + 1)]) / 4 for i in range(nb)]
        print("  perfil da frente (inclinação por cm acima da peça):", " ".join("%.2f" % p for p in prof))
        re = edge(rbody, side)
        M, no, ns, rv = verts(byname[rpaint])
        for k, w, nw in rv:
            if w[0] * side <= .7 or nw[0] * side <= .3:
                continue
            ey = edge_at(re, w[2])
            if ey is None:
                continue
            d = w[1] - ey
            if not (0 <= d < DMAX):
                continue
            # faixa fixa (como a da frente) e só onde a peça preta é baixa: perto da roda, onde ela sobe,
            # a pintura fica como veio (ali a dobra virava manchas)
            if ey > EDGE_MAX or not (P_LOW <= w[1] < P_HIGH):
                continue
            t = min(1.0, max(0.0, (P_HIGH - w[1]) / (P_HIGH - P_LOW)))
            t = t * t * (3 - 2 * t)
            ty = min(nw[1], P_NY * t)          # só dobra mais para baixo, nunca menos
            h = math.sqrt(max(1e-6, nw[0] ** 2 + nw[2] ** 2)); hh = math.sqrt(max(0.0, 1 - ty * ty))
            nn = [nw[0] / h * hh, ty, nw[2] / h * hh]
            loc = norm([sum(M[r][c] * nn[r] for r in range(3)) for c in range(3)])
            struct.pack_into("<3f", b, no + k * ns, *loc)
            painted += 1
    print("  pintura das portas traseiras (sombra de cima da peça): %d pontos" % painted)
    save(path, j, b)
    print("%s: vidros dos dois lados %d · normais do acabamento traseiro ajustadas %d" % (path, n_glass, changed))


for p in sys.argv[1:]:
    fix(p)

