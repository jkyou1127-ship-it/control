import assert from "node:assert/strict";
import test from "node:test";
import { COMMUNITY_MAX, applyIndex, indexLine, cleanBody, postEntry, adminHash, base64Utf8, cleanText, communityEntry, handle, sameSecret, sha256Hex, tierOf, validId, validRequest } from "./worker.js";

const env = { PANEL_CODE: "panel-code", GITHUB_TOKEN: "token" };
const post = (body) => new Request("https://relay.example/", { method: "POST", body: JSON.stringify(body) });

test("request shapes", () => {
  assert.ok(validRequest("build", "1.21"));
  assert.ok(validRequest("build", "BETA 1.9"));
  assert.ok(validRequest("lab", "press-pop"));
  assert.ok(validRequest("hide", "kps"));
  assert.ok(validRequest("restore", "kps"));
  assert.equal(validRequest("hide", "1.21; rm"), false);
  for (const [k, t] of [["build", "1.21; rm"], ["build", ""], ["lab", "KPS"], ["lab", "../x"], ["other", "1.0"], ["build", 5]]) {
    assert.equal(validRequest(k, t), false, `${k} ${t}`);
  }
});

test("secret comparison", async () => {
  assert.ok(await sameSecret("abc", "abc"));
  assert.equal(await sameSecret("abc", "abd"), false);
  assert.equal(await sameSecret("abc", "abcd"), false);
});

test("a valid request dispatches the workflow with the token and nothing from the caller but kind and target", async () => {
  const calls = [];
  const res = await handle(post({ code: "panel-code", kind: "build", target: "1.21", extra: "ignored" }), env, async (url, init) => {
    calls.push({ url, init });
    return new Response(null, { status: 204 });
  });
  assert.deepEqual(await res.json(), { ok: true });
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, "https://api.github.com/repos/jkyou1127-ship-it/control/actions/workflows/panel.yml/dispatches");
  assert.equal(calls[0].init.headers.authorization, "Bearer token");
  assert.deepEqual(JSON.parse(calls[0].init.body), {
    ref: "claude/vibrant-ramanujan-pdzsly",
    inputs: { kind: "build", target: "1.21" },
  });
});

test("wrong code, bad shape and missing setup never reach GitHub", async () => {
  const never = async () => assert.fail("GitHub was called");
  const cases = [
    [post({ code: "wrong", kind: "build", target: "1.21" }), env, 403, "bad_code"],
    [post({ kind: "build", target: "1.21" }), env, 403, "bad_code"],
    [post({ code: "panel-code", kind: "lab", target: "$(x)" }), env, 400, "bad_request"],
    [new Request("https://relay.example/", { method: "POST", body: "not json" }), env, 400, "bad_request"],
    [post({ code: "panel-code", kind: "build", target: "1.21" }), {}, 500, "relay_not_configured"],
    [new Request("https://relay.example/", { method: "PUT" }), env, 405, "method"],
  ];
  for (const [req, e, status, error] of cases) {
    const res = await handle(req, e, never);
    assert.equal(res.status, status);
    assert.equal((await res.json()).error, error);
  }
  assert.equal((await handle(new Request("https://relay.example/"), env, never)).status, 200);
});

test("a GitHub failure is reported, not hidden", async () => {
  const res = await handle(post({ code: "panel-code", kind: "lab", target: "kps" }), env, async () => new Response("no", { status: 401 }));
  assert.equal(res.status, 502);
  assert.equal((await res.json()).error, "github_401");
});

const layoutText = JSON.stringify({ format: "controlpad-layout", version: 1, id: "x", name: "pad", buttons: [{ x: 0 }, { x: 0.5 }], images: {} });

