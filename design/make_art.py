"""Codex가 만든 원본(design/art/source)에서 앱 리소스를 만듭니다.

    python design/make_art.py          # 전부
    python design/make_art.py icon     # 런처 아이콘만 (이름·아이콘을 바꿀 때)

- 런처 아이콘: 적응형 아이콘 전경(108dp, 66dp 안전 원 안), README·웹용 전체 아이콘
  (단색 테마 아이콘·알림 아이콘은 손으로 그린 벡터: res/drawable/ic_launcher_monochrome.xml, ic_stat_doran.xml)
- 카테고리·자산 아이콘 32종: 4x4 시트 두 장을 칸마다 잘라 투명 여백을 맞춘 192px WebP
- 일러스트 4장: 가로 960px WebP
원본 출처와 프롬프트는 design/art/SOURCES.md.
"""
import io
import sys
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


def webp(img: Image.Image, path: Path, quality: int) -> tuple[Path, bytes]:
    """WebP로 인코딩만 합니다(파일은 build_art 끝에 한꺼번에 씁니다)."""
    buf = io.BytesIO()
    try:
        img.save(buf, "WEBP", quality=quality, method=6)
    except (OSError, ValueError) as e:
        # Pillow의 "encoding error N"은 libwebp 오류 번호이고, 1은 메모리 부족(VP8_ENC_ERROR_OUT_OF_MEMORY)입니다.
        # 이 PC의 커밋 여유가 바닥난 날 docs/assets/art에서 멈춘 적이 있어요(#29).
        raise SystemExit(f"WebP 인코딩 실패({path.name}): {e}. 'encoding error 1'은 메모리 부족이에요 — "
                         "메모리를 비우고 다시 돌리세요. 아직 아무 파일도 바꾸지 않았어요")
    return path, buf.getvalue()


def build_art() -> None:
    """카테고리·자산 아이콘과 일러스트(이름과 상관없는 그림).
    전부 인코딩한 뒤에 씁니다: 예전에는 도중에 실패하면 그 전 파일만 다시 인코딩돼 바이트가 바뀐 채 남았어요(#29)."""
    nodpi = RES / "drawable-nodpi"
    web_art = DOCS / "art"
    out: list[tuple[Path, bytes]] = []
    icons = {**sheet_cells(SRC / "categories-a.png", SHEET_A), **sheet_cells(SRC / "categories-b.png", SHEET_B)}
    for name, img in icons.items():
        out.append(webp(img, nodpi / f"art_cat_{name}.webp", 88))

    for src, dst in ILLUSTRATIONS.items():
        img = trim(Image.open(SRC / f"{src}.png").convert("RGBA"))
        img.thumbnail((960, 960), Image.LANCZOS)
        out.append(webp(img, nodpi / f"{dst}.webp", 86))
        out.append(webp(img, web_art / f"{dst.removeprefix('art_')}.webp", 84))  # 소개 페이지용
    for name in ("card", "savings", "bank"):
        out.append(webp(icons[name].resize((128, 128), Image.LANCZOS), web_art / f"cat-{name}.webp", 88))

    for path, data in out:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    print(f"categories {len(icons)}, illustrations {len(ILLUSTRATIONS)}")


def build_icon() -> None:
    """런처 아이콘 전경(밀도별)과 README·웹용 전체 아이콘."""
    icon = Image.open(SRC / "icon-nest.png").convert("RGBA")  # 둥지 A안 (DECISIONS.md D-24)
    for density, px in DENSITIES.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        fg = fit(icon, px, 0.58)  # 0.58 x 108dp = 63dp, 66dp 안전 원 안
        fg.save(d / "ic_launcher_foreground.png", optimize=True)

    full = gradient(1024)
    full.alpha_composite(fit(icon, 1024, 0.66))
    DOCS.mkdir(parents=True, exist_ok=True)
    full.save(ROOT / "design/art/icon-full.png", optimize=True)
    for px in (192, 512):
        full.resize((px, px), Image.LANCZOS).save(DOCS / f"icon-{px}.png", optimize=True)
    print(f"launcher {len(DENSITIES)} densities")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    if what in ("all", "art"):
        build_art()
    if what in ("all", "icon"):
        build_icon()
