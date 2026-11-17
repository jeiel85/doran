# 그림 출처

모든 그림은 2026-11-17 Codex CLI(`codex exec`, 이미지 생성 도구)로 만들었습니다. 프롬프트는 `prompts/`, 원본은 `source/`.
`python design/make_art.py`가 원본에서 앱 리소스(런처 아이콘, 카테고리 아이콘, 일러스트)를 만듭니다.

| 파일 | 프롬프트 | 쓰는 곳 |
|---|---|---|
| source/icon-bubble.png | prompts/icon.txt (B 변형) | 런처 아이콘 (기본) |
| source/icon-peak.png | prompts/icon.txt (A 변형) | 후보로 보관 |
| source/categories-a.png | prompts/categories-a.txt | 지출 카테고리 16종 |
| source/categories-b.png | prompts/categories-b.txt (4장 중 순서가 맞은 1장) | 나머지 카테고리·자산 16종 |
| source/ill-hero.png 외 3장 | prompts/illustrations.txt | 온보딩·빈 화면·가족 연결·자동 기록 |
