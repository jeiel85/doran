#!/usr/bin/env bash
# Google Play용 AAB를 만듭니다(play 채널: 자가 업데이트·문자 권한 없음, Play 업로드 키 — DECISIONS.md D-16).
#
#   scripts/play-bundle.sh 0.1.2
#
# 결과: dist/doran-play-<버전>-<커밋>.aab — Play Console에 올립니다(처음엔 내부 테스트).
# 두 세션이 같은 버전을 따로 빌드해도 어느 커밋 빌드인지 헷갈리지 않게, 파일 이름에 커밋을 넣고 같은 이름이 있으면 멈춥니다.
# 빌드마다 dist/play-builds.log에 시각·버전·커밋·SHA-256을 한 줄 남깁니다.
set -euo pipefail
cd "$(dirname "$0")/.."
VER="${1:?usage: scripts/play-bundle.sh <version>}"

[ -f android/app/google-services.json ] || { echo "✗ android/app/google-services.json 없음 (scripts/finish-firebase.sh)"; exit 1; }
[ -f android/play-upload-signing.properties ] || [ -n "${DORAN_PLAY_UPLOAD_STORE_FILE:-}" ] || { echo "✗ Play 업로드 키 설정 없음"; exit 1; }

COMMIT="$(git rev-parse --short=7 HEAD)"
# 커밋하지 않은 추적 파일 변경이 있으면 그 커밋의 빌드가 아니므로 표시합니다.
git diff --quiet HEAD -- || { COMMIT="$COMMIT-dirty"; echo "! 커밋하지 않은 변경이 있어요 — 파일 이름에 -dirty를 붙입니다"; }
mkdir -p dist
OUT="dist/doran-play-$VER-$COMMIT.aab"
[ ! -e "$OUT" ] || { echo "✗ $OUT 이(가) 이미 있어요. 같은 커밋을 다시 빌드하려면 먼저 지우세요"; exit 1; }

(cd android && ./gradlew.bat --console=plain -q -Papp.version="$VER" testPlayDebugUnitTest bundlePlayRelease)
cp android/app/build/outputs/bundle/playRelease/app-play-release.aab "$OUT"
SHA="$(sha256sum "$OUT" | cut -d' ' -f1)"
printf '%s\t%s\t%s\t%s\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$VER" "$(git rev-parse HEAD)" "$SHA" "$OUT" >> dist/play-builds.log
echo "✓ $OUT"
echo "  commit $(git rev-parse HEAD)"
echo "  sha256 $SHA"
keytool -printcert -jarfile "$OUT" | grep -m1 SHA256 | sed 's/^\s*/  signer /'
