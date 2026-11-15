// ControlPad PC 수신 프로그램.
//
// 태블릿 앱이 UDP 로 보내는 키·마우스 상태를 받아 PC 입력으로 바꾼다.
// 앱의 '연결 설정 > Wi-Fi / USB 테더링' 방식에서만 필요하다(블루투스 방식은 필요 없음).
// 같은 일을 하는 파이썬 판이 ../controlpad_receiver.py 에 있고, 형식 설명도 거기에 있다.
package main

import (
	"flag"
	"fmt"
	"net"
	"os"
	"sort"
	"sync"
	"time"
)

const (
	defaultPort = 34555
	reportSize  = 20
	timeout     = time.Second // 이 시간 동안 패킷이 없으면 눌린 것을 모두 뗀다(앱 종료·연결 끊김 대비)
)

// Injector 는 실제로 PC 에 입력을 넣는 쪽이다.
type Injector interface {
	Key(usage int, down bool)
	Move(dx, dy int)
	Button(index int, down bool)
	Wheel(steps int)
}

type scan struct {
	code     uint16
	extended bool
}

// HID usage → PS/2 set 1 스캔코드
var scancodes = buildScancodes()

// 스캔코드 대신 가상 키로 넣는 것들: 한/영(VK_HANGUL), 한자(VK_HANJA)
var vkeys = map[int]uint16{0x90: 0x15, 0x91: 0x19}

func buildScancodes() map[int]scan {
	m := map[int]scan{}
	for i, c := range []uint16{0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
		0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C} {
		m[0x04+i] = scan{c, false} // A-Z
	}
	for i := 0; i < 10; i++ {
		m[0x1E+i] = scan{uint16(0x02 + i), false} // 1-9, 0
		m[0x3A+i] = scan{uint16(0x3B + i), false} // F1-F10
	}
	for i, c := range []uint16{0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49, 0x52, 0x53} {
		m[0x59+i] = scan{c, false} // 숫자패드 1-9, 0, .
	}
	for u, s := range map[int]scan{
		0x28: {0x1C, false}, 0x29: {0x01, false}, 0x2A: {0x0E, false}, 0x2B: {0x0F, false},
		0x2C: {0x39, false}, 0x2D: {0x0C, false}, 0x2E: {0x0D, false}, 0x2F: {0x1A, false},
		0x30: {0x1B, false}, 0x31: {0x2B, false}, 0x33: {0x27, false}, 0x34: {0x28, false},
		0x35: {0x29, false}, 0x36: {0x33, false}, 0x37: {0x34, false}, 0x38: {0x35, false},
		0x39: {0x3A, false}, 0x44: {0x57, false}, 0x45: {0x58, false},
		0x46: {0x37, true}, 0x49: {0x52, true}, 0x4A: {0x47, true}, 0x4B: {0x49, true},
		0x4C: {0x53, true}, 0x4D: {0x4F, true}, 0x4E: {0x51, true},
		0x4F: {0x4D, true}, 0x50: {0x4B, true}, 0x51: {0x50, true}, 0x52: {0x48, true},
		0x53: {0x45, false}, 0x54: {0x35, true}, 0x55: {0x37, false}, 0x56: {0x4A, false},
		0x57: {0x4E, false}, 0x58: {0x1C, true},
		0xE0: {0x1D, false}, 0xE1: {0x2A, false}, 0xE2: {0x38, false}, 0xE3: {0x5B, true},
		0xE4: {0x1D, true}, 0xE5: {0x36, false}, 0xE6: {0x38, true}, 0xE7: {0x5C, true},
	} {
		m[u] = s
	}
	return m
}

// usages 는 리포트에서 눌린 HID usage 집합을 꺼낸다.
func usages(report []byte) map[int]bool {
	down := map[int]bool{}
	for bit := 0; bit < 8; bit++ {
		if report[0]&(1<<bit) != 0 {
			down[0xE0+bit] = true
		}
	}
	for u := 4; u < (reportSize-1)*8; u++ { // 0~3 은 키가 아닌 예약 값
		if report[1+u/8]&(1<<(u%8)) != 0 {
			down[u] = true
		}
	}
	return down
}

