"""Servidor local de exportação (só no PC): entrega o site ex2-3d/ (GET) e grava o que o navegador
envia (PUT /out/nome) em ex2-native/assets-src/. Mesma origem, sem bloqueio entre portas.
   python receive.py [porta]"""
import os
import sys
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
SITE = os.path.normpath(os.path.join(HERE, "..", "..", "ex2-3d"))
OUT = os.path.normpath(os.path.join(HERE, "..", "assets-src"))
os.makedirs(OUT, exist_ok=True)


class Handler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def do_PUT(self):
        path = self.path.split("?")[0]
        name = os.path.basename(path)
        if not path.startswith("/out/") or not name or name.startswith("."):
            self.send_error(400)
            return
        size = int(self.headers.get("Content-Length", 0))
        with open(os.path.join(OUT, name), "wb") as f:
            remaining = size
            while remaining:
                chunk = self.rfile.read(min(remaining, 1 << 20))
                if not chunk:
                    break
                f.write(chunk)
                remaining -= len(chunk)
        self.send_response(200)
        self.send_header("Content-Length", "2")
        self.end_headers()
        self.wfile.write(b"ok")
        print("salvo", name, size, flush=True)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8799
    ThreadingHTTPServer(("127.0.0.1", port), partial(Handler, directory=SITE)).serve_forever()
