#!/usr/bin/env bash
# Google Play용 AAB를 만듭니다(play 채널: 자가 업데이트·문자 권한 없음, Play 업로드 키 — DECISIONS.md D-16).
#
#   scripts/play-bundle.sh 0.1.2 [Gradle 인자…]
#
# 결과: dist/doran-play-<버전>-<커밋>.aab — Play Console에 올립니다(처음엔 내부 테스트).
# 두 세션이 같은 버전을 따로 빌드해도 어느 커밋 빌드인지 헷갈리지 않게, 파일 이름에 커밋을 넣고 같은 이름이 있으면 멈춥니다.
# 빌드마다 dist/play-builds.log에 시각·버전·커밋·SHA-256을 한 줄 남깁니다.
# 버전 뒤의 인자는 Gradle에 그대로 넘깁니다. 메모리가 빠듯한 날(README "개발"):
#   scripts/play-bundle.sh 0.3.7 --no-daemon -Dorg.gradle.jvmargs="-Xmx2400m -XX:+UseSerialGC -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process
set -euo pipefail
cd "$(dirname "$0")/.."
VER="${1:?usage: scripts/play-bundle.sh <version> [gradle args…]}"
shift

# 필수(required="false"가 아닌) uses-feature로 허용하는 것. 비어 있으면 필수 기능이 하나도 없어야 합니다.
# 화면 고정 속성·라이브러리 매니페스트가 필수 기능을 늘리면 그 기능이 없는 기기가 Play에서 빠지는데, 올린 뒤에야 알았어요
# (0.1.0~0.1.14의 screen.portrait — #24, #25). 정말 필요한 기능이면 여기에 이름을 넣습니다.
ALLOWED_REQUIRED_FEATURES=""
# 브라우저로 Play Console에 올릴 때의 파일 한도(10MB)에 가까우면 알립니다. 넘으면 손으로 올리거나 의존성을 줄여야 해요(#45).
AAB_WARN_BYTES=$((9500 * 1024))

[ -f android/app/google-services.json ] || { echo "✗ android/app/google-services.json 없음 (scripts/finish-firebase.sh)"; exit 1; }
[ -f android/play-upload-signing.properties ] || [ -n "${DORAN_PLAY_UPLOAD_STORE_FILE:-}" ] || { echo "✗ Play 업로드 키 설정 없음"; exit 1; }

COMMIT="$(git rev-parse --short=7 HEAD)"
# 커밋하지 않은 추적 파일 변경이 있으면 그 커밋의 빌드가 아니므로 표시합니다.
git diff --quiet HEAD -- || { COMMIT="$COMMIT-dirty"; echo "! 커밋하지 않은 변경이 있어요 — 파일 이름에 -dirty를 붙입니다"; }
mkdir -p dist/logs
OUT="dist/doran-play-$VER-$COMMIT.aab"
[ ! -e "$OUT" ] || { echo "✗ $OUT 이(가) 이미 있어요. 같은 커밋을 다시 빌드하려면 먼저 지우세요"; exit 1; }

# Gradle 출력은 로그 파일에 남깁니다: 첫 실행이 Kotlin 컴파일에서 실패하고 다시 하면 통과하던 일이 있었는데(#40),
# 원인을 남기지 않아 메모리 경합인지 알 수 없었어요.
LOG="dist/logs/play-bundle-$VER-$COMMIT-$(date '+%Y%m%d-%H%M%S').log"
if ! (cd android && ./gradlew.bat --console=plain -Papp.version="$VER" "$@" testPlayDebugUnitTest bundlePlayRelease) >"$LOG" 2>&1; then
  echo "✗ Gradle 빌드 실패 — 전체 로그: $LOG"
  grep -n -m 20 -E "^e: |error:|What went wrong|OutOfMemory|Could not reserve|daemon .*(disappeared|stopped)|FAILED" "$LOG" || tail -n 30 "$LOG"
  echo "  메모리 부족으로 보이면 README \"개발\"의 저메모리 옵션을 Gradle 인자로 넘겨 다시 하세요"
  exit 1
fi

MANIFEST="android/app/build/intermediates/bundle_manifest/playRelease/processApplicationManifestPlayReleaseForBundle/AndroidManifest.xml"
[ -f "$MANIFEST" ] || { echo "✗ 번들 매니페스트를 찾지 못했어요: $MANIFEST (Android Gradle 플러그인이 경로를 바꿨으면 고치세요)"; exit 1; }
PYTHONIOENCODING=utf-8 python - "$MANIFEST" "$ALLOWED_REQUIRED_FEATURES" <<'PY' || { echo "✗ 허용 목록 밖의 필수 기능이 있어요 — 그 기능이 없는 기기가 Play에서 빠집니다. 매니페스트에 required=\"false\"로 선언하거나 ALLOWED_REQUIRED_FEATURES에 넣으세요"; exit 1; }
import sys, xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
allowed = set(sys.argv[2].split())
bad = []
for f in ET.parse(sys.argv[1]).getroot().iter("uses-feature"):
    if f.get(A + "required") == "false":
        continue
    name = f.get(A + "name") or "glEsVersion " + (f.get(A + "glEsVersion") or "?")
    ok = name in allowed
    print(f"  필수 기능 {name}{'' if ok else '  ← 허용 목록 밖'}")
    if not ok:
        bad.append(name)
sys.exit(1 if bad else 0)
PY

cp android/app/build/outputs/bundle/playRelease/app-play-release.aab "$OUT"
SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
SIZE="$(wc -c <"$OUT" | tr -d ' ')"
printf '%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$VER" "$(git rev-parse HEAD)" "$SHA" "$OUT" >> dist/play-builds.log
echo "✓ $OUT"
echo "  commit $(git rev-parse HEAD)"
echo "  sha256 $SHA"
echo "  size   $(awk -v b="$SIZE" 'BEGIN { printf "%.2fMB", b / 1048576 }')"
[ "$SIZE" -le "$AAB_WARN_BYTES" ] || echo "! AAB가 9.5MB를 넘어요 — 브라우저 업로드 한도(10MB)에 가까워요. 넘으면 Play Console에 손으로 올려야 합니다"
keytool -printcert -jarfile "$OUT" | grep -m1 SHA256 | sed 's/^\s*/  signer /'
