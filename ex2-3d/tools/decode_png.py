import base64, sys

HEX = open(sys.argv[1], "r").read().strip().replace('"', '').replace("'", "")
raw = bytes.fromhex(HEX)
out = sys.argv[2]
open(out, "wb").write(raw)
print("written", out, len(raw))
