# Generates the MaterialPods icon as SVG (preview) and Android vector drawables from one model.
import math, sys, os
OUT = sys.argv[1]  # usage: python3 icon-gen.py <outdir>

BG = "#4E5AC8"; COOKIE = "#6673DA"
INK = "#2E2A5C"; BODY = "#E4EEFF"; TIP = "#A9B1D6"; SHADE = "#C3CDEB"; WHITE = "#FFFFFF"; SHADOW = "#33000000"
o, b = 2.4, 3.0   # outline width, sticker border
LOBES, AMP = 9, 0.07   # background cookie shape

def ellipse(cx, cy, rx, ry):
    return f"M{cx-rx:.2f},{cy:.2f} a{rx:.2f},{ry:.2f} 0 1,0 {2*rx:.2f},0 a{rx:.2f},{ry:.2f} 0 1,0 {-2*rx:.2f},0 Z"

def cookie(cx, cy, r, lobes, amp, n=240):
    base = r / (1 + amp); pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        rr = base * (1 + amp * math.cos(lobes * t))
        pts.append(f"{cx + rr*math.cos(t):.2f},{cy + rr*math.sin(t):.2f}")
    return "M" + " L".join(pts) + " Z"

# One AirPod Pro in local coords (head centre = origin), ear tip on the left, stem down-right.
TIPP = ellipse(-11.5, 0.5, 5.2, 7.6)
HEAD = ellipse(0, -1, 11.5, 10.5)
STEM = "M5.5,6 L7.5,27"; SW = 8.0
SLOT = "M-1.5,-6.5 L5,-6.5"
SHADE_ARC = "M-9,4 Q0,10 9,4"  # underside shading

def bud_layers():
    """(kind, path, fill, stroke, width) in paint order, for one bud."""
    L = []
    # sticker border (union look)
    for p in (TIPP, HEAD): L.append(("shape", p, WHITE, WHITE, o + 2*b))
    L.append(("line", STEM, None, WHITE, SW + o + 2*b))
    # tip, stem, head with outlines
    L.append(("shape", TIPP, TIP, INK, o))
    L.append(("line", STEM, None, INK, SW + o))
    L.append(("line", STEM, None, BODY, SW - o))
    L.append(("shape", HEAD, BODY, INK, o))
    L.append(("line", SHADE_ARC, None, SHADE, 2.2))
    L.append(("line", SLOT, None, INK, 2.4))
    return L

def shadow_layers():
    return [("shape", p, SHADOW, SHADOW, o + 2*b) for p in (TIPP, HEAD)] + [("line", STEM, None, SHADOW, SW + o + 2*b)]

# Placement on the 108 grid (safe zone ~21..87): two buds, mirrored, tips outward, slight tilt.
S = 0.84
BUDS = [  # (tx, ty, rotation, mirror)
    (39, 46, -8, False),
    (69, 46, 8, True),
]

def svg_group(layers, tx, ty, rot, mirror, dx=0, dy=0):
    sx = -1 if mirror else 1
    out = [f'<g transform="translate({tx+dx},{ty+dy}) rotate({rot}) scale({sx*S},{S})">']
    for kind, p, fill, stroke, w in layers:
        fa = fill if fill else "none"
        def c(x):  # #AARRGGBB -> svg rgba
            if x and len(x) == 9:
                a = int(x[1:3], 16) / 255; return f'rgba({int(x[3:5],16)},{int(x[5:7],16)},{int(x[7:9],16)},{a:.2f})'
            return x
        out.append(f'<path d="{p}" fill="{c(fa) if fa!="none" else "none"}" stroke="{c(stroke)}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round"/>')
    out.append('</g>')
    return "\n".join(out)

