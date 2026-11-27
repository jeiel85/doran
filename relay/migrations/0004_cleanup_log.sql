-- 정기 정리(cleanup, DECISIONS.md D-41)가 지운 수. Workers 로그는 오래 남지 않아, 잘못 지웠는지 나중에 볼 수 있게 남깁니다(#99).
-- 무엇을 지웠는지(가구·기기 id)는 남기지 않고 수만 남깁니다.
CREATE TABLE cleanup_log (
  at        INTEGER NOT NULL,   -- unix ms
  spaces    INTEGER NOT NULL,   -- 지운 혼자 든 가구 수
  devices   INTEGER NOT NULL    -- 지운 기기 수(그 가구의 기기 + 가구 없는 기기)
);
