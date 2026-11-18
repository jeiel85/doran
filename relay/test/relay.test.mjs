// Worker 핸들러를 node:sqlite로 만든 D1 대역과 가짜 FCM에 붙여 시험합니다.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { readFileSync } from "node:fs";
import { generateKeyPairSync } from "node:crypto";
import { handle, cleanup } from "../src/index.js";
import { _resetFcmCacheForTests } from "../src/fcm.js";

class Stmt {
  constructor(db, sql) { this.db = db; this.sql = sql; this.args = []; }
  bind(...a) { this.args = a; return this; }
  async first() { return this.db.prepare(this.sql).get(...this.args) ?? null; }
  async run() { this.db.prepare(this.sql).run(...this.args); return { success: true }; }
  async all() { return { results: this.db.prepare(this.sql).all(...this.args) }; }
}
function d1() {
  const db = new DatabaseSync(":memory:");
  for (const m of ["0001_init.sql", "0002_wake_failures.sql", "0003_member_invite.sql"]) db.exec(readFileSync(new URL(`../migrations/${m}`, import.meta.url), "utf8"));
  return {
    raw: db,
    prepare: (sql) => new Stmt(db, sql),
    batch: async (stmts) => {
      db.exec("BEGIN");
      try { for (const s of stmts) await s.run(); db.exec("COMMIT"); } catch (e) { db.exec("ROLLBACK"); throw e; }
    },
  };
}

const { privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
const SA = JSON.stringify({
  project_id: "demo", client_email: "relay@demo.iam.gserviceaccount.com",
  private_key: privateKey.export({ type: "pkcs8", format: "pem" }),
});

let env, sent, fcmMode, clock;
const deps = {
  now: () => clock,
  fetch: async (url, init) => {
    if (url.startsWith("https://oauth2.googleapis.com/")) {
      return new Response(JSON.stringify({ access_token: "at", expires_in: 3600 }), { status: 200 });
    }
    const msg = JSON.parse(init.body).message;
    if (fcmMode === "dead") {
      return new Response(JSON.stringify({ error: { details: [{ errorCode: "UNREGISTERED" }] } }), { status: 404 });
    }
    if (fcmMode === "down") return new Response("{}", { status: 503 });
    sent.push(msg);
    return new Response("{}", { status: 200 });
  },
};

beforeEach(() => {
  env = { DB: d1(), FCM_SERVICE_ACCOUNT: SA };
  sent = []; fcmMode = "ok"; clock = 1_800_000_000_000;
  _resetFcmCacheForTests();
});

async function call(method, path, { token, body, raw, ip } = {}) {
  const headers = {};
  if (token) headers.authorization = `Bearer ${token}`;
  if (ip) headers["cf-connecting-ip"] = ip;
  const res = await handle(new Request(`https://relay.test${path}`, {
    method, headers, body: raw !== undefined ? raw : body === undefined ? undefined : JSON.stringify(body),
  }), env, deps);
  const text = await res.text();
  let parsed; try { parsed = JSON.parse(text); } catch { parsed = text; }
  return { status: res.status, body: parsed };
}

const FCM = "f".repeat(30);
const ENV = "e".repeat(40);
const ek = (c) => c.repeat(43);
const hlc = (ms, n = 0, dev = "dev") => `${String(ms).padStart(13, "0")}-${String(n).padStart(5, "0")}-${dev}`;

async function register(tag = "x", fcm = true) {
  const r = await call("POST", "/v1/devices", { body: fcm ? { fcmToken: FCM + tag } : {} });
  assert.equal(r.status, 201);
  return r.body;
}

/** 남편이 가구를 만들고 아내를 초대해 합류시킵니다. */
async function household() {
  const h = await register("h");
  const w = await register("w");
  const s = await call("POST", "/v1/spaces", { token: h.token });
  assert.equal(s.status, 201);
  const spaceId = s.body.spaceId;
  const inv = await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId: "I".repeat(43), role: "member", bundle: "B".repeat(60), ttlSec: 600 },
  });
  assert.equal(inv.status, 201);
  const c = await call("POST", `/v1/invites/${"I".repeat(43)}/claim`, { token: w.token });
  assert.equal(c.status, 200);
  assert.deepEqual([c.body.spaceId, c.body.role, c.body.bundle], [spaceId, "member", "B".repeat(60)]);
  sent = [];
  return { h, w, spaceId };
}

