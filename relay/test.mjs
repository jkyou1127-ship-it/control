import assert from "node:assert/strict";
import test from "node:test";
import { COMMUNITY_MAX, base64Utf8, cleanText, communityEntry, handle, sameSecret, validRequest } from "./worker.js";

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
  assert.match(calls[0].url, /^https:\/\/api\.github\.com\/repos\/jkyou1127-ship-it\/control\/contents\/community\/layouts\/\d{8}T\d{6}-[0-9a-f]{6}\.json$/);
  assert.equal(calls[0].init.method, "PUT");
  const sent = JSON.parse(calls[0].init.body);
  assert.equal(sent.branch, "claude/vibrant-ramanujan-pdzsly");
  const file = JSON.parse(Buffer.from(sent.content, "base64").toString("utf8"));
  assert.equal(file.name, "패드");
  assert.equal(file.layout.format, "controlpad-layout");
  // 잘못된 레이아웃은 GitHub 까지 가지 않는다
  const bad = await handle(new Request("https://relay.example/community", { method: "POST", body: JSON.stringify({ name: "x", layout: "nope" }) }), env, async () => { throw new Error("called"); });
  assert.equal(bad.status, 400);
});
