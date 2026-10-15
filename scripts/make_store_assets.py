"""Genera la grafica della scheda Google Play e App Store a partire da design/.

Uso: python3 scripts/make_store_assets.py
Scrive in fastlane/metadata/android/<lingua>/images/ (icona 512, feature graphic 1024x500,
banner TV 1280x720) e l'asset catalog Apple TV in apple/FotoFestaTV/Resources/Assets.xcassets
(icona a livelli per la parallasse, immagini Top Shelf).
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


def play_icon(size: int) -> Image.Image:
    """Icona Google Play: quadrata e opaca, perché Play applica da sé la maschera con gli angoli arrotondati.
    Gli angoli chiari del disegno sorgente vengono coperti con il blu dello sfondo."""
    background = Image.new("RGB", (size, size), (1, 10, 47))
    icon = rounded_icon(size)
    background.paste(icon, (0, 0), icon)
    return background


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


def write_json(path: Path, data: dict) -> None:
    import json
    path.mkdir(parents=True, exist_ok=True)
    (path / "Contents.json").write_text(json.dumps(data, indent=2) + "\n")


INFO = {"author": "xcode", "version": 1}


def icon_layers(size: tuple[int, int]) -> tuple[Image.Image, Image.Image]:
    """Livello di fondo (foto sfocata, opaco) e livello frontale (icona, trasparente) per la parallasse tvOS."""
    width, height = size
    back = cover(Image.open(DESIGN / "demo_coast.png").convert("RGB"), size).filter(ImageFilter.GaussianBlur(radius=max(2, width // 120)))
    back = Image.blend(back, Image.new("RGB", size, NAVY), 0.45)
    front = Image.new("RGBA", size, (0, 0, 0, 0))
    icon_size = int(height * 0.72)
    icon = rounded_icon(icon_size)
    front.paste(icon, ((width - icon_size) // 2, (height - icon_size) // 2), icon)
    return back, front


def image_stack(directory: Path, scales: dict[str, tuple[int, int]]) -> None:
    write_json(directory, {"info": INFO, "layers": [{"filename": "Front.imagestacklayer"}, {"filename": "Back.imagestacklayer"}]})
    for layer in ("Front", "Back"):
        layer_dir = directory / f"{layer}.imagestacklayer"
        write_json(layer_dir, {"info": INFO})
        images = []
        content = layer_dir / "Content.imageset"
        content.mkdir(parents=True, exist_ok=True)
        for scale, size in scales.items():
            back, front = icon_layers(size)
            name = f"{layer.lower()}@{scale}.png"
            (front if layer == "Front" else back).save(content / name, optimize=True)
            images.append({"idiom": "tv", "scale": scale, "filename": name})
        write_json(content, {"images": images, "info": INFO})


def image_set(directory: Path, scales: dict[str, tuple[int, int]], tagline: str) -> None:
    images = []
    directory.mkdir(parents=True, exist_ok=True)
    for scale, size in scales.items():
        name = f"topshelf@{scale}.png"
        hero(size, tagline).save(directory / name, optimize=True)
        images.append({"idiom": "tv", "scale": scale, "filename": name})
    write_json(directory, {"images": images, "info": INFO})


def make_tvos_asset_catalog() -> None:
    """Asset catalog Apple TV: icona a livelli (Home e App Store) e immagini Top Shelf."""
    catalog = ROOT / "apple/FotoFestaTV/Resources/Assets.xcassets"
    write_json(catalog, {"info": INFO})
    brand = catalog / "App Icon & Top Shelf Image.brandassets"
    write_json(brand, {
        "assets": [
            {"filename": "App Icon - App Store.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "1280x768"},
            {"filename": "App Icon.imagestack", "idiom": "tv", "role": "primary-app-icon", "size": "400x240"},
            {"filename": "Top Shelf Image Wide.imageset", "idiom": "tv", "role": "top-shelf-image-wide", "size": "2320x720"},
            {"filename": "Top Shelf Image.imageset", "idiom": "tv", "role": "top-shelf-image", "size": "1920x720"},
        ],
        "info": INFO,
    })
    image_stack(brand / "App Icon - App Store.imagestack", {"1x": (1280, 768)})
    image_stack(brand / "App Icon.imagestack", {"1x": (400, 240), "2x": (800, 480)})
    tagline = TAGLINES["en-US"]
    image_set(brand / "Top Shelf Image Wide.imageset", {"1x": (2320, 720), "2x": (4640, 1440)}, tagline)
    image_set(brand / "Top Shelf Image.imageset", {"1x": (1920, 720), "2x": (3840, 1440)}, tagline)
    write_json(catalog / "AccentColor.colorset", {
        "colors": [{"idiom": "universal", "color": {"color-space": "srgb", "components": {"red": "1.000", "green": "0.475", "blue": "0.357", "alpha": "1.000"}}}],
        "info": INFO,
    })


def main() -> None:
    for locale, tagline in TAGLINES.items():
        images = ROOT / "fastlane/metadata/android" / locale / "images"
        images.mkdir(parents=True, exist_ok=True)
        play_icon(512).save(images / "icon.png", optimize=True)
        hero((1024, 500), tagline).save(images / "featureGraphic.png", optimize=True)
        hero((1280, 720), tagline).save(images / "tvBanner.png", optimize=True)

    make_tvos_asset_catalog()
    print("Grafica store generata.")


if __name__ == "__main__":
    main()
