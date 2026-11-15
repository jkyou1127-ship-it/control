import assert from "node:assert/strict";
import test from "node:test";
import { handle, sameSecret, validRequest } from "./worker.js";

const env = { PANEL_CODE: "panel-code", GITHUB_TOKEN: "token" };
const post = (body) => new Request("https://relay.example/", { method: "POST", body: JSON.stringify(body) });

test("request shapes", () => {
  assert.ok(validRequest("build", "1.21"));
  assert.ok(validRequest("build", "BETA 1.9"));
  assert.ok(validRequest("lab", "press-pop"));
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
