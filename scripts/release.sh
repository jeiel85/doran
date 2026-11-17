#!/usr/bin/env bash
# GitHub 채널 릴리스를 냅니다(포너디 scripts/release.sh와 같은 흐름). Play는 scripts/play-bundle.sh.
#
#   scripts/release.sh 0.1.0
#
# 그 버전으로 빌드 → 태그와 GitHub 릴리스(APK + SHA-256) → 연결된 폰 모두에 같은 APK 설치(로컬 배포)
# → design/hardening-<버전>.md가 있으면 "Hardening candidates" 이슈 등록.
# 폰의 도란은 하루 두 번 releases/latest를 보고, 서명과 해시가 맞으면 스스로 업데이트합니다(DECISIONS.md D-8).
# 자산 이름은 반드시 doran.apk — 앱 업데이터와 소개·연결 페이지의 다운로드 링크가 이 이름을 씁니다.
set -euo pipefail
cd "$(dirname "$0")/.."
VER="${1:?usage: scripts/release.sh <version>}"
TAG="v$VER"

[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "✗ version must be x.y.z"; exit 1; }
[ -f android/release-signing.properties ] || { echo "✗ android/release-signing.properties 없음 (릴리스 키)"; exit 1; }
grep -q '^database_id = "00000000' relay/wrangler.toml && { echo "✗ 중계 서버가 아직 배포되지 않았어요 (scripts/deploy-relay.sh)"; exit 1; }
[ -f android/app/google-services.json ] || echo "! google-services.json 없음: 실시간 알림 없이 15분 주기 동기화로 나갑니다"
[ -z "$(git status --porcelain)" ] || { echo "✗ 작업 트리가 깨끗하지 않아요"; exit 1; }
git fetch -q origin && [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "✗ main이 origin/main과 다릅니다"; exit 1; }
grep -q "^## \[$VER\]\|^## v$VER" CHANGELOG.md || { echo "✗ CHANGELOG.md에 $VER 항목이 없어요"; exit 1; }

(cd relay && npm test --silent >/dev/null) && echo "✓ relay tests"
(cd android && ./gradlew.bat --console=plain -q -Papp.version="$VER" testGithubDebugUnitTest testPlayDebugUnitTest assembleGithubRelease)
mkdir -p dist
cp android/app/build/outputs/apk/github/release/app-github-release.apk dist/doran.apk
SHA=$(sha256sum dist/doran.apk | cut -d' ' -f1)

NOTES=$(mktemp)
awk -v a="## [$VER]" -v b="## v$VER" 'index($0, a)==1 || index($0, b)==1 {on=1; next} /^## / {on=0} on' CHANGELOG.md > "$NOTES"
printf '\n---\n**APK SHA-256** `%s`\n\n설치·가족 연결 안내: https://jeiel85.github.io/doran/\n' "$SHA" >> "$NOTES"
gh release create "$TAG" dist/doran.apk --title "도란 $TAG" --notes-file "$NOTES" --target main
rm -f "$NOTES"
echo "✓ released $TAG (sha256 $SHA)"

# 로컬 배포: 이 집의 실제 폰들도 방금 낸 빌드로(에뮬레이터는 다른 프로젝트 것일 수 있어 건너뜀).
for d in $(adb devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1}'); do
  adb -s "$d" install -r dist/doran.apk >/dev/null && echo "✓ installed on $d"
done

if [ -f "design/hardening-$VER.md" ]; then
  gh issue create --title "Hardening candidates: $TAG" --body-file "design/hardening-$VER.md" >/dev/null && echo "✓ hardening issue opened"
fi
