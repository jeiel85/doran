#!/usr/bin/env bash
# 중계 서버(Cloudflare Worker + D1)를 처음 배포하거나 다시 배포합니다.
#
#   scripts/deploy-relay.sh
#
# 1. D1 데이터베이스 doran-relay가 없으면 만들고 wrangler.toml의 database_id를 채웁니다.
# 2. 원격 마이그레이션 → wrangler deploy → /v1/health 확인.
# FCM 비밀값은 scripts/finish-firebase.sh가 넣습니다(없어도 동작: 깨우기 없이 15분 주기 동기화).
set -euo pipefail
cd "$(dirname "$0")/../relay"
[ -d node_modules ] || npm install --silent

if grep -q '^database_id = "00000000' wrangler.toml; then
  ID=$(npx wrangler d1 list --json | python -c "import json,sys; print(next((d['uuid'] for d in json.load(sys.stdin) if d['name']=='doran-relay'), ''))")
  if [ -z "$ID" ]; then
    npx wrangler d1 create doran-relay --location apac >/dev/null
    ID=$(npx wrangler d1 list --json | python -c "import json,sys; print(next(d['uuid'] for d in json.load(sys.stdin) if d['name']=='doran-relay'))")
  fi
  sed -i "s/^database_id = \".*\"/database_id = \"$ID\"/" wrangler.toml
  echo "✓ D1 doran-relay ($ID) — wrangler.toml을 커밋해 주세요"
fi

npm test --silent >/dev/null && echo "✓ relay tests"
npx wrangler d1 migrations apply doran-relay --remote
npx wrangler deploy
curl -fsS https://doran-relay.jeiel85.workers.dev/v1/health && echo && echo "✓ relay is up"
