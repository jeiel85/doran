-- 실시간 알림(FCM 깨우기) 실패 집계 (하드닝 #2, DECISIONS.md D-9).
-- 연속 실패 횟수와 마지막 실패 이유를 기기에 남겨, 가족 화면에서 "실시간 알림 안 되는 기기"를 보여 줍니다.
-- 깨우기가 한 번 성공하면 0으로 돌아갑니다.
ALTER TABLE devices ADD COLUMN wake_fail INTEGER NOT NULL DEFAULT 0;
ALTER TABLE devices ADD COLUMN wake_err TEXT;
