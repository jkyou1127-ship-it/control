//go:build windows

package main

// 수신 프로그램 창. 브라우저를 열지 않고 Windows 창을 직접 그린다(GDI).
// 창은 0.2초마다 상태(uiState)를 읽어 다시 그리고, 버튼은 그린 자리를 기억해 두었다가 클릭을 맞춰 본다.

import (
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
	"unsafe"
)

func init() { runtime.LockOSThread() } // 창은 만든 스레드에서만 메시지를 받는다

var (
	gdi32 = syscall.NewLazyDLL("gdi32.dll")

	pRegisterClassEx   = user32.NewProc("RegisterClassExW")
	pCreateWindowEx    = user32.NewProc("CreateWindowExW")
	pDefWindowProc     = user32.NewProc("DefWindowProcW")
	pGetMessage        = user32.NewProc("GetMessageW")
	pTranslateMessage  = user32.NewProc("TranslateMessage")
	pDispatchMessage   = user32.NewProc("DispatchMessageW")
	pPostQuitMessage   = user32.NewProc("PostQuitMessage")
	pBeginPaint        = user32.NewProc("BeginPaint")
	pEndPaint          = user32.NewProc("EndPaint")
	pInvalidateRect    = user32.NewProc("InvalidateRect")
	pSetTimer          = user32.NewProc("SetTimer")
	pGetClientRect     = user32.NewProc("GetClientRect")
	pLoadCursor        = user32.NewProc("LoadCursorW")
	pLoadIcon          = user32.NewProc("LoadIconW")
	pSetCursor         = user32.NewProc("SetCursor")
	pSetWindowPos      = user32.NewProc("SetWindowPos")
	pAdjustWindowRect  = user32.NewProc("AdjustWindowRectEx")
	pShowWindow        = user32.NewProc("ShowWindow")
	pMessageBox        = user32.NewProc("MessageBoxW")
	pFillRect          = user32.NewProc("FillRect")
	pDrawText          = user32.NewProc("DrawTextW")
	pTrackMouseEvent   = user32.NewProc("TrackMouseEvent")
	pOpenClipboard     = user32.NewProc("OpenClipboard")
	pEmptyClipboard    = user32.NewProc("EmptyClipboard")
	pSetClipboardData  = user32.NewProc("SetClipboardData")
	pCloseClipboard    = user32.NewProc("CloseClipboard")
	pGetDpiForWindow   = user32.NewProc("GetDpiForWindow")
	pSetDpiAwareness   = user32.NewProc("SetProcessDpiAwarenessContext")
	pSetProcessDPIAwar = user32.NewProc("SetProcessDPIAware")
	pRegisterHotKey    = user32.NewProc("RegisterHotKey")
	pSetWindowLongPtr  = user32.NewProc("SetWindowLongPtrW")
	pMonitorFromWindow = user32.NewProc("MonitorFromWindow")
	pGetMonitorInfo    = user32.NewProc("GetMonitorInfoW")
	pMessageBeep       = user32.NewProc("MessageBeep")
	pSetLayeredAttr    = user32.NewProc("SetLayeredWindowAttributes")
	pPostMessage       = user32.NewProc("PostMessageW")
	pGetWindowLongPtr  = user32.NewProc("GetWindowLongPtrW")
	pGetWindowRect     = user32.NewProc("GetWindowRect")
	pGetModuleHandle   = kernel32.NewProc("GetModuleHandleW")
	pGlobalAlloc       = kernel32.NewProc("GlobalAlloc")
	pGlobalLock        = kernel32.NewProc("GlobalLock")
	pGlobalUnlock      = kernel32.NewProc("GlobalUnlock")
	pMoveMemory        = kernel32.NewProc("RtlMoveMemory")

	pCreateCompatibleDC  = gdi32.NewProc("CreateCompatibleDC")
	pCreateCompatibleBmp = gdi32.NewProc("CreateCompatibleBitmap")
	pSelectObject        = gdi32.NewProc("SelectObject")
	pDeleteObject        = gdi32.NewProc("DeleteObject")
	pDeleteDC            = gdi32.NewProc("DeleteDC")
	pBitBlt              = gdi32.NewProc("BitBlt")
	pCreateSolidBrush    = gdi32.NewProc("CreateSolidBrush")
	pCreatePen           = gdi32.NewProc("CreatePen")
	pRoundRect           = gdi32.NewProc("RoundRect")
	pCreateFont          = gdi32.NewProc("CreateFontW")
	pSetTextColor        = gdi32.NewProc("SetTextColor")
	pSetBkMode           = gdi32.NewProc("SetBkMode")
	pGetTextExtent       = gdi32.NewProc("GetTextExtentPoint32W")
)

type rect struct{ l, t, r, b int32 }

type wndClassEx struct {
	size       uint32
	style      uint32
	wndProc    uintptr
	clsExtra   int32
	wndExtra   int32
	instance   uintptr
	icon       uintptr
	cursor     uintptr
	background uintptr
	menuName   *uint16
	className  *uint16
	iconSm     uintptr
}

type msg struct {
	hwnd    uintptr
	message uint32
	wParam  uintptr
	lParam  uintptr
	time    uint32
	x, y    int32
	private uint32
}

type paintStruct struct {
	hdc       uintptr
	erase     int32
	paint     rect
	restore   int32
	incUpdate int32
	reserved  [32]byte
}

type trackMouse struct {
	size  uint32
	flags uint32
	hwnd  uintptr
	hover uint32
}

const (
	style = 0x00C00000 | 0x00080000 | 0x00020000 // WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX

	wmDestroy    = 0x0002
	wmPaint      = 0x000F
	wmEraseBkgnd = 0x0014
	wmSetCursor  = 0x0020
	wmTimer      = 0x0113
	wmMouseMove  = 0x0200
	wmLButtonUp  = 0x0202
	wmMouseLeave = 0x02A3
	wmHotKey     = 0x0312
	wmClose      = 0x0010

	dtCenter     = 0x1
	dtVCenter    = 0x4
	dtWordBreak  = 0x10
	dtSingleLine = 0x20
	dtCalcRect   = 0x400
	dtNoPrefix   = 0x800
	dtEllipsis   = 0x8000

	idCopy = iota + 1
	idPause
	idRelease
	idTop
	idAuto
	idThemePrev
	idThemeNext
	idReset
	idFull
	idBeep
	idMousePrev
	idMouseNext
	idMini
	idUpdate
	idOpacityPrev
	idOpacityNext
	idLock
)

const (
	wmKeyDown      = 0x0100
	wmUpdateResult = 0x8001 // WM_APP+1: 업데이트 확인 결과(wParam: 0 최신, 1 새 버전, 2 실패 / lParam: 새 버전 번호)
)

