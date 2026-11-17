// Control Pad 평가단 중계 서버 (Cloudflare Workers).
//
// 앱의 '실험실 · 평가단' 화면이 여기로 정식 추가 요청을 보낸다. 평가단 코드가 맞으면
// 저장소의 자동화(.github/workflows/panel.yml)를 대신 실행하고, 바로 반영된다.
// 평가단은 GitHub 계정이 없어도 된다. 설정 방법은 relay/README.md 에 있다.
//
// 레이아웃 커뮤니티(LOD): 주소 끝이 /community 인 요청은 누구나 레이아웃을 올릴 수 있다(평가단 코드 필요 없음).
// 올리면 '지우기 열쇠'를 돌려준다. 저장소에는 열쇠의 SHA-256 만 남긴다. /community/delete 에 열쇠(또는 평가단 코드)를
// 보내면 그 레이아웃을 지운다(KDSKD).
//
// 회원 등급: 올린 레이아웃 수(앱이 알려 준다)로 🌱 새싹 · 🌿 일반 · 🌳 활동가 · 🏆 장인, 관리자는 👑 관리자.
// 관리자: 닉네임 cupompu 는 관리자 비밀번호가 맞아야 쓸 수 있다. 비밀번호의 해시는 저장소의
// community/admin.json 에 있고(처음 0000), /community/admin/login 으로 확인, /community/admin/password 로 바꾼다.
// 관리자는 어떤 레이아웃이든 지울 수 있다.
//
// 커뮤니티 글(jf): /community/post 로 글(제목 · 내용)을 올린다. community/posts/ 에 저장되고 목록에 함께 실린다.
// 지우기는 레이아웃과 같고, 요청에 kind: "post" 를 붙인다.
// 올린 레이아웃은 저장소의 community/layouts/ 에 파일로 저장되고, 목록(community/index.json)은
// 저장소의 자동화(.github/workflows/community.yml)가 만든다. 받기는 앱이 GitHub 에서 바로 한다.
//
// 비밀 값(대시보드의 Variables and Secrets 에 넣는다. 코드나 저장소에 적지 않는다):
//   PANEL_CODE    평가단에게 알려 줄 코드
//   GITHUB_TOKEN  이 저장소의 Actions 를 실행하고(Actions: Read and write),
//                 커뮤니티 파일을 쓸 수 있는(Contents: Read and write) 토큰
// 선택 값: REPO (기본 jkyou1127-ship-it/control), BRANCH (기본 claude/vibrant-ramanujan-pdzsly)

const DEFAULT_REPO = "jkyou1127-ship-it/control";
const DEFAULT_BRANCH = "claude/vibrant-ramanujan-pdzsly";

/** 요청의 종류와 대상이 정해진 모양인지. scripts/panel.py 의 검사와 같아야 한다. */
export function validRequest(kind, target) {
  if (typeof target !== "string") return false;
  if (kind === "build") return /^[0-9A-Za-z][0-9A-Za-z. ]{0,19}$/.test(target);
  if (kind === "lab" || kind === "hide" || kind === "restore") return /^[a-z0-9][a-z0-9-]{0,39}$/.test(target);
  return false;
}

/** 두 문자열이 같은지, 걸리는 시간으로 내용을 짐작할 수 없게 비교한다. */
export async function sameSecret(a, b) {
  const enc = new TextEncoder();
  const [x, y] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const p = new Uint8Array(x);
  const q = new Uint8Array(y);
  let diff = 0;
  for (let i = 0; i < p.length; i++) diff |= p[i] ^ q[i];
  return diff === 0;
}

/** 커뮤니티에 올릴 수 있는 레이아웃의 최대 크기(글자 수). GitHub 파일 API 로 무리 없이 쓸 수 있는 크기. */
export const COMMUNITY_MAX = 900_000;

/** 이름 · 만든 사람에서 줄바꿈 같은 제어 문자를 지우고 길이를 자른다. */
export function cleanText(s, max) {
  if (typeof s !== "string") return "";
  return s.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, max);
}

/**
 * 커뮤니티에 올릴 레이아웃을 검사하고, 저장할 내용을 만든다. 안 되면 { error }.
 * layout 은 앱의 '파일로 저장'과 같은 형식(format: controlpad-layout)의 JSON 글자.
 */