func seqOf(p []byte) uint32 {
	return uint32(p[3])<<24 | uint32(p[4])<<16 | uint32(p[5])<<8 | uint32(p[6])
}

// parseKeys 는 키 패킷이면 (순번, 리포트, true) 를 돌려준다.
func parseKeys(p []byte) (uint32, []byte, bool) {
	if len(p) != 7+reportSize || p[0] != 'C' || p[1] != 'P' || p[2] != 1 {
		return 0, nil, false
	}
	return seqOf(p), p[7:], true
}

type mousePacket struct {
	seq     uint32
	dx, dy  int
	buttons int
	wheel   int
}

// parseMouse 는 마우스 패킷이면 그 내용을 돌려준다.
func parseMouse(p []byte) (mousePacket, bool) {
	if len(p) != 13 || p[0] != 'C' || p[1] != 'M' || p[2] != 1 {
		return mousePacket{}, false
	}
	return mousePacket{
		seq:     seqOf(p),
		dx:      int(int16(uint16(p[7])<<8 | uint16(p[8]))),
		dy:      int(int16(uint16(p[9])<<8 | uint16(p[10]))),
		buttons: int(p[11] & 0x07),
		wheel:   int(int8(p[12])),
	}, true
}

// discoveryReply 는 'PC 자동으로 찾기' 질문이면 PC 이름을 담은 답을 만든다. 아니면 nil.
func discoveryReply(p []byte, name string) []byte {
	if len(p) != 3 || p[0] != 'C' || p[1] != 'D' || p[2] != 1 {
		return nil
	}
	return append([]byte{'C', 'R', 1}, name...)
}

// newer 는 순번이 앞선 패킷인지 본다. 크게 뒤로 가면 앱이 다시 시작된 것으로 본다.
func newer(seq, last uint32) bool {
	return seq > last || int64(seq) < int64(last)-1000
}

// Keys 는 키 상태를 따라가며 달라진 만큼만 입력한다.
type Keys struct {
	inj    Injector
	down   map[int]bool
	seq    uint32
	sender string
}

func (k *Keys) Feed(seq uint32, report []byte, sender string) {
	if sender != k.sender { // 다른 기기
		k.sender, k.seq = sender, 0
	}
	if !newer(seq, k.seq) { // 늦게 도착한 옛 패킷은 무시
		return
	}
	k.seq = seq
	k.apply(usages(report))
}

func (k *Keys) apply(now map[int]bool) {
	var press, release []int
	for u := range now {
		if !k.down[u] {
			press = append(press, u)
		}
	}
	for u := range k.down {
		if !now[u] {
			release = append(release, u)
		}
	}
	// 수정키는 먼저 누르고 나중에 떼야 Ctrl+C 같은 조합이 제대로 들어간다.
	mod := func(u int) bool { return u >= 0xE0 }
	sort.Slice(press, func(i, j int) bool {
		if mod(press[i]) != mod(press[j]) {
			return mod(press[i])
		}
		return press[i] < press[j]
	})
	sort.Slice(release, func(i, j int) bool {
		if mod(release[i]) != mod(release[j]) {
			return !mod(release[i])
		}
		return release[i] < release[j]
	})
	for _, u := range press {
		k.inj.Key(u, true)
	}
	for _, u := range release {
		k.inj.Key(u, false)
	}
	k.down = now
}

func (k *Keys) ReleaseAll() { k.apply(map[int]bool{}) }

// Mouse 는 이동과 휠은 받는 대로 넣고, 버튼은 달라진 것만 넣는다.
type Mouse struct {
	inj     Injector
	buttons int
	seq     uint32
	Scale   float64 // 마우스 감도 배율(0 이면 1배). 창에서 바꾼다.
	fx, fy  float64 // 배율을 곱하고 남은 소수 부분(느리게 움직여도 버려지지 않게)
}

// moveScaled 는 배율을 곱한 이동량을 돌려준다. 남은 소수는 다음 이동에 더한다.
func (m *Mouse) moveScaled(dx, dy int) (int, int) {
	s := m.Scale
	if s <= 0 {
		s = 1
	}
	m.fx += float64(dx) * s
	m.fy += float64(dy) * s
	ox, oy := int(m.fx), int(m.fy)
	m.fx -= float64(ox)
	m.fy -= float64(oy)
	return ox, oy
}

