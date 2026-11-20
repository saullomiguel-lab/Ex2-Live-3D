"""Gera os ícones da interface (VectorDrawable, traço 24×24) em app/src/main/res/drawable.
   python make_icons.py"""
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
os.makedirs(OUT, exist_ok=True)

C = "M{x},{y}m-{r},0a{r},{r} 0,1 1,{d},0a{r},{r} 0,1 1,-{d},0"   # círculo


def circle(x, y, r):
    return C.format(x=x, y=y, r=r, d=2 * r)


# (traço, preenchimento) — o traço usa a cor branca; o tint da View muda a cor
ICONS = {
    "ic_edit": ("M4,20h4L19,9l-4,-4L4,16v4z M13.5,6.5l4,4", None),
    # vistas: silhueta do carro de cima/lado
    "ic_view_34": ("M3,15.5l2.2,-4.6c0.4,-0.8 1.2,-1.4 2.1,-1.4h6.4c0.9,0 1.7,0.5 2.2,1.2L19,15.5 M3,15.5h18v2.5H3z M4,11l-1.5,-0.5 M20,11l1.5,-0.5", None),
    "ic_view_front": ("M5,11l1.6,-4.2c0.3,-0.8 1,-1.3 1.9,-1.3h7c0.9,0 1.6,0.5 1.9,1.3L19,11 M4,11h16v6H4z M4,17v2h3v-2 M17,17v2h3v-2 M7,14h2 M15,14h2", None),
    "ic_view_side": ("M2,15v-2.5c0,-0.8 0.5,-1.5 1.3,-1.7l3.2,-0.8l2.6,-3c0.4,-0.4 0.9,-0.7 1.5,-0.7h4.6c0.6,0 1.2,0.3 1.6,0.8l2.4,3.2c1.6,0.3 2.8,1.2 2.8,2.6V15H2z " + circle(6.5, 16, 2) + " " + circle(17.5, 16, 2), None),
    "ic_view_rear": ("M5,11l1.6,-4.2c0.3,-0.8 1,-1.3 1.9,-1.3h7c0.9,0 1.6,0.5 1.9,1.3L19,11 M4,11h16v6H4z M4,17v2h3v-2 M17,17v2h3v-2 M6,13.5h3 M15,13.5h3 M10,15.5h4", None),
    "ic_view_top": ("M8,3h8c1.1,0 2,0.9 2,2v14c0,1.1 -0.9,2 -2,2H8c-1.1,0 -2,-0.9 -2,-2V5c0,-1.1 0.9,-2 2,-2z M7.5,8.5h9 M7.5,15.5h9 M8.5,8.5l1,-2.5h5l1,2.5", None),
    # luzes
    "ic_headlight": ("M10,5c-3.3,0 -6,3.1 -6,7s2.7,7 6,7c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2z M15,7.5h6 M15,10.5h6 M15,13.5h6 M15,16.5h6", None),
    "ic_drl": ("M3,9h7l2,3h-9z M3,15h7 M14,7l1.5,1.5 M14,17l1.5,-1.5 M16,12h5 M19,7l1.5,-1 M19,17l1.5,1", None),
    "ic_brake": (circle(12, 12, 6) + " M12,9v3.5 M12,15v0.1 M4.5,6.5c-1.6,1.5 -2.5,3.4 -2.5,5.5s0.9,4 2.5,5.5 M19.5,6.5c1.6,1.5 2.5,3.4 2.5,5.5s-0.9,4 -2.5,5.5", None),
    "ic_left": (None, "M10,4L2,12l8,8v-5h11V9H10z"),
    "ic_right": (None, "M14,4l8,8l-8,8v-5H3V9h11z"),
    "ic_hazard": ("M12,3L2,20h20z M12,8.5L7,17h10z", None),
    # clima e horário
    "ic_sun": (circle(12, 12, 4.2) + " M12,2.7v2.1M12,19.2v2.1M2.7,12h2.1M19.2,12h2.1M5.4,5.4l1.5,1.5M17.1,17.1l1.5,1.5M18.6,5.4l-1.5,1.5M6.9,17.1l-1.5,1.5", None),
    "ic_cloud": ("M8.4,17.6h9.1a3.3,3.3 0,0 0,0.2 -6.6a5,5 0,0 0,-9.5 -1.4a4,4 0,0 0,0.2 8z", None),
    "ic_rain": ("M7.4,13.6h10a3.3,3.3 0,0 0,0.2 -6.6a5,5 0,0 0,-9.5 -1.4a4,4 0,0 0,-0.7 8z M8.6,16.2l-0.8,2.6M12,16.2l-0.8,2.6M15.4,16.2l-0.8,2.6", None),
    "ic_storm": ("M7.4,13.2h10a3.3,3.3 0,0 0,0.2 -6.6A5,5 0,0 0,7.9 5.2a4,4 0,0 0,-0.5 8z", "M12.9,13.9L10.1,18.3h2.4l-0.8,4.1l4.1,-5.6h-2.6l1.1,-2.9z"),
    "ic_sunrise": ("M4,18h16 M7,18a5,5 0,0 1,10 0 M12,4v5 M9.5,6.5L12,4l2.5,2.5 M3.5,13.5l1.5,0.8 M20.5,13.5l-1.5,0.8", None),
    "ic_sunset": ("M4,18h16 M7,18a5,5 0,0 1,10 0 M12,4v5 M9.5,6.5L12,9l2.5,-2.5 M3.5,13.5l1.5,0.8 M20.5,13.5l-1.5,0.8 M17,4.5a2.5,2.5 0,1 0,2.5 3.2", None),
    "ic_clock": (circle(12, 12, 8.5) + " M12,7v5l3.5,2", None),
    "ic_auto": ("M4,12a8,8 0,0 1,13.7 -5.6 M20,12a8,8 0,0 1,-13.7 5.6 M17.7,3v3.4h-3.4 M6.3,21v-3.4h3.4", None),
    "ic_wallpaper": ("M4,5h16v12H4z M9,21h6 M12,17v4 M6.5,14l3,-3.5l2.5,2.5l2,-2l3.5,3", None),
    "ic_gear": (circle(12, 12, 3) + " M12,2.5v3M12,18.5v3M2.5,12h3M18.5,12h3M5.3,5.3l2.1,2.1M16.6,16.6l2.1,2.1M18.7,5.3l-2.1,2.1M7.4,16.6l-2.1,2.1", None),
    "ic_pin": ("M12,21s-6,-5.3 -6,-10a6,6 0,0 1,12 0c0,4.7 -6,10 -6,10z " + circle(12, 11, 2.2), None),
    "ic_close": ("M6,6l12,12 M18,6L6,18", None),
    "ic_check": ("M5,12.5l4.5,4.5L19,7.5", None),
}

for name, (stroke, fill) in ICONS.items():
    paths = []
    if stroke:
        paths.append('    <path android:pathData="%s" android:strokeColor="#FFFFFFFF" android:strokeWidth="1.7"\n'
                     '        android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000" />' % stroke)
    if fill:
        paths.append('    <path android:pathData="%s" android:fillColor="#FFFFFFFF" />' % fill)
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">\n'
           + "\n".join(paths) + "\n</vector>\n")
    open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8").write(xml)
print(len(ICONS), "ícones")
