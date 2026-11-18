#!/usr/bin/env bash
# FCM 서비스 계정 키를 교체합니다(design/FIREBASE_SETUP.md "키 교체").
#
#   scripts/rotate-fcm-key.sh ~/Downloads/doran-e8c18-xxxxxxxx.json
#
# 새 키 확인(같은 Firebase 프로젝트인지) → 중계 비밀값 교체 → 이 PC 보관본 교체(옛 것은 .old로 남김)
# → 옛 키를 Google Cloud 콘솔에서 지우라는 안내. 어떤 키 파일도 자동으로 지우지 않습니다.
set -euo pipefail
cd "$(dirname "$0")/.."
NEW="${1:?usage: scripts/rotate-fcm-key.sh <new-service-account.json>}"
[ -f "$NEW" ] || { echo "✗ $NEW 없음"; exit 1; }

python - "$NEW" "android/app/google-services.json" <<'EOF'
import json, os, sys
key = json.load(open(sys.argv[1], encoding="utf-8"))
assert key.get("type") == "service_account", "서비스 계정 키(JSON)가 아니에요"
gs_path = sys.argv[2]
if os.path.exists(gs_path):
    gp = json.load(open(gs_path, encoding="utf-8"))["project_info"]["project_id"]
    assert key.get("project_id") == gp, f"다른 프로젝트의 키예요: 키 '{key.get('project_id')}', 앱 '{gp}'"
print(f"✓ 새 키 {key['private_key_id'][:8]}… ({key['client_email']})")
EOF

KEEP="${LOCALAPPDATA:-$HOME/.local/share}/doran"
mkdir -p "$KEEP"
OLD_ID=""
if [ -f "$KEEP/service-account.json" ]; then
  OLD_ID=$(python -c "import json,sys;print(json.load(open(sys.argv[1],encoding='utf-8'))['private_key_id'][:8])" "$KEEP/service-account.json")
  cp "$KEEP/service-account.json" "$KEEP/service-account.old.json"
fi

(cd relay && npx wrangler secret put FCM_SERVICE_ACCOUNT < "$NEW" >/dev/null)
echo "✓ 중계 비밀값 FCM_SERVICE_ACCOUNT 교체"

cp "$NEW" "$KEEP/service-account.json"
if command -v icacls >/dev/null 2>&1; then
  MSYS_NO_PATHCONV=1 icacls "$(cygpath -w "$KEEP/service-account.json")" /inheritance:r /grant:r "${USERNAME}:F" >/dev/null
fi
echo "✓ 이 PC 보관본 교체: $KEEP/service-account.json (현재 사용자만)"
echo
echo "남은 일(직접):"
echo "  1. 가족 폰에서 결제 알림이 몇 초 안에 오는지 확인 (실시간 알림)"
echo "  2. Google Cloud 콘솔 → IAM 및 관리자 → 서비스 계정 → 키에서 옛 키 ${OLD_ID:-(없음)}… 삭제"
echo "  3. $KEEP/service-account.old.json 과 내려받은 새 키 원본($NEW)을 휴지통으로"
