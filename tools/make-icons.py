# Builds launcher icons and splash screens from native/brand/logo-source.png into native/res/.
# Run again whenever the logo changes:  python3 tools/make-icons.py
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
src = Image.open(os.path.join(ROOT, 'native/brand/logo-source.png')).convert('RGB')
W, H = src.size

# 1. find the glowing cyan border of the rounded square (the page around it is white with a soft shadow)
px = src.load()
def cyan(p):
    return p[2] > 120 and p[1] > 100 and p[2] - p[0] > 60
def span(pts):
    idx = [i for i, p in enumerate(pts) if cyan(p)]
    return idx[0], idx[-1]
ls, rs, ts, bs = [], [], [], []
for f in (0.40, 0.45, 0.50, 0.55, 0.60):          # several scan lines, away from the rounded corners
    l, r = span([px[x, int(H * f)] for x in range(W)]); ls.append(l); rs.append(r)
    t, b = span([px[int(W * f), y] for y in range(H)]); ts.append(t); bs.append(b)
l, r, t, b = min(ls), max(rs), min(ts), max(bs)
side = max(r - l, b - t) + 1
cx, cy = (l + r) / 2.0, (t + b) / 2.0
half = side / 2.0 * 1.012                           # keep the whole border line
logo = src.crop((int(round(cx - half)), int(round(cy - half)), int(round(cx + half)), int(round(cy + half))))
S = logo.size[0]

# 2. background colour: the darkest calm area inside the square
bg = logo.getpixel((int(S * 0.12), int(S * 0.50)))
print('square', (l, t, r, b), 'side', S, 'background', bg)

def rounded(size, radius_frac, scale=4):
    m = Image.new('L', (size * scale, size * scale), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size * scale - 1, size * scale - 1), radius=int(size * scale * radius_frac), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def circle(size, scale=4):
    m = Image.new('L', (size * scale, size * scale), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size * scale - 1, size * scale - 1), fill=255)
    return m.resize((size, size), Image.LANCZOS)

def logo_rgba(size, mask):
    im = logo.resize((size, size), Image.LANCZOS).convert('RGBA')
    im.putalpha(mask)
    return im

def save(im, rel):
    path = os.path.join(ROOT, 'native/res', rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    im.save(path, optimize=True)

RADIUS = 0.19   # matches the corner of the logo's own square
dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for name, k in dens.items():
    s = int(48 * k)
    save(logo_rgba(s, rounded(s, RADIUS)), 'mipmap-%s/ic_launcher.png' % name)
    # round launchers: shrink a little so the wordmark stays inside the circle
    canvas = Image.new('RGBA', (s, s), bg + (255,))
    inner = int(s * 0.86)
    lg = logo_rgba(inner, rounded(inner, RADIUS))
    canvas.alpha_composite(lg, ((s - inner) // 2, (s - inner) // 2))
    canvas.putalpha(circle(s))
    save(canvas, 'mipmap-%s/ic_launcher_round.png' % name)
    # adaptive icon foreground: 108dp canvas, artwork kept inside the 66dp safe zone
    f = int(108 * k)
    fg = Image.new('RGBA', (f, f), bg + (255,))
    inner = int(f * 0.70)
    lg = logo_rgba(inner, rounded(inner, RADIUS))
    fg.alpha_composite(lg, ((f - inner) // 2, (f - inner) // 2))
    save(fg, 'mipmap-%s/ic_launcher_foreground.png' % name)

splash = {'drawable': (480, 320), 'drawable-land-mdpi': (480, 320), 'drawable-land-hdpi': (800, 480), 'drawable-land-xhdpi': (1280, 720),
          'drawable-land-xxhdpi': (1600, 960), 'drawable-land-xxxhdpi': (1920, 1280), 'drawable-port-mdpi': (320, 480),
          'drawable-port-hdpi': (480, 800), 'drawable-port-xhdpi': (720, 1280), 'drawable-port-xxhdpi': (960, 1600), 'drawable-port-xxxhdpi': (1280, 1920)}
for folder, (w, h) in splash.items():
    im = Image.new('RGBA', (w, h), bg + (255,))
    s = int(min(w, h) * 0.5)
    im.alpha_composite(logo_rgba(s, rounded(s, RADIUS)), ((w - s) // 2, (h - s) // 2))
    save(im.convert('RGB'), folder + '/splash.png')

# in-app copy for the top bar / about
logo_rgba(112, rounded(112, RADIUS)).save(os.path.join(ROOT, 'native/brand/logo-112.png'), optimize=True)
open(os.path.join(ROOT, 'native/brand/background.txt'), 'w').write('#%02X%02X%02X' % bg)
