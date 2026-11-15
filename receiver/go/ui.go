package main

import (
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"
)

// UI 는 브라우저로 보는 상태 화면이다(http://127.0.0.1:34556). 검은 콘솔 창 대신 연결 상태를 보기 좋게 보여 준다.
const uiPort = 34556

type uiState struct {
	mu        sync.Mutex
	Version   int      `json:"version"`
	Hostname  string   `json:"hostname"`
	Addresses []string `json:"addresses"`
	Port      int      `json:"port"`
	Admin     bool     `json:"admin"`
	Device    string   `json:"device"`
	LastInput int64    `json:"lastInput"` // 유닉스 밀리초. 0 이면 아직 없음
	Presses   int      `json:"presses"`
	Recent    []string `json:"recent"`
	Started   int64    `json:"started"`
}

func (s *uiState) connected(ip string) {
	s.mu.Lock()
	s.Device = ip
	s.mu.Unlock()
}

func (s *uiState) pressed(name string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Presses++
	s.LastInput = time.Now().UnixMilli()
	s.Recent = append(s.Recent, name)
	if len(s.Recent) > 12 {
		s.Recent = s.Recent[len(s.Recent)-12:]
	}
}

func (s *uiState) moved() {
	s.mu.Lock()
	s.LastInput = time.Now().UnixMilli()
	s.mu.Unlock()
}

func (s *uiState) snapshot() []byte {
	s.mu.Lock()
	defer s.mu.Unlock()
	b, _ := json.Marshal(s)
	return b
}

// uiInjector 는 실제 입력을 넣으면서 상태 화면에 보여 줄 기록을 남긴다.
type uiInjector struct {
	inner Injector
	state *uiState
}

func (u uiInjector) Key(usage int, down bool) {
	u.inner.Key(usage, down)
	if down {
		u.state.pressed(keyName(usage))
	}
}
func (u uiInjector) Move(dx, dy int) { u.inner.Move(dx, dy); u.state.moved() }
func (u uiInjector) Button(i int, down bool) {
	u.inner.Button(i, down)
	if down {
		u.state.pressed([]string{"좌클릭", "우클릭", "휠클릭"}[i])
	}
}
func (u uiInjector) Wheel(steps int) { u.inner.Wheel(steps); u.state.moved() }

// keyName 은 HID usage 를 사람이 읽을 이름으로 바꾼다.
func keyName(u int) string {
	switch {
	case u >= 0x04 && u <= 0x1D:
		return string(rune('A' + u - 0x04))
	case u >= 0x1E && u <= 0x26:
		return string(rune('1' + u - 0x1E))
	case u == 0x27:
		return "0"
	case u >= 0x3A && u <= 0x45:
		return fmt.Sprintf("F%d", u-0x39)
	case u >= 0x59 && u <= 0x61:
		return fmt.Sprintf("Num %d", u-0x58)
	}
	names := map[int]string{
		0x28: "Enter", 0x29: "Esc", 0x2A: "⌫", 0x2B: "Tab", 0x2C: "Space", 0x2D: "-", 0x2E: "=",
		0x2F: "[", 0x30: "]", 0x31: "\\", 0x33: ";", 0x34: "'", 0x35: "`", 0x36: ",", 0x37: ".", 0x38: "/",
		0x39: "Caps", 0x4C: "Del", 0x49: "Ins", 0x4A: "Home", 0x4D: "End", 0x4B: "PgUp", 0x4E: "PgDn",
		0x50: "←", 0x4F: "→", 0x52: "↑", 0x51: "↓", 0x46: "PrtSc", 0x62: "Num 0", 0x63: "Num .",
		0x90: "한/영", 0x91: "한자",
		0xE0: "Ctrl", 0xE1: "Shift", 0xE2: "Alt", 0xE3: "Win", 0xE4: "R Ctrl", 0xE5: "R Shift", 0xE6: "R Alt",
	}
	if n, ok := names[u]; ok {
		return n
	}
	return fmt.Sprintf("0x%02X", u)
}

// startUI 는 상태 화면을 띄운다. 포트를 열 수 없으면 조용히 건너뛰고 빈 문자열을 돌려준다.
func startUI(state *uiState) string {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", uiPort))
	if err != nil {
		return ""
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(uiPage))
	})
	mux.HandleFunc("/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(state.snapshot())
	})
	go http.Serve(ln, mux)
	return fmt.Sprintf("http://127.0.0.1:%d/", uiPort)
}

