"""Google Play 스토어 등록 그림을 만듭니다(에뮬레이터 캡처 + Codex 그림에서).

    python design/make_play.py

- icon-512.png: 앱 아이콘 512x512 (32비트 PNG, 런처와 같은 그림)
- feature-1024x500.png: 그래픽 이미지 1024x500 (24비트, 투명 없음)
- github-social-1280x640.png: GitHub 저장소 Social preview(링크 미리보기) 1280x640 — 저장소 Settings에서 손으로 올림
- screenshots/0N-*.png: 휴대전화 스크린샷 1080x1920(9:16). Play는 긴 변이 짧은 변의 2배 이하여야 해서
  1080x2400 캡처를 그대로 쓰지 못하고, 설명 문구와 함께 틀에 담습니다.
원본 캡처: design/play/source (예시 장부 — scripts/fake-peer.mjs demo, 로컬 중계에서 만든 가상의 가족 데이터).
첫 장의 알림(fcm1.png, 2026-11-17)은 옛 이름 '도란' 때 찍은 실제 알림이라, 아이콘 원만 2026-11-20 '둥지' 앱이 실제로 띄운 알림에서
잘라 둔 notify-icon.png로 바꿔 붙입니다(같은 기기 설정·같은 자리·같은 크기). 가족 알림을 다시 찍으면 이 단계를 지우세요.
"""
import hashlib
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "design/play/source"
OUT = ROOT / "design/play"
FONT_B = "C:/Windows/Fonts/malgunbd.ttf"
FONT_R = "C:/Windows/Fonts/malgun.ttf"
OAT, INK, SOFT = (246, 242, 234), (30, 28, 39), (111, 106, 121)
PINE, PINE_DEEP, APRICOT = (46, 122, 98), (27, 77, 62), (255, 159, 90)


def font(path: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(path, size)


def gradient(w: int, h: int, a, b) -> Image.Image:
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w * 0.4 + y / h * 0.6)
            px[x, y] = tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return img


def rounded(img: Image.Image, r: int) -> Image.Image:
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, img.width - 1, img.height - 1), radius=r, fill=255)
    out = img.convert("RGBA")
    out.putalpha(mask)
    return out


def phone(shot: Image.Image, height: int) -> Image.Image:
    """캡처를 둥근 모서리 + 부드러운 그림자로."""
    w = round(shot.width * height / shot.height)
    s = rounded(shot.resize((w, height), Image.LANCZOS), 44)
    pad = 40
    canvas = Image.new("RGBA", (w + pad * 2, height + pad * 2), (0, 0, 0, 0))
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((pad, pad + 14, pad + w, pad + height + 14), radius=48, fill=(60, 40, 20, 70))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(18)))
    canvas.alpha_composite(s, (pad, pad))
    return canvas


