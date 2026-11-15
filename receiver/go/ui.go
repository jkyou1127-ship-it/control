package main

import (
	"fmt"
	"sort"
	"sync"
	"time"
)

// uiState 는 수신 프로그램 창에 보여 줄 상태다. 네트워크 쪽이 고치고, 창이 0.1초마다 읽어 그린다.
type uiState struct {
	mu         sync.Mutex
	Version    int
	Hostname   string
	Addresses  []string
	Port       int
	Admin      bool
	Device     string
	LastInput  time.Time // 0 이면 아직 없음
	Presses    int
	Recent     []string
	Started    time.Time
	Paused     bool         // 입력 일시정지: 받기는 하되 PC 에 넣지 않는다
	Down       map[int]bool // 지금 누르고 있는 키(usage)
	Peak       int          // 가장 높았던 초당 입력 수
	pressTimes []time.Time  // 최근 1초 동안 누른 시각(초당 입력 수 계산용)
	LastPacket time.Time    // 태블릿에서 마지막으로 패킷을 받은 시각(앱은 쉬는 동안에도 0.7초마다 보낸다)
	perSecond  [60]int      // 최근 1분의 초별 누른 횟수(그래프용). 칸은 유닉스 초 % 60
	secondOf   [60]int64    // 각 칸이 어느 초의 것인지
}

// heard 는 태블릿에서 패킷을 받았다고 적는다.
func (s *uiState) heard(now time.Time) {
	s.mu.Lock()
	s.LastPacket = now
	s.mu.Unlock()
}

func newState() *uiState { return &uiState{Started: time.Now(), Down: map[int]bool{}} }

func (s *uiState) connected(ip string) {
	s.mu.Lock()
	s.Device = ip
	s.mu.Unlock()
}

func (s *uiState) pressed(name string, now time.Time) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.Presses++
	s.LastInput = now
	s.Recent = append(s.Recent, name)
	if len(s.Recent) > 12 {
		s.Recent = s.Recent[len(s.Recent)-12:]
	}
	s.pressTimes = append(s.pressTimes, now)
	sec := now.Unix()
	if s.secondOf[sec%60] != sec {
		s.secondOf[sec%60], s.perSecond[sec%60] = sec, 0
	}
	s.perSecond[sec%60]++
	if k := s.kpsLocked(now); k > s.Peak {
		s.Peak = k
	}
}

func (s *uiState) kpsLocked(now time.Time) int {
	i := 0
	for i < len(s.pressTimes) && now.Sub(s.pressTimes[i]) > time.Second {
		i++
	}
	s.pressTimes = s.pressTimes[i:]
	return len(s.pressTimes)
}

func (s *uiState) keyDown(u int, down bool) {
	s.mu.Lock()
	if s.Down == nil {
		s.Down = map[int]bool{}
	}
	if down {
		s.Down[u] = true
	} else {
		delete(s.Down, u)
	}
	s.mu.Unlock()
}

func (s *uiState) moved(now time.Time) {
	s.mu.Lock()
	s.LastInput = now
	s.mu.Unlock()
}

// view 는 창이 한 번 그릴 때 쓰는 값들(잠금 없이 읽을 수 있게 복사한 것).
type view struct {
	Version            int
	Hostname, Device   string
	Addresses          []string
	Port               int
	Admin, Paused      bool
	Presses, KPS, Peak int
	Recent, Down       []string
	LastInput, Uptime  string
	Live               bool  // 3초 안에 태블릿에서 패킷이 왔는지
	History            []int // 최근 60초의 초별 누른 횟수(오래된 것부터)
}

func (s *uiState) view(now time.Time) view {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := view{Version: s.Version, Hostname: s.Hostname, Device: s.Device, Port: s.Port, Admin: s.Admin, Paused: s.Paused,
		Presses: s.Presses, KPS: s.kpsLocked(now), Peak: s.Peak}
	v.Addresses = append(v.Addresses, s.Addresses...)
	v.Recent = append(v.Recent, s.Recent...)
	var down []int
	for u := range s.Down {
		down = append(down, u)
	}
	sort.Ints(down)
	for _, u := range down {
		v.Down = append(v.Down, keyName(u))
	}
	if s.LastInput.IsZero() {
		v.LastInput = "아직 입력이 없습니다"
	} else {
		v.LastInput = "마지막 입력 " + ago(now.Sub(s.LastInput))
	}
	v.Live = !s.LastPacket.IsZero() && now.Sub(s.LastPacket) < 3*time.Second
	for sec := now.Unix() - 59; sec <= now.Unix(); sec++ {
		n := 0
		if s.secondOf[sec%60] == sec {
			n = s.perSecond[sec%60]
		}
		v.History = append(v.History, n)
	}
	up := int(now.Sub(s.Started).Seconds())
	v.Uptime = fmt.Sprintf("실행한 지 %d시간 %d분", up/3600, up/60%60)
	return v
}

func ago(d time.Duration) string {
	switch s := int(d.Seconds()); {
	case s < 2:
		return "방금"
	case s < 60:
		return fmt.Sprintf("%d초 전", s)
	case s < 3600:
		return fmt.Sprintf("%d분 전", s/60)
	default:
		return fmt.Sprintf("%d시간 전", s/3600)
	}
}

// uiInjector 는 실제 입력을 넣으면서 창에 보여 줄 기록을 남긴다.
type uiInjector struct {
	inner Injector
	state *uiState
}

func (u uiInjector) Key(usage int, down bool) {
	u.inner.Key(usage, down)
	u.state.keyDown(usage, down)
	if down {
		u.state.pressed(keyName(usage), time.Now())
	}
}
func (u uiInjector) Move(dx, dy int) { u.inner.Move(dx, dy); u.state.moved(time.Now()) }
func (u uiInjector) Button(i int, down bool) {
	u.inner.Button(i, down)
	if down {
		u.state.pressed([]string{"좌클릭", "우클릭", "휠클릭"}[i], time.Now())
	}
}
func (u uiInjector) Wheel(steps int) { u.inner.Wheel(steps); u.state.moved(time.Now()) }

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
		0x28: "Enter", 0x29: "Esc", 0x2A: "Backspace", 0x2B: "Tab", 0x2C: "Space", 0x2D: "-", 0x2E: "=",
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