func (m *Mouse) Feed(p mousePacket) {
	if p.dx != 0 || p.dy != 0 {
		if dx, dy := m.moveScaled(p.dx, p.dy); dx != 0 || dy != 0 {
			m.inj.Move(dx, dy)
		}
	}
	if p.wheel != 0 {
		m.inj.Wheel(p.wheel)
	}
	if newer(p.seq, m.seq) {
		m.seq = p.seq
		m.set(p.buttons)
	}
}

func (m *Mouse) set(buttons int) {
	for bit := 0; bit < 3; bit++ {
		mask := 1 << bit
		if (buttons^m.buttons)&mask != 0 {
			m.inj.Button(bit, buttons&mask != 0)
		}
	}
	m.buttons = buttons
}

func (m *Mouse) ReleaseAll() { m.set(0) }

// printer 는 입력을 넣지 않고 화면에만 적는다(--dry-run).
type printer struct{}

func (printer) Key(usage int, down bool) {
	if down {
		fmt.Printf("누름 0x%02X\n", usage)
	} else {
		fmt.Printf("뗌   0x%02X\n", usage)
	}
}
func (printer) Move(dx, dy int) { fmt.Printf("마우스 %+d %+d\n", dx, dy) }
func (printer) Button(i int, down bool) {
	if down {
		fmt.Printf("마우스 버튼 %d 누름\n", i)
	} else {
		fmt.Printf("마우스 버튼 %d 뗌\n", i)
	}
}
func (printer) Wheel(steps int) { fmt.Printf("휠 %+d\n", steps) }

func localAddresses() []string {
	var out []string
	addrs, _ := net.InterfaceAddrs()
	for _, a := range addrs {
		if ipn, ok := a.(*net.IPNet); ok {
			if ip := ipn.IP.To4(); ip != nil && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() {
				out = append(out, ip.String())
			}
		}
	}
	return out
}

// app 은 창의 버튼이 부르는 동작들이다. 입력을 넣는 쪽(네트워크)과 창이 서로 다른 스레드라 inputMu 로 막는다.
type app struct {
	state   *uiState
	keys    *Keys
	mouse   *Mouse
	inputMu sync.Mutex

	startFull bool   // -fullscreen: 전체 화면으로 시작
	lockIP    string // '이 태블릿만 받기': 비어 있지 않으면 이 주소에서 온 패킷만 받는다(inputMu 로 막음)
}

var errLocked = fmt.Errorf("이 태블릿만 받기")

// SetLock 은 '이 태블릿만 받기'를 켜고(ip) 끈다(빈 문자열).
func (a *app) SetLock(ip string) {
	a.inputMu.Lock()
	a.lockIP = ip
	a.inputMu.Unlock()
}

// accepts 는 이 주소에서 온 패킷을 받을지 알려 준다. inputMu 를 잡은 채로 부른다.
func (a *app) accepts(ip string) bool { return a.lockIP == "" || a.lockIP == ip }

// SetPaused 는 입력 일시정지를 켜고 끈다. 켜는 순간 누르고 있던 것을 모두 뗀다.
func (a *app) SetPaused(on bool) {
	a.inputMu.Lock()
	defer a.inputMu.Unlock()
	a.state.mu.Lock()
	a.state.Paused = on
	a.state.mu.Unlock()
	if on {
		a.keys.ReleaseAll()
		a.mouse.ReleaseAll()
	}
}

func (a *app) Paused() bool {
	a.state.mu.Lock()
	defer a.state.mu.Unlock()
	return a.state.Paused
}

// SetMouseScale 은 마우스 감도 배율을 바꾼다.
func (a *app) SetMouseScale(s float64) {
	a.inputMu.Lock()
	a.mouse.Scale = s
	a.inputMu.Unlock()
}

// ReleaseAll 은 키가 눌린 채로 남았을 때 모두 뗀다.
func (a *app) ReleaseAll() {
	a.inputMu.Lock()
	defer a.inputMu.Unlock()
	a.keys.ReleaseAll()
	a.mouse.ReleaseAll()
}

