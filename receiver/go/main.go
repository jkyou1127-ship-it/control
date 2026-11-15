// ControlPad PC 수신 프로그램.
//
// 태블릿 앱이 UDP 로 보내는 키·마우스 상태를 받아 PC 입력으로 바꾼다.
// 앱의 '연결 설정 > Wi-Fi / USB 테더링' 방식에서만 필요하다(블루투스 방식은 필요 없음).
// 같은 일을 하는 파이썬 판이 ../controlpad_receiver.py 에 있고, 형식 설명도 거기에 있다.
package main

import (
	"bufio"
	"flag"
	"fmt"
	"net"
	"os"
	"sort"
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
}

func (m *Mouse) Feed(p mousePacket) {
	if p.dx != 0 || p.dy != 0 {
		m.inj.Move(p.dx, p.dy)
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

// fail 은 창이 바로 닫혀 버리지 않게, 이유를 보여 주고 Enter 를 기다린 뒤 끝낸다.
func fail(msg string) {
	fmt.Println(msg)
	fmt.Println("Enter 를 누르면 닫힙니다.")
	bufio.NewReader(os.Stdin).ReadString('\n')
	os.Exit(1)
}

func main() {
	port := flag.Int("port", defaultPort, "받을 UDP 포트")
	dry := flag.Bool("dry-run", false, "입력을 넣지 않고 받은 내용만 출력")
	noUpdate := flag.Bool("no-update", false, "시작할 때 새 버전을 확인하지 않음")
	noAdmin := flag.Bool("no-admin", false, "관리자 권한을 요청하지 않음")
	noUI := flag.Bool("no-ui", false, "상태 화면(브라우저)을 열지 않음")
	flag.Parse()
	setupConsole()
	// 관리자 권한으로 실행된 게임에는 일반 프로그램이 입력을 넣을 수 없다(Windows 의 보안 규칙).
	// 그래서 처음부터 관리자 권한으로 다시 실행한다. 사용자가 거절하면 그대로 계속한다.
	if !*dry && !*noAdmin && !isAdmin() {
		if relaunchAsAdmin(os.Args[1:]) {
			return
		}
		fmt.Println("관리자 권한 없이 실행합니다. 관리자 권한으로 실행된 게임에는 입력이 들어가지 않을 수 있습니다.")
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
		fail(fmt.Sprintf("포트 %d 을(를) 열 수 없습니다. 이미 실행 중인지 확인하세요.\n(%v)", *port, err))
	}
	defer conn.Close()

	hostname, _ := os.Hostname()
	state := &uiState{
		Version: version, Hostname: hostname, Addresses: localAddresses(), Port: *port,
		Admin: isAdmin(), Started: time.Now().UnixMilli(),
	}
	inj = uiInjector{inner: inj, state: state}

	fmt.Println("┌──────────────────────────────────────────────┐")
	fmt.Printf("│  Control Pad 수신 프로그램  ·  버전 %-9d│\n", version)
	fmt.Println("└──────────────────────────────────────────────┘")
	if state.Admin {
		fmt.Println("  ● 관리자 권한으로 실행 중 (모든 창에 입력 가능)")
	} else {
		fmt.Println("  ○ 관리자 권한 없음 (관리자 권한 게임에는 입력이 안 들어갈 수 있음)")
	}
	fmt.Println()
	fmt.Println("  태블릿 앱: 설정 → Wi-Fi / USB 테더링 → PC 자동으로 찾기")
	fmt.Printf("  찾지 못하면 아래 주소를 직접 입력하세요 (포트 %d)\n", *port)
	for _, a := range state.Addresses {
		fmt.Println("     ", a)
	}
	if url := startUI(state); url != "" {
		fmt.Println()
		fmt.Println("  상태 화면:", url)
		if !*noUI {
			openBrowser(url)
		}
	}
	fmt.Println()
	fmt.Println("  끝내려면 이 창을 닫으세요.")

	keys := &Keys{inj: inj, down: map[int]bool{}}
	mouse := &Mouse{inj: inj}
	last := time.Now()
	connected := ""
	buf := make([]byte, 64)
	for {
		conn.SetReadDeadline(time.Now().Add(timeout))
		n, from, err := conn.ReadFromUDP(buf)
		if err == nil {
			p := buf[:n]
			if reply := discoveryReply(p, hostname); reply != nil {
				conn.WriteToUDP(reply, from)
			} else if m, ok := parseMouse(p); ok {
				mouse.Feed(m)
				last = time.Now()
			} else if seq, report, ok := parseKeys(p); ok {
				keys.Feed(seq, report, from.String())
				last = time.Now()
				conn.WriteToUDP([]byte{'C', 'A', p[3], p[4], p[5], p[6]}, from)
				if ip := from.IP.String(); ip != connected {
					connected = ip
					state.connected(ip)
					fmt.Println("  ● 태블릿 연결됨:", ip)
				}
			}
		}
		if (len(keys.down) > 0 || mouse.buttons != 0) && time.Since(last) > timeout {
			keys.ReleaseAll()
			mouse.ReleaseAll()
		}
	}
}