async function addSender(h, spaceId, tag = "mom") {
  const mom = await register(tag);
  const inviteId = tag.padEnd(43, "M").slice(0, 43);
  await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId, role: "sender", bundle: "B".repeat(60) },
  });
  const c = await call("POST", `/v1/invites/${inviteId}/claim`, { token: mom.token });
  assert.equal(c.body.role, "sender");
  sent = [];
  return mom;
}

test("health는 인증 없이 응답", async () => {
  const r = await call("GET", "/v1/health");
  assert.equal(r.status, 200);
  assert.equal(r.body.ok, true);
});

test("FCM 토큰 없이도 기기 등록 가능 (Firebase 미설정 빌드)", async () => {
  const d = await register("n", false);
  const row = env.DB.raw.prepare("SELECT fcm_token FROM devices WHERE id = ?").get(d.deviceId);
  assert.equal(row.fcm_token, null);
});

test("초대 합류: 1회용, 합류하면 기존 구성원을 깨움", async () => {
  const h = await register("h");
  const w = await register("w");
  const { spaceId } = (await call("POST", "/v1/spaces", { token: h.token })).body;
  await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId: "J".repeat(43), role: "member", bundle: "B".repeat(60) },
  });
  sent = [];
  assert.equal((await call("POST", `/v1/invites/${"J".repeat(43)}/claim`, { token: w.token })).status, 200);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].token, FCM + "h");
  assert.equal(sent[0].data.k, "joined");
  const again = await call("POST", `/v1/invites/${"J".repeat(43)}/claim`, { token: (await register("z")).token });
  assert.equal(again.status, 404);
});

test("이미 member인 기기는 같은 가구 초대를 다시 받을 수 없음", async () => {
  const { h, w, spaceId } = await household();
  await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId: "Z".repeat(43), role: "sender", bundle: "B".repeat(60) },
  });
  assert.equal((await call("POST", `/v1/invites/${"Z".repeat(43)}/claim`, { token: w.token })).status, 409);
});

test("만료된 초대는 410", async () => {
  const h = await register("h");
  const { spaceId } = (await call("POST", "/v1/spaces", { token: h.token })).body;
  await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId: "K".repeat(43), role: "sender", bundle: "B".repeat(60), ttlSec: 60 },
  });
  clock += 61_000;
  const r = await call("POST", `/v1/invites/${"K".repeat(43)}/claim`, { token: (await register("m")).token });
  assert.equal(r.status, 410);
});

test("member만 초대를 만들 수 있음", async () => {
  const { spaceId } = await household();
  const outsider = await register("o");
  const r = await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: outsider.token, body: { inviteId: "L".repeat(43), role: "member", bundle: "B".repeat(60) },
  });
  assert.equal(r.status, 404);
});

test("op 올리기 → 다른 member만 깨우고, 받아 가기는 커서 이후만", async () => {
  const { h, w, spaceId } = await household();
  const up = await call("POST", `/v1/spaces/${spaceId}/ops`, {
    token: h.token, body: { ops: [{ ek: ek("a"), h: hlc(1), env: ENV }, { ek: ek("b"), h: hlc(2), env: ENV + "x" }] },
  });
  assert.equal(up.status, 200);
  assert.equal(up.body.accepted, 2);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].token, FCM + "w");
  assert.deepEqual(sent[0].data, { k: "sync", s: spaceId });
  assert.equal(sent[0].android.collapse_key, `doran-${spaceId}`);

  const all = await call("GET", `/v1/spaces/${spaceId}/ops?after=0`, { token: w.token });
  assert.equal(all.status, 200);
  assert.equal(all.body.ops.length, 2);
  const first = all.body.ops[0].seq;
  const rest = await call("GET", `/v1/spaces/${spaceId}/ops?after=${first}`, { token: w.token });
  assert.equal(rest.body.ops.length, 1);
  assert.equal(rest.body.ops[0].env, ENV + "x");
});

