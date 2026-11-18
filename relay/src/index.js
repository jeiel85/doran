// 도란 중계 서버 (Cloudflare Worker + D1).
//
// 가구마다 암호화된 op 로그 하나를 보관하고, 새 op가 오면 다른 구성원 폰을 FCM으로 깨웁니다.
// 서버가 아는 것: 기기 id·토큰 해시·FCM 토큰, 어떤 기기가 어떤 가구의 member/sender인지,
// 봉투(암호문)와 그 크기·시각. 금액·가맹점·이름은 모릅니다. 형식은 design/PROTOCOL.md.

import { parseServiceAccount, sendData } from "./fcm.js";

const SCHEMA = 1;
const B64URL = /^[A-Za-z0-9_-]+$/;
const ID22 = /^[A-Za-z0-9_-]{22}$/;
const ID43 = /^[A-Za-z0-9_-]{43}$/;
const HLC = /^[0-9]{13}-[0-9]{5}-[A-Za-z0-9_-]{1,32}$/;
const ROLES = new Set(["member", "sender"]);

const MAX_BODY = 600_000;            // op 묶음 요청 하나
const MAX_OP_ENV = 64_000;           // op 하나 (사진은 blob으로)
const MAX_OPS_PER_PUSH = 200;
const MAX_BUNDLE = 6_000;
const MAX_BLOB = 2_000_000;          // D1 행 한도(2MB) 안
const SPACE_BLOB_QUOTA = 450_000_000; // D1 무료 DB 500MB 안 (DECISIONS.md D-12)
// QR은 10분, 멀리 사는 가족에게 코드를 메시지로 보낼 때는 30분까지 (DECISIONS.md D-3).
const INVITE_MAX_TTL_MS = 30 * 60_000;
const MAX_OPEN_INVITES = 5;
const PULL_LIMIT = 500;

const LIMITS = {
  register: { max: 20, windowMs: 3600_000 },   // IP당
  invite: { max: 30, windowMs: 3600_000 },     // 기기당
  claim: { max: 30, windowMs: 3600_000 },      // 기기당
  push: { max: 600, windowMs: 600_000 },       // 기기당 요청 수
  blob: { max: 200, windowMs: 3600_000 },      // 기기당 업로드
};

class HttpError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

