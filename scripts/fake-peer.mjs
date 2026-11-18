// 가짜 가족 폰: 도란 동기화 프로토콜(design/PROTOCOL.md)을 Node로 구현해 에뮬레이터 한 대와 E2E 시험을 합니다.
// 에뮬레이터 2대를 동시에 못 띄우는 PC용. 사용법은 README "두 폰 동기화 시험".
// usage: node scripts/fake-peer.mjs join <code> | pull | push-card | push-sender <링크>
//        sender-again (push-sender로 붙은 가짜 어머니가 한 건 더 보냄 — 끊은 뒤엔 거절돼야 함)
//        host (가짜 남편이 가구를 만들고 초대 코드 출력) | push-hyundai (가짜 남편의 현대카드 결제)
//        leave (이 가짜 기기를 서버에서 지움 — 운영 서버 시험 뒤 정리)
//        demo (host 뒤 에뮬레이터가 아내로 합류하면, 스토어 화면용 예시 장부를 채움 — 로컬 중계에서만)
//        host-wife (가짜 아내가 가구를 만들고 초대 링크 출력 — 에뮬레이터가 남편으로 합류)
//        share <구성원 이름> <카드사,은행|all> (가짜 아내가 그 구성원의 공유 범위를 바꿈 — DECISIONS.md D-19)
// 환경 변수: RELAY(기본 로컬 8787), PEER_STATE(상태 파일)
import crypto from "node:crypto";
import fs from "node:fs";

const RELAY = process.env.RELAY ?? "http://127.0.0.1:8787";
const STATE = process.env.PEER_STATE ?? "fake-peer-state.json"; // 실행한 폴더에 생김(git 제외)
const b64 = (b) => Buffer.from(b).toString("base64url");
const unb64 = (s) => Buffer.from(s, "base64url");
const sha256 = (b) => crypto.createHash("sha256").update(b).digest();
const hkdf = (ikm, info) => Buffer.from(crypto.hkdfSync("sha256", ikm, Buffer.alloc(0), Buffer.from(info), 32));
const hmac = (k, d) => crypto.createHmac("sha256", k).update(d).digest();
const kid = (k) => b64(sha256(k).subarray(0, 8));

function seal(key, plain, aad) {
  const nonce = crypto.randomBytes(12);
  const c = crypto.createCipheriv("aes-256-gcm", key, nonce);
  c.setAAD(Buffer.from(aad));
  const ct = Buffer.concat([c.update(plain), c.final(), c.getAuthTag()]);
  return b64(Buffer.concat([Buffer.from([1]), sha256(key).subarray(0, 8), nonce, ct]));
}
function open(key, env, aad) {
  const b = unb64(env);
  const nonce = b.subarray(9, 21), body = b.subarray(21);
  const d = crypto.createDecipheriv("aes-256-gcm", key, nonce);
  d.setAAD(Buffer.from(aad));
  d.setAuthTag(body.subarray(body.length - 16));
  return Buffer.concat([d.update(body.subarray(0, body.length - 16)), d.final()]);
}
const ek = (key, type, id) => b64(hmac(hkdf(key, "doran ek v1"), `${type}:${id}`));
const aad = (space, e) => `doran1|${space}|${e}`;

