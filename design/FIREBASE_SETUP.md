# Firebase (실시간 알림) 설정

없어도 도란은 동작합니다(15분마다·앱을 열 때 동기화). 있으면 가족이 결제하자마자 몇 초 안에 알림이 옵니다 (DECISIONS.md D-9).
포너디와 같은 순서입니다.

1. https://console.firebase.google.com → 프로젝트 추가 → 이름 `doran` (Google 애널리틱스·Gemini 끄기)
2. 프로젝트 설정 → 내 앱 → Android 앱 추가 → 패키지 이름 **`com.jeiel85.doran`** → `google-services.json` 받기
3. 프로젝트 설정 → 서비스 계정 → **새 비공개 키 생성** → `doran-…-firebase-adminsdk-….json` 받기
4. Git Bash에서 저장소 루트:

   ```bash
   scripts/finish-firebase.sh
   ```

   다운로드 폴더에서 두 파일을 찾아 같은 프로젝트인지 확인하고, `android/app/google-services.json`(git 제외)과
   중계 서버 비밀값 `FCM_SERVICE_ACCOUNT`을 설정한 뒤 시험·릴리스 빌드까지 합니다.
   서비스 계정 키 사본은 `%LOCALAPPDATA%\doran\service-account.json`(현재 사용자만)에 둡니다. 잃으면 콘솔에서 새 키를 만들면 됩니다.
