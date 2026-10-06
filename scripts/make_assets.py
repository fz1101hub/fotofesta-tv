from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
source = Image.open(ROOT / "design/app_icon_source.png").convert("RGBA")

# Icona TV quadrata ad alta risoluzione, senza ritaglio del simbolo.
icon = source.resize((512, 512), Image.Resampling.LANCZOS)
mask = Image.new("L", (512, 512), 0)
ImageDraw.Draw(mask).rounded_rectangle((10, 10, 502, 502), radius=104, fill=255)
icon.putalpha(mask)
icon_dir = ROOT / "app/src/main/res/mipmap-xxxhdpi"
icon_dir.mkdir(parents=True, exist_ok=True)
icon.save(icon_dir / "ic_launcher.png", optimize=True)

# Banner richiesto da Google Play/Android TV: 320x180 con nome leggibile.
banner_dir = ROOT / "app/src/main/res/drawable-xhdpi"
banner_dir.mkdir(parents=True, exist_ok=True)
banner = Image.new("RGB", (320, 180), "#050A18")
draw = ImageDraw.Draw(banner)
for y in range(180):
    t = y / 179
    color = (int(5 + 12*t), int(10 + 20*t), int(24 + 38*t))
    draw.line((0, y, 320, y), fill=color)
mini = source.resize((128, 128), Image.Resampling.LANCZOS).convert("RGB")
banner.paste(mini, (18, 26))
font_candidates = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
]
font_path = next(Path(p) for p in font_candidates if Path(p).exists())
font_title = ImageFont.truetype(str(font_path), 27)
font_sub = ImageFont.truetype(str(font_path), 13)
draw = ImageDraw.Draw(banner)
draw.text((154, 58), "FotoFesta", font=font_title, fill="#F8FAFF")
draw.text((154, 91), "TV", font=font_title, fill="#FF795B")
draw.text((154, 126), "I tuoi ricordi", font=font_sub, fill="#B9C3D9")
banner.save(banner_dir / "tv_banner.png", optimize=True)

# Le immagini generate arrivano come PNG; JPEG riduce l'APK senza differenze visibili sul TV.
demo_dir = ROOT / "app/src/main/res/drawable-nodpi"
for name in ("demo_coast", "demo_picnic", "demo_mountains", "demo_birthday"):
    demo = Image.open(ROOT / f"design/{name}.png").convert("RGB")
    demo.save(demo_dir / f"{name}.jpg", format="JPEG", quality=90, optimize=True, progressive=True)