export function communityEntry(body, now = new Date(), rand = Math.random) {
  const name = cleanText(body?.name, 40);
  const author = cleanText(body?.author, 20) || "익명";
  const text = body?.layout;
  if (!name) return { error: "no_name" };
  if (typeof text !== "string" || text.length === 0) return { error: "no_layout" };
  if (text.length > COMMUNITY_MAX) return { error: "too_big" };
  let layout;
  try {
    layout = JSON.parse(text);
  } catch {
    return { error: "bad_layout" };
  }
  if (!layout || layout.format !== "controlpad-layout" || !Array.isArray(layout.buttons)) return { error: "bad_layout" };
  const stamp = now.toISOString().replace(/[-:]/g, "").replace(/\..*/, "");      // 20261117T083000
  const id = stamp + "-" + Math.floor(rand() * 0xffffff).toString(16).padStart(6, "0");
  return { id, path: `community/layouts/${id}.json`, file: { id, name, author, created: now.toISOString(), buttons: layout.buttons.length, layout } };
}

/** 문자열을 UTF-8 바이트의 base64 로(GitHub 파일 API 가 받는 형식). */
export function base64Utf8(s) {
  const bytes = new TextEncoder().encode(s);
  let bin = "";
  for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(bin);
}

/** 지우기 열쇠: 무작위 16바이트(16진수 32자). */
export function newKey() {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export async function sha256Hex(s) {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  return [...d].map((x) => x.toString(16).padStart(2, "0")).join("");
}

export function validId(id) {
  return typeof id === "string" && /^[0-9A-Za-z_-]{1,64}$/.test(id);
}

function githubHeaders(env) {
  return {
    authorization: `Bearer ${env.GITHUB_TOKEN}`,
    accept: "application/vnd.github+json",
    "x-github-api-version": "2022-11-28",
    "user-agent": "controlpad-panel-relay",
    "content-type": "application/json",
  };
}

/** 올린 레이아웃 지우기: 지우기 열쇠가 맞거나 평가단 코드가 맞을 때만. */
async function communityDelete(request, env, fetcher) {
  if (!env.GITHUB_TOKEN) return reply(500, { ok: false, error: "relay_not_configured" });
  let body;
  try {
    body = await request.json();
  } catch {
    return reply(400, { ok: false, error: "bad_request" });
  }
  const { id, key, code } = body ?? {};
  if (!validId(id)) return reply(400, { ok: false, error: "bad_request" });
  const folder = body?.kind === "post" ? "posts" : "layouts";
  const repo = env.REPO || DEFAULT_REPO;
  const branch = env.BRANCH || DEFAULT_BRANCH;
  const url = `https://api.github.com/repos/${repo}/contents/community/${folder}/${id}.json`;
  const got = await fetcher(url + "?ref=" + encodeURIComponent(branch), { method: "GET", headers: githubHeaders(env) });
  if (got.status === 404) return reply(404, { ok: false, error: "not_found" });
  if (got.status !== 200) return reply(502, { ok: false, error: "github_" + got.status });
  const meta = await got.json();
  let file;
  try {
    const bin = atob(String(meta.content || "").replace(/\s/g, ""));
    file = JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
  } catch {
    return reply(502, { ok: false, error: "bad_file" });
  }
  const byOwner = typeof key === "string" && key.length > 0 && typeof file.deleteHash === "string" &&
    (await sameSecret(await sha256Hex(key), file.deleteHash));
  const byPanel = typeof code === "string" && !!env.PANEL_CODE && (await sameSecret(code, env.PANEL_CODE));
  const byAdmin = !byOwner && !byPanel && (await isAdmin(body?.adminPassword, env, fetcher));
  if (!byOwner && !byPanel && !byAdmin) return reply(403, { ok: false, error: "bad_key" });
  const del = await fetcher(url, {
    method: "DELETE",
    headers: githubHeaders(env),
    body: JSON.stringify({ message: `Community: 지움 ${String(file.name || id).slice(0, 40)}`, sha: meta.sha, branch }),
  });
  if (del.status !== 200) return reply(502, { ok: false, error: "github_" + del.status });
  return reply(200, { ok: true });
}

export const ADMIN_NAME = "cupompu";

/** 회원 등급: 올린 레이아웃 수로 정한다. 관리자는 따로. */
export function tierOf(uploads, admin = false) {
  if (admin) return "👑 관리자";
  const n = Number.isFinite(uploads) ? uploads : 0;
  if (n >= 30) return "🏆 장인";
  if (n >= 10) return "🌳 활동가";
  if (n >= 3) return "🌿 일반";
  return "🌱 새싹";
}

export function adminHash(password) {
  return sha256Hex("controlpad-admin:" + password);
}

const ADMIN_PATH = "community/admin.json";

/** 저장소의 관리자 비밀번호 해시를 읽는다. { hash, sha } 또는 null. */
async function readAdmin(env, fetcher) {
  const repo = env.REPO || DEFAULT_REPO;
  const res = await fetcher(`https://api.github.com/repos/${repo}/contents/${ADMIN_PATH}?ref=${encodeURIComponent(env.BRANCH || DEFAULT_BRANCH)}`,
    { method: "GET", headers: githubHeaders(env) });
  if (res.status !== 200) return null;
  const meta = await res.json();
  try {
    const bin = atob(String(meta.content || "").replace(/\s/g, ""));
    const o = JSON.parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
    return typeof o.hash === "string" ? { hash: o.hash, sha: meta.sha } : null;
  } catch {
    return null;
  }
}

/** 관리자 비밀번호가 맞는지. */
async function isAdmin(password, env, fetcher) {
  if (typeof password !== "string" || password.length === 0) return false;
  const admin = await readAdmin(env, fetcher);
  return !!admin && (await sameSecret(await adminHash(password), admin.hash));
}

async function adminLogin(request, env, fetcher) {
  if (!env.GITHUB_TOKEN) return reply(500, { ok: false, error: "relay_not_configured" });
  const body = await request.json().catch(() => null);
  if (!(await isAdmin(body?.password, env, fetcher))) return reply(403, { ok: false, error: "bad_password" });
  return reply(200, { ok: true, tier: tierOf(0, true) });
}

/** 관리자 비밀번호 바꾸기: 지금 비밀번호가 맞아야 하고, 새 비밀번호는 4자 이상. */
async function adminPassword(request, env, fetcher) {
  if (!env.GITHUB_TOKEN) return reply(500, { ok: false, error: "relay_not_configured" });
  const body = await request.json().catch(() => null);
  const next = body?.newPassword;
  if (typeof next !== "string" || next.length < 4 || next.length > 64) return reply(400, { ok: false, error: "weak_password" });
  const admin = await readAdmin(env, fetcher);
  if (!admin || !(await sameSecret(await adminHash(String(body?.password ?? "")), admin.hash))) return reply(403, { ok: false, error: "bad_password" });
  const repo = env.REPO || DEFAULT_REPO;
  const res = await fetcher(`https://api.github.com/repos/${repo}/contents/${ADMIN_PATH}`, {
    method: "PUT",
    headers: githubHeaders(env),
    body: JSON.stringify({
      message: "Community: 관리자 비밀번호 변경",
      branch: env.BRANCH || DEFAULT_BRANCH,
      sha: admin.sha,
      content: base64Utf8(JSON.stringify({ hash: await adminHash(next) })),
    }),
  });
  if (res.status !== 200 && res.status !== 201) return reply(502, { ok: false, error: "github_" + res.status });
  return reply(200, { ok: true });
}

/** 글 내용: 줄바꿈은 남기고 나머지 제어 문자만 지운다. */
export function cleanBody(s, max) {
  if (typeof s !== "string") return "";
  return s.replace(/\r\n?/g, "\n").replace(/[\u0000-\u0009\u000b-\u001f\u007f]/g, " ").trim().slice(0, max);
}

/** 커뮤니티 글을 검사하고 저장할 내용을 만든다. 안 되면 { error }. */
export function postEntry(body, now = new Date(), rand = Math.random) {
  const title = cleanText(body?.title, 60);
  const text = cleanBody(body?.text, 2000);
  const author = cleanText(body?.author, 20) || "익명";
  if (!title) return { error: "no_title" };
  if (!text) return { error: "no_text" };
  const stamp = now.toISOString().replace(/[-:]/g, "").replace(/\..*/, "");
  const id = stamp + "-" + Math.floor(rand() * 0xffffff).toString(16).padStart(6, "0");
  return { id, path: `community/posts/${id}.json`, file: { id, title, text, author, created: now.toISOString() } };
}

async function communityPost(request, env, fetcher) {
  if (!env.GITHUB_TOKEN) return reply(500, { ok: false, error: "relay_not_configured" });
  const body = await request.json().catch(() => null);
  if (!body) return reply(400, { ok: false, error: "bad_request" });
  const entry = postEntry(body);
  if (entry.error) return reply(400, { ok: false, error: entry.error });
  const wantsAdmin = entry.file.author.toLowerCase() === ADMIN_NAME;
  const admin = wantsAdmin && (await isAdmin(body?.adminPassword, env, fetcher));
  if (wantsAdmin && !admin) return reply(403, { ok: false, error: "admin_only_name" });
  entry.file.tier = tierOf(Number(body?.uploads) || 0, admin);
  const key = newKey();
  entry.file.deleteHash = await sha256Hex(key);
  const repo = env.REPO || DEFAULT_REPO;
  const res = await fetcher(`https://api.github.com/repos/${repo}/contents/${entry.path}`, {
    method: "PUT",
    headers: githubHeaders(env),
    body: JSON.stringify({
      message: `Community 글: ${entry.file.title} (${entry.file.author})`,
      branch: env.BRANCH || DEFAULT_BRANCH,
      content: base64Utf8(JSON.stringify(entry.file)),
    }),
  });
  if (res.status !== 201 && res.status !== 200) return reply(502, { ok: false, error: "github_" + res.status });
  return reply(200, { ok: true, id: entry.id, key });
}

async function community(request, env, fetcher) {
  if (!env.GITHUB_TOKEN) return reply(500, { ok: false, error: "relay_not_configured" });
  let body;
  try {
    body = await request.json();
  } catch {
    return reply(400, { ok: false, error: "bad_request" });
  }
  const entry = communityEntry(body);
  if (entry.error) return reply(400, { ok: false, error: entry.error });
  // 관리자 닉네임은 관리자 비밀번호가 맞을 때만
  const wantsAdmin = entry.file.author.toLowerCase() === ADMIN_NAME;
  const admin = wantsAdmin && (await isAdmin(body?.adminPassword, env, fetcher));
  if (wantsAdmin && !admin) return reply(403, { ok: false, error: "admin_only_name" });
  entry.file.tier = tierOf(Number(body?.uploads) || 0, admin);
  const key = newKey();
  entry.file.deleteHash = await sha256Hex(key);       // 열쇠 자체는 저장하지 않는다
  const repo = env.REPO || DEFAULT_REPO;
  const res = await fetcher(`https://api.github.com/repos/${repo}/contents/${entry.path}`, {
    method: "PUT",
    headers: {
      authorization: `Bearer ${env.GITHUB_TOKEN}`,
      accept: "application/vnd.github+json",
      "x-github-api-version": "2022-11-28",
      "user-agent": "controlpad-panel-relay",
      "content-type": "application/json",
    },
    body: JSON.stringify({
      message: `Community: ${entry.file.name} (${entry.file.author})`,
      branch: env.BRANCH || DEFAULT_BRANCH,
      content: base64Utf8(JSON.stringify(entry.file)),
    }),
  });
  if (res.status !== 201 && res.status !== 200) return reply(502, { ok: false, error: "github_" + res.status });
  return reply(200, { ok: true, id: entry.id, key });
}

function reply(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

export async function handle(request, env, fetcher = fetch) {
  if (request.method === "GET") return new Response("Control Pad panel relay");
  if (request.method !== "POST") return reply(405, { ok: false, error: "method" });
  const path = new URL(request.url).pathname.replace(/\/+$/, "");
  if (path === "/community") return community(request, env, fetcher);
  if (path === "/community/delete") return communityDelete(request, env, fetcher);
  if (path === "/community/post") return communityPost(request, env, fetcher);
  if (path === "/community/admin/login") return adminLogin(request, env, fetcher);
  if (path === "/community/admin/password") return adminPassword(request, env, fetcher);
  if (!env.PANEL_CODE || !env.GITHUB_TOKEN) return reply(500, { ok: false, error: "relay_not_configured" });

  let body;
  try {
    body = await request.json();
  } catch {
    return reply(400, { ok: false, error: "bad_request" });
  }
  const { code, kind, target } = body ?? {};
  if (typeof code !== "string" || !(await sameSecret(code, env.PANEL_CODE))) {
    return reply(403, { ok: false, error: "bad_code" });
  }
  if (!validRequest(kind, target)) return reply(400, { ok: false, error: "bad_request" });

  const repo = env.REPO || DEFAULT_REPO;
  const res = await fetcher(`https://api.github.com/repos/${repo}/actions/workflows/panel.yml/dispatches`, {
    method: "POST",
    headers: {
      authorization: `Bearer ${env.GITHUB_TOKEN}`,
      accept: "application/vnd.github+json",
      "x-github-api-version": "2022-11-28",
      "user-agent": "controlpad-panel-relay",
      "content-type": "application/json",
    },
    body: JSON.stringify({ ref: env.BRANCH || DEFAULT_BRANCH, inputs: { kind, target } }),
  });
  if (res.status !== 204) return reply(502, { ok: false, error: "github_" + res.status });
  return reply(200, { ok: true });
}

export default {
  fetch(request, env) {
    return handle(request, env);
  },
};
