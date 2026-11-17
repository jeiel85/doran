0.1.1(실시간 알림)을 내며 드러난 개선 후보입니다(전역 지침: 릴리스 때 이슈로 등록하고 그대로 릴리스).

- [ ] 서비스 계정 키 교체 절차 · FCM 키가 `%LOCALAPPDATA%\doran\`과 중계 비밀값 두 곳에만 있음 · 키 교체(새 키 → secret put → 옛 키 삭제) 스크립트와 문서 · `scripts/finish-firebase.sh`, design/FIREBASE_SETUP.md
- [ ] 깨우기 실패 관측 · 중계는 FCM 실패를 로그로만 남김 · 실패 횟수를 응답(`woke`)과 함께 가구 정보에 집계해 가족 화면에 "실시간 알림 안 되는 기기" 표시 · `relay/src/index.js` wakeMembers
- [ ] Play 서비스 없는 폰 · FCM 토큰이 없으면 15분 주기로만 동기화(설정에 "실시간 알림 꺼짐"만 표시) · 이유 안내(Play 서비스 필요)와 동기화 주기 설명 · `SettingsScreen`, `FcmService`
- [ ] 다운로드 폴더의 키 사본 · finish-firebase.sh가 키를 안전한 곳에 복사한 뒤에도 다운로드 폴더 원본이 남음 · 복사 뒤 원본을 휴지통으로 옮기라고 안내(자동 삭제는 하지 않음) · `scripts/finish-firebase.sh`
