"""Codex가 만든 원본(design/art/source)에서 앱 리소스를 만듭니다.

    python design/make_art.py

- 런처 아이콘: 적응형 아이콘 전경(108dp, 66dp 안전 원 안), 단색(테마 아이콘) 레이어, README용 전체 아이콘
- 카테고리·자산 아이콘 32종: 4x4 시트 두 장을 칸마다 잘라 투명 여백을 맞춘 192px WebP
- 일러스트 4장: 가로 960px WebP
원본 출처와 프롬프트는 design/art/SOURCES.md.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "design/art/source"
RES = ROOT / "android/app/src/main/res"
DOCS = ROOT / "docs/assets"

SHEET_A = ["food", "cafe", "drinks", "household", "shopping", "beauty", "transit", "car",
           "housing", "telecom", "medical", "education", "childcare", "pets", "leisure", "travel"]
SHEET_B = ["gift", "insurance", "tax", "subscription", "allowance", "etc", "salary", "side",
           "interest", "income_etc", "transfer", "savings", "cash", "bank", "card", "invest"]
ILLUSTRATIONS = {"ill-hero": "art_hero", "ill-family-link": "art_family", "ill-empty": "art_empty", "ill-capture": "art_capture"}
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
PINE_START, PINE_END = (0x2E, 0x7A, 0x62), (0x1B, 0x4D, 0x3E)


def trim(img: Image.Image) -> Image.Image:
    """알파가 거의 0인 그림자 가장자리는 버리고 실제 그림만 남깁니다."""
    alpha = img.getchannel("A").point(lambda a: 255 if a > 12 else 0)
    box = alpha.getbbox()
    return img.crop(box) if box else img


def fit(img: Image.Image, size: int, fraction: float) -> Image.Image:
    motif = trim(img)
    scale = size * fraction / max(motif.size)
    motif = motif.resize((max(1, round(motif.width * scale)), max(1, round(motif.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(motif, ((size - motif.width) // 2, (size - motif.height) // 2))
    return canvas


def sheet_cells(path: Path, names: list[str]) -> dict[str, Image.Image]:
    sheet = Image.open(path).convert("RGBA")
    w, h = sheet.size
    out = {}
    for i, name in enumerate(names):
        r, c = divmod(i, 4)
        cell = sheet.crop((round(c * w / 4), round(r * h / 4), round((c + 1) * w / 4), round((r + 1) * h / 4)))
        out[name] = fit(cell, 192, 0.92)
    return out


def gradient(size: int) -> Image.Image:
    bg = Image.new("RGBA", (size, size))
    px = bg.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(PINE_START, PINE_END)) + (255,)
    return bg


def monochrome(img: Image.Image) -> Image.Image:
    white = Image.new("RGBA", img.size, (255, 255, 255, 0))
    white.putalpha(img.getchannel("A").point(lambda a: 255 if a > 40 else 0))
    return white


def main() -> None:
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    icons = {**sheet_cells(SRC / "categories-a.png", SHEET_A), **sheet_cells(SRC / "categories-b.png", SHEET_B)}
    for name, img in icons.items():
        img.save(nodpi / f"art_cat_{name}.webp", "WEBP", quality=88, method=6)

    for src, dst in ILLUSTRATIONS.items():
        img = trim(Image.open(SRC / f"{src}.png").convert("RGBA"))
        img.thumbnail((960, 960), Image.LANCZOS)
        img.save(nodpi / f"{dst}.webp", "WEBP", quality=86, method=6)

    icon = Image.open(SRC / "icon-bubble.png").convert("RGBA")
    for density, px in DENSITIES.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        fg = fit(icon, px, 0.58)  # 0.58 x 108dp = 63dp, 66dp 안전 원 안
        fg.save(d / "ic_launcher_foreground.png", optimize=True)
        monochrome(fg).save(d / "ic_launcher_monochrome.png", optimize=True)

    full = gradient(1024)
    full.alpha_composite(fit(icon, 1024, 0.66))
    DOCS.mkdir(parents=True, exist_ok=True)
    full.save(ROOT / "design/art/icon-full.png", optimize=True)
    for px in (192, 512):
        full.resize((px, px), Image.LANCZOS).save(DOCS / f"icon-{px}.png", optimize=True)
    print(f"categories {len(icons)}, illustrations {len(ILLUSTRATIONS)}, launcher {len(DENSITIES)} densities")


if __name__ == "__main__":
    main()