// serve 는 태블릿이 보내는 패킷을 받아 입력으로 바꾼다. 끝나지 않는다.
func (a *app) serve(conn *net.UDPConn, hostname string) {
	last := time.Now()
	connected := ""
	buf := make([]byte, 64)
	for {
		conn.SetReadDeadline(time.Now().Add(timeout))
		n, from, err := conn.ReadFromUDP(buf)
		a.inputMu.Lock()
		paused := a.Paused()
		if err == nil && !a.accepts(from.IP.String()) {
			err = errLocked // 다른 기기에서 온 것은 찾기 요청까지 모두 무시한다
		}
		if err == nil {
			p := buf[:n]
			if reply := discoveryReply(p, hostname); reply != nil {
				conn.WriteToUDP(reply, from)
			} else if m, ok := parseMouse(p); ok {
				a.state.heard(time.Now())
				if !paused {
					a.mouse.Feed(m)
				}
				last = time.Now()
			} else if seq, report, ok := parseKeys(p); ok {
				if !paused {
					a.keys.Feed(seq, report, from.String())
				}
				last = time.Now()
				a.state.heard(last)
				conn.WriteToUDP(ackPacket(p, paused), from)
				if ip := from.IP.String(); ip != connected {
					connected = ip
					a.state.connected(ip)
					fmt.Println("  ● 태블릿 연결됨:", ip)
				}
			}
		}
		if (len(a.keys.down) > 0 || a.mouse.buttons != 0) && time.Since(last) > timeout {
			a.keys.ReleaseAll()
			a.mouse.ReleaseAll()
		}
		a.inputMu.Unlock()
	}
}

// ackPacket 은 키 패킷에 대한 답이다: 'C' 'A' 순번(4바이트) 상태(1바이트, 비트 0 = 입력 일시정지 중).
// 옛 앱은 앞의 6바이트만 읽으므로 상태 바이트가 붙어도 괜찮다.
func ackPacket(p []byte, paused bool) []byte {
	var flags byte
	if paused {
		flags |= 1
	}
	return []byte{'C', 'A', p[3], p[4], p[5], p[6], flags}
}

func main() {
	port := flag.Int("port", defaultPort, "받을 UDP 포트")
	dry := flag.Bool("dry-run", false, "입력을 넣지 않고 받은 내용만 출력")
	noUpdate := flag.Bool("no-update", false, "시작할 때 새 버전을 확인하지 않음")
	noAdmin := flag.Bool("no-admin", false, "관리자 권한을 요청하지 않음")
	full := flag.Bool("fullscreen", false, "전체 화면으로 시작")
	flag.Parse()
	setupConsole()
	// 관리자 권한으로 실행된 게임에는 일반 프로그램이 입력을 넣을 수 없다(Windows 의 보안 규칙).
	// 그래서 처음부터 관리자 권한으로 다시 실행한다. 사용자가 거절하면 그대로 계속한다(창에 안내가 뜬다).
	if !*dry && !*noAdmin && !isAdmin() {
		if relaunchAsAdmin(os.Args[1:]) {
			return
		}
	}
	if !*noUpdate && !*dry {
		selfUpdate()
	}

	var inj Injector = printer{}
	if !*dry {
		real, err := newInjector()
		if err != nil {
			fail(err.Error())
		}
		inj = real
	}

	conn, err := net.ListenUDP("udp4", &net.UDPAddr{Port: *port})
	if err != nil {
		fail(fmt.Sprintf("포트 %d 을(를) 열 수 없습니다. 수신 프로그램이 이미 켜져 있는지 확인하세요.\n(%v)", *port, err))
	}
	defer conn.Close()

	hostname, _ := os.Hostname()
	state := newState()
	state.Version, state.Hostname, state.Addresses, state.Port, state.Admin = version, hostname, localAddresses(), *port, isAdmin()
	inj = uiInjector{inner: inj, state: state}
	a := &app{state: state, keys: &Keys{inj: inj, down: map[int]bool{}}, mouse: &Mouse{inj: inj}, startFull: *full}

	fmt.Printf("Control Pad 수신 프로그램 · 버전 %d · 포트 %d\n", version, *port)
	for _, addr := range state.Addresses {
		fmt.Println("  이 PC 주소:", addr)
	}
	go a.serve(conn, hostname)
	runWindow(a) // 창을 닫으면 돌아온다(창이 없는 OS 에서는 끝나지 않는다)
}