test("같은 엔티티는 가장 새 HLC 하나만 남고, 늦게 온 옛 op는 무시", async () => {
  const { h, w, spaceId } = await household();
  const post = (token, ops) => call("POST", `/v1/spaces/${spaceId}/ops`, { token, body: { ops } });
  await post(h.token, [{ ek: ek("a"), h: hlc(10, 0, "h"), env: ENV + "v1" }]);
  await post(w.token, [{ ek: ek("a"), h: hlc(20, 0, "w"), env: ENV + "v2" }]);
  // 오프라인이던 폰이 더 오래된 수정을 늦게 올림
  await post(h.token, [{ ek: ek("a"), h: hlc(15, 0, "h"), env: ENV + "late" }]);
  const r = await call("GET", `/v1/spaces/${spaceId}/ops`, { token: w.token });
  assert.equal(r.body.ops.length, 1);
  assert.equal(r.body.ops[0].env, ENV + "v2");
  // 같은 op 재전송(재시도)은 중복을 만들지 않음
  await post(w.token, [{ ek: ek("a"), h: hlc(20, 0, "w"), env: ENV + "v2" }]);
  assert.equal((await call("GET", `/v1/spaces/${spaceId}/ops`, { token: w.token })).body.ops.length, 1);
});

test("새 버전 op는 새 seq를 받아 커서가 지난 폰도 받음", async () => {
  const { h, w, spaceId } = await household();
  const post = (token, ops) => call("POST", `/v1/spaces/${spaceId}/ops`, { token, body: { ops } });
  await post(h.token, [{ ek: ek("a"), h: hlc(10), env: ENV + "v1" }]);
  const r1 = await call("GET", `/v1/spaces/${spaceId}/ops?after=0`, { token: w.token });
  await post(h.token, [{ ek: ek("a"), h: hlc(11), env: ENV + "v2" }]);
  const r2 = await call("GET", `/v1/spaces/${spaceId}/ops?after=${r1.body.next}`, { token: w.token });
  assert.equal(r2.body.ops.length, 1);
  assert.equal(r2.body.ops[0].env, ENV + "v2");
});

test("sender(다른 가족)는 올리기만 하고 읽기·초대·사진은 못 함", async () => {
  const { h, spaceId } = await household();
  const mom = await addSender(h, spaceId);
  const up = await call("POST", `/v1/spaces/${spaceId}/ops`, {
    token: mom.token, body: { ops: [{ ek: ek("m"), h: hlc(5, 0, "mom"), env: ENV }] },
  });
  assert.equal(up.status, 200);
  assert.equal(up.body.head, undefined);
  assert.equal(sent.length, 2, "남편·아내 모두 깨움");
  assert.equal((await call("GET", `/v1/spaces/${spaceId}/ops`, { token: mom.token })).status, 404);
  assert.equal((await call("GET", `/v1/spaces/${spaceId}`, { token: mom.token })).status, 404);
  assert.equal((await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: mom.token, body: { inviteId: "N".repeat(43), role: "member", bundle: "B".repeat(60) },
  })).status, 404);
  assert.equal((await call("PUT", `/v1/spaces/${spaceId}/blobs/${"r".repeat(22)}`, { token: mom.token, raw: ENV })).status, 404);
});

test("sender는 남을 내보낼 수 없지만 자기 연결은 끊을 수 있음", async () => {
  const { h, w, spaceId } = await household();
  const mom = await addSender(h, spaceId);
  assert.equal((await call("DELETE", `/v1/spaces/${spaceId}/members/${w.deviceId}`, { token: mom.token })).status, 403);
  assert.equal((await call("DELETE", `/v1/spaces/${spaceId}/members/${mom.deviceId}`, { token: mom.token })).status, 200);
  const info = await call("GET", `/v1/spaces/${spaceId}`, { token: h.token });
  assert.equal(info.body.members.length, 2);
});

test("가구 정보는 기기마다 들어온 초대 id를 알려 줌 (보내는 가족 이름 짝짓기)", async () => {
  const { h, w, spaceId } = await household();
  const mom = await addSender(h, spaceId);
  const info = await call("GET", `/v1/spaces/${spaceId}`, { token: w.token });
  const by = Object.fromEntries(info.body.members.map((m) => [m.deviceId, m.invite]));
  assert.equal(by[mom.deviceId], "mom".padEnd(43, "M"));
  assert.equal(by[w.deviceId], "I".repeat(43));
  assert.equal(by[h.deviceId], null, "가구를 만든 기기는 초대 없이 들어옴");
});