// 색(0x00BBGGRR)
func rgb(c uint32) uintptr { return uintptr((c>>16)&0xFF | (c & 0xFF00) | (c&0xFF)<<16) }

// 지금 테마의 색들. useTheme 이 바꾼다.
var cBg, cCard, cCardHover, cLine, cText, cSub, cOk, cWarn, cAccent, cAccentDark, cChip, cBarOld uintptr

func useTheme(t windowTheme) {
	cBg, cCard, cCardHover, cLine = rgb(t.Bg), rgb(t.Card), rgb(t.Hover), rgb(t.Line)
	cText, cSub, cOk, cWarn = rgb(t.Text), rgb(t.Sub), rgb(t.Ok), rgb(t.Warn)
	cAccent, cAccentDark, cChip = rgb(t.Accent), rgb(t.OnAccent), rgb(t.Line)
	cBarOld = rgb(mix(t.Accent, t.Card, 0.45)) // 그래프의 지난 초 막대
}

type hit struct {
	r  rect
	id int
}

type window struct {
	a         *app
	hwnd      uintptr
	scale     float64
	fonts     map[string]uintptr
	hits      []hit
	hover     int
	tracking  bool
	set       settings
	setPath   string
	autostart bool
	toast     string
	toastTill time.Time
	height    int32
	lastKeys  int

	full       bool    // 전체 화면
	normalRect rect    // 전체 화면 전의 창 자리
	baseScale  float64 // 창 모드의 배율(DPI)
	ox, oy     int32   // 전체 화면에서 내용을 가운데 두려고 민 만큼
	contentH   int32   // 마지막으로 그린 내용의 높이
	wasLive    bool    // 지난번에 태블릿이 연결돼 있었는지(알림음용)
}

var win *window

func utf16(s string) *uint16 { p, _ := syscall.UTF16PtrFromString(s); return p }

func (w *window) px(v int) int32 { return int32(float64(v)*w.scale + 0.5) }

func (w *window) font(name string, size int, bold bool) uintptr {
	key := name + string(rune(size)) + map[bool]string{true: "b", false: ""}[bold]
	if f, ok := w.fonts[key]; ok {
		return f
	}
	weight := uintptr(400)
	if bold {
		weight = 700
	}
	f, _, _ := pCreateFont.Call(uintptr(-w.px(size)), 0, 0, 0, weight, 0, 0, 0, 1, 0, 0, 5, 0, uintptr(unsafe.Pointer(utf16(name))))
	w.fonts[key] = f
	return f
}

func (w *window) normal(size int) uintptr { return w.font("Malgun Gothic", size, false) }
func (w *window) bold(size int) uintptr   { return w.font("Malgun Gothic", size, true) }

// ── 그리기 도구 ──

func roundRect(dc uintptr, r rect, radius int32, fill, border uintptr) {
	brush, _, _ := pCreateSolidBrush.Call(fill)
	var pen uintptr
	if border == fill {
		pen, _, _ = pCreatePen.Call(0, 1, fill)
	} else {
		pen, _, _ = pCreatePen.Call(0, 1, border)
	}
	ob, _, _ := pSelectObject.Call(dc, brush)
	op, _, _ := pSelectObject.Call(dc, pen)
	pRoundRect.Call(dc, uintptr(r.l), uintptr(r.t), uintptr(r.r), uintptr(r.b), uintptr(radius*2), uintptr(radius*2))
	pSelectObject.Call(dc, ob)
	pSelectObject.Call(dc, op)
	pDeleteObject.Call(brush)
	pDeleteObject.Call(pen)
}

func circle(dc uintptr, cx, cy, radius int32, fill uintptr) {
	roundRect(dc, rect{cx - radius, cy - radius, cx + radius + 1, cy + radius + 1}, radius, fill, fill)
}

func text(dc uintptr, s string, r rect, font, color uintptr, flags uintptr) {
	pSelectObject.Call(dc, font)
	pSetTextColor.Call(dc, color)
	p, _ := syscall.UTF16FromString(s)
	pDrawText.Call(dc, uintptr(unsafe.Pointer(&p[0])), uintptr(len(p)-1), uintptr(unsafe.Pointer(&r)), flags|dtNoPrefix)
}

func textWidth(dc uintptr, s string, font uintptr) int32 {
	pSelectObject.Call(dc, font)
	p, _ := syscall.UTF16FromString(s)
	var size struct{ cx, cy int32 }
	pGetTextExtent.Call(dc, uintptr(unsafe.Pointer(&p[0])), uintptr(len(p)-1), uintptr(unsafe.Pointer(&size)))
	return size.cx
}

func textHeight(dc uintptr, s string, width int32, font uintptr) int32 {
	r := rect{0, 0, width, 0}
	pSelectObject.Call(dc, font)
	p, _ := syscall.UTF16FromString(s)
	pDrawText.Call(dc, uintptr(unsafe.Pointer(&p[0])), uintptr(len(p)-1), uintptr(unsafe.Pointer(&r)), dtWordBreak|dtCalcRect|dtNoPrefix)
	return r.b
}

// chips 는 키 이름들을 둥근 칸으로 줄줄이 놓는다. draw 가 거짓이면 높이만 잰다.
func (w *window) chips(dc uintptr, items []string, x, y, width int32, highlight int, draw bool) int32 {
	return w.chipsSized(dc, items, x, y, width, highlight, draw, 13, 28)
}

// chipsSized 는 글자 크기와 칸 높이를 정해 chips 를 그린다(미니 모드에서는 크게).
func (w *window) chipsSized(dc uintptr, items []string, x, y, width int32, highlight int, draw bool, size, height int) int32 {
	font := w.bold(size)
	h, gap, padX := w.px(height), w.px(6), w.px(size*3/4+1)
	cx, cy := x, y
	for i, s := range items {
		cw := textWidth(dc, s, font) + padX*2
		if cx+cw > x+width && cx > x {
			cx, cy = x, cy+h+gap
		}
		if draw {
			fill, color := cChip, cText
			if i >= len(items)-highlight {
				fill, color = cAccent, cAccentDark
			}
			roundRect(dc, rect{cx, cy, cx + cw, cy + h}, w.px(8), fill, fill)
			text(dc, s, rect{cx, cy, cx + cw, cy + h}, font, color, dtCenter|dtVCenter|dtSingleLine)
		}
		cx += cw + gap
	}
	return cy + h - y
}

