-- 기기가 어느 초대로 들어왔는지 (DECISIONS.md D-22).
-- 초대를 만든 폰은 초대 id를 장부(Link.inviteId)에 적어 두므로, 가족 화면에서 보내는 가족 기기를
-- "어머니 폰"처럼 사람 이름과 짝지어 보여 줄 수 있습니다. 초대 id는 합류하면 초대가 지워져 더는 쓸 수 없는 값입니다.
-- 이 마이그레이션 전에 들어온 기기는 NULL(이름 없이 "보내는 가족 폰").
ALTER TABLE members ADD COLUMN invite TEXT;
