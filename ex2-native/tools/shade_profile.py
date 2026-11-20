import json, struct, sys, re
import numpy as np
f = open(sys.argv[1], 'rb'); data = f.read()
ln = struct.unpack('<I', data[12:16])[0]; j = json.loads(data[20:20+ln])
off = 20 + ln; bl = struct.unpack('<I', data[off:off+4])[0]; bin_ = data[off+8: off+8+bl]
nodes = j['nodes']
def mat(n):
    if 'matrix' in n: return np.array(n['matrix']).reshape(4,4).T
    t = n.get('translation',[0,0,0]); r = n.get('rotation',[0,0,0,1]); s = n.get('scale',[1,1,1])
    x,y,z,w = r
    R = np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)],[2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)],[2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])
    M = np.eye(4); M[:3,:3] = R @ np.diag(s); M[:3,3] = t; return M
def acc(i):
    a = j['accessors'][i]; bv = j['bufferViews'][a['bufferView']]
    ct = {5126:np.float32,5123:np.uint16,5121:np.uint8,5122:np.int16,5120:np.int8}[a['componentType']]
    n = {'VEC3':3,'VEC2':2,'SCALAR':1,'VEC4':4}[a['type']]
    size = np.dtype(ct).itemsize
    stride = bv.get('byteStride', size*n); base = bv.get('byteOffset',0) + a.get('byteOffset',0)
    raw = np.frombuffer(bin_, dtype=np.uint8, count=stride*(a['count']-1)+size*n, offset=base)
    out = np.lib.stride_tricks.as_strided(raw.view(np.uint8), shape=(a['count'], size*n), strides=(stride,1)).copy().view(ct).reshape(a['count'], n).astype(np.float64)
    if a.get('normalized'): out /= {np.uint16:65535,np.uint8:255,np.int16:32767,np.int8:127}[ct]
    return out
skip = re.compile(r'(?i)^(X_WHEEL|E22H_SuspensionSystem|E22H_shachepian|Object_4|E22H_Door_(FL|FR|RL|RR)_Body|.*mirror.*|.*Mirror.*|X_LAMP\|mirrorLens.*|E22H_Windshield_wiper.*)')
parent = {}
for i, n in enumerate(nodes):
    for c in n.get('children', []): parent[c] = i
def world(i):
    M = np.eye(4)
    while True:
        M = mat(nodes[i]) @ M
        if i not in parent: return M
        i = parent[i]
pts = []
for i, n in enumerate(nodes):
    if 'mesh' not in n: continue
    if skip.match(n.get('name', '')): continue
    W = world(i)
    for p in j['meshes'][n['mesh']]['primitives']:
        v = acc(p['attributes']['POSITION'])
        pts.append((W @ np.c_[v, np.ones(len(v))].T).T[:, :3])
P = np.vstack(pts)
P = P[np.abs(P[:, 0]) < 0.93]          # fora: pontas dos retrovisores
CY = 0.78; BINS = 48; MARGIN = 0.035; FLOOR = 0.26
stations = []
for z in np.arange(-1.88, 2.16, 0.06):
    sl = P[np.abs(P[:, 2] - z) < 0.035]
    if len(sl) < 80: continue
    dx = sl[:, 0]; dy = sl[:, 1] - CY
    ang = np.arctan2(dy, dx); rad = np.hypot(dx, dy)
    b = ((ang + np.pi) / (2 * np.pi) * BINS).astype(int) % BINS
    r = np.full(BINS, np.nan)
    for k in range(BINS):
        m = rad[b == k]
        if len(m): r[k] = m.max()
    # simetria (o carro é simétrico): usa o menor dos dois lados
    for k in range(BINS):
        k2 = (BINS // 2 - 1 - k) % BINS        # espelho em x: ângulo π−θ
        if not np.isnan(r[k]) and not np.isnan(r[k2]): r[k] = r[k2] = min(r[k], r[k2])
    # buracos: interpola pelos vizinhos
    if np.isnan(r).all(): continue
    idx = np.arange(BINS); good = ~np.isnan(r)
    r = np.interp(idx, idx[good], r[good], period=BINS)
    # suaviza só para baixo (nunca aumenta)
    r2 = np.minimum(r, (np.roll(r, 1) + r + np.roll(r, -1)) / 3 + 0.01)
    r2 = np.maximum(r2 - MARGIN, 0.05)
    th = (idx + 0.5) / BINS * 2 * np.pi - np.pi
    xs = r2 * np.cos(th); ys = CY + r2 * np.sin(th)
    ys = np.maximum(ys, FLOOR)
    stations.append((z, r2 + MARGIN, th))
# superfícies inclinadas (vidro traseiro sob o aerofólio, capô, para-choques): cada seção usa o MENOR
# raio das seções vizinhas (±15 cm) e só depois a margem; e as pontas do carro ficam de fora
WIN = 0.15; MARGIN2 = 0.05
raw = stations
stations = []
for z, r, th in raw:
    if z < -1.80 or z > 2.02: continue
    near = [rr for zz, rr, _ in raw if abs(zz - z) <= WIN + 1e-6]
    rmin = np.min(np.vstack(near), axis=0) - MARGIN2
    rmin = np.maximum(rmin, 0.05)
    xs = rmin * np.cos(th); ys = np.maximum(CY + rmin * np.sin(th), FLOOR)
    stations.append((z, xs, ys))
# saída Kotlin
out = ['package br.com.ex2.live3d', '', '/** Gerado por tools/shade_profile.py: seções da carroceria por dentro (3,5 cm para dentro da lataria). */',
       'object ShadeProfile {', f'    const val RING = {BINS}', '    val Z = floatArrayOf(' + ', '.join(f'{s[0]:.3f}f' for s in stations) + ')',
       '    /** por seção: x0, y0, x1, y1, … (RING pontos) */', '    val XY = floatArrayOf(']
rows = []
for z, xs, ys in stations:
    rows.append('        ' + ', '.join(f'{x:.3f}f, {y:.3f}f' for x, y in zip(xs, ys)))
out.append(',\n'.join(rows)); out.append('    )'); out.append('}')
open(sys.argv[2], 'w', encoding='utf-8').write('\n'.join(out) + '\n')
print('seções', len(stations))