test("community entries are checked and cleaned", () => {
  const now = new Date("2026-11-17T08:30:00Z");
  const e = communityEntry({ name: " 내 패드\n", author: "누군가", layout: layoutText }, now, () => 0.5);
  assert.equal(e.error, undefined);
  assert.equal(e.id, "20261117T083000-7fffff");
  assert.equal(e.path, "community/layouts/20261117T083000-7fffff.json");
  assert.equal(e.file.name, "내 패드");
  assert.equal(e.file.author, "누군가");
  assert.equal(e.file.buttons, 2);
  assert.equal(communityEntry({ name: "a", layout: layoutText }).file.author, "익명");
  assert.equal(communityEntry({ name: "", layout: layoutText }).error, "no_name");
  assert.equal(communityEntry({ name: "a", layout: "{" }).error, "bad_layout");
  assert.equal(communityEntry({ name: "a", layout: JSON.stringify({ format: "other", buttons: [] }) }).error, "bad_layout");
  assert.equal(communityEntry({ name: "a", layout: "x".repeat(COMMUNITY_MAX + 1) }).error, "too_big");
  assert.equal(cleanText("a\u0000b", 10), "a b");
  assert.equal(cleanText("가".repeat(50), 40).length, 40);
  assert.equal(Buffer.from(base64Utf8("한글 ok"), "base64").toString("utf8"), "한글 ok");
});

test("community uploads go to the contents API without the panel code", async () => {
  const calls = [];
  const req = new Request("https://relay.example/community", { method: "POST", body: JSON.stringify({ name: "패드", author: "나", layout: layoutText }) });
  const res = await handle(req, env, async (url, init) => { calls.push({ url, init }); return new Response("{}", { status: 201 }); });
  const out = await res.json();
  assert.equal(out.ok, true);
  assert.match(out.key, /^[0-9a-f]{32}$/);
  assert.match(calls[0].url, /^https:\/\/api\.github\.com\/repos\/jkyou1127-ship-it\/control\/contents\/community\/layouts\/\d{8}T\d{6}-[0-9a-f]{6}\.json$/);
  assert.equal(calls[0].init.method, "PUT");
  const sent = JSON.parse(calls[0].init.body);
  assert.equal(sent.branch, "claude/vibrant-ramanujan-pdzsly");
  const file = JSON.parse(Buffer.from(sent.content, "base64").toString("utf8"));
  assert.equal(file.name, "패드");
  assert.equal(file.deleteHash, await sha256Hex(out.key));        // 열쇠 대신 해시만 저장
  assert.equal(JSON.stringify(file).includes(out.key), false);
  assert.equal(file.layout.format, "controlpad-layout");
  // 잘못된 레이아웃은 GitHub 까지 가지 않는다
  const bad = await handle(new Request("https://relay.example/community", { method: "POST", body: JSON.stringify({ name: "x", layout: "nope" }) }), env, async () => { throw new Error("called"); });
  assert.equal(bad.status, 400);
});

test("community delete needs the key or the panel code", async () => {
  const key = "0123456789abcdef0123456789abcdef";
  const stored = { id: "20261117T083000-abcdef", name: "패드", deleteHash: await sha256Hex(key), layout: { format: "controlpad-layout", buttons: [] } };
  const content = Buffer.from(JSON.stringify(stored), "utf8").toString("base64");
  const run = async (body) => {
    const calls = [];
    const res = await handle(new Request("https://relay.example/community/delete", { method: "POST", body: JSON.stringify(body) }), env, async (url, init) => {
      calls.push({ url, init });
      if (init.method === "GET") return new Response(JSON.stringify({ sha: "abc123", content }), { status: 200 });
      return new Response("{}", { status: 200 });
    });
    return { status: res.status, calls };
  };
  const ok = await run({ id: stored.id, key });
  assert.equal(ok.status, 200);
  assert.equal(ok.calls[1].init.method, "DELETE");
  assert.match(ok.calls[1].url, /contents\/community\/layouts\/20261117T083000-abcdef\.json$/);
  assert.equal(JSON.parse(ok.calls[1].init.body).sha, "abc123");
  assert.equal((await run({ id: stored.id, key: "wrong" })).status, 403);
  assert.equal((await run({ id: stored.id, code: "panel-code" })).status, 200);    // 관리용: 평가단 코드
  assert.equal((await run({ id: stored.id, code: "nope" })).status, 403);
  assert.equal((await run({ id: "../x", key })).status, 400);
  assert.ok(validId("abc_1-2") && !validId("a/b"));
});