async function call(method, path, token, body) {
  const r = await fetch(RELAY + path, {
    method, headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const t = await r.text();
  if (!r.ok) throw new Error(`${method} ${path} -> ${r.status} ${t}`);
  return JSON.parse(t);
}
const load = () => (fs.existsSync(STATE) ? JSON.parse(fs.readFileSync(STATE, "utf8")) : {});
const save = (s) => fs.writeFileSync(STATE, JSON.stringify(s, null, 2));
// 옛 코드(doran:1:<비밀값>)와 초대 링크(…/doran/join/#s.<비밀값>.<초대한 사람>) 둘 다 받습니다.
const secretOf = (code) => {
  const t = code.trim();
  if (t.includes("doran:1:")) return unb64(t.slice(t.indexOf("doran:1:") + 8));
  return unb64(t.slice(t.indexOf("#") + 1).split(".")[1]);
};
let counter = 0;
const hlc = (dev) => `${String(Date.now()).padStart(13, "0")}-${String(counter++).padStart(5, "0")}-${dev}`;

async function register(st) {
  if (!st.token) Object.assign(st, await call("POST", "/v1/devices", null, {}));
  return st;
}

async function push(st, key, items) {
  const ops = items.map(([type, id, v]) => {
    const e = ek(key, type, id);
    const h = hlc(st.deviceId);
    const plain = JSON.stringify({ t: type, id, h, d: false, v, by: "wife-" + st.deviceId });
    return { ek: e, h, env: seal(key, Buffer.from(plain), aad(st.space, e)) };
  });
  return call("POST", `/v1/spaces/${st.space}/ops`, st.token, { ops });
}

const [cmd, arg, arg2] = process.argv.slice(2);

// 가구의 op를 모두 풀어 엔티티별 최신 값으로 (pull·share가 씀)
async function latest(st) {
  const key = unb64(st.hk);
  const r = await call("GET", `/v1/spaces/${st.space}/ops?after=0`, st.token);
  const ents = {}; let unreadable = 0;
  for (const op of r.ops) {
    let o;
    try { o = JSON.parse(open(key, op.env, aad(st.space, op.ek)).toString()); } catch { unreadable++; continue; }
    if (ek(key, o.t, o.id) !== op.ek) throw new Error("ek mismatch");
    const k = `${o.t}:${o.id}`;
    if (!ents[k] || ents[k].h < o.h) ents[k] = o;
  }
  return { ops: r.ops.length, unreadable, ents: Object.values(ents) };
}
const st = load();
if (cmd === "join") {
  await register(st);
  const secret = secretOf(arg);
  const c = await call("POST", `/v1/invites/${b64(sha256(secret))}/claim`, st.token);
  const bundle = JSON.parse(open(hkdf(secret, "doran invite v1"), c.bundle, "doran1|invite").toString());
  st.space = c.spaceId; st.hk = bundle.hk; st.memberId = "wifeMember000000000000";
  console.log("joined", c.role, "household:", bundle.name, "inviter:", bundle.inviter);
  save(st);
  await push(st, unb64(st.hk), [["member", st.memberId, { name: "Wife", role: "member", color: 1, deviceIds: [st.deviceId], allowance: 0, order: 1, removed: false }]]);
  console.log("pushed wife member");
} else if (cmd === "pull") {
  const key = unb64(st.hk);
  const r = await call("GET", `/v1/spaces/${st.space}/ops?after=0`, st.token);
  const out = {}; let unreadable = 0;
  for (const op of r.ops) {
    let o;
    try { o = JSON.parse(open(key, op.env, aad(st.space, op.ek)).toString()); } catch { unreadable++; continue; }
    if (ek(key, o.t, o.id) !== op.ek) throw new Error("ek mismatch");
    (out[o.t] ??= []).push(o.d ? `${o.id} (deleted)` : o.t === "txn" ? `${o.v.merchant} ${o.v.amount} ${o.v.kind} ${o.v.issuer} src=${o.v.source} cum=${o.v.cumulative} priv=${o.v.private}`
      : o.t === "member" ? `${o.v.name} share=${o.v.share ?? "all"}:${(o.v.shareIssuers ?? []).join("|")} allowance=${o.v.allowance}`
      : o.t === "account" ? `${o.v.name}/${o.v.type}${o.v.family ? " family" : ""}` : o.t === "allowance" ? `${o.v.month} spent=${o.v.spent} n=${o.v.count}`
      : o.t === "recurring" ? `${o.v.name} ${o.v.amount} day=${o.v.day}` : o.id);
  }
  console.log("ops", r.ops.length, "unreadable(private or other keys):", unreadable);
  for (const [t, v] of Object.entries(out)) console.log(t, v.length, JSON.stringify(v.slice(0, 40)));
} else if (cmd === "push-card") {
  const key = unb64(st.hk);
  const id = "wifeTxn" + Date.now().toString().slice(-15).padStart(15, "0");
  const r = await push(st, key, [["txn", id, {
    kind: "e", amount: 34500, at: Date.now() - 60000, accountId: null, categoryId: "c.food", merchant: "배달의민족",
    memo: "", memberId: st.memberId, source: "push", installment: 0, cumulative: 812000, issuer: "신한카드",
    cardLabel: "신한카드(5678)", needsReview: false, receiptIds: [], private: false, createdBy: "wife-" + st.deviceId, createdAt: Date.now(),
  }]]);
  console.log("pushed wife card txn", id, JSON.stringify(r));
} else if (cmd === "host") {
  // 가짜 남편: 가구를 만들고 '함께 쓸 사람' 초대 코드를 출력합니다(에뮬레이터가 아내로 합류).
  await register(st);
  st.space = (await call("POST", "/v1/spaces", st.token)).spaceId;
  st.hk = b64(crypto.randomBytes(32)); st.memberId = "husbandMember000000000";
  save(st);
  await push(st, unb64(st.hk), [
    ["household", "home", { name: "우리집", monthStartDay: 1, budget: 2000000 }],
    ["member", st.memberId, { name: "남편", role: "member", color: 0, deviceIds: [st.deviceId], allowance: 0, order: 0, removed: false }],
    ["account", "husbandCard00000000000", { name: "현대카드", type: "card", ownerId: st.memberId, institution: "현대카드", last4: "1234", cycleStartDay: 1, billingDay: 14 }],
  ]);
  const secret = crypto.randomBytes(32);
  const bundle = { v: 1, role: "member", space: st.space, hk: st.hk, name: "우리집", inviter: "남편" };
  await call("POST", `/v1/spaces/${st.space}/invites`, st.token, {
    inviteId: b64(sha256(secret)), role: "member", ttlSec: 1800,
    bundle: seal(hkdf(secret, "doran invite v1"), Buffer.from(JSON.stringify(bundle)), "doran1|invite"),
  });
  // 앱과 같은 초대 링크 형식(Pairing.link): #m.<비밀값>.<초대한 사람 이름>
  console.log("https://jeiel85.github.io/doran/join/#m." + b64(secret) + "." + b64(Buffer.from("남편")));
} else if (cmd === "host-wife") {
  // 가짜 아내: 가구를 만들고 '함께 쓸 사람' 초대 링크를 출력합니다(에뮬레이터가 남편으로 합류).
  await register(st);
  st.space = (await call("POST", "/v1/spaces", st.token)).spaceId;
  st.hk = b64(crypto.randomBytes(32)); st.memberId = "wifeHostMember00000000";
  save(st);
  await push(st, unb64(st.hk), [
    ["household", "home", { name: "새해 가계부", monthStartDay: 1, budget: 3000000 }],
    ["member", st.memberId, { name: "아내", role: "member", color: 1, deviceIds: [st.deviceId], allowance: 0, order: 0, removed: false }],
  ]);
  const secret = crypto.randomBytes(32);
  const bundle = { v: 1, role: "member", space: st.space, hk: st.hk, name: "새해 가계부", inviter: "아내" };
  await call("POST", `/v1/spaces/${st.space}/invites`, st.token, {
    inviteId: b64(sha256(secret)), role: "member", ttlSec: 1800,
    bundle: seal(hkdf(secret, "doran invite v1"), Buffer.from(JSON.stringify(bundle)), "doran1|invite"),
  });
  console.log("https://jeiel85.github.io/doran/join/#m." + b64(secret) + "." + b64(Buffer.from("아내")));
} else if (cmd === "share") {
  // 가짜 아내가 구성원(예: 남편)의 공유 범위를 바꿈: share 남편 현대카드 / share 남편 all
  const { ents } = await latest(st);
  const m = ents.find((o) => o.t === "member" && !o.d && o.v.name === arg);
  if (!m) throw new Error(`no member named ${arg}`);
  const picked = arg2 && arg2 !== "all";
  const v = { ...m.v, share: picked ? "picked" : "all", shareIssuers: picked ? arg2.split(",") : [], shareBy: st.memberId };
  await push(st, unb64(st.hk), [["member", m.id, v]]);
  console.log("share set", arg, v.share, v.shareIssuers.join("|"));
} else if (cmd === "push-hyundai") {
  const key = unb64(st.hk);
  const id = "husbandTxn" + Date.now().toString().slice(-12).padStart(12, "0");
  const r = await push(st, key, [["txn", id, {
    kind: "e", amount: 12300, at: Date.now() - 20000, accountId: "husbandCard00000000000", categoryId: "c.cafe", merchant: "스타벅스 성수점",
    memo: "", memberId: st.memberId, source: "push", installment: 0, cumulative: 523000, issuer: "현대카드",
    cardLabel: "현대카드(1234)", needsReview: false, receiptIds: [], private: false, createdBy: "husband-" + st.deviceId, createdAt: Date.now(),
  }]]);
  console.log("pushed husband hyundai txn", id, JSON.stringify(r));
} else if (cmd === "leave") {
  await call("DELETE", "/v1/devices/me", st.token);
  console.log("left: device removed from relay");
} else if (cmd === "demo") {
  if (!RELAY.includes("127.0.0.1") && !RELAY.includes("localhost")) throw new Error("demo는 로컬 중계에서만");
  const key = unb64(st.hk);
  // 합류한 아내 구성원 id 찾기
  const r = await call("GET", `/v1/spaces/${st.space}/ops?after=0`, st.token);
  let wife = null;
  for (const op of r.ops) {
    try { const o = JSON.parse(open(key, op.env, aad(st.space, op.ek)).toString()); if (o.t === "member" && o.v?.name === "아내") wife = o.id; } catch {}
  }
  if (!wife) throw new Error("아내 구성원을 아직 못 찾았어요(에뮬레이터가 먼저 합류해야 함)");
  const H = st.memberId, M = "motherMember0000000000";
  // 에뮬레이터 시간대(UTC)에서 자연스러운 시각으로 보이게 UTC로 만듭니다.
  const day = (d, h = 12, m = 0) => Date.UTC(2026, 10, d, h, m);
  const prev = (mo, d, h = 12) => Date.UTC(2026, 10 - mo, d, h, 0);
  const items = [
    ["member", M, { name: "어머니", role: "sender", color: 2, deviceIds: [], allowance: 0, order: 2, removed: false }],
    ["account", "husbandCard00000000000", { name: "현대카드", type: "card", ownerId: H, institution: "현대카드", hidden: true }],
    ["account", "accHyundai000000000000", { name: "현대카드 M", type: "card", ownerId: H, institution: "현대카드", last4: "1234", cycleStartDay: 1, billingDay: 14, order: 0 }],
    ["account", "accShinhan000000000000", { name: "신한카드 Deep", type: "card", ownerId: wife, institution: "신한카드", last4: "5678", cycleStartDay: 1, billingDay: 25, order: 1 }],
    ["account", "accKB00000000000000000", { name: "생활비 통장", type: "bank", ownerId: null, institution: "KB국민은행", anchor: 3840000, anchorAt: day(1, 8), order: 2 }],
    ["account", "accSave000000000000000", { name: "청년 적금", type: "save", ownerId: wife, institution: "카카오뱅크", anchor: 6200000, anchorAt: day(1, 8), order: 3 }],
    ["account", "accCash000000000000000", { name: "지갑 현금", type: "cash", ownerId: null, anchor: 120000, anchorAt: day(1, 8), order: 4 }],
    ["account", "accMomCard000000000000", { name: "현대카드", type: "card", ownerId: M, institution: "현대카드", cycleStartDay: 1, order: 5 }],
    ["household", "home", { name: "우리집", monthStartDay: 1, budget: 2500000 }],
    ["budget", "b.c.food", { categoryId: "c.food", amount: 700000 }],
    ["budget", "b.c.cafe", { categoryId: "c.cafe", amount: 120000 }],
    ["budget", "b.c.shopping", { categoryId: "c.shopping", amount: 300000 }],
    ["recurring", "recNetflix000000000000", { name: "넷플릭스", kind: "e", amount: 13500, categoryId: "c.subscription", accountId: "accHyundai000000000000", memberId: H, period: "m", day: 20, month: 1, startAt: prev(3, 1), paused: false }],
    ["recurring", "recMgmt000000000000000", { name: "아파트 관리비", kind: "e", amount: 231000, categoryId: "c.housing", accountId: "accKB00000000000000000", memberId: null, period: "m", day: 25, month: 1, startAt: prev(3, 1), paused: false }],
    ["goal", "goalJeju0000000000000", { name: "제주도 가족 여행", target: 2000000, icon: "travel", color: 0, entries: [{ at: prev(2, 5), amount: 500000, memo: "" }, { at: prev(1, 5), amount: 500000, memo: "" }, { at: day(5), amount: 300000, memo: "" }], done: false, order: 0 }],
  ];
  let n = 0;
  const t = (id, kind, amount, at, acc, cat, merchant, member, extra = {}) => items.push(["txn", "demo" + String(n++).padStart(18, "0"),
    { kind, amount, at, accountId: acc, categoryId: cat, merchant, memo: "", memberId: member, source: extra.source ?? "push", installment: extra.inst ?? 0,
      cumulative: extra.cum ?? null, issuer: extra.issuer ?? null, cardLabel: extra.label ?? null, receiptIds: [], private: false,
      createdBy: "demo", createdAt: at, needsReview: false, toAccountId: extra.to ?? null }]);
  const HY = ["accHyundai000000000000", { issuer: "현대카드", label: "현대카드(1234)" }], SH = ["accShinhan000000000000", { issuer: "신한카드", label: "신한카드(5678)" }];
  t(0, "i", 3850000, day(10, 9), "accKB00000000000000000", "c.salary", "(주)도란 급여", H, { source: "sms", issuer: "KB국민은행" });
  t(0, "i", 2400000, day(15, 9), "accKB00000000000000000", "c.salary", "11월 급여", wife, { source: "sms", issuer: "KB국민은행" });
  const spend = [
    [1, "c.food", "이마트 성수점", 86400, wife, SH], [2, "c.cafe", "스타벅스 성수점", 12300, H, HY], [2, "c.transit", "카카오T", 8900, H, HY],
    [3, "c.food", "배달의민족", 32500, wife, SH], [4, "c.shopping", "쿠팡", 45900, wife, SH], [5, "c.medical", "온누리약국", 21000, M, ["accMomCard000000000000", { issuer: "현대카드", label: "현대카드(9012)" }]],
    [6, "c.cafe", "블루보틀", 14000, wife, SH], [7, "c.food", "본죽 성수점", 18000, H, HY], [8, "c.leisure", "CGV 왕십리", 28000, H, HY],
    [8, "c.food", "고깃집 숯불당", 67000, H, HY], [9, "c.childcare", "키즈카페 놀이터", 24000, wife, SH], [10, "c.car", "GS칼텍스 주유", 78000, H, HY],
    [11, "c.beauty", "올리브영", 38500, wife, SH], [12, "c.food", "컬리", 54200, wife, SH], [13, "c.cafe", "메가커피", 4500, H, HY],
    [13, "c.telecom", "SKT 통신요금", 59000, H, HY], [14, "c.household", "다이소", 12000, wife, SH], [15, "c.food", "홈플러스", 92300, wife, SH],
    [15, "c.gift", "꽃집 플라워", 45000, H, HY], [16, "c.education", "교보문고", 31000, wife, SH], [16, "c.food", "김밥천국", 9500, M, ["accMomCard000000000000", { issuer: "현대카드", label: "현대카드(9012)" }]],
    [17, "c.cafe", "스타벅스 성수점", 12300, H, HY], [17, "c.food", "배달의민족", 27800, wife, SH],
  ];
  let cumH = 0;
  for (const [d, cat, m, amt, who, [acc, ex]] of spend) {
    if (who === H) cumH += amt;
    t(0, "e", amt, day(d, 8 + (d * 5) % 13, (d * 7) % 60), acc, cat, m, who, { ...ex, cum: who === H ? 380000 + cumH : null });
  }
  t(0, "t", 412000, day(14, 6), "accKB00000000000000000", null, "현대카드 카드값", H, { source: "sms", issuer: "KB국민은행", to: "accHyundai000000000000" });
  // 지난 달들 추이
  const hist = [[1, 1980000], [2, 2240000], [3, 2110000], [4, 2350000], [5, 1890000]];
  for (const [mo, total] of hist) {
    const cats = [["c.food", 0.36], ["c.shopping", 0.16], ["c.cafe", 0.06], ["c.transit", 0.08], ["c.housing", 0.12], ["c.leisure", 0.08], ["c.etc", 0.14]];
    for (const [c, f] of cats) t(0, "e", Math.round(total * f / 100) * 100, prev(mo, 10 + cats.indexOf(cats.find((x) => x[0] === c))), "accKB00000000000000000", c, ({ "c.food": "장보기", "c.shopping": "온라인 쇼핑", "c.cafe": "카페", "c.transit": "교통", "c.housing": "관리비", "c.leisure": "여가", "c.etc": "기타" })[c], H, { source: "manual" });
  }
  for (let i = 0; i < items.length; i += 100) await push(st, key, items.slice(i, i + 100));
  console.log("demo seeded:", items.length, "ops; wife", wife);
} else if (cmd === "push-sender") {
  // 어머니 폰 흉내: sender 초대를 받아 링크 키로 카드 내역 하나를 보냄
  const mom = { };
  Object.assign(mom, await call("POST", "/v1/devices", null, {}));
  const secret = secretOf(arg);
  const c = await call("POST", `/v1/invites/${b64(sha256(secret))}/claim`, mom.token);
  const bundle = JSON.parse(open(hkdf(secret, "doran invite v1"), c.bundle, "doran1|invite").toString());
  console.log("mom joined as", c.role, "member", bundle.memberName, bundle.memberId);
  const lk = unb64(bundle.lk);
  const st2 = { token: mom.token, deviceId: mom.deviceId, space: c.spaceId };
  const acc = `acc.${bundle.memberId}.현대카드`;
  const tid = "momTxn" + Date.now().toString().slice(-16).padStart(16, "0");
  const all = load(); all.mom = { ...st2, lk: bundle.lk, memberId: bundle.memberId, acc }; save(all); // sender-again용
  const r = await push(st2, lk, [
    ["account", acc, { name: "현대카드", type: "card", ownerId: bundle.memberId, institution: "현대카드", cycleStartDay: 1 }],
    ["txn", tid, { kind: "e", amount: 21000, at: Date.now() - 30000, accountId: acc, categoryId: "c.medical", merchant: "온누리약국",
      memberId: bundle.memberId, source: "family", installment: 0, cumulative: 230000, issuer: "현대카드", cardLabel: "현대카드(9012)",
      receiptIds: [], private: false, createdBy: "mom", createdAt: Date.now() }],
    // 링크 키로 가구 설정을 바꾸려는 시도 — 받는 폰이 버려야 함
    ["household", "home", { name: "HACKED", monthStartDay: 1, budget: 0 }],
  ]);
  console.log("mom pushed", JSON.stringify(r));
  const denied = await fetch(`${RELAY}/v1/spaces/${c.spaceId}/ops`, { headers: { authorization: `Bearer ${mom.token}` } });
  console.log("mom read attempt ->", denied.status);
} else if (cmd === "sender-again") {
  // 가짜 어머니가 한 건 더: 연결돼 있으면 받는 폰에 "어머니가 … 썼어요", 끊겼으면 중계가 거절
  const mom = load().mom;
  if (!mom) throw new Error("먼저 push-sender");
  const tid = "momTxn" + Date.now().toString().slice(-16).padStart(16, "0");
  try {
    const r = await push(mom, unb64(mom.lk), [
      ["txn", tid, { kind: "e", amount: 8500, at: Date.now() - 10000, accountId: mom.acc, categoryId: "c.food", merchant: "동네빵집",
        memberId: mom.memberId, source: "family", installment: 0, cumulative: 238500, issuer: "현대카드", cardLabel: "현대카드(9012)",
        receiptIds: [], private: false, createdBy: "mom", createdAt: Date.now() }],
    ]);
    console.log("mom pushed again", JSON.stringify(r));
  } catch (e) {
    console.log("mom push refused:", e.message);
  }
}
