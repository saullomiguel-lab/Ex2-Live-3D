"""Gera app/src/main/assets/i18n/<idioma>.json a partir de tools/i18n/textos-*.txt e saudacoes.txt.
Uso: python tools\\make_i18n.py   (rodar de novo depois de editar as tabelas)"""
import json, os, glob, re

here = os.path.dirname(os.path.abspath(__file__))
LANGS = ["en", "es", "fr", "ru", "ar", "th", "vi", "id", "zh"]
out = {l: {} for l in LANGS}
problems = 0
for path in sorted(glob.glob(os.path.join(here, "i18n", "textos-*.txt"))):
    for n, line in enumerate(open(path, encoding="utf-8"), 1):
        line = line.rstrip("\n")
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("‖")
        if len(cols) != 10:
            print(f"{os.path.basename(path)}:{n}: {len(cols)} colunas"); problems += 1; continue
        pt = cols[0]
        fmt = sorted(re.findall(r"%[0-9.]*[sdf%]", pt))
        for l, v in zip(LANGS, cols[1:]):
            if pt != "@data" and sorted(re.findall(r"%[0-9.]*[sdf%]", v)) != fmt:
                print(f"{os.path.basename(path)}:{n} [{l}] formato diferente: {v}"); problems += 1
            out[l][pt] = v
for line in open(os.path.join(here, "i18n", "saudacoes.txt"), encoding="utf-8"):
    line = line.strip()
    if not line or line.startswith("#"):
        continue
    key, val = line.split("=", 1)
    l, group = key.strip().split(".")
    out[l]["@saudacoes." + group] = val.strip()
dst = os.path.join(here, "..", "app", "src", "main", "assets", "i18n")
os.makedirs(dst, exist_ok=True)
for l in LANGS:
    with open(os.path.join(dst, l + ".json"), "w", encoding="utf-8") as f:
        json.dump(out[l], f, ensure_ascii=False, indent=0)
print(f"{len(out['en'])} textos por idioma, {problems} problema(s)")