test("tiers and the admin account", async () => {
  assert.equal(tierOf(0), "🌱 새싹");
  assert.equal(tierOf(3), "🌿 일반");
  assert.equal(tierOf(10), "🌳 활동가");
  assert.equal(tierOf(30), "🏆 장인");
  assert.equal(tierOf(0, true), "매니저");
  // 저장소에는 관리자 비밀번호의 해시만 있다
  const fs = await import("node:fs");
  const stored = JSON.parse(fs.readFileSync(new URL("../community/admin.json", import.meta.url), "utf8"));
  assert.match(stored.hash, /^[0-9a-f]{64}$/);          // 비밀번호를 바꾸면 달라지므로 해시 모양만 본다

  let adminFile = { hash: await adminHash("0000") };
  const puts = [];
  const fetcher = async (url, init) => {
    if (init.method === "GET" && url.includes("community/admin.json")) {
      return new Response(JSON.stringify({ sha: "s1", content: Buffer.from(JSON.stringify(adminFile)).toString("base64") }), { status: 200 });
    }
    puts.push({ url, init });
    return new Response("{}", { status: 201 });
  };
  const call = async (path, body) => {
    const res = await handle(new Request("https://relay.example" + path, { method: "POST", body: JSON.stringify(body) }), env, fetcher);
    return { status: res.status, body: await res.json() };
  };
  assert.equal((await call("/community/admin/login", { password: "0000" })).status, 200);
  assert.equal((await call("/community/admin/login", { password: "1234" })).status, 403);
  // 관리자 닉네임은 비밀번호 없이 못 쓴다
  assert.equal((await call("/community", { name: "x", author: "CupomPu", layout: layoutText })).body.error, "admin_only_name");
  const up = await call("/community", { name: "x", author: "cupompu", adminPassword: "0000", layout: layoutText, uploads: 99 });
  assert.equal(up.status, 200);
  const lastLayout = () => JSON.parse(Buffer.from(JSON.parse(puts.filter((p) => p.url.includes("/community/layouts/")).at(-1).init.body).content, "base64").toString("utf8"));
  assert.equal(lastLayout().tier, "매니저");
  const user = await call("/community", { name: "x", author: "철수", layout: layoutText, uploads: 4 });
  assert.equal(lastLayout().tier, "🌿 일반");
  assert.equal(user.status, 200);
  // 비밀번호 바꾸기: 4자 이상, 지금 비밀번호가 맞아야
  assert.equal((await call("/community/admin/password", { password: "0000", newPassword: "abc" })).body.error, "weak_password");
  assert.equal((await call("/community/admin/password", { password: "nope", newPassword: "long-enough-1" })).status, 403);
  const ch = await call("/community/admin/password", { password: "0000", newPassword: "long-enough-1" });
  assert.equal(ch.status, 200);
  const saved = JSON.parse(Buffer.from(JSON.parse(puts.at(-1).init.body).content, "base64").toString("utf8"));
  assert.equal(saved.hash, await adminHash("long-enough-1"));
  assert.equal(JSON.stringify(saved).includes("long-enough-1"), false);     // 비밀번호 자체는 저장하지 않는다
});