test("sender를 member로 다시 초대하면 승격", async () => {
  const { h, spaceId } = await household();
  const mom = await addSender(h, spaceId);
  await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId: "P".repeat(43), role: "member", bundle: "B".repeat(60) },
  });
  assert.equal((await call("POST", `/v1/invites/${"P".repeat(43)}/claim`, { token: mom.token })).body.role, "member");
  assert.equal((await call("GET", `/v1/spaces/${spaceId}/ops`, { token: mom.token })).status, 200);
  const info = await call("GET", `/v1/spaces/${spaceId}`, { token: h.token });
  assert.equal(info.body.members.find((m) => m.deviceId === mom.deviceId).invite, "P".repeat(43), "승격하면 새 초대로 바뀜");
});

test("member가 모두 떠나면 가구 데이터 삭제", async () => {
  const { h, w, spaceId } = await household();
  await call("POST", `/v1/spaces/${spaceId}/ops`, { token: h.token, body: { ops: [{ ek: ek("a"), h: hlc(1), env: ENV }] } });
  await call("DELETE", `/v1/spaces/${spaceId}/members/${w.deviceId}`, { token: h.token });
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM ops").get().c, 1);
  await call("DELETE", "/v1/devices/me", { token: h.token });
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM ops").get().c, 0);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM spaces").get().c, 0);
});

test("FCM이 토큰 만료를 알리면 지우고, 깨우기 실패해도 op는 저장", async () => {
  const { h, w, spaceId } = await household();
  fcmMode = "dead";
  const up = await call("POST", `/v1/spaces/${spaceId}/ops`, { token: h.token, body: { ops: [{ ek: ek("a"), h: hlc(1), env: ENV }] } });
  assert.equal(up.status, 200);
  assert.equal(env.DB.raw.prepare("SELECT fcm_token FROM devices WHERE id = ?").get(w.deviceId).fcm_token, null);
  fcmMode = "down";
  const up2 = await call("POST", `/v1/spaces/${spaceId}/ops`, { token: w.token, body: { ops: [{ ek: ek("b"), h: hlc(2), env: ENV }] } });
  assert.equal(up2.status, 200);
  assert.equal(up2.body.woke, 0);
});

test("깨우기 연속 실패는 기기에 세고 가구 정보로 보이며, 성공하면 0으로", async () => {
  const { h, w, spaceId } = await household();
  fcmMode = "down";
  for (const n of [1, 2]) {
    await call("POST", `/v1/spaces/${spaceId}/ops`, { token: h.token, body: { ops: [{ ek: ek("a"), h: hlc(n), env: ENV }] } });
  }
  let info = await call("GET", `/v1/spaces/${spaceId}`, { token: h.token });
  const wife = info.body.members.find((m) => m.deviceId === w.deviceId);
  assert.equal(wife.wakeFail, 2);
  assert.equal(wife.wakeErr, "error");
  assert.equal(info.body.members.find((m) => m.deviceId === h.deviceId).wakeFail, 0);
  fcmMode = "ok";
  await call("POST", `/v1/spaces/${spaceId}/ops`, { token: h.token, body: { ops: [{ ek: ek("a"), h: hlc(3), env: ENV }] } });
  info = await call("GET", `/v1/spaces/${spaceId}`, { token: h.token });
  assert.equal(info.body.members.find((m) => m.deviceId === w.deviceId).wakeFail, 0);
  assert.equal(info.body.members.find((m) => m.deviceId === w.deviceId).wakeErr, null);
});

test("Firebase 비밀값이 없으면 깨우기 없이 저장만", async () => {
  const { h, spaceId } = await household();
  delete env.FCM_SERVICE_ACCOUNT;
  const up = await call("POST", `/v1/spaces/${spaceId}/ops`, { token: h.token, body: { ops: [{ ek: ek("a"), h: hlc(1), env: ENV }] } });
  assert.equal(up.status, 200);
  assert.equal(up.body.skipped, "fcm_not_configured");
});

