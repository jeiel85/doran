# 그림 출처

모든 그림은 Codex CLI(`codex exec`, 이미지 생성 도구)로 만들었습니다(2026-11-17, 런처 아이콘은 이름을 '둥지'로 바꾸며 2026-11-20). 프롬프트는 `prompts/`, 원본은 `source/`.
`python design/make_art.py`가 원본에서 앱 리소스(런처 아이콘, 카테고리 아이콘, 일러스트)를 만듭니다.

| 파일 | 프롬프트 | 쓰는 곳 |
|---|---|---|
| source/icon-nest.png | prompts/icon-dungji.txt (A 변형, 금빛 알 하나) | 런처 아이콘 (기본, 2026-11-20 사용자 선택) |
| source/icon-nest-two.png | prompts/icon-dungji.txt (B 변형, 알 두 개) | 후보로 보관 |
| source/icon-bubble.png | prompts/icon.txt (B 변형) | 옛 이름 '도란'의 런처 아이콘 (보관) |
| source/icon-peak.png | prompts/icon.txt (A 변형) | 옛 이름 '도란'의 후보 (보관) |
| source/categories-a.png | prompts/categories-a.txt | 지출 카테고리 16종 |
| source/categories-b.png | prompts/categories-b.txt (4장 중 순서가 맞은 1장) | 나머지 카테고리·자산 16종 |
| source/ill-hero.png 외 3장 | prompts/illustrations.txt | 온보딩·빈 화면·가족 연결·자동 기록 |

단색 테마 아이콘과 상태 표시줄 알림 아이콘은 그림이 아니라 손으로 그린 벡터(`android/app/src/main/res/drawable/ic_launcher_monochrome.xml`, `ic_stat_doran.xml`)입니다. 알과 둥지 사이 틈·엮은 띠 두 줄로 작게 봐도 둥지로 읽히게 했습니다.