test("community posts", async () => {
  const now = new Date("2026-11-17T09:00:00Z");
  const e = postEntry({ title: " 안녕\n", text: "첫 줄\r\n둘째 줄\u0000", author: "철수" }, now, () => 0);
  assert.equal(e.path, "community/posts/20261117T090000-000000.json");
  assert.equal(e.file.title, "안녕");
  assert.equal(e.file.text, "첫 줄\n둘째 줄");
  assert.equal(postEntry({ title: "", text: "x" }).error, "no_title");
  assert.equal(postEntry({ title: "x", text: "  " }).error, "no_text");
  assert.equal(cleanBody("a".repeat(3000), 2000).length, 2000);
  const puts = [];
  const res = await handle(new Request("https://relay.example/community/post", { method: "POST", body: JSON.stringify({ title: "t", text: "본문", author: "나", uploads: 3 }) }), env,
    async (url, init) => { puts.push({ url, init }); return new Response("{}", { status: 201 }); });
  const out = await res.json();
  assert.equal(out.ok, true);
  assert.match(puts[0].url, /contents\/community\/posts\/\d{8}T\d{6}-[0-9a-f]{6}\.json$/);
  const file = JSON.parse(Buffer.from(JSON.parse(puts[0].init.body).content, "base64").toString("utf8"));
  assert.equal(file.tier, "🌿 일반");
  assert.equal(file.deleteHash, await sha256Hex(out.key));
  // 글 지우기는 posts 폴더
  const content = Buffer.from(JSON.stringify(file)).toString("base64");
  const calls = [];
  const del = await handle(new Request("https://relay.example/community/delete", { method: "POST", body: JSON.stringify({ id: file.id, key: out.key, kind: "post" }) }), env,
    async (url, init) => { calls.push(url); return init.method === "GET" ? new Response(JSON.stringify({ sha: "s", content }), { status: 200 }) : new Response("{}", { status: 200 }); });
  assert.equal(del.status, 200);
  assert.match(calls[0], /community\/posts\//);
});

test("uploads update the list right away", async () => {
  // 목록 고치기: 같은 id 는 바꾸고, 새것부터
  let idx = applyIndex({ layouts: [{ id: "a", created: "2026-11-16" }] }, "layout", { line: { id: "b", created: "2026-11-17" } });
  assert.deepEqual(idx.layouts.map((e) => e.id), ["b", "a"]);
  idx = applyIndex(idx, "layout", { remove: "a" });
  assert.deepEqual(idx.layouts.map((e) => e.id), ["b"]);
  idx = applyIndex(idx, "post", { line: { id: "p", created: "2026-11-17" } });
  assert.equal(idx.posts.length, 1);
  assert.equal(indexLine("layout", { id: "x", name: "n", author: "a", created: "c", layout: { buttons: [1, 2, 3] } }, 10).buttons, 3);

  // 올리기 → 레이아웃 파일 PUT → 목록 GET → 목록 PUT(충돌 한 번 뒤 성공)
  let index = { version: 1, layouts: [{ id: "old", name: "옛것", created: "2026-11-01T00:00:00Z" }], posts: [] };
  const calls = [];
  let conflicts = 1;
  const fetcher = async (url, init) => {
    calls.push(`${init.method} ${url.replace(/\?.*/, "")}`);
    if (url.includes("community/index.json")) {
      if (init.method === "GET") return new Response(JSON.stringify({ sha: "i1", content: Buffer.from(JSON.stringify(index)).toString("base64") }), { status: 200 });
      if (conflicts-- > 0) return new Response("{}", { status: 409 });
      index = JSON.parse(Buffer.from(JSON.parse(init.body).content, "base64").toString("utf8"));
      return new Response("{}", { status: 200 });
    }
    return new Response("{}", { status: 201 });
  };
  const res = await handle(new Request("https://relay.example/community", { method: "POST", body: JSON.stringify({ name: "새 패드", author: "영희", layout: layoutText }) }), env, fetcher);
  const out = await res.json();
  assert.equal(out.ok, true);
  assert.equal(out.listed, true);
  assert.equal(out.line.name, "새 패드");
  assert.deepEqual(index.layouts.map((e) => e.name), ["새 패드", "옛것"]);
  assert.ok(calls.filter((c) => c.startsWith("PUT") && c.includes("index.json")).length === 2);   // 충돌 뒤 다시
});