def screenshot(name: str, title: str, sub: str, src: str, overlay: Image.Image | None = None, dark_bg: bool = False):
    W, H = 1080, 1920
    bg = gradient(W, H, (36, 110, 88), (24, 70, 57)) if dark_bg else gradient(W, H, (250, 246, 238), (238, 230, 216))
    img = bg.convert("RGBA")
    d = ImageDraw.Draw(img)
    tc, sc = ((255, 255, 255), (220, 236, 228)) if dark_bg else (INK, SOFT)
    d.text((W // 2, 120), title, font=font(FONT_B, 70), fill=tc, anchor="mm")
    d.text((W // 2, 205), sub, font=font(FONT_R, 38), fill=sc, anchor="mm")
    shot = Image.open(SRC / f"{src}.png").convert("RGB")
    p = phone(shot, 1560)
    img.alpha_composite(p, ((W - p.width) // 2, 270))
    if overlay is not None:
        img.alpha_composite(overlay, ((W - overlay.width) // 2, 560))
    (OUT / "screenshots").mkdir(parents=True, exist_ok=True)
    img.convert("RGB").save(OUT / "screenshots" / f"{name}.png", optimize=True)


# 아이콘 덧붙이기는 옛 '도란' 캡처(fcm1.png, 2026-11-17 1f16c78)에만 맞습니다. 가족 알림을 새로 찍어 fcm1.png를 바꾸면
# 새 캡처 위 옛 자리에 아이콘을 덮게 되니 멈춥니다(#29) — 그때는 아래 아이콘 붙이기(icon·ring·paste)와 notify-icon.png를 지우세요.
OLD_FCM1_SHA256 = "0f1c878353bcfd2afd481cb8a8f3ac9ea85dc178f71e4e7e588a61c7d565e05d"


def notification_card() -> Image.Image:
    """실제 알림(아내 폰, 앱은 꺼진 상태)을 잘라 크게 띄웁니다."""
    if hashlib.sha256((SRC / "fcm1.png").read_bytes()).hexdigest() != OLD_FCM1_SHA256:
        raise SystemExit("fcm1.png가 옛 '도란' 캡처가 아니에요. 새로 찍은 알림이면 notification_card()의 아이콘 붙이기와 "
                         "notify-icon.png를 지우고 OLD_FCM1_SHA256 검사도 지우세요(make_play.py 맨 위 설명)")
    shade = Image.open(SRC / "fcm1.png").convert("RGB")
    icon = Image.open(SRC / "notify-icon.png").convert("RGB")  # 67x67, 원(63px) 둘레 2px 포함
    ring = Image.new("L", (icon.width * 4, icon.height * 4), 0)
    ImageDraw.Draw(ring).ellipse((4, 4, icon.width * 4 - 5, icon.height * 4 - 5), fill=255)
    shade.paste(icon, (82, 1101), ring.resize(icon.size, Image.LANCZOS))
    card = shade.crop((42, 1025, 1038, 1243))
    card = card.resize((round(card.width * 0.98), round(card.height * 0.98)), Image.LANCZOS)
    c = rounded(card, 46)
    pad = 36
    canvas = Image.new("RGBA", (c.width + pad * 2, c.height + pad * 2), (0, 0, 0, 0))
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad + 16, pad + c.width, pad + c.height + 16), radius=48, fill=(0, 0, 0, 110))
    canvas.alpha_composite(sh.filter(ImageFilter.GaussianBlur(20)))
    canvas.alpha_composite(c, (pad, pad))
    return canvas


def feature_graphic():
    W, H = 1024, 500
    img = gradient(W, H, (46, 122, 98), (24, 70, 57)).convert("RGBA")
    hero = Image.open(ROOT / "design/art/source/ill-hero.png").convert("RGBA")
    hero = hero.crop(hero.getchannel("A").getbbox())
    hero.thumbnail((470, 430), Image.LANCZOS)
    img.alpha_composite(hero, (W - hero.width - 34, (H - hero.height) // 2 + 10))
    icon = rounded(Image.open(ROOT / "design/art/icon-full.png").convert("RGB").resize((112, 112), Image.LANCZOS), 26)
    img.alpha_composite(icon, (60, 92))
    d = ImageDraw.Draw(img)
    d.text((190, 148), "둥지", font=font(FONT_B, 64), fill=(255, 255, 255), anchor="lm")
    d.text((60, 270), "카드 알림이 오면", font=font(FONT_B, 46), fill=(255, 255, 255))
    d.text((60, 330), "바로 장부에, 가족 폰에", font=font(FONT_B, 46), fill=(255, 211, 107))
    d.text((60, 410), "부부가 함께 쓰는 가족 가계부", font=font(FONT_R, 28), fill=(220, 236, 228))
    img.convert("RGB").save(OUT / "feature-1024x500.png", optimize=True)


def social_preview():
    """GitHub 저장소 링크 미리보기(Social preview). 그래픽 이미지와 같은 구성을 2:1로, 가장자리 여백을 넉넉히."""
    W, H = 1280, 640
    img = gradient(W, H, (46, 122, 98), (24, 70, 57)).convert("RGBA")
    hero = Image.open(ROOT / "design/art/source/ill-hero.png").convert("RGBA")
    hero = hero.crop(hero.getchannel("A").getbbox())
    hero.thumbnail((580, 520), Image.LANCZOS)
    img.alpha_composite(hero, (W - hero.width - 60, (H - hero.height) // 2 + 12))
    icon = rounded(Image.open(ROOT / "design/art/icon-full.png").convert("RGB").resize((136, 136), Image.LANCZOS), 32)
    img.alpha_composite(icon, (84, 116))
    d = ImageDraw.Draw(img)
    d.text((248, 184), "둥지", font=font(FONT_B, 80), fill=(255, 255, 255), anchor="lm")
    d.text((84, 330), "카드 알림이 오면", font=font(FONT_B, 56), fill=(255, 255, 255))
    d.text((84, 404), "바로 장부에, 가족 폰에", font=font(FONT_B, 56), fill=(255, 211, 107))
    d.text((84, 500), "부부가 함께 쓰는 가족 가계부 · Android", font=font(FONT_R, 32), fill=(220, 236, 228))
    img.convert("RGB").save(OUT / "github-social-1280x640.png", optimize=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    Image.open(ROOT / "docs/assets/icon-512.png").convert("RGBA").resize((512, 512), Image.LANCZOS).save(OUT / "icon-512.png", optimize=True)
    feature_graphic()
    social_preview()
    screenshot("01-notify", "카드 쓰면 가족 폰에 바로", "\"남편이 현대카드로 12,300원 썼어요\" · 누적까지", "p_home", notification_card(), dark_bg=True)
    screenshot("02-family", "부부가 한 장부를 함께", "가족별 지출 · 카드 결제 예정 · 예산", "p_home2")
    screenshot("03-calendar", "달력으로 한눈에", "날짜별 지출과 수입, 사람별로 골라 보기", "p_cal")
    screenshot("04-stats", "어디에 썼는지 통계로", "카테고리 · 6개월 흐름 · 많이 쓴 곳", "p_stats")
    screenshot("05-assets", "우리집 자산과 카드", "순자산 · 계좌 잔액 · 갚을 카드값", "p_assets")
    screenshot("06-invite", "QR 한 번으로 가족 연결", "부모님 카드 알림도 사람별로 합산", "p_invite", dark_bg=True)
    screenshot("07-entry", "빠르고 예쁜 입력", "그림 카테고리와 금액 자판", "p_entry")
    print("ok:", sorted(p.name for p in (OUT / "screenshots").iterdir()))


if __name__ == "__main__":
    main()
