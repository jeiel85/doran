# 둥지 동기화 프로토콜

중계 서버(`relay/`, Cloudflare Worker + D1)와 폰(`android/`)이 주고받는 형식입니다. 왜 이렇게 했는지는 [DECISIONS.md](../DECISIONS.md) D-2·D-3·D-6·D-7.

## 개념

| 이름 | 뜻 |
|---|---|
| 기기(device) | 앱 설치본 하나. 서버 등록 때 `deviceId`(22자)와 bearer 토큰(43자)을 받음 |
| 가구(space) | 장부 로그 하나. 구성원은 `member`(읽기·쓰기) 또는 `sender`(쓰기만) |
| 엔티티 | 장부의 한 줄: `household`, `member`, `account`, `category`, `txn`, `budget`, `recurring`, `goal`, `rule`, `allowance`, `link` |
| op | 엔티티 한 건의 최신 상태(또는 삭제 묘비)를 봉한 것 |
| HLC | `ms(13자리)-counter(5자리)-installId`. 문자열 비교 = 시간순 비교 |

## 키 (모두 AES-256)

- `hk` 가구 키: member 폰만 가짐
- `pk` 개인 키: 구성원마다, 용돈 거래(`txn.private = true`)만
- `lk` 링크 키: 보내는 가족(sender)마다 하나. 가구 쪽은 `link` 엔티티(가구 키로 봉함)에 보관

## 봉투

```
env = base64url( 0x01 ‖ kid(8) ‖ nonce(12) ‖ AES-256-GCM(key, plain, aad) )
kid = SHA-256(key)[0..8]
```

op 평문: `{"t": type, "id": id, "h": HLC, "d": 삭제여부, "v": 엔티티 JSON | null, "by": installId}`

- `ek = base64url(HMAC-SHA256(HKDF(key, salt="", info="doran ek v1"), "type:id"))` — 서버가 같은 엔티티의 옛 op를 지울 수 있게 하되 무엇인지는 모름
- `aad = "doran1|" + spaceId + "|" + ek`
- 받는 폰은 열어 본 뒤 `ek`를 다시 계산해 맞는지 확인합니다.

## 합치기

같은 (type, id)에서 HLC가 더 큰 쪽이 이깁니다(엔티티 통째). 서버도 같은 규칙으로 `(space, ek)`마다 가장 큰 `h` 하나만 남깁니다.

- 승인 취소는 원거래를 고치지 않고 `txn.cancelOf`가 있는 새 엔티티로 표현합니다.
- 공유하던 거래를 용돈으로 돌리면 `pk` op와 함께, 같은 (ms, counter)의 가장 낮은 HLC(`ms-counter--`)로 가구 키 묘비를 보냅니다. 배우자 폰에서는 옛 공유본을 지우고, 내 다른 기기에서는 용돈 op에 집니다.
- 링크 키로 연 op는 `txn`(memberId가 그 링크의 구성원)과 `account`(ownerId가 그 구성원)만 받습니다. 나머지는 버립니다.
- 키가 아직 없는 봉투(링크 엔티티보다 먼저 온 sender op 등)는 `pending_env`에 두었다가 키 고리가 바뀌면 다시 엽니다. 90일 뒤 버립니다.

## 초대

QR·링크: `https://jeiel85.github.io/doran/join/#<m|s>.<base64url(s)>[.<base64url(초대한 사람 이름 UTF-8)>]` (s = 32바이트 비밀값, m = 함께 쓰는 사람, s = 보내기 전용).
연결 페이지는 앱에 `doran://join/<같은 # 뒤 내용>`으로 넘깁니다. 옛 형식 `doran:1:<base64url(s)>`도 받습니다.

```
inviteId = base64url(SHA-256(s))
bundle   = seal(HKDF(s, salt="", info="doran invite v1"), JSON, aad="doran1|invite")
  member: {"v":1,"role":"member","space","hk","name","inviter"}
  sender: {"v":1,"role":"sender","space","lk","memberId","memberName","label","inviter"}
```

서버는 `inviteId`와 봉한 `bundle`만 압니다. 1회용, 30분(설치까지 하는 동안 끝나지 않게).

## HTTP API (모두 JSON, `schema: 1`)

| 메서드·경로 | 누가 | 설명 |
|---|---|---|
| `GET /v1/health` | 누구나 | |
| `POST /v1/devices` `{fcmToken?}` | 누구나(IP당 시간 20회) | → `{deviceId, token}` |
| `PUT /v1/devices/me` `{fcmToken}` | 기기 | FCM 토큰 갱신(없으면 null) |
| `DELETE /v1/devices/me` | 기기 | 모든 가구에서 빠짐. member가 없어진 가구는 통째로 삭제 |
| `POST /v1/spaces` | 기기 | 가구 만들기 → `{spaceId}` |
| `GET /v1/spaces/:id` | member | 연결된 기기(실시간 알림 연속 실패 `wakeFail`·`wakeErr`, 들어온 초대 `invite` 포함)·op 수·사진 용량 |
| `DELETE /v1/spaces/:id/members/:device` | member(누구든) / sender(자기만) | 기기 내보내기 |
| `POST /v1/spaces/:id/invites` `{inviteId, role, bundle, ttlSec}` | member | 초대 맡기기 |
| `DELETE /v1/spaces/:id/invites/:inviteId` | member | 초대 취소 |
| `POST /v1/invites/:inviteId/claim` | 기기 | → `{spaceId, role, bundle}`, 다른 member를 `{k:"joined"}`로 깨움. 기기에 초대 id를 남김(보내는 가족 이름 짝짓기, DECISIONS.md D-22) |
| `POST /v1/spaces/:id/ops` `{ops:[{ek,h,env}]}` | member·sender | 최대 200개. 다른 member를 `{k:"sync", s}`로 깨움 |
| `GET /v1/spaces/:id/ops?after=&limit=` | member | → `{ops:[{seq,ek,h,env}], next, more}` |
| `PUT/GET/DELETE /v1/spaces/:id/blobs/:blobId` | member | 영수증 사진 봉투(텍스트, 2MB 이하, 가구당 450MB) |

FCM 데이터 메시지에는 금액·이름이 없고 `k`(sync/joined)와 가구 id만 들어갑니다. 알림 문구는 폰이 복호화한 뒤 만듭니다.