def svg(full=True):
    s = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="512" height="512">']
    if full:
        s.append('<defs><clipPath id="m"><circle cx="54" cy="54" r="54"/></clipPath></defs><g clip-path="url(#m)">')
        s.append(f'<rect width="108" height="108" fill="{BG}"/>')
        s.append(f'<path d="{cookie(54,54,40,LOBES,AMP)}" fill="{COOKIE}"/>')
    for tx, ty, r, m in BUDS: s.append(svg_group(shadow_layers(), tx, ty, r, m, 1.4, 2.0))
    for tx, ty, r, m in BUDS: s.append(svg_group(bud_layers(), tx, ty, r, m))
    if full: s.append('</g>')
    s.append('</svg>')
    return "\n".join(s)

# ---- Android vector drawables ----
def vd_paths(layers, mono=False):
    out = []
    for kind, p, fill, stroke, w in layers:
        if mono:  # monochrome: just the silhouette + inner details as cut-outs aren't supported; keep fills
            if stroke == WHITE or fill == SHADOW: continue
        attrs = [f'android:pathData="{p}"']
        if fill: attrs.append(f'android:fillColor="{fill if not mono else "#FFFFFFFF"}"')
        if stroke: attrs += [f'android:strokeColor="{stroke if not mono else "#FFFFFFFF"}"', f'android:strokeWidth="{w}"', 'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        out.append('        <path ' + " ".join(attrs) + '/>')
    return "\n".join(out)

def vd_group(body, tx, ty, rot, mirror, dx=0, dy=0):
    return (f'    <group android:translateX="{tx+dx}" android:translateY="{ty+dy}">\n'
            f'      <group android:rotation="{rot}" android:scaleX="{-S if mirror else S}" android:scaleY="{S}">\n{body}\n      </group>\n    </group>')

def vd(content, w=108):
    return (f'<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="{w}dp" android:height="{w}dp"\n'
            f'    android:viewportWidth="108" android:viewportHeight="108">\n{content}\n</vector>\n')

def foreground():
    parts = [vd_group(vd_paths(shadow_layers()), tx, ty, r, m, 1.4, 2.0) for tx, ty, r, m in BUDS]
    parts += [vd_group(vd_paths(bud_layers()), tx, ty, r, m) for tx, ty, r, m in BUDS]
    return vd("\n".join(parts))

def background():
    return vd(f'    <path android:pathData="M0,0h108v108h-108z" android:fillColor="{BG}"/>\n    <path android:pathData="{cookie(54,54,40,LOBES,AMP)}" android:fillColor="{COOKIE}"/>')

def monochrome():
    # Solid silhouettes; details (slot) drawn transparent isn't possible, so draw slot in black-ish via fillType trick omitted.
    L = [("shape", TIPP, "#FFFFFFFF", None, 0), ("shape", HEAD, "#FFFFFFFF", None, 0), ("line", STEM, None, "#FFFFFFFF", SW)]
    return vd("\n".join(vd_group(vd_paths(L), tx, ty, r, m) for tx, ty, r, m in BUDS))

os.makedirs(OUT, exist_ok=True)
open(f"{OUT}/icon.svg", "w").write(svg())
open(f"{OUT}/fg.svg", "w").write(svg(full=False))
open(f"{OUT}/ic_launcher_foreground.xml", "w").write(foreground())
open(f"{OUT}/ic_launcher_background.xml", "w").write(background())
open(f"{OUT}/ic_launcher_monochrome.xml", "w").write(monochrome())
print("ok")

# Small status/tile icon: same silhouettes, viewport cropped to the buds.
mono = monochrome()
stat = mono.replace('android:width="108dp" android:height="108dp"', 'android:width="24dp" android:height="24dp"')
stat = stat.replace('android:viewportWidth="108" android:viewportHeight="108"', 'android:viewportWidth="56" android:viewportHeight="56"')
stat = stat.replace('<group android:translateX="39"', '<group android:translateX="13"').replace('<group android:translateX="69"', '<group android:translateX="43"')
stat = stat.replace('android:translateY="46"', 'android:translateY="20"')
open(f"{OUT}/ic_stat_pods.xml", "w").write(stat)
