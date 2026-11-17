// 가짜 가족 폰: 도란 동기화 프로토콜(design/PROTOCOL.md)을 Node로 구현해 에뮬레이터 한 대와 E2E 시험을 합니다.
// 에뮬레이터 2대를 동시에 못 띄우는 PC용. 사용법은 README "두 폰 동기화 시험".
// usage: node scripts/fake-peer.mjs join <code> | pull | push-card | push-sender <code>
//        host (가짜 남편이 가구를 만들고 초대 코드 출력) | push-hyundai (가짜 남편의 현대카드 결제)
//        leave (이 가짜 기기를 서버에서 지움 — 운영 서버 시험 뒤 정리)
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
const secretOf = (code) => unb64(code.trim().slice(code.indexOf("doran:1:") + 8));
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

const [cmd, arg] = process.argv.slice(2);
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
    (out[o.t] ??= []).push(o.d ? `${o.id} (deleted)` : o.t === "txn" ? `${o.v.merchant} ${o.v.amount} ${o.v.kind} src=${o.v.source} cum=${o.v.cumulative} priv=${o.v.private}` : o.t === "member" ? o.v.name : o.t === "account" ? `${o.v.name}/${o.v.type}` : o.id);
  }
  console.log("ops", r.ops.length, "unreadable(private or other keys):", unreadable);
  for (const [t, v] of Object.entries(out)) console.log(t, v.length, JSON.stringify(v.slice(0, 12)));
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
}