func (w *window) button(dc uintptr, r rect, label string, id int, primary bool) {
	fill, border, color := cCard, cLine, cText
	if w.hover == id {
		fill = cCardHover
	}
	if primary {
		fill, border, color = cAccent, cAccent, cAccentDark
	}
	roundRect(dc, r, w.px(10), fill, border)
	text(dc, label, r, w.bold(14), color, dtCenter|dtVCenter|dtSingleLine)
	w.hits = append(w.hits, hit{r, id})
}

func (w *window) toggle(dc uintptr, r rect, label, sub string, on bool, id int) {
	if w.hover == id {
		roundRect(dc, r, w.px(10), cCardHover, cCardHover)
	}
	text(dc, label, rect{r.l + w.px(12), r.t + w.px(6), r.r - w.px(70), r.t + w.px(26)}, w.bold(14), cText, dtSingleLine)
	text(dc, sub, rect{r.l + w.px(12), r.t + w.px(26), r.r - w.px(70), r.b}, w.normal(12), cSub, dtSingleLine|dtEllipsis)
	sw, sh := w.px(44), w.px(24)
	sx, sy := r.r-w.px(12)-sw, r.t+(r.b-r.t-sh)/2
	track := cChip
	if on {
		track = cAccent
	}
	roundRect(dc, rect{sx, sy, sx + sw, sy + sh}, sh/2, track, track)
	knob := sx + sh/2
	if on {
		knob = sx + sw - sh/2
	}
	knobColor := cText
	if themeAt(w.set.Theme).Light {
		knobColor = rgb(0xFFFFFF) // 밝은 테마에서는 흰 손잡이
	}
	circle(dc, knob, sy+sh/2, sh/2-w.px(3), knobColor)
	w.hits = append(w.hits, hit{r, id})
}

func (w *window) card(dc uintptr, x, y, width, height int32, border uintptr) {
	roundRect(dc, rect{x, y, x + width, y + height}, w.px(14), cCard, border)
}

// ── 화면 ──

