-- 도란 중계 서버 상태. 서버는 장부 내용을 볼 수 없습니다: op·사진·초대 묶음은 모두
-- 폰에서 암호화된 봉투이고, 서버는 "어느 기기가 어느 가구의 로그를 읽고 쓸 수 있는지"만 압니다.
-- 자세한 형식은 design/PROTOCOL.md.

CREATE TABLE devices (
  id          TEXT PRIMARY KEY,          -- 22자 base64url (16 random bytes)
  token_hash  TEXT NOT NULL UNIQUE,      -- bearer 토큰의 SHA-256 hex
  fcm_token   TEXT,                      -- Firebase 미설정 빌드이거나 FCM이 토큰 만료를 알리면 NULL
  created_at  INTEGER NOT NULL,          -- unix ms
  seen_at     INTEGER NOT NULL
);

-- 가구(장부 로그 하나).
CREATE TABLE spaces (
  id          TEXT PRIMARY KEY,          -- 22자 base64url
  created_at  INTEGER NOT NULL
);

-- member: 로그를 읽고 씀. sender: 다른 가족 — 올리기만 하고 읽지 못함 (DECISIONS.md D-6).
CREATE TABLE members (
  space       TEXT NOT NULL,
  device      TEXT NOT NULL,
  role        TEXT NOT NULL CHECK (role IN ('member', 'sender')),
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (space, device)
);
CREATE INDEX members_device ON members (device);

-- 엔티티별 최신 op만 남는 로그. ek = 폰이 키로 만든 HMAC(엔티티 종류·id)이라 서버는 무엇인지 모름.
-- h = HLC 문자열(사전순 = 시간순). 같은 ek의 더 새 h가 오면 옛 행을 지우고 새 seq로 넣습니다.
CREATE TABLE ops (
  seq         INTEGER PRIMARY KEY AUTOINCREMENT,
  space       TEXT NOT NULL,
  ek          TEXT NOT NULL,
  h           TEXT NOT NULL,
  env         TEXT NOT NULL,
  author      TEXT NOT NULL,
  created_at  INTEGER NOT NULL,
  UNIQUE (space, ek)
);
CREATE INDEX ops_space_seq ON ops (space, seq);

-- 영수증 사진 등 큰 암호문 (DECISIONS.md D-12).
CREATE TABLE blobs (
  space       TEXT NOT NULL,
  id          TEXT NOT NULL,
  env         TEXT NOT NULL,
  size        INTEGER NOT NULL,
  created_at  INTEGER NOT NULL,
  PRIMARY KEY (space, id)
);

-- 1회용 초대. id = base64url(SHA-256(QR 비밀값)), bundle = 그 비밀값으로 감싼 키 묶음.
CREATE TABLE invites (
  id          TEXT PRIMARY KEY,
  space       TEXT NOT NULL,
  role        TEXT NOT NULL CHECK (role IN ('member', 'sender')),
  bundle      TEXT NOT NULL,
  owner       TEXT NOT NULL,
  expires_at  INTEGER NOT NULL
);
CREATE INDEX invites_owner ON invites (owner);

CREATE TABLE rate (
  k           TEXT PRIMARY KEY,
  win         INTEGER NOT NULL,
  n           INTEGER NOT NULL
);
