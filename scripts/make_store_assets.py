"""Genera la grafica della scheda Google Play e App Store a partire da design/.

Uso: python3 scripts/make_store_assets.py
Scrive in fastlane/metadata/android/<lingua>/images/ (icona 512, feature graphic 1024x500,
banner TV 1280x720) e in apple/FotoFestaTV/Assets (icone e top shelf per Apple TV).
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DESIGN = ROOT / "design"
NAVY = (5, 10, 24)
CORAL = (255, 121, 91)
WHITE = (248, 250, 255)
GREY = (185, 195, 217)

TAGLINES = {
    "it-IT": "I tuoi ricordi sul grande schermo",
    "en-US": "Your memories on the big screen",
}


def font(size: int) -> ImageFont.FreeTypeFont:
    for candidate in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    ):
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    raise SystemExit("Nessun font TrueType trovato")


def cover(image: Image.Image, size: tuple[int, int]) -> Image.Image:
    """Ridimensiona riempiendo l'area e ritagliando al centro."""
    scale = max(size[0] / image.width, size[1] / image.height)
    resized = image.resize((round(image.width * scale), round(image.height * scale)), Image.Resampling.LANCZOS)
    left = (resized.width - size[0]) // 2
    top = (resized.height - size[1]) // 2
    return resized.crop((left, top, left + size[0], top + size[1]))


def rounded_icon(size: int) -> Image.Image:
    source = Image.open(DESIGN / "app_icon_source.png").convert("RGBA").resize((size, size), Image.Resampling.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size - 1, size - 1), radius=size // 5, fill=255)
    source.putalpha(mask)
    return source


def hero(size: tuple[int, int], tagline: str) -> Image.Image:
    """Foto demo sfocata a destra, sfumatura scura a sinistra con icona, nome e slogan."""
    width, height = size
    canvas = cover(Image.open(DESIGN / "demo_coast.png").convert("RGB"), size)
    canvas = canvas.filter(ImageFilter.GaussianBlur(radius=max(2, width // 400)))
    shade = Image.new("L", size)
    shade_draw = ImageDraw.Draw(shade)
    for x in range(width):
        alpha = int(255 * max(0.0, min(1.0, 1.15 - x / (width * 0.75))))
        shade_draw.line((x, 0, x, height), fill=alpha)
    canvas.paste(Image.new("RGB", size, NAVY), (0, 0), shade)
    icon_size = int(height * 0.42)
    icon = rounded_icon(icon_size)
    margin = int(height * 0.12)
    top = (height - icon_size) // 2
    canvas.paste(icon, (margin, top), icon)
    draw = ImageDraw.Draw(canvas)
    text_x = margin + icon_size + int(height * 0.08)
    title_font = font(int(height * 0.15))
    draw.text((text_x, top + int(icon_size * 0.05)), "FotoFesta", font=title_font, fill=WHITE)
    draw.text((text_x, top + int(icon_size * 0.42)), "TV", font=title_font, fill=CORAL)
    draw.text((text_x, top + int(icon_size * 0.85)), tagline, font=font(int(height * 0.055)), fill=GREY)
    return canvas


def main() -> None:
    for locale, tagline in TAGLINES.items():
        images = ROOT / "fastlane/metadata/android" / locale / "images"
        images.mkdir(parents=True, exist_ok=True)
        rounded_icon(512).save(images / "icon.png", optimize=True)
        hero((1024, 500), tagline).save(images / "featureGraphic.png", optimize=True)
        hero((1280, 720), tagline).save(images / "tvBanner.png", optimize=True)

    apple = ROOT / "apple/FotoFestaTV/Assets"
    apple.mkdir(parents=True, exist_ok=True)
    # tvOS: icona App Store 1280x768 e immagini Top Shelf (senza trasparenza).
    hero((1280, 768), TAGLINES["en-US"]).save(apple / "AppStoreIcon-1280x768.png", optimize=True)
    hero((1920, 720), TAGLINES["en-US"]).save(apple / "TopShelf-1920x720.png", optimize=True)
    hero((2320, 720), TAGLINES["en-US"]).save(apple / "TopShelfWide-2320x720.png", optimize=True)
    print("Grafica store generata.")


if __name__ == "__main__":
    main()