func (w *window) paint(dc uintptr, width int32) int32 {
	v := w.a.state.view(time.Now())
	w.hits = w.hits[:0]
	if w.set.Mini {
		return w.paintMini(dc, width, v)
	}
	pad, gap := w.px(20), w.px(12)
	inner := width - pad*2
	y := pad
	pSetBkMode.Call(dc, 1) // TRANSPARENT

	// 머리: 로고와 제목
	logo := w.px(44)
	roundRect(dc, rect{pad, y, pad + logo, y + logo}, w.px(12), cAccent, cAccent)
	text(dc, "C", rect{pad, y, pad + logo, y + logo}, w.bold(22), cAccentDark, dtCenter|dtVCenter|dtSingleLine)
	text(dc, "Control Pad 수신기", rect{pad + logo + w.px(14), y, width - pad, y + w.px(26)}, w.bold(19), cText, dtSingleLine)
	text(dc, "버전 "+strconv.Itoa(v.Version)+"  ·  "+v.Hostname, rect{pad + logo + w.px(14), y + w.px(26), width - pad, y + logo}, w.normal(12), cSub, dtSingleLine|dtEllipsis)
	y += logo + w.px(18)

	// 연결 상태
	h := w.px(78)
	dot, title := cSub, "태블릿을 기다리는 중"
	switch {
	case v.Paused:
		dot, title = cWarn, "입력 일시정지됨"
	case v.Device != "" && v.Live:
		dot, title = cOk, "연결됨  ·  "+v.Device
	case v.Device != "":
		dot, title = cWarn, "연결 끊김  ·  "+v.Device
	}
	w.card(dc, pad, y, inner, h, cLine)
	circle(dc, pad+w.px(26), y+w.px(30), w.px(6), dot)
	text(dc, title, rect{pad + w.px(42), y + w.px(14), pad + inner - w.px(16), y + w.px(46)}, w.bold(18), cText, dtSingleLine|dtEllipsis)
	text(dc, v.LastInput+"  ·  "+v.Uptime, rect{pad + w.px(42), y + w.px(46), pad + inner - w.px(16), y + h - w.px(8)}, w.normal(12), cSub, dtSingleLine|dtEllipsis)
	y += h + gap

	// 숫자 세 칸
	cw := (inner - gap*2) / 3
	h = w.px(72)
	for i, s := range []struct {
		label string
		n     int
	}{{"누른 횟수", v.Presses}, {"초당 입력", v.KPS}, {"최고 초당 입력", v.Peak}} {
		x := pad + int32(i)*(cw+gap)
		w.card(dc, x, y, cw, h, cLine)
		text(dc, s.label, rect{x + w.px(14), y + w.px(10), x + cw - w.px(8), y + w.px(30)}, w.normal(12), cSub, dtSingleLine)
		color := cText
		if i == 1 && s.n > 0 {
			color = cAccent
		}
		text(dc, itoa(s.n), rect{x + w.px(14), y + w.px(30), x + cw - w.px(8), y + h - w.px(8)}, w.bold(22), color, dtSingleLine)
	}
	y += h + gap

	// 최근 1분 입력 그래프(초마다 막대 하나)
	h = w.px(96)
	w.card(dc, pad, y, inner, h, cLine)
	total := 0
	for _, n := range v.History {
		total += n
	}
	text(dc, "최근 1분 입력  ·  "+strconv.Itoa(total)+"번  ·  누적 "+itoa(v.Total)+"번", rect{pad + w.px(16), y + w.px(10), pad + inner - w.px(16), y + w.px(30)}, w.normal(12), cSub, dtSingleLine)
	gx, gy, gw, gh := pad+w.px(16), y+w.px(36), inner-w.px(32), w.px(46)
	most := 1
	for _, n := range v.History {
		if n > most {
			most = n
		}
	}
	bar := gw / int32(len(v.History))
	base := rect{gx, gy + gh - w.px(2), gx + gw, gy + gh}
	roundRect(dc, base, w.px(1), cLine, cLine)
	for i, n := range v.History {
		if n == 0 {
			continue
		}
		bh := int32(float64(gh-w.px(4)) * float64(n) / float64(most))
		if bh < w.px(3) {
			bh = w.px(3)
		}
		x := gx + int32(i)*bar
		color := cAccent
		if i < len(v.History)-1 {
			color = cBarOld
		}
		roundRect(dc, rect{x + 1, gy + gh - bh, x + bar - 1, gy + gh}, w.px(2), color, color)
	}
	y += h + gap

	// 이 PC 주소
	addrs := v.Addresses
	if len(addrs) == 0 {
		addrs = []string{"주소를 찾지 못했습니다"}
	}
	line := w.px(30)
	h = w.px(40) + int32(len(addrs))*line + w.px(30)
	w.card(dc, pad, y, inner, h, cLine)
	text(dc, "이 PC 주소  ·  앱이 찾지 못하면 직접 입력", rect{pad + w.px(16), y + w.px(12), pad + inner - w.px(90), y + w.px(32)}, w.normal(12), cSub, dtSingleLine|dtEllipsis)
	w.button(dc, rect{pad + inner - w.px(80), y + w.px(10), pad + inner - w.px(12), y + w.px(40)}, "복사", idCopy, false)
	for i, a := range addrs {
		ay := y + w.px(42) + int32(i)*line
		text(dc, a, rect{pad + w.px(16), ay, pad + inner - w.px(16), ay + line}, w.font("Consolas", 21, true), cText, dtSingleLine|dtVCenter)
	}
	text(dc, "포트 "+strconv.Itoa(v.Port), rect{pad + w.px(16), y + h - w.px(28), pad + inner, y + h - w.px(8)}, w.normal(12), cSub, dtSingleLine)
	y += h + gap

	// 입력 보기
	cIn := inner - w.px(32)
	down := v.Down
	if len(down) == 0 {
		down = []string{"없음"}
	}
	recent := v.Recent
	if len(recent) == 0 {
		recent = []string{"아직 없음"}
	}
	fresh := v.Presses - w.lastKeys
	if fresh > len(v.Recent) {
		fresh = len(v.Recent)
	}
	w.lastKeys = v.Presses
	hDown := w.chips(dc, down, 0, 0, cIn, 0, false)
	hRecent := w.chips(dc, recent, 0, 0, cIn, 0, false)
	h = w.px(12) + w.px(22) + hDown + w.px(14) + w.px(22) + hRecent + w.px(16)
	w.card(dc, pad, y, inner, h, cLine)
	cy := y + w.px(12)
	text(dc, "지금 누르는 키", rect{pad + w.px(16), cy, pad + inner, cy + w.px(20)}, w.normal(12), cSub, dtSingleLine)
	cy += w.px(22)
	w.chips(dc, down, pad+w.px(16), cy, cIn, 0, true)
	cy += hDown + w.px(14)
	text(dc, "최근 입력", rect{pad + w.px(16), cy, pad + inner, cy + w.px(20)}, w.normal(12), cSub, dtSingleLine)
	cy += w.px(22)
	w.chips(dc, recent, pad+w.px(16), cy, cIn, fresh, true)
	y += h + gap

	// 관리자 권한 안내
	if !v.Admin {
		msg := "관리자 권한으로 실행된 게임에는 입력이 들어가지 않습니다. 이 프로그램을 닫고 다시 켠 뒤 권한 요청에 '예'를 누르세요."
		th := textHeight(dc, msg, inner-w.px(32), w.normal(13))
		h = w.px(40) + th + w.px(12)
		w.card(dc, pad, y, inner, h, cWarn)
		text(dc, "관리자 권한 없이 실행 중", rect{pad + w.px(16), y + w.px(12), pad + inner, y + w.px(34)}, w.bold(14), cWarn, dtSingleLine)
		text(dc, msg, rect{pad + w.px(16), y + w.px(36), pad + inner - w.px(16), y + h}, w.normal(13), cText, dtWordBreak)
		y += h + gap
	}

	// 조작
	bw := (inner - gap) / 2
	bh := w.px(42)
	pauseLabel := "입력 일시정지"
	if v.Paused {
		pauseLabel = "입력 다시 받기"
	}
	w.button(dc, rect{pad, y, pad + bw, y + bh}, pauseLabel, idPause, v.Paused)
	w.button(dc, rect{pad + bw + gap, y, pad + inner, y + bh}, "모든 키 떼기", idRelease, false)
	y += bh + gap
	fullLabel := "⛶  전체 화면 (F11)"
	if w.full {
		fullLabel = "창 모드로 (Esc)"
	}
	w.button(dc, rect{pad, y, pad + bw, y + bh}, "통계 초기화", idReset, false)
	w.button(dc, rect{pad + bw + gap, y, pad + inner, y + bh}, fullLabel, idFull, false)
	y += bh + gap
	w.button(dc, rect{pad, y, pad + bw, y + bh}, "▣  미니 모드", idMini, false)
	w.button(dc, rect{pad + bw + gap, y, pad + inner, y + bh}, "업데이트 확인", idUpdate, false)
	y += bh + gap

	h = w.px(8) + w.px(52)*7 + w.px(8)
	w.card(dc, pad, y, inner, h, cLine)
	ty := y + w.px(8)
	w.toggle(dc, rect{pad + w.px(4), ty, pad + inner - w.px(4), ty + w.px(52)}, "항상 위에 표시", "게임 위에 이 창을 띄워 둡니다", w.set.OnTop, idTop)
	ty += w.px(52)
	w.toggle(dc, rect{pad + w.px(4), ty, pad + inner - w.px(4), ty + w.px(52)}, "Windows 시작 시 자동 실행", "로그인하면 관리자 권한으로 바로 켜집니다", w.autostart, idAuto)
	ty += w.px(52)
	w.toggle(dc, rect{pad + w.px(4), ty, pad + inner - w.px(4), ty + w.px(52)}, "연결 알림음", "태블릿이 연결되거나 끊기면 소리로 알려 줍니다", w.set.Beep, idBeep)
	ty += w.px(52)
	lockNote := "켜면 지금 연결된 태블릿에서 온 입력만 받습니다"
	if w.set.LockIP != "" {
		lockNote = w.set.LockIP + " 에서 온 입력만 받는 중"
	}
	w.toggle(dc, rect{pad + w.px(4), ty, pad + inner - w.px(4), ty + w.px(52)}, "이 태블릿만 받기", lockNote, w.set.LockIP != "", idLock)
	ty += w.px(52)
	// ◀ 값 ▶ 로 고르는 줄
	stepper := func(label, value string, prev, next int) {
		text(dc, label, rect{pad + w.px(16), ty + w.px(6), pad + inner - w.px(180), ty + w.px(26)}, w.bold(14), cText, dtSingleLine)
		text(dc, value, rect{pad + w.px(16), ty + w.px(26), pad + inner - w.px(110), ty + w.px(48)}, w.normal(12), cSub, dtSingleLine|dtEllipsis)
		sw := w.px(40)
		bx := pad + inner - w.px(16) - sw*2 - w.px(6)
		w.button(dc, rect{bx, ty + w.px(8), bx + sw, ty + w.px(44)}, "◀", prev, false)
		w.button(dc, rect{bx + sw + w.px(6), ty + w.px(8), bx + sw*2 + w.px(6), ty + w.px(44)}, "▶", next, false)
	}
	stepper("창 투명도", "불투명 "+strconv.Itoa(opacityAt(w.set.Opacity))+"%", idOpacityPrev, idOpacityNext)
	ty += w.px(52)
	stepper("마우스 감도", "트랙패드 · 가상 마우스 이동 "+strconv.FormatFloat(mouseScale(w.set.Mouse), 'g', -1, 64)+"배", idMousePrev, idMouseNext)
	ty += w.px(52)
	th := themeAt(w.set.Theme)
	stepper("테마", th.Name+"  ("+strconv.Itoa(w.set.Theme+1)+"/"+strconv.Itoa(len(windowThemes))+")", idThemePrev, idThemeNext)
	y += h + w.px(10)

	// 바닥글(알림이 있으면 알림)
	foot, color := "Ctrl+Alt+P 일시정지  ·  F11 전체 화면  ·  창을 닫으면 꺼집니다", cSub
	if time.Now().Before(w.toastTill) {
		foot, color = w.toast, cAccent
	}
	text(dc, foot, rect{pad, y, width - pad, y + w.px(20)}, w.normal(12), color, dtCenter|dtSingleLine)
	y += w.px(20) + pad
	return y
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b []byte
	for i := 0; n > 0; i++ {
		if i > 0 && i%3 == 0 {
			b = append([]byte{','}, b...)
		}
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	if neg {
		b = append([]byte{'-'}, b...)
	}
	return string(b)
}

func (w *window) say(s string) {
	w.toast, w.toastTill = s, time.Now().Add(2500*time.Millisecond)
	pInvalidateRect.Call(w.hwnd, 0, 0)
}

func (w *window) click(id int) {
	switch id {
	case idCopy:
		v := w.a.state.view(time.Now())
		if len(v.Addresses) > 0 && copyText(v.Addresses[0]) {
			w.say("주소 " + v.Addresses[0] + " 을(를) 복사했습니다")
		}
	case idPause:
		on := !w.a.Paused()
		w.a.SetPaused(on)
		if on {
			w.say("입력을 잠시 멈췄습니다. 태블릿에서 눌러도 PC 에 들어가지 않습니다")
		} else {
			w.say("다시 입력을 받습니다")
		}
	case idRelease:
		w.a.ReleaseAll()
		w.say("눌려 있던 키를 모두 뗐습니다")
	case idTop:
		w.set.OnTop = !w.set.OnTop
		w.applyOnTop()
		saveSettings(w.setPath, w.set)
	case idMini:
		w.set.Mini = !w.set.Mini
		saveSettings(w.setPath, w.set)
		w.height = 0
		if !w.set.Mini {
			w.say("전체 보기로 돌아왔습니다")
		}
	case idUpdate:
		w.say("업데이트를 확인하는 중…")
		hwnd := w.hwnd
		go func() {
			latest, err := latestVersion(updateBase)
			switch {
			case err != nil:
				pPostMessage.Call(hwnd, wmUpdateResult, 2, 0)
			case latest > version:
				pPostMessage.Call(hwnd, wmUpdateResult, 1, uintptr(latest))
			default:
				pPostMessage.Call(hwnd, wmUpdateResult, 0, 0)
			}
		}()
	case idOpacityPrev, idOpacityNext:
		if id == idOpacityNext && w.set.Opacity < len(opacities)-1 {
			w.set.Opacity++
		} else if id == idOpacityPrev && w.set.Opacity > 0 {
			w.set.Opacity--
		}
		w.applyOpacity()
		saveSettings(w.setPath, w.set)
	case idLock:
		if w.set.LockIP != "" {
			w.set.LockIP = ""
			w.say("모든 태블릿의 입력을 받습니다")
		} else if dev := w.a.state.view(time.Now()).Device; dev != "" {
			w.set.LockIP = dev
			w.say(dev + " 에서 온 입력만 받습니다")
		} else {
			w.say("먼저 태블릿을 연결한 뒤 켜세요")
		}
		w.a.SetLock(w.set.LockIP)
		saveSettings(w.setPath, w.set)
	case idReset:
		w.a.state.resetStats()
		w.say("이번 실행의 통계를 지웠습니다 (누적은 남김)")
	case idFull:
		w.toggleFull()
	case idBeep:
		w.set.Beep = !w.set.Beep
		saveSettings(w.setPath, w.set)
	case idMousePrev, idMouseNext:
		if id == idMousePrev && w.set.Mouse > 0 {
			w.set.Mouse--
		} else if id == idMouseNext && w.set.Mouse < len(mouseScales)-1 {
			w.set.Mouse++
		}
		w.a.SetMouseScale(mouseScale(w.set.Mouse))
		saveSettings(w.setPath, w.set)
	case idThemePrev, idThemeNext:
		step := 1
		if id == idThemePrev {
			step = len(windowThemes) - 1
		}
		w.set.Theme = (w.set.Theme + step) % len(windowThemes)
		w.applyTheme()
		saveSettings(w.setPath, w.set)
		w.say("테마: " + themeAt(w.set.Theme).Name)
	case idAuto:
		if err := setAutostart(!w.autostart); err != nil {
			w.say("자동 실행을 바꾸지 못했습니다 (관리자 권한 필요)")
		} else {
			w.autostart = !w.autostart
			if w.autostart {
				w.say("Windows 에 로그인하면 자동으로 켜집니다")
			} else {
				w.say("자동 실행을 껐습니다")
			}
		}
	}
	pInvalidateRect.Call(w.hwnd, 0, 0)
}

// applyTheme 은 고른 테마의 색을 쓰고, 제목줄도 밝게/어둡게 맞춘다(Windows 10 2004 이상, 11).
func (w *window) applyTheme() {
	t := themeAt(w.set.Theme)
	useTheme(t)
	dark := int32(1)
	if t.Light {
		dark = 0
	}
	syscall.NewLazyDLL("dwmapi.dll").NewProc("DwmSetWindowAttribute").Call(w.hwnd, 20, uintptr(unsafe.Pointer(&dark)), 4)
	pInvalidateRect.Call(w.hwnd, 0, 0)
}

// workArea 는 창이 있는 모니터의 작업 영역(작업 표시줄 제외)이다.
func (w *window) workArea() rect {
	mon, _, _ := pMonitorFromWindow.Call(w.hwnd, 2)
	info := struct {
		size        uint32
		monitor, wk rect
		flags       uint32
	}{size: 40}
	pGetMonitorInfo.Call(mon, uintptr(unsafe.Pointer(&info)))
	return info.wk
}

// applyOpacity 는 창 투명도를 적용한다(불투명 100% 면 겹친 창 기능을 끈다).
func (w *window) applyOpacity() {
	const gwlExStyle = ^uintptr(19) // -20
	ex, _, _ := pGetWindowLongPtr.Call(w.hwnd, gwlExStyle)
	alpha := opacityAt(w.set.Opacity)
	if alpha >= 100 {
		pSetWindowLongPtr.Call(w.hwnd, gwlExStyle, ex&^0x00080000)
		return
	}
	pSetWindowLongPtr.Call(w.hwnd, gwlExStyle, ex|0x00080000)           // WS_EX_LAYERED
	pSetLayeredAttr.Call(w.hwnd, 0, uintptr(alpha*255/100), 0x00000002) // LWA_ALPHA
}

// paintMini 는 미니 모드 화면이다: 연결 상태 한 줄, 지금 누르는 키를 크게, 아래에 초당 입력.
// 방송 화면에 띄워 두거나 게임 옆에 작게 두기 좋다.
func (w *window) paintMini(dc uintptr, width int32, v view) int32 {
	pad := w.px(14)
	inner := width - pad*2
	y := pad
	pSetBkMode.Call(dc, 1)
	dot, title := cSub, "태블릿을 기다리는 중"
	switch {
	case v.Paused:
		dot, title = cWarn, "입력 일시정지됨"
	case v.Device != "" && v.Live:
		dot, title = cOk, "연결됨  ·  "+v.Device
	case v.Device != "":
		dot, title = cWarn, "연결 끊김"
	}
	circle(dc, pad+w.px(7), y+w.px(14), w.px(6), dot)
	text(dc, title, rect{pad + w.px(22), y, pad + inner - w.px(100), y + w.px(28)}, w.bold(14), cText, dtSingleLine|dtVCenter|dtEllipsis)
	w.button(dc, rect{pad + inner - w.px(92), y, pad + inner, y + w.px(28)}, "전체 보기", idMini, false)
	y += w.px(40)

	down := v.Down
	if len(down) == 0 {
		down = []string{"—"}
	}
	h := w.chipsSized(dc, down, 0, 0, inner-w.px(24), 0, false, 24, 52) + w.px(24)
	if min := w.px(76); h < min {
		h = min
	}
	w.card(dc, pad, y, inner, h, cLine)
	w.chipsSized(dc, down, pad+w.px(12), y+w.px(12), inner-w.px(24), 0, true, 24, 52)
	y += h + w.px(10)

	text(dc, "초당 "+strconv.Itoa(v.KPS)+"  ·  최고 "+strconv.Itoa(v.Peak)+"  ·  누른 횟수 "+itoa(v.Presses),
		rect{pad, y, pad + inner, y + w.px(22)}, w.bold(13), cAccent, dtSingleLine|dtCenter)
	y += w.px(22) + pad
	return y
}

// clearFonts 는 배율이 바뀌었을 때 글꼴을 다시 만들게 한다.
func (w *window) clearFonts() {
	for _, f := range w.fonts {
		pDeleteObject.Call(f)
	}
	w.fonts = map[string]uintptr{}
}

// toggleFull 은 전체 화면과 창 모드를 오간다. 전체 화면에서는 테두리 없이 모니터를 덮고,
// 내용을 모니터 높이에 맞게 키워 가운데에 둔다.
func (w *window) toggleFull() {
	const gwlStyle = ^uintptr(15) // -16
	if !w.full {
		pGetWindowRect.Call(w.hwnd, uintptr(unsafe.Pointer(&w.normalRect)))
		mon, _, _ := pMonitorFromWindow.Call(w.hwnd, 2) // MONITOR_DEFAULTTONEAREST
		info := struct {
			size        uint32
			monitor, wk rect
			flags       uint32
		}{size: 40}
		pGetMonitorInfo.Call(mon, uintptr(unsafe.Pointer(&info)))
		mw, mh := info.monitor.r-info.monitor.l, info.monitor.b-info.monitor.t
		// 창 모드에서의 내용 높이(배율 1 기준)로 얼마나 키울지 정한다.
		logical := float64(w.contentH) / w.scale
		if logical <= 0 {
			logical = 900
		}
		zoom := float64(mh) * 0.94 / (logical * w.baseScale)
		if wz := float64(mw) * 0.9 / (460 * w.baseScale); wz < zoom {
			zoom = wz
		}
		if zoom < 0.6 {
			zoom = 0.6 // 아주 낮은 화면에서도 글자가 너무 작아지지 않게
		}
		w.full = true
		w.scale = w.baseScale * zoom
		w.clearFonts()
		pSetWindowLongPtr.Call(w.hwnd, gwlStyle, 0x80000000|0x10000000)                                                          // WS_POPUP | WS_VISIBLE
		pSetWindowPos.Call(w.hwnd, 0, uintptr(info.monitor.l), uintptr(info.monitor.t), uintptr(mw), uintptr(mh), 0x0020|0x0004) // FRAMECHANGED | NOZORDER
		w.say("전체 화면 · Esc 또는 F11 로 돌아갑니다")
	} else {
		w.full = false
		w.scale = w.baseScale
		w.clearFonts()
		pSetWindowLongPtr.Call(w.hwnd, gwlStyle, style|0x10000000)
		r := w.normalRect
		pSetWindowPos.Call(w.hwnd, 0, uintptr(r.l), uintptr(r.t), uintptr(r.r-r.l), uintptr(r.b-r.t), 0x0020|0x0004)
		w.height = 0 // 다음 그리기에서 창 크기를 내용에 다시 맞춘다
	}
	w.contentH = 0
	w.applyOnTop()
	pInvalidateRect.Call(w.hwnd, 0, 0)
}

func (w *window) applyOnTop() {
	after := ^uintptr(1) // HWND_NOTOPMOST (-2)
	if w.set.OnTop {
		after = ^uintptr(0) // HWND_TOPMOST (-1)
	}
	pSetWindowPos.Call(w.hwnd, after, 0, 0, 0, 0, 0x0001|0x0002|0x0010) // NOSIZE | NOMOVE | NOACTIVATE
}

// fit 은 내용 높이에 맞춰 창 크기를 바꾼다.
func (w *window) fit(width, height int32) {
	if w.full || height == w.height {
		return
	}
	w.height = height
	r := rect{0, 0, width, height}
	pAdjustWindowRect.Call(uintptr(unsafe.Pointer(&r)), style, 0, 0)
	pSetWindowPos.Call(w.hwnd, 0, 0, 0, uintptr(r.r-r.l), uintptr(r.b-r.t), 0x0002|0x0004|0x0010) // NOMOVE | NOZORDER | NOACTIVATE
}

func wndProc(hwnd, message, wParam, lParam uintptr) uintptr {
	w := win
	switch message {
	case wmEraseBkgnd:
		return 1
	case wmPaint:
		var ps paintStruct
		hdc, _, _ := pBeginPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
		var cr rect
		pGetClientRect.Call(hwnd, uintptr(unsafe.Pointer(&cr)))
		// 내용은 따로 그린 뒤(창 모드 너비) 화면 버퍼에 붙인다. 전체 화면이면 가운데에 둔다.
		contentW := cr.r
		if w.full {
			contentW = w.px(460)
		}
		tall := cr.b
		if tall < w.px(1400) {
			tall = w.px(1400) // 내용이 창보다 길어도 다 그릴 수 있게 넉넉히 그린 뒤 창 크기를 맞춘다
		}
		content, _, _ := pCreateCompatibleDC.Call(hdc)
		cbmp, _, _ := pCreateCompatibleBmp.Call(hdc, uintptr(contentW), uintptr(tall))
		cold, _, _ := pSelectObject.Call(content, cbmp)
		bg, _, _ := pCreateSolidBrush.Call(cBg)
		area := rect{0, 0, contentW, tall}
		pFillRect.Call(content, uintptr(unsafe.Pointer(&area)), bg)
		height := w.paint(content, contentW)
		w.contentH = height

		screen, _, _ := pCreateCompatibleDC.Call(hdc)
		sbmp, _, _ := pCreateCompatibleBmp.Call(hdc, uintptr(cr.r), uintptr(cr.b))
		sold, _, _ := pSelectObject.Call(screen, sbmp)
		pFillRect.Call(screen, uintptr(unsafe.Pointer(&cr)), bg)
		pDeleteObject.Call(bg)
		w.ox, w.oy = 0, 0
		if w.full {
			w.ox = (cr.r - contentW) / 2
			if height < cr.b {
				w.oy = (cr.b - height) / 2
			}
		}
		pBitBlt.Call(screen, uintptr(w.ox), uintptr(w.oy), uintptr(contentW), uintptr(height), content, 0, 0, 0x00CC0020) // SRCCOPY
		pBitBlt.Call(hdc, 0, 0, uintptr(cr.r), uintptr(cr.b), screen, 0, 0, 0x00CC0020)
		pSelectObject.Call(content, cold)
		pDeleteObject.Call(cbmp)
		pDeleteDC.Call(content)
		pSelectObject.Call(screen, sold)
		pDeleteObject.Call(sbmp)
		pDeleteDC.Call(screen)
		pEndPaint.Call(hwnd, uintptr(unsafe.Pointer(&ps)))
		// 전체 화면: 내용이 화면 높이의 94% 쯤이 되도록 배율을 다시 맞춘다(한 번이면 맞는다).
		if w.full && (height > cr.b || float64(height) < float64(cr.b)*0.8) {
			z := w.scale * float64(cr.b) * 0.94 / float64(height)
			if lo := w.baseScale * 0.6; z < lo {
				z = lo
			}
			if hi := w.baseScale * 3; z > hi {
				z = hi
			}
			if z != w.scale && (z/w.scale > 1.02 || z/w.scale < 0.98) {
				w.scale = z
				w.clearFonts()
				pInvalidateRect.Call(hwnd, 0, 0)
			}
		}
		// 창 모드: 내용이 모니터 작업 영역보다 길면 배율을 줄여 다 보이게 한다(최대 70% 까지).
		if !w.full {
			if work := w.workArea(); work.b-work.t > 0 {
				limit := float64(work.b-work.t) * 0.95
				// 제목줄 높이를 빼고 비교한다
				if float64(height)+float64(w.px(40)) > limit {
					z := w.scale * (limit - float64(w.px(40))) / float64(height)
					if lo := w.baseScale * 0.7; z < lo {
						z = lo
					}
					if z/w.scale < 0.98 {
						w.scale = z
						w.clearFonts()
						w.height = 0
						pInvalidateRect.Call(hwnd, 0, 0)
						return 0
					}
				}
			}
		}
		w.fit(w.px(460), height)
		return 0
	case wmHotKey:
		w.click(idPause) // Ctrl+Alt+P: 게임 중에도 바로 일시정지 / 다시 받기
		return 0
	case wmClose:
		// 창 위치와 누적 횟수를 기억해 두고 닫는다
		if w.full {
			w.toggleFull()
		}
		var r rect
		pGetWindowRect.Call(hwnd, uintptr(unsafe.Pointer(&r)))
		w.set.X, w.set.Y, w.set.Saved = r.l, r.t, true
		w.set.Total = w.a.state.view(time.Now()).Total
		saveSettings(w.setPath, w.set)
	case wmUpdateResult:
		switch wParam {
		case 0:
			w.say("최신 버전입니다 (버전 " + strconv.Itoa(version) + ")")
		case 2:
			w.say("업데이트를 확인하지 못했습니다. 인터넷 연결을 확인하세요")
		case 1:
			// 새 버전: 지금 상태를 저장하고 스스로 업데이트한 뒤 새 프로그램으로 다시 켠다.
			w.say("새 버전 " + strconv.Itoa(int(lParam)) + " 으로 업데이트합니다…")
			w.set.Total = w.a.state.view(time.Now()).Total
			saveSettings(w.setPath, w.set)
			w.a.ReleaseAll()
			selfUpdate()
			w.say("업데이트하지 못했습니다. 다음에 켤 때 다시 시도합니다")
		}
		return 0
	case wmKeyDown:
		switch wParam {
		case 0x7A: // F11
			w.toggleFull()
		case 0x1B: // Esc
			if w.full {
				w.toggleFull()
			}
		}
		return 0
	case wmTimer:
		// 연결 알림음: 태블릿이 연결되거나 끊기는 순간 한 번
		if live := w.a.state.view(time.Now()).Live; live != w.wasLive {
			w.wasLive = live
			if w.set.Beep {
				if live {
					pMessageBeep.Call(0x40) // MB_ICONASTERISK
				} else {
					pMessageBeep.Call(0x30) // MB_ICONEXCLAMATION
				}
			}
		}
		pInvalidateRect.Call(hwnd, 0, 0)
		return 0
	case wmMouseMove:
		x, y := int32(int16(lParam&0xFFFF))-w.ox, int32(int16((lParam>>16)&0xFFFF))-w.oy
		id := 0
		for _, h := range w.hits {
			if x >= h.r.l && x < h.r.r && y >= h.r.t && y < h.r.b {
				id = h.id
			}
		}
		if !w.tracking {
			t := trackMouse{flags: 2, hwnd: hwnd} // TME_LEAVE
			t.size = uint32(unsafe.Sizeof(t))
			pTrackMouseEvent.Call(uintptr(unsafe.Pointer(&t)))
			w.tracking = true
		}
		if id != w.hover {
			w.hover = id
			pInvalidateRect.Call(hwnd, 0, 0)
		}
		return 0
	case wmMouseLeave:
		w.tracking = false
		if w.hover != 0 {
			w.hover = 0
			pInvalidateRect.Call(hwnd, 0, 0)
		}
		return 0
	case wmSetCursor:
		if lParam&0xFFFF == 1 && w.hover != 0 { // HTCLIENT
			c, _, _ := pLoadCursor.Call(0, 32649) // IDC_HAND
			pSetCursor.Call(c)
			return 1
		}
	case wmLButtonUp:
		x, y := int32(int16(lParam&0xFFFF))-w.ox, int32(int16((lParam>>16)&0xFFFF))-w.oy
		for _, h := range w.hits {
			if x >= h.r.l && x < h.r.r && y >= h.r.t && y < h.r.b {
				w.click(h.id)
				break
			}
		}
		return 0
	case wmDestroy:
		pPostQuitMessage.Call(0)
		return 0
	}
	r, _, _ := pDefWindowProc.Call(hwnd, message, wParam, lParam)
	return r
}

// runWindow 는 창을 띄우고, 창을 닫을 때까지 돌아오지 않는다. 닫으면 누르고 있던 것을 모두 떼고 끝낸다.
func runWindow(a *app) {
	if r, _, _ := pSetDpiAwareness.Call(^uintptr(3)); r == 0 { // PER_MONITOR_AWARE_V2 (-4)
		pSetProcessDPIAwar.Call()
	}
	w := &window{a: a, scale: 1, fonts: map[string]uintptr{}, setPath: settingsPath()}
	w.set = loadSettings(w.setPath)
	a.SetLock(w.set.LockIP)
	a.state.mu.Lock()
	a.state.TotalBase = w.set.Total
	a.state.mu.Unlock()
	a.SetMouseScale(mouseScale(w.set.Mouse))
	useTheme(themeAt(w.set.Theme))
	w.autostart = autostartEnabled()
	win = w

	inst, _, _ := pGetModuleHandle.Call(0)
	cursor, _, _ := pLoadCursor.Call(0, 32512) // IDC_ARROW
	icon, _, _ := pLoadIcon.Call(0, 32512)     // IDI_APPLICATION
	cls := wndClassEx{wndProc: syscall.NewCallback(wndProc), instance: inst, cursor: cursor, icon: icon, iconSm: icon,
		className: utf16("ControlPadReceiver")}
	cls.size = uint32(unsafe.Sizeof(cls))
	pRegisterClassEx.Call(uintptr(unsafe.Pointer(&cls)))

	hwnd, _, _ := pCreateWindowEx.Call(0, uintptr(unsafe.Pointer(cls.className)), uintptr(unsafe.Pointer(utf16("Control Pad 수신기"))),
		style, 0x80000000, 0x80000000, 480, 760, 0, 0, inst, 0)
	if hwnd == 0 {
		select {} // 창을 못 만들면 받기만 계속한다
	}
	w.hwnd = hwnd
	if dpi, _, _ := pGetDpiForWindow.Call(hwnd); dpi != 0 {
		w.scale = float64(dpi) / 96
	}
	w.baseScale = w.scale
	w.fit(w.px(460), w.px(760))
	w.height = 0
	w.applyOnTop()
	w.applyOpacity()
	if w.set.Saved {
		pSetWindowPos.Call(hwnd, 0, uintptr(w.set.X), uintptr(w.set.Y), 0, 0, 0x0001|0x0004|0x0010) // NOSIZE | NOZORDER | NOACTIVATE
	}
	pRegisterHotKey.Call(hwnd, 1, 0x0001|0x0002|0x4000, 'P') // Ctrl+Alt+P (MOD_ALT | MOD_CONTROL | MOD_NOREPEAT)
	dark := int32(1)                                         // 제목줄도 어둡게(Windows 10 2004 이상, 11)
	syscall.NewLazyDLL("dwmapi.dll").NewProc("DwmSetWindowAttribute").Call(hwnd, 20, uintptr(unsafe.Pointer(&dark)), 4)
	pShowWindow.Call(hwnd, 5) // SW_SHOW
	pSetTimer.Call(hwnd, 1, 200, 0)
	if a.startFull {
		w.contentH = w.px(900)
		w.toggleFull()
	}

	var m msg
	for {
		r, _, _ := pGetMessage.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 {
			break
		}
		pTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		pDispatchMessage.Call(uintptr(unsafe.Pointer(&m)))
	}
	a.ReleaseAll()
	os.Exit(0)
}

// fail 은 이유를 알림 창으로 보여 주고 끝낸다(콘솔 창이 없으므로).
func fail(message string) {
	pMessageBox.Call(0, uintptr(unsafe.Pointer(utf16(message))), uintptr(unsafe.Pointer(utf16("Control Pad 수신기"))), 0x10) // MB_ICONERROR
	os.Exit(1)
}

// ── Windows 시작 시 자동 실행: 작업 스케줄러에 '로그인할 때, 가장 높은 권한으로' 작업을 둔다(권한 요청 창 없이 켜짐). ──

const taskName = "ControlPadReceiver"

func schtasks(args ...string) error {
	cmd := exec.Command("schtasks", args...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true, CreationFlags: 0x08000000} // CREATE_NO_WINDOW
	return cmd.Run()
}

func autostartEnabled() bool { return schtasks("/Query", "/TN", taskName) == nil }

func setAutostart(on bool) error {
	if !on {
		return schtasks("/Delete", "/F", "/TN", taskName)
	}
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	return schtasks("/Create", "/F", "/SC", "ONLOGON", "/RL", "HIGHEST", "/TN", taskName, "/TR", `"`+strings.ReplaceAll(exe, `"`, "")+`"`)
}

// copyText 는 글자를 클립보드에 넣는다.
func copyText(s string) bool {
	if r, _, _ := pOpenClipboard.Call(0); r == 0 {
		return false
	}
	defer pCloseClipboard.Call()
	pEmptyClipboard.Call()
	p, _ := syscall.UTF16FromString(s)
	size := uintptr(len(p) * 2)
	h, _, _ := pGlobalAlloc.Call(0x0002, size) // GMEM_MOVEABLE
	if h == 0 {
		return false
	}
	dst, _, _ := pGlobalLock.Call(h)
	pMoveMemory.Call(dst, uintptr(unsafe.Pointer(&p[0])), size)
	pGlobalUnlock.Call(h)
	r, _, _ := pSetClipboardData.Call(13, h) // CF_UNICODETEXT
	return r != 0
}
