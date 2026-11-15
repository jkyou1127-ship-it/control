// Control Pad 평가단 중계 서버 (Cloudflare Workers).
//
// 앱의 '실험실 · 평가단' 화면이 여기로 정식 추가 요청을 보낸다. 평가단 코드가 맞으면
// 저장소의 자동화(.github/workflows/panel.yml)를 대신 실행하고, 5분 뒤 정식에 반영된다.
// 평가단은 GitHub 계정이 없어도 된다. 설정 방법은 relay/README.md 에 있다.
//
// 비밀 값(대시보드의 Variables and Secrets 에 넣는다. 코드나 저장소에 적지 않는다):
//   PANEL_CODE    평가단에게 알려 줄 코드
//   GITHUB_TOKEN  이 저장소의 Actions 를 실행할 수 있는 토큰
// 선택 값: REPO (기본 jkyou1127-ship-it/control), BRANCH (기본 claude/vibrant-ramanujan-pdzsly)

const DEFAULT_REPO = "jkyou1127-ship-it/control";
const DEFAULT_BRANCH = "claude/vibrant-ramanujan-pdzsly";

/** 요청의 종류와 대상이 정해진 모양인지. scripts/panel.py 의 검사와 같아야 한다. */
export function validRequest(kind, target) {
  if (typeof target !== "string") return false;
  if (kind === "build") return /^[0-9A-Za-z][0-9A-Za-z. ]{0,19}$/.test(target);
  if (kind === "lab") return /^[a-z0-9][a-z0-9-]{0,39}$/.test(target);
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

function reply(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json; charset=utf-8" } });
}

export async function handle(request, env, fetcher = fetch) {
  if (request.method === "GET") return new Response("Control Pad panel relay");
  if (request.method !== "POST") return reply(405, { ok: false, error: "method" });
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