function json(status, body) {
  return new Response(JSON.stringify({ schema: SCHEMA, ...body }), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function randomB64url(n) {
  const b = crypto.getRandomValues(new Uint8Array(n));
  let s = "";
  for (const x of b) s += String.fromCharCode(x);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

async function sha256Hex(text) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  return [...d].map((x) => x.toString(16).padStart(2, "0")).join("");
}

async function readText(req, max) {
  const len = Number(req.headers.get("content-length") ?? "0");
  if (len > max) throw new HttpError(413, "too_large");
  const text = await req.text();
  if (text.length > max) throw new HttpError(413, "too_large");
  return text;
}

async function readJson(req, max = 16_384) {
  const text = await readText(req, max);
  if (!text) return {};
  try {
    const v = JSON.parse(text);
    if (v === null || typeof v !== "object" || Array.isArray(v)) throw new Error();
    return v;
  } catch {
    throw new HttpError(400, "invalid_json");
  }
}

function requireString(v, re, code) {
  if (typeof v !== "string" || !re.test(v)) throw new HttpError(400, code);
  return v;
}

function requireEnvelope(v, max, code = "invalid_envelope") {
  if (typeof v !== "string" || v.length < 16 || v.length > max || !B64URL.test(v)) throw new HttpError(400, code);
  return v;
}

function optionalFcmToken(v) {
  if (v === undefined || v === null) return null;
  if (typeof v !== "string" || v.length < 20 || v.length > 4096 || !/^[\x21-\x7e]+$/.test(v)) {
    throw new HttpError(400, "invalid_fcm_token");
  }
  return v;
}

async function hit(db, key, { max, windowMs }, now) {
  const win = Math.floor(now / windowMs) * windowMs;
  const row = await db.prepare(
    `INSERT INTO rate (k, win, n) VALUES (?1, ?2, 1)
     ON CONFLICT (k) DO UPDATE SET n = CASE WHEN rate.win = excluded.win THEN rate.n + 1 ELSE 1 END,
                                   win = excluded.win
     RETURNING n`).bind(key, win).first();
  if (row.n > max) throw new HttpError(429, "rate_limited");
}

async function authenticate(req, db, now) {
  const m = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(req.headers.get("authorization") ?? "");
  if (!m) throw new HttpError(401, "unauthorized");
  const dev = await db.prepare("SELECT id, fcm_token, seen_at FROM devices WHERE token_hash = ?1")
    .bind(await sha256Hex(m[1])).first();
  if (!dev) throw new HttpError(401, "unauthorized");
  if (now - dev.seen_at > 3600_000) {
    await db.prepare("UPDATE devices SET seen_at = ?1 WHERE id = ?2").bind(now, dev.id).run();
  }
  return dev;
}

async function roleIn(db, space, device) {
  const row = await db.prepare("SELECT role FROM members WHERE space = ?1 AND device = ?2").bind(space, device).first();
  return row?.role ?? null;
}

async function requireRole(db, space, device, allowed) {
  const role = await roleIn(db, space, device);
  // 남의 가구인지 없는 가구인지 구분해 알려 주지 않습니다.
  if (!role || !allowed.includes(role)) throw new HttpError(404, "space_not_found");
  return role;
}

/**
 * 다른 member 기기들을 깨웁니다. 깨우기 실패는 op 저장을 되돌리지 않습니다 — 폰은 주기 동기화로도
 * 따라잡기 때문입니다. FCM이 토큰을 죽었다고 하면 지워서 다음부터 시도하지 않습니다.
 */
async function wakeMembers(env, deps, db, space, exceptDevice, data) {
  if (!env.FCM_SERVICE_ACCOUNT) return { woke: 0, skipped: "fcm_not_configured" };
  const { results } = await db.prepare(
    `SELECT d.id, d.fcm_token, d.wake_fail FROM members m JOIN devices d ON d.id = m.device
     WHERE m.space = ?1 AND m.role = 'member' AND d.id != ?2 AND d.fcm_token IS NOT NULL`)
    .bind(space, exceptDevice).all();
  if (!results.length) return { woke: 0 };
  let sa;
  try { sa = parseServiceAccount(env.FCM_SERVICE_ACCOUNT); } catch (e) {
    console.error("fcm config", e.message);
    return { woke: 0, skipped: "fcm_misconfigured" };
  }
  let woke = 0;
  // 연속 실패 횟수·이유를 기기에 남깁니다(가족 화면 "실시간 알림 안 되는 기기"). 성공하면 0으로.
  const fail = (id, why) => db.prepare("UPDATE devices SET wake_fail = wake_fail + 1, wake_err = ?2 WHERE id = ?1")
    .bind(id, String(why).slice(0, 60)).run();
  for (const t of results) {
    try {
      const r = await sendData(sa, t.fcm_token, data, 86_400, deps.fetch, deps.now(), `doran-${space}`);
      if (r === "ok") {
        woke++;
        if (t.wake_fail) await db.prepare("UPDATE devices SET wake_fail = 0, wake_err = NULL WHERE id = ?1").bind(t.id).run();
      } else if (r === "unregistered") {
        await db.prepare("UPDATE devices SET fcm_token = NULL, wake_fail = wake_fail + 1, wake_err = 'unregistered' WHERE id = ?1").bind(t.id).run();
      } else {
        await fail(t.id, r);
      }
    } catch (e) {
      console.warn("wake failed", e?.message ?? e);
      await fail(t.id, e?.message ?? "exception").catch(() => {});
    }
  }
  return { woke };
}

async function deleteSpace(db, space) {
  await db.batch([
    db.prepare("DELETE FROM ops WHERE space = ?1").bind(space),
    db.prepare("DELETE FROM blobs WHERE space = ?1").bind(space),
    db.prepare("DELETE FROM invites WHERE space = ?1").bind(space),
    db.prepare("DELETE FROM members WHERE space = ?1").bind(space),
    db.prepare("DELETE FROM spaces WHERE id = ?1").bind(space),
  ]);
}

/** member가 한 명도 남지 않은 가구는 아무도 읽을 수 없으므로 지웁니다. */
async function dropIfOrphan(db, space) {
  const row = await db.prepare("SELECT COUNT(*) AS c FROM members WHERE space = ?1 AND role = 'member'").bind(space).first();
  if (row.c === 0) await deleteSpace(db, space);
}

async function route(req, env, deps) {
  const url = new URL(req.url);
  const db = env.DB;
  const now = deps.now();
  const path = url.pathname;
  const method = req.method;

  if (method === "GET" && path === "/v1/health") return json(200, { ok: true });

  if (method === "POST" && path === "/v1/devices") {
    const ip = req.headers.get("cf-connecting-ip") ?? "unknown";
    await hit(db, `register:${ip}`, LIMITS.register, now);
    const body = await readJson(req);
    const fcm = optionalFcmToken(body.fcmToken);
    const id = randomB64url(16);
    const token = randomB64url(32);
    await db.prepare("INSERT INTO devices (id, token_hash, fcm_token, created_at, seen_at) VALUES (?1, ?2, ?3, ?4, ?4)")
      .bind(id, await sha256Hex(token), fcm, now).run();
    return json(201, { deviceId: id, token });
  }

  const me = await authenticate(req, db, now);

  if (path === "/v1/devices/me") {
    if (method === "PUT") {
      const body = await readJson(req);
      const fcm = optionalFcmToken(body.fcmToken);
      await db.prepare("UPDATE devices SET fcm_token = ?1, seen_at = ?2 WHERE id = ?3").bind(fcm, now, me.id).run();
      return json(200, { ok: true });
    }
    if (method === "DELETE") {
      const { results } = await db.prepare("SELECT space FROM members WHERE device = ?1").bind(me.id).all();
      await db.batch([
        db.prepare("DELETE FROM members WHERE device = ?1").bind(me.id),
        db.prepare("DELETE FROM invites WHERE owner = ?1").bind(me.id),
        db.prepare("DELETE FROM devices WHERE id = ?1").bind(me.id),
      ]);
      for (const r of results) await dropIfOrphan(db, r.space);
      return json(200, { ok: true });
    }
  }

  if (method === "POST" && path === "/v1/spaces") {
    await hit(db, `invite:${me.id}`, LIMITS.invite, now);
    const id = randomB64url(16);
    await db.batch([
      db.prepare("INSERT INTO spaces (id, created_at) VALUES (?1, ?2)").bind(id, now),
      db.prepare("INSERT INTO members (space, device, role, created_at) VALUES (?1, ?2, 'member', ?3)").bind(id, me.id, now),
    ]);
    return json(201, { spaceId: id });
  }

  const claim = /^\/v1\/invites\/([A-Za-z0-9_-]{43})\/claim$/.exec(path);
  if (method === "POST" && claim) {
    await hit(db, `claim:${me.id}`, LIMITS.claim, now);
    const inv = await db.prepare("SELECT space, role, bundle, owner, expires_at FROM invites WHERE id = ?1").bind(claim[1]).first();
    if (!inv) throw new HttpError(404, "invite_not_found");
    if (inv.expires_at <= now) {
      await db.prepare("DELETE FROM invites WHERE id = ?1").bind(claim[1]).run();
      throw new HttpError(410, "invite_expired");
    }
    const existing = await roleIn(db, inv.space, me.id);
    if (existing === "member") throw new HttpError(409, "already_member");
    await db.batch([
      // sender가 member로 다시 초대받는 경우(부모님이 장부를 함께 쓰기로 함)는 승격합니다.
      // 어느 초대로 들어왔는지 남겨, 가족 화면이 기기를 초대한 사람 이름과 짝짓게 합니다(D-22).
      db.prepare(`INSERT INTO members (space, device, role, created_at, invite) VALUES (?1, ?2, ?3, ?4, ?5)
                  ON CONFLICT (space, device) DO UPDATE SET role = excluded.role, invite = excluded.invite`)
        .bind(inv.space, me.id, inv.role, now, claim[1]),
      db.prepare("DELETE FROM invites WHERE id = ?1").bind(claim[1]),
    ]);
    await wakeMembers(env, deps, db, inv.space, me.id, { k: "joined", s: inv.space });
    return json(200, { spaceId: inv.space, role: inv.role, bundle: inv.bundle });
  }

  const sp = /^\/v1\/spaces\/([A-Za-z0-9_-]{22})(\/.*)?$/.exec(path);
  if (!sp) throw new HttpError(404, "not_found");
  const space = sp[1];
  const sub = sp[2] ?? "";

  if (method === "GET" && sub === "") {
    await requireRole(db, space, me.id, ["member"]);
    const { results } = await db.prepare(
      `SELECT m.device, m.role, m.created_at, m.invite, d.seen_at, d.fcm_token IS NOT NULL AS push, d.wake_fail, d.wake_err
       FROM members m JOIN devices d ON d.id = m.device WHERE m.space = ?1 ORDER BY m.created_at`).bind(space).all();
    const usage = await db.prepare(
      "SELECT (SELECT COUNT(*) FROM ops WHERE space = ?1) AS ops, (SELECT COALESCE(SUM(size), 0) FROM blobs WHERE space = ?1) AS blobBytes")
      .bind(space).first();
    return json(200, {
      members: results.map((r) => ({
        deviceId: r.device, role: r.role, createdAt: r.created_at, seenAt: r.seen_at, push: !!r.push, me: r.device === me.id,
        wakeFail: r.wake_fail ?? 0, wakeErr: r.wake_err ?? null, invite: r.invite ?? null,
      })),
      ops: usage.ops, blobBytes: usage.blobBytes, blobQuota: SPACE_BLOB_QUOTA,
    });
  }

  const memberPath = /^\/members\/([A-Za-z0-9_-]{22})$/.exec(sub);
  if (method === "DELETE" && memberPath) {
    const myRole = await requireRole(db, space, me.id, ["member", "sender"]);
    const target = memberPath[1];
    // 가족끼리는 서로의 기기를 내보낼 수 있습니다(잃어버린 폰). sender는 자기 자신만 끊을 수 있습니다.
    if (myRole === "sender" && target !== me.id) throw new HttpError(403, "forbidden");
    await db.batch([
      db.prepare("DELETE FROM members WHERE space = ?1 AND device = ?2").bind(space, target),
      db.prepare("DELETE FROM invites WHERE space = ?1 AND owner = ?2").bind(space, target),
    ]);
    await dropIfOrphan(db, space);
    return json(200, { ok: true });
  }

  if (method === "POST" && sub === "/invites") {
    await requireRole(db, space, me.id, ["member"]);
    await hit(db, `invite:${me.id}`, LIMITS.invite, now);
    const body = await readJson(req, 16_384);
    const inviteId = requireString(body.inviteId, ID43, "invalid_invite");
    if (!ROLES.has(body.role)) throw new HttpError(400, "invalid_role");
    const bundle = requireEnvelope(body.bundle, MAX_BUNDLE, "invalid_bundle");
    const ttl = Math.min(Math.max(Number(body.ttlSec) || 600, 30), INVITE_MAX_TTL_MS / 1000) * 1000;
    await db.prepare("DELETE FROM invites WHERE owner = ?1 AND expires_at <= ?2").bind(me.id, now).run();
    const open = await db.prepare("SELECT COUNT(*) AS c FROM invites WHERE owner = ?1").bind(me.id).first();
    if (open.c >= MAX_OPEN_INVITES) {
      await db.prepare(
        "DELETE FROM invites WHERE id IN (SELECT id FROM invites WHERE owner = ?1 ORDER BY expires_at ASC LIMIT ?2)")
        .bind(me.id, open.c - MAX_OPEN_INVITES + 1).run();
    }
    await db.prepare("INSERT OR REPLACE INTO invites (id, space, role, bundle, owner, expires_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
      .bind(inviteId, space, body.role, bundle, me.id, now + ttl).run();
    return json(201, { inviteId, expiresAt: now + ttl });
  }

  const inviteDel = /^\/invites\/([A-Za-z0-9_-]{43})$/.exec(sub);
  if (method === "DELETE" && inviteDel) {
    await requireRole(db, space, me.id, ["member"]);
    await db.prepare("DELETE FROM invites WHERE id = ?1 AND space = ?2").bind(inviteDel[1], space).run();
    return json(200, { ok: true });
  }

  if (method === "POST" && sub === "/ops") {
    const role = await requireRole(db, space, me.id, ["member", "sender"]);
    await hit(db, `push:${me.id}`, LIMITS.push, now);
    const body = await readJson(req, MAX_BODY);
    if (!Array.isArray(body.ops) || body.ops.length === 0 || body.ops.length > MAX_OPS_PER_PUSH) {
      throw new HttpError(400, "invalid_ops");
    }
    const stmts = [];
    for (const op of body.ops) {
      if (op === null || typeof op !== "object") throw new HttpError(400, "invalid_ops");
      const ek = requireString(op.ek, ID43, "invalid_ek");
      const h = requireString(op.h, HLC, "invalid_hlc");
      const envl = requireEnvelope(op.env, MAX_OP_ENV);
      // 같은 엔티티의 더 오래된 op는 지우고, 이미 더 새 op가 있으면 이 op는 무시됩니다(UNIQUE + OR IGNORE).
      // 폰들도 HLC로 같은 판단을 하므로 무시된 op는 어느 폰에서도 이기지 못합니다.
      stmts.push(db.prepare("DELETE FROM ops WHERE space = ?1 AND ek = ?2 AND h < ?3").bind(space, ek, h));
      stmts.push(db.prepare(
        "INSERT OR IGNORE INTO ops (space, ek, h, env, author, created_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
        .bind(space, ek, h, envl, me.id, now));
    }
    await db.batch(stmts);
    const head = await db.prepare("SELECT COALESCE(MAX(seq), 0) AS head FROM ops WHERE space = ?1").bind(space).first();
    const wake = await wakeMembers(env, deps, db, space, me.id, { k: "sync", s: space });
    // sender는 로그를 읽을 수 없으므로 head도 알려 주지 않습니다.
    return json(200, role === "member" ? { accepted: body.ops.length, head: head.head, ...wake } : { accepted: body.ops.length });
  }

  if (method === "GET" && sub === "/ops") {
    await requireRole(db, space, me.id, ["member"]);
    const after = Math.max(0, Math.floor(Number(url.searchParams.get("after")) || 0));
    const limit = Math.min(Math.max(Math.floor(Number(url.searchParams.get("limit")) || PULL_LIMIT), 1), PULL_LIMIT);
    const { results } = await db.prepare(
      "SELECT seq, ek, h, env FROM ops WHERE space = ?1 AND seq > ?2 ORDER BY seq LIMIT ?3").bind(space, after, limit + 1).all();
    const more = results.length > limit;
    const page = more ? results.slice(0, limit) : results;
    return json(200, { ops: page, next: page.length ? page[page.length - 1].seq : after, more });
  }

  const blob = /^\/blobs\/([A-Za-z0-9_-]{22})$/.exec(sub);
  if (blob) {
    await requireRole(db, space, me.id, ["member"]);
    const id = blob[1];
    if (method === "PUT") {
      await hit(db, `blob:${me.id}`, LIMITS.blob, now);
      const text = await readText(req, MAX_BLOB);
      requireEnvelope(text, MAX_BLOB);
      const usage = await db.prepare("SELECT COALESCE(SUM(size), 0) AS used FROM blobs WHERE space = ?1 AND id != ?2")
        .bind(space, id).first();
      if (usage.used + text.length > SPACE_BLOB_QUOTA) throw new HttpError(507, "quota_exceeded");
      await db.prepare("INSERT OR REPLACE INTO blobs (space, id, env, size, created_at) VALUES (?1, ?2, ?3, ?4, ?5)")
        .bind(space, id, text, text.length, now).run();
      return json(201, { ok: true, size: text.length });
    }
    if (method === "GET") {
      const row = await db.prepare("SELECT env FROM blobs WHERE space = ?1 AND id = ?2").bind(space, id).first();
      if (!row) throw new HttpError(404, "blob_not_found");
      return new Response(row.env, { status: 200, headers: { "content-type": "text/plain", "cache-control": "no-store" } });
    }
    if (method === "DELETE") {
      await db.prepare("DELETE FROM blobs WHERE space = ?1 AND id = ?2").bind(space, id).run();
      return json(200, { ok: true });
    }
  }

  throw new HttpError(404, "not_found");
}

export async function handle(req, env, deps = { fetch, now: Date.now }) {
  try {
    return await route(req, env, deps);
  } catch (e) {
    if (e instanceof HttpError) return json(e.status, { error: e.code });
    console.error("unhandled", e?.stack ?? e);
    return json(500, { error: "internal" });
  }
}

/** 하루 한 번: 만료 초대·오래된 속도 제한 행 정리. */
export async function cleanup(env, now = Date.now()) {
  await env.DB.batch([
    env.DB.prepare("DELETE FROM invites WHERE expires_at <= ?1").bind(now),
    env.DB.prepare("DELETE FROM rate WHERE win < ?1").bind(now - 86_400_000),
  ]);
}

export default {
  fetch: (req, env) => handle(req, env),
  scheduled: (_event, env, ctx) => ctx.waitUntil(cleanup(env)),
};
