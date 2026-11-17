#!/usr/bin/env bash
# Google Play용 AAB를 만듭니다(play 채널: 자가 업데이트·문자 권한 없음, Play 업로드 키 — DECISIONS.md D-16).
#
#   scripts/play-bundle.sh 0.1.2
#
# 결과: dist/doran-play-<버전>.aab — Play Console에 올립니다(처음엔 내부 테스트).
set -euo pipefail
cd "$(dirname "$0")/.."
VER="${1:?usage: scripts/play-bundle.sh <version>}"

[ -f android/app/google-services.json ] || { echo "✗ android/app/google-services.json 없음 (scripts/finish-firebase.sh)"; exit 1; }
[ -f android/play-upload-signing.properties ] || [ -n "${DORAN_PLAY_UPLOAD_STORE_FILE:-}" ] || { echo "✗ Play 업로드 키 설정 없음"; exit 1; }

(cd android && ./gradlew.bat --console=plain -q -Papp.version="$VER" testPlayDebugUnitTest bundlePlayRelease)
mkdir -p dist
OUT="dist/doran-play-$VER.aab"
cp android/app/build/outputs/bundle/playRelease/app-play-release.aab "$OUT"
echo "✓ $OUT"
keytool -printcert -jarfile "$OUT" | grep -m1 SHA256 | sed 's/^\s*/  signer /'
