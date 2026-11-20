/* Exporta o carro montado pelo site (three.js) como GLB para o app nativo (Filament).
   Rodado no PC, dentro do site já carregado: window.EX2Export(opções) → ArrayBuffer GLB.
   Escreve só o que está visível; nomes, materiais PBR (com verniz e emissivo), texturas e clipes. */
(function () {
  'use strict';

  var GL_FLOAT = 5126, GL_UINT = 5125, ARRAY = 34962, ELEMENTS = 34963;

  function Builder() {
    this.json = { asset: { version: '2.0', generator: 'EX2 export-car' }, scene: 0, scenes: [{ nodes: [] }],
      nodes: [], meshes: [], materials: [], textures: [], images: [], samplers: [{ magFilter: 9729, minFilter: 9987, wrapS: 10497, wrapT: 10497 }],
      accessors: [], bufferViews: [], buffers: [], animations: [], extensionsUsed: [] };
    this.chunks = [];
    this.byteLength = 0;
    this.matIndex = new Map();
    this.texIndex = new Map();
    this.nodeIndex = new Map();
    this.pending = [];
  }

  Builder.prototype.use = function (ext) { if (this.json.extensionsUsed.indexOf(ext) < 0) this.json.extensionsUsed.push(ext); };

  Builder.prototype.view = function (bytes, target) {
    var pad = (4 - (this.byteLength % 4)) % 4;
    if (pad) { this.chunks.push(new Uint8Array(pad)); this.byteLength += pad; }
    var v = { buffer: 0, byteOffset: this.byteLength, byteLength: bytes.byteLength };
    if (target) v.target = target;
    this.chunks.push(new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.byteLength));
    this.byteLength += bytes.byteLength;
    this.json.bufferViews.push(v);
    return this.json.bufferViews.length - 1;
  };

  Builder.prototype.accessor = function (arr, type, count, target, minmax) {
    var a = { bufferView: this.view(arr, target), componentType: arr instanceof Uint32Array ? GL_UINT : GL_FLOAT, count: count, type: type };
    if (minmax) {
      var n = { VEC2: 2, VEC3: 3, VEC4: 4, SCALAR: 1 }[type], mn = [], mx = [];
      for (var k = 0; k < n; k++) { mn.push(Infinity); mx.push(-Infinity); }
      for (var i = 0; i < count; i++) for (k = 0; k < n; k++) { var v = arr[i * n + k]; if (v < mn[k]) mn[k] = v; if (v > mx[k]) mx[k] = v; }
      a.min = mn; a.max = mx;
    }
    this.json.accessors.push(a);
    return this.json.accessors.length - 1;
  };

  /* atributos podem ser intercalados/normalizados: lê componente a componente como float */
  function floats(attr, n) {
    var out = new Float32Array(attr.count * n), getters = ['getX', 'getY', 'getZ', 'getW'];
    for (var i = 0; i < attr.count; i++) for (var k = 0; k < n; k++) out[i * n + k] = attr[getters[k]](i);
    return out;
  }

  /* imagem → PNG (mantém transparência) ou JPEG (cor opaca), via canvas */
  Builder.prototype.image = function (img, opaque) {
    var w = img.width || img.videoWidth, h = img.height || img.videoHeight, max = this.maxTexture;
    var s = Math.min(1, max / Math.max(w, h));
    var c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w * s)); c.height = Math.max(1, Math.round(h * s));
    c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
    var mime = opaque ? 'image/jpeg' : 'image/png';
    var url = c.toDataURL(mime, 0.9), bin = atob(url.slice(url.indexOf(',') + 1));
    var bytes = new Uint8Array(bin.length);
    for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    this.json.images.push({ mimeType: mime, bufferView: this.view(bytes) });
    return this.json.images.length - 1;
  };

  Builder.prototype.texture = function (tex, opaque) {
    if (!tex || !tex.image) return null;
    var key = tex.uuid + (opaque ? 'j' : 'p');
    if (!this.texIndex.has(key)) {
      this.json.textures.push({ sampler: 0, source: this.image(tex.image, opaque) });
      this.texIndex.set(key, this.json.textures.length - 1);
    }
    var info = { index: this.texIndex.get(key) };
    if (tex.channel) info.texCoord = tex.channel;
    var o = tex.offset, r = tex.repeat;
    if (o.x || o.y || r.x !== 1 || r.y !== 1 || tex.rotation) {
      this.use('KHR_texture_transform');
      info.extensions = { KHR_texture_transform: { offset: [o.x, o.y], scale: [r.x, r.y], rotation: tex.rotation } };
    }
    return info;
  };

  Builder.prototype.material = function (m) {
    if (this.matIndex.has(m)) return this.matIndex.get(m);
    var c = m.color || { r: 1, g: 1, b: 1 };
    var j = { name: m.name || 'mat', pbrMetallicRoughness: {
      baseColorFactor: [c.r, c.g, c.b, m.transparent ? m.opacity : 1],
      metallicFactor: m.metalness !== undefined ? m.metalness : 0,
      roughnessFactor: m.roughness !== undefined ? m.roughness : 1 } };
    var opaque = !m.transparent && !m.alphaTest;
    var t = this.texture(m.map, opaque); if (t) j.pbrMetallicRoughness.baseColorTexture = t;
    t = this.texture(m.roughnessMap || m.metalnessMap, true); if (t) j.pbrMetallicRoughness.metallicRoughnessTexture = t;
    t = this.texture(m.normalMap, true); if (t) { t.scale = m.normalScale ? m.normalScale.x : 1; j.normalTexture = t; }
    t = this.texture(m.aoMap, true); if (t) { t.strength = m.aoMapIntensity !== undefined ? m.aoMapIntensity : 1; j.occlusionTexture = t; }
    if (m.emissive && (m.emissive.r || m.emissive.g || m.emissive.b || m.emissiveMap)) {
      j.emissiveFactor = [m.emissive.r, m.emissive.g, m.emissive.b];
      t = this.texture(m.emissiveMap, true); if (t) j.emissiveTexture = t;
      if (m.emissiveIntensity && m.emissiveIntensity !== 1) {
        this.use('KHR_materials_emissive_strength');
        j.extensions = j.extensions || {};
        j.extensions.KHR_materials_emissive_strength = { emissiveStrength: m.emissiveIntensity };
      }
    }
    if (m.clearcoat > 0) {
      this.use('KHR_materials_clearcoat');
      j.extensions = j.extensions || {};
      j.extensions.KHR_materials_clearcoat = { clearcoatFactor: m.clearcoat, clearcoatRoughnessFactor: m.clearcoatRoughness || 0 };
    }
    if (m.transparent) j.alphaMode = 'BLEND';
    else if (m.alphaTest > 0) { j.alphaMode = 'MASK'; j.alphaCutoff = m.alphaTest; }
    if (m.side === 2) j.doubleSided = true;
    if (m.isMeshBasicMaterial) { this.use('KHR_materials_unlit'); j.extensions = j.extensions || {}; j.extensions.KHR_materials_unlit = {}; }
    this.json.materials.push(j);
    this.matIndex.set(m, this.json.materials.length - 1);
    return this.json.materials.length - 1;
  };

  Builder.prototype.mesh = function (o) {
    var g = o.geometry, at = g.attributes, prim = { attributes: {} };
    if (!at.position) return null;
    prim.attributes.POSITION = this.accessor(floats(at.position, 3), 'VEC3', at.position.count, ARRAY, true);
    if (at.normal) prim.attributes.NORMAL = this.accessor(floats(at.normal, 3), 'VEC3', at.normal.count, ARRAY);
    if (at.uv) prim.attributes.TEXCOORD_0 = this.accessor(floats(at.uv, 2), 'VEC2', at.uv.count, ARRAY);
    if (at.uv1) prim.attributes.TEXCOORD_1 = this.accessor(floats(at.uv1, 2), 'VEC2', at.uv1.count, ARRAY);
    var mats = Array.isArray(o.material) ? o.material : [o.material];
    var groups = g.groups.length && Array.isArray(o.material) ? g.groups : [{ start: 0, count: g.index ? g.index.count : at.position.count, materialIndex: 0 }];
    var prims = [], self = this;
    groups.forEach(function (gr) {
      var p = { attributes: prim.attributes, material: self.material(mats[gr.materialIndex] || mats[0]) };
      if (g.index) {
        var idx = new Uint32Array(gr.count);
        for (var i = 0; i < gr.count; i++) idx[i] = g.index.getX(gr.start + i);
        p.indices = self.accessor(idx, 'SCALAR', gr.count, ELEMENTS);
      }
      prims.push(p);
    });
    this.json.meshes.push({ name: o.name, primitives: prims });
    return this.json.meshes.length - 1;
  };

  Builder.prototype.node = function (o) {
    if (!o.visible || o.isLight || o.isCamera) return null;
    var n = { name: o.name || (o.userData && o.userData.name) || '' };
    if (o.position.x || o.position.y || o.position.z) n.translation = o.position.toArray();
    var q = o.quaternion; if (q.x || q.y || q.z || q.w !== 1) n.rotation = [q.x, q.y, q.z, q.w];
    var s = o.scale; if (s.x !== 1 || s.y !== 1 || s.z !== 1) n.scale = s.toArray();
    if (o.isMesh) { var mi = this.mesh(o); if (mi !== null) n.mesh = mi; }
    this.json.nodes.push(n);
    var idx = this.json.nodes.length - 1;
    this.nodeIndex.set(o, idx);
    var kids = [], self = this;
    o.children.forEach(function (c) { var ci = self.node(c); if (ci !== null) kids.push(ci); });
    if (kids.length) n.children = kids;
    return idx;
  };

  Builder.prototype.animation = function (clip, root) {
    var self = this, anim = { name: clip.name, channels: [], samplers: [] };
    clip.tracks.forEach(function (t) {
      var dot = t.name.lastIndexOf('.'), target = t.name.slice(0, dot), prop = t.name.slice(dot + 1);
      var obj = root.getObjectByName(target);
      if (!obj) root.traverse(function (o) { if (!obj && o.userData && o.userData.name === target) obj = o; });
      var ni = obj ? self.nodeIndex.get(obj) : undefined;
      var path = { position: 'translation', quaternion: 'rotation', scale: 'scale' }[prop];
      if (ni === undefined || !path) return;
      var n = path === 'rotation' ? 4 : 3;
      var input = self.accessor(new Float32Array(t.times), 'SCALAR', t.times.length, null, true);
      var output = self.accessor(new Float32Array(t.values), n === 4 ? 'VEC4' : 'VEC3', t.times.length);
      anim.samplers.push({ input: input, output: output, interpolation: 'LINEAR' });
      anim.channels.push({ sampler: anim.samplers.length - 1, target: { node: ni, path: path } });
    });
    if (anim.channels.length) this.json.animations.push(anim);
  };

  /* Prepara o carro do site para o nativo e exporta: peças das duas versões visíveis e nomeadas,
     interior fora, aberturas com o lado real do carro. Grava em ex2-native/assets-src/<nome>. */
  window.EX2ExportCar = function (fileName) {
    var M = window.EX2_model, root = M.root;
    function nameOf(o) { return (o.userData && o.userData.name) || o.name || ''; }
    Object.keys(M.wheels).forEach(function (kind) {
      M.wheels[kind].forEach(function (o, i) { o.visible = true; o.name = 'X_WHEEL|' + kind + '|' + i; });
    });
    M.surroundCameras.forEach(function (c, i) {
      c.lens.visible = true; c.blank.visible = true;
      c.lens.name = 'X_CAMLENS|' + i; c.blank.name = 'X_CAMBLANK|' + i;
    });
    M.contrastParts.forEach(function (p, i) { p.mesh.name = 'X_CONTRAST|' + (p.mirror ? 'mirror' : 'body') + '|' + i; });
    var strips = 0;
    root.traverse(function (o) {
      if (!o.isMesh || !o.material || o.material.name !== 'E22H_MatteChrome_Silver') return;
      for (var p = o.parent; p; p = p.parent) if (/Inner top/.test(nameOf(p))) return;
      o.name = 'X_STRIP|' + strips++;
    });
    window.EX2L.parts.forEach(function (p, i) {
      p.mat.emissiveIntensity = 0;
      p.mesh.name = 'X_LAMP|' + p.kind + '|' + p.side + '|' + p.rank + '|' + i;
    });
    var INTERIOR = /^E22H_(Inner top|IP|IP Charging_L|IP_LED|Front seat|Rear seats|Steering wheel|Seatbelt|brake throttle|Carpet|Rear component)/;
    root.traverse(function (o) { if (o !== root && INTERIOR.test(nameOf(o))) o.visible = false; });
    /* no modelo, Door_FL fica do lado direito do carro (x < 0) e Door_FR do esquerdo: nomes reais */
    var real = { doorFL: 'doorFR', doorFR: 'doorFL', doorRL: 'doorRL', doorRR: 'doorRR', tailgate: 'tailgate', frunk: 'frunk' };
    var clips = Object.keys(real).map(function (k) {
      var c = M.openings.get(real[k]).action.getClip().clone(); c.name = k; return c;
    });
    window.__exp = { state: 'exportando' };
    return window.EX2Export(root, { clips: clips, maxTexture: 2048 }).then(function (glb) {
      window.__exp = { state: 'enviando', mb: +(glb.byteLength / 1e6).toFixed(1) };
      return fetch('./out/' + fileName, { method: 'PUT', body: glb });
    }).then(function (r) { window.__exp.state = r.ok ? 'ok' : 'erro HTTP ' + r.status; })
      .catch(function (e) { window.__exp = { state: 'erro ' + e }; });
  };

  /* Interior 3D (bancos, painel, volante, forro, carpete, porta-malas) num GLB próprio, na mesma
     posição do car.glb. O nativo só desenha quando alguma porta/tampa está aberta. */
  window.EX2ExportInteriorMesh = function (fileName) {
    var root = window.EX2_model.root;
    function nameOf(o) { return (o.userData && o.userData.name) || o.name || ''; }
    var INTERIOR = /^E22H_(Inner top|IP|IP Charging_L|IP_LED|Front seat|Rear seats|Steering wheel|Seatbelt|brake throttle|Carpet|Rear component)/;
    var changed = [];
    function inInterior(o) { for (var p = o; p && p !== root; p = p.parent) if (INTERIOR.test(nameOf(p))) return true; return false; }
    root.traverse(function (o) {
      if (o === root) return;
      if (INTERIOR.test(nameOf(o)) && !o.visible) { o.visible = true; changed.push([o, false]); }
    });
    root.traverse(function (o) {
      if (o.isMesh && o.visible && !inInterior(o)) { o.visible = false; changed.push([o, true]); }
    });
    window.__exp = { state: 'exportando interior' };
    return window.EX2Export(root, { clips: [], maxTexture: 1024 }).then(function (glb) {
      changed.forEach(function (c) { c[0].visible = c[1]; });
      window.__exp = { state: 'enviando', mb: +(glb.byteLength / 1e6).toFixed(1) };
      return fetch('./out/' + fileName, { method: 'PUT', body: glb });
    }).then(function (r) { window.__exp.state = r.ok ? 'ok' : 'erro HTTP ' + r.status; })
      .catch(function (e) { window.__exp = { state: 'erro ' + e }; });
  };

  /* "Fotos" do interior para o app nativo (o interior 3D não vai para o nativo): cada uma vista de
     longe com lente quase sem perspectiva, para casar com um plano no vão de porta/vidro.
     Lado esquerdo do carro = x positivo. Retorna as áreas (m) de cada foto. */
  window.EX2ExportInterior = function () {
    var app = window.EX2L.app, root = window.EX2_model.root, T = window.EX2_three;
    function nameOf(o) { return (o.userData && o.userData.name) || o.name || ''; }
    var INTERIOR = /^E22H_(Inner top|IP|IP Charging_L|IP_LED|Front seat|Rear seats|Steering wheel|Seatbelt|brake throttle|Carpet|Rear component)/;
    root.traverse(function (o) { if (INTERIOR.test(nameOf(o))) o.visible = true; });
    var world = window.EX2Sky && window.EX2Sky.reflector && window.EX2Sky.reflector().parent;
    if (world) world.visible = false;
    app.scene.background = new T.Color(0x050506);
    app.controls.maxDistance = 1000;
    var shots = [
      { file: 'interior-left.jpg', pos: [14, 0.92, -0.05], target: [0, 0.92, -0.05], w: 2.0, h: 1.15, hide: /Door_(FR|RL)/ },
      { file: 'interior-right.jpg', pos: [-14, 0.92, -0.05], target: [0, 0.92, -0.05], w: 2.0, h: 1.15, hide: /Door_(FL|RR)/ },
      { file: 'interior-front.jpg', pos: [0, 1.15, 14], target: [0, 1.15, 0], w: 1.5, h: 0.62, hide: /^$/ },
      { file: 'interior-rear.jpg', pos: [0, 1.15, -14], target: [0, 1.15, 0], w: 1.3, h: 0.5, hide: /^$/ }
    ];
    var hiddenNow = [];
    function restore() { hiddenNow.forEach(function (o) { o.visible = true; }); hiddenNow = []; }
    var jobs = shots.map(function (s) {
      restore();
      root.traverse(function (o) {
        var glass = o.isMesh && o.material && /Glass/.test(o.material.name || '') && !/Light/.test(o.material.name || '');
        if (o.visible && (glass || s.hide.test(nameOf(o)))) { o.visible = false; hiddenNow.push(o); }
      });
      var dist = Math.hypot(s.pos[0] - s.target[0], s.pos[1] - s.target[1], s.pos[2] - s.target[2]);
      var fov = 2 * Math.atan((Math.max(s.h, s.w / app.camera.aspect) * 0.55) / dist) * 180 / Math.PI;
      app.placeCamera(new T.Vector3().fromArray(s.pos), new T.Vector3().fromArray(s.target), fov);
      app.renderer.render(app.scene, app.camera);
      var cv = app.renderer.domElement, ppu = cv.height / (2 * dist * Math.tan(fov * Math.PI / 360));
      var cw = Math.round(s.w * ppu), ch = Math.round(s.h * ppu);
      var out = document.createElement('canvas'); out.width = cw; out.height = ch;
      out.getContext('2d').drawImage(cv, (cv.width - cw) / 2, (cv.height - ch) / 2, cw, ch, 0, 0, cw, ch);
      var url = out.toDataURL('image/jpeg', 0.88), bin = atob(url.slice(url.indexOf(',') + 1)), bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return fetch('./out/' + s.file, { method: 'PUT', body: bytes }).then(function (r) { return s.file + ' ' + cw + 'x' + ch + ' ' + (r.ok ? 'ok' : r.status); });
    });
    restore();
    return Promise.all(jobs);
  };

  /* Panorama 360° (equiretangular 2:1) do cenário — céu, montanhas e lago — sem o carro, para o
     fundo do app nativo. Convenção da projeção (a mesma do shader nativo):
       u = atan2(dir.x, -dir.z) / 2π + 0.5,  v = acos(dir.y) / π   (v = 0 em cima). */
  window.EX2ExportSky = function (file, hour, weather, width) {
    var app = window.EX2L.app, T = window.EX2_three, root = window.EX2_model.root;
    width = width || 2048;
    var sky = window.EX2Sky, water = sky.reflector(), world = water.parent;
    world.visible = true; root.visible = false;
    app.scene.background = null;
    sky.setWeather(weather); sky.setHour(hour);
    var FACE = 512, cam = app.camera, oldFov = cam.fov, oldAspect = cam.aspect;
    var size = { x: 0, y: 0, set: function (x, y) { this.x = x; this.y = y; return this; } };
    app.renderer.getSize(size);
    var dome = null; world.children.forEach(function (c) { if (c.geometry && c.geometry.type === 'SphereGeometry') dome = c; });
    var eye = new T.Vector3(0, 1.0, 0);
    var dirs = [
      { d: [1, 0, 0], up: [0, 1, 0] }, { d: [-1, 0, 0], up: [0, 1, 0] },
      { d: [0, 1, 0], up: [0, 0, 1] }, { d: [0, -1, 0], up: [0, 0, -1] },
      { d: [0, 0, 1], up: [0, 1, 0] }, { d: [0, 0, -1], up: [0, 1, 0] }
    ];
    /* deixa o clima assentar (transição de nuvens anda por quadro) */
    return new Promise(function (res) {
      var n = 0;
      (function warm() { app.requestRender(); if (++n < 90) requestAnimationFrame(warm); else res(); })();
    }).then(function () {
      app.renderer.setSize(FACE, FACE, false);
      /* o site desloca a perspectiva para abrir espaço ao painel: sem isso as faces não casam */
      var view = cam.view && cam.view.enabled ? Object.assign({}, cam.view) : null;
      cam.clearViewOffset();
      cam.fov = 90; cam.aspect = 1; cam.updateProjectionMatrix();
      /* sombras do carro ficam fora do cenário (o nativo desenha as dele) */
      var shadows = [];
      world.children.forEach(function (c) {
        if (c.isMesh && c.visible && (c.material.type === 'ShadowMaterial' || (c.material.isMeshBasicMaterial && c.material.map))) { c.visible = false; shadows.push(c); }
      });
      var faces = dirs.map(function (f) {
        cam.position.copy(eye); cam.up.fromArray(f.up);
        cam.lookAt(eye.x + f.d[0], eye.y + f.d[1], eye.z + f.d[2]);
        cam.updateMatrixWorld();
        if (dome) dome.position.copy(eye);
        app.renderer.render(app.scene, cam);
        var c = document.createElement('canvas'); c.width = c.height = FACE;
        var g = c.getContext('2d'); g.drawImage(app.renderer.domElement, 0, 0, FACE, FACE);
        /* base de cada face: frente f, direita r, cima u (como a câmera vê) */
        var fw = new T.Vector3().fromArray(f.d), up = new T.Vector3().fromArray(f.up);
        var right = new T.Vector3().crossVectors(fw, up).normalize();
        return { px: g.getImageData(0, 0, FACE, FACE).data, f: fw, r: right, u: new T.Vector3().crossVectors(right, fw) };
      });
      if (window.__skyDebug) {
        var strip = document.createElement('canvas'); strip.width = FACE * 6; strip.height = FACE;
        var sg = strip.getContext('2d');
        faces.forEach(function (fc, i) { var id = sg.createImageData(FACE, FACE); id.data.set(fc.px); sg.putImageData(id, i * FACE, 0); });
        window.__skyDebug = strip.toDataURL('image/jpeg', 0.8);
      }
      var W = width, H = width / 2, out = document.createElement('canvas');
      out.width = W; out.height = H;
      var og = out.getContext('2d'), img = og.createImageData(W, H), dst = img.data;
      for (var y = 0; y < H; y++) {
        var th = (y + 0.5) / H * Math.PI, sy = Math.cos(th), sr = Math.sin(th);
        for (var x = 0; x < W; x++) {
          var ph = ((x + 0.5) / W - 0.5) * 2 * Math.PI;
          var dx = Math.sin(ph) * sr, dz = -Math.cos(ph) * sr, best = null, bd = -2;
          for (var k = 0; k < 6; k++) {
            var fk = faces[k].f, dot = dx * fk.x + sy * fk.y + dz * fk.z;
            if (dot > bd) { bd = dot; best = faces[k]; }
          }
          var a = (dx * best.r.x + sy * best.r.y + dz * best.r.z) / bd;
          var b = (dx * best.u.x + sy * best.u.y + dz * best.u.z) / bd;
          var px = Math.min(FACE - 1, Math.max(0, Math.floor((a * 0.5 + 0.5) * FACE)));
          var py = Math.min(FACE - 1, Math.max(0, Math.floor((0.5 - b * 0.5) * FACE)));
          var si = (py * FACE + px) * 4, di = (y * W + x) * 4;
          dst[di] = best.px[si]; dst[di + 1] = best.px[si + 1]; dst[di + 2] = best.px[si + 2]; dst[di + 3] = 255;
        }
      }
      og.putImageData(img, 0, 0);
      app.renderer.setSize(size.x, size.y, false);
      shadows.forEach(function (c) { c.visible = true; });
      cam.fov = oldFov; cam.aspect = oldAspect; cam.up.set(0, 1, 0);
      if (view) cam.setViewOffset(view.fullWidth, view.fullHeight, view.offsetX, view.offsetY, view.width, view.height);
      cam.updateProjectionMatrix();
      root.visible = true;
      var url = out.toDataURL('image/jpeg', 0.9), bin = atob(url.slice(url.indexOf(',') + 1)), bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      return fetch('./out/' + file, { method: 'PUT', body: bytes }).then(function (r) { return file + ' ' + (r.ok ? 'ok' : r.status); });
    });
  };

  window.EX2Export = function (root, opts) {
    opts = opts || {};
    var b = new Builder();
    b.maxTexture = opts.maxTexture || 2048;
    var top = b.node(root);
    b.json.scenes[0].nodes = [top];
    (opts.clips || []).forEach(function (c) { b.animation(c, root); });
    return Promise.all(b.pending).then(function () {
      if (!b.json.extensionsUsed.length) delete b.json.extensionsUsed;
      ['animations', 'textures', 'images'].forEach(function (k) { if (!b.json[k].length) delete b.json[k]; });
      if (!b.json.textures) delete b.json.samplers;
      b.json.buffers = [{ byteLength: b.byteLength }];
      var js = new TextEncoder().encode(JSON.stringify(b.json));
      var jsPad = (4 - js.length % 4) % 4, binPad = (4 - b.byteLength % 4) % 4;
      var total = 12 + 8 + js.length + jsPad + 8 + b.byteLength + binPad;
      var out = new Uint8Array(total), dv = new DataView(out.buffer), p = 0;
      dv.setUint32(0, 0x46546C67, true); dv.setUint32(4, 2, true); dv.setUint32(8, total, true); p = 12;
      dv.setUint32(p, js.length + jsPad, true); dv.setUint32(p + 4, 0x4E4F534A, true); p += 8;
      out.set(js, p); p += js.length; for (var i = 0; i < jsPad; i++) out[p++] = 0x20;
      dv.setUint32(p, b.byteLength + binPad, true); dv.setUint32(p + 4, 0x004E4942, true); p += 8;
      b.chunks.forEach(function (c) { out.set(c, p); p += c.byteLength; });
      return out.buffer;
    });
  };
})();