test("잘못된 op 형식은 400, 아무것도 저장 안 함", async () => {
  const { h, spaceId } = await household();
  const bad = await call("POST", `/v1/spaces/${spaceId}/ops`, {
    token: h.token, body: { ops: [{ ek: ek("a"), h: hlc(1), env: ENV }, { ek: "short", h: hlc(2), env: ENV }] },
  });
  assert.equal(bad.status, 400);
  assert.equal(env.DB.raw.prepare("SELECT COUNT(*) AS c FROM ops").get().c, 0);
  assert.equal((await call("POST", `/v1/spaces/${spaceId}/ops`, { token: h.token, body: { ops: [] } })).status, 400);
  assert.equal((await call("POST", `/v1/spaces/${spaceId}/ops`, {
    token: h.token, body: { ops: [{ ek: ek("a"), h: "2026-11-17", env: ENV }] },
  })).status, 400);
});

test("받아 가기는 쪽 나눔(more/next)", async () => {
  const { h, w, spaceId } = await household();
  const ops = Array.from({ length: 150 }, (_, i) => ({ ek: String(i).padStart(43, "k"), h: hlc(i + 1), env: ENV }));
  await call("POST", `/v1/spaces/${spaceId}/ops`, { token: h.token, body: { ops } });
  const p1 = await call("GET", `/v1/spaces/${spaceId}/ops?after=0&limit=100`, { token: w.token });
  assert.equal(p1.body.ops.length, 100);
  assert.equal(p1.body.more, true);
  const p2 = await call("GET", `/v1/spaces/${spaceId}/ops?after=${p1.body.next}&limit=100`, { token: w.token });
  assert.equal(p2.body.ops.length, 50);
  assert.equal(p2.body.more, false);
});

test("사진 blob 올리기·받기·용량 표시", async () => {
  const { h, w, spaceId } = await household();
  const id = "r".repeat(22);
  const put = await call("PUT", `/v1/spaces/${spaceId}/blobs/${id}`, { token: h.token, raw: ENV.repeat(10) });
  assert.equal(put.status, 201);
  const got = await call("GET", `/v1/spaces/${spaceId}/blobs/${id}`, { token: w.token });
  assert.equal(got.status, 200);
  assert.equal(got.body, ENV.repeat(10));
  const info = await call("GET", `/v1/spaces/${spaceId}`, { token: w.token });
  assert.equal(info.body.blobBytes, 400);
  assert.equal((await call("DELETE", `/v1/spaces/${spaceId}/blobs/${id}`, { token: w.token })).status, 200);
  assert.equal((await call("GET", `/v1/spaces/${spaceId}/blobs/${id}`, { token: w.token })).status, 404);
});

test("다른 가구 로그는 보이지 않음", async () => {
  const a = await household();
  const stranger = await register("s");
  const own = (await call("POST", "/v1/spaces", { token: stranger.token })).body.spaceId;
  assert.notEqual(own, a.spaceId);
  assert.equal((await call("GET", `/v1/spaces/${a.spaceId}/ops`, { token: stranger.token })).status, 404);
  assert.equal((await call("POST", `/v1/spaces/${a.spaceId}/ops`, {
    token: stranger.token, body: { ops: [{ ek: ek("a"), h: hlc(1), env: ENV }] },
  })).status, 404);
});

test("인증 없으면 401, 등록 속도 제한", async () => {
  assert.equal((await call("GET", `/v1/spaces/${"x".repeat(22)}/ops`)).status, 401);
  for (let i = 0; i < 20; i++) await call("POST", "/v1/devices", { body: {}, ip: "1.2.3.4" });
  assert.equal((await call("POST", "/v1/devices", { body: {}, ip: "1.2.3.4" })).status, 429);
});

test("cleanup은 만료 초대만 지움", async () => {
  const h = await register("h");
  const { spaceId } = (await call("POST", "/v1/spaces", { token: h.token })).body;
  await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId: "Q".repeat(43), role: "member", bundle: "B".repeat(60), ttlSec: 60 },
  });
  await call("POST", `/v1/spaces/${spaceId}/invites`, {
    token: h.token, body: { inviteId: "R".repeat(43), role: "member", bundle: "B".repeat(60), ttlSec: 600 },
  });
  await cleanup(env, clock + 120_000);
  const left = env.DB.raw.prepare("SELECT id FROM invites").all().map((r) => r.id);
  assert.deepEqual(left, ["R".repeat(43)]);
});