const uiPage = `<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Control Pad 수신 프로그램</title>
<style>
:root{--bg:#0f1115;--card:#181b22;--line:#262b35;--text:#e8ebf2;--sub:#9aa3b5;--ok:#4ade80;--warn:#fbbf24;--accent:#4fc3f7}
@media (prefers-color-scheme: light){:root{--bg:#f3f5f9;--card:#fff;--line:#e3e7ee;--text:#151821;--sub:#5b6476;--ok:#16a34a;--warn:#b45309;--accent:#0288d1}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 "Malgun Gothic","Segoe UI",system-ui,sans-serif}
.wrap{max-width:860px;margin:0 auto;padding:28px 20px}
header{display:flex;align-items:center;gap:14px;margin-bottom:22px}
.logo{width:44px;height:44px;border-radius:12px;background:var(--accent);display:grid;place-items:center;color:#05202c;font-weight:800;font-size:20px}
h1{font-size:22px;margin:0}.ver{color:var(--sub);font-size:13px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px}
.card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:18px}
.label{color:var(--sub);font-size:13px;margin-bottom:6px}
.big{font-size:26px;font-weight:700;letter-spacing:.3px}
.dot{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:8px;background:var(--sub);vertical-align:middle}
.on .dot{background:var(--ok);box-shadow:0 0 10px var(--ok)}
.addr{font:600 22px/1.4 Consolas,monospace;padding:6px 0}
.keys{display:flex;flex-wrap:wrap;gap:6px;min-height:34px}
.key{padding:5px 10px;border-radius:8px;background:var(--line);font-weight:600;font-size:14px}
.key.new{background:var(--accent);color:#05202c}
.warn{border-color:var(--warn)}.warn .label{color:var(--warn)}
ol{margin:6px 0 0;padding-left:20px;color:var(--sub)}li{margin:3px 0}
.full{grid-column:1/-1}
</style></head><body><div class="wrap">
<header><div class="logo">C</div><div><h1>Control Pad 수신 프로그램</h1><div class="ver" id="ver"></div></div></header>
<div class="grid">
 <div class="card" id="conn"><div class="label">태블릿 연결</div><div class="big"><span class="dot"></span><span id="connText">기다리는 중</span></div><div class="ver" id="last"></div></div>
 <div class="card"><div class="label">누른 횟수</div><div class="big" id="presses">0</div><div class="ver" id="uptime"></div></div>
 <div class="card full"><div class="label">이 PC의 주소 (태블릿 앱이 찾지 못하면 직접 입력)</div><div id="addrs"></div><div class="ver" id="port"></div></div>
 <div class="card full"><div class="label">최근 입력</div><div class="keys" id="recent"></div></div>
 <div class="card full warn" id="adminWarn" hidden><div class="label">관리자 권한 없이 실행 중</div>
  관리자 권한으로 실행된 게임이나 프로그램에는 입력이 들어가지 않습니다. 이 프로그램을 닫고 다시 실행해 관리자 권한 요청에 '예'를 누르세요.</div>
 <div class="card full"><div class="label">연결 방법</div><ol>
  <li>태블릿과 이 PC를 같은 공유기(Wi-Fi)에 연결합니다.</li>
  <li>태블릿 앱의 <b>설정 → Wi-Fi / USB 테더링 → PC 자동으로 찾기</b>를 누릅니다.</li>
  <li>찾지 못하면 위 주소를 직접 입력하고 <b>저장하고 연결 확인</b>을 누릅니다.</li>
  <li>Windows 방화벽이 물으면 허용합니다. 이 창은 닫아도 되고, 검은 창을 닫으면 끝납니다.</li></ol></div>
</div></div>
<script>
let seen=0;
function ago(ms){const s=Math.round((Date.now()-ms)/1000);return s<2?'방금':s<60?s+'초 전':Math.round(s/60)+'분 전'}
async function tick(){try{const r=await fetch('/status');const d=await r.json();
 document.getElementById('ver').textContent='버전 '+d.version+' · '+d.hostname;
 const live=d.lastInput&&Date.now()-d.lastInput<5000;
 document.getElementById('conn').classList.toggle('on',!!d.device);
 document.getElementById('connText').textContent=d.device?('연결됨 · '+d.device):'기다리는 중';
 document.getElementById('last').textContent=d.lastInput?('마지막 입력 '+ago(d.lastInput)):'아직 입력이 없습니다';
 document.getElementById('presses').textContent=d.presses.toLocaleString();
 const up=Math.round((Date.now()-d.started)/1000);document.getElementById('uptime').textContent='실행한 지 '+Math.floor(up/60)+'분 '+(up%60)+'초';
 document.getElementById('addrs').innerHTML=(d.addresses||[]).map(a=>'<div class="addr">'+a+'</div>').join('')||'<div class="ver">네트워크 주소를 찾지 못했습니다</div>';
 document.getElementById('port').textContent='포트 '+d.port;
 document.getElementById('adminWarn').hidden=d.admin;
 const rec=d.recent||[];const fresh=d.presses-seen;seen=d.presses;
 document.getElementById('recent').innerHTML=rec.map((k,i)=>'<span class="key'+(i>=rec.length-Math.min(fresh,rec.length)?' new':'')+'">'+k.replace(/</g,'&lt;')+'</span>').join('')||'<span class="ver">아직 없음</span>';
}catch(e){document.getElementById('connText').textContent='수신 프로그램이 꺼졌습니다'}}
tick();setInterval(tick,500);
</script></body></html>`
