0.1.2(골라 보내기, 배포 채널 분리)를 내며 드러난 개선 후보입니다(전역 지침: 릴리스 때 이슈로 등록하고 그대로 릴리스).

- [ ] Play 빌드의 묶인 문자 알림 · 문자 앱이 한 대화방의 여러 문자를 한 알림(MessagingStyle)으로 다시 올리면 마지막 줄만 읽거나 같은 문자를 다시 읽을 수 있음 · `EXTRA_MESSAGES`를 문자별로 나눠 읽고 문자 시각으로 중복 거르기 · `CaptureService`(play 경로), 실기기(삼성 메시지·Google 메시지)
- [ ] 자동 보내기 규칙 백업 · "앞으로도 자동으로 보내기" 규칙이 그 폰 설정에만 있어 백업 파일로 옮겨지지 않음 · 백업·복원에 규칙 포함 · `LocalSettings.rules`, 백업 형식
- [ ] 후보 알림 쌓임 · 형식을 몰라 남긴 후보가 30일 동안 개수 제한 없이 쌓임(같은 앱의 반복 안내 등) · 발신처별 최근 N건만 남기거나 같은 원문 합치기 · `Db.logCapture`, `CaptureProcessor.process`
- [ ] 채널 바꿔 설치할 때 안내 · GitHub APK 위에 Play 버전을 설치하면 서명 충돌로 설치가 실패하는데 앱 안에는 안내가 없음 · 설정에 "받은 곳(GitHub/Play)"과 바꾸는 방법 표시 · `SettingsScreen`, `UpdateSection`
- [ ] Play 업로드 키 분실 절차 · 업로드 키를 잃으면 Play Console에서 키 재설정 요청이 필요 · 절차와 백업 위치를 문서로 · DECISIONS.md D-16, README "배포"
