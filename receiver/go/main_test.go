package main

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"unsafe"
)

type recorder struct{ events []string }

func (r *recorder) Key(u int, d bool)    { r.events = append(r.events, fmt.Sprintf("key %02X %v", u, d)) }
func (r *recorder) Move(dx, dy int)      { r.events = append(r.events, fmt.Sprintf("move %d %d", dx, dy)) }
func (r *recorder) Button(i int, d bool) { r.events = append(r.events, fmt.Sprintf("btn %d %v", i, d)) }
func (r *recorder) Wheel(s int)          { r.events = append(r.events, fmt.Sprintf("wheel %d", s)) }

func keyPacket(seq uint32, down ...int) []byte {
	rep := make([]byte, reportSize)
	for _, u := range down {
		if u >= 0xE0 {
			rep[0] |= 1 << (u - 0xE0)
		} else {
			rep[1+u/8] |= 1 << (u % 8)
		}
	}
	return append([]byte{'C', 'P', 1, byte(seq >> 24), byte(seq >> 16), byte(seq >> 8), byte(seq)}, rep...)
}

func mousePkt(seq uint32, dx, dy int16, buttons byte, wheel int8) []byte {
	return []byte{'C', 'M', 1, byte(seq >> 24), byte(seq >> 16), byte(seq >> 8), byte(seq),
		byte(uint16(dx) >> 8), byte(dx), byte(uint16(dy) >> 8), byte(dy), buttons, byte(wheel)}
}

func feed(k *Keys, p []byte) {
	if seq, rep, ok := parseKeys(p); ok {
		k.Feed(seq, rep, "tablet")
	}
}

func TestModifierPressedFirstReleasedLast(t *testing.T) {
	r := &recorder{}
	k := &Keys{inj: r, down: map[int]bool{}}
	feed(k, keyPacket(1, 0x06, 0xE0))
	feed(k, keyPacket(2))
	want := []string{"key E0 true", "key 06 true", "key 06 false", "key E0 false"}
	if !reflect.DeepEqual(r.events, want) {
		t.Fatalf("got %v", r.events)
	}
}

func TestRepeatsStalePacketsAndRestart(t *testing.T) {
	r := &recorder{}
	k := &Keys{inj: r, down: map[int]bool{}}
	feed(k, keyPacket(5000, 0x07))
	feed(k, keyPacket(5001, 0x07)) // 같은 상태 재전송
	feed(k, keyPacket(4999))       // 늦게 도착한 패킷
	if !reflect.DeepEqual(r.events, []string{"key 07 true"}) {
		t.Fatalf("got %v", r.events)
	}
	feed(k, keyPacket(1, 0x09)) // 앱이 다시 시작되어 순번이 처음부터
	if !k.down[0x09] || k.down[0x07] {
		t.Fatalf("down = %v", k.down)
	}
	feed(k, []byte("hello")) // 엉뚱한 패킷
	feed(k, keyPacket(2)[:20])
	if !k.down[0x09] {
		t.Fatal("garbage changed state")
	}
}

func TestHangulKeyAndLowLetters(t *testing.T) {
	_, rep, ok := parseKeys(keyPacket(1, 0x90, 0x04, 0x07))
	if !ok || !reflect.DeepEqual(usages(rep), map[int]bool{0x90: true, 0x04: true, 0x07: true}) {
		t.Fatalf("usages = %v", usages(rep))
	}
}

func TestMouse(t *testing.T) {
	r := &recorder{}
	m := &Mouse{inj: r}
	for _, p := range [][]byte{
		mousePkt(1, 4, -300, 0b001, 0),
		mousePkt(2, 0, 0, 0b001, 0), // 상태 유지용 재전송
		mousePkt(4, 0, 0, 0b010, -2),
		mousePkt(3, 0, 0, 0b001, 0), // 늦게 도착한 패킷
	} {
		mp, ok := parseMouse(p)
		if !ok {
			t.Fatal("not parsed")
		}
		m.Feed(mp)
	}
	m.ReleaseAll()
	want := []string{"move 4 -300", "btn 0 true", "wheel -2", "btn 0 false", "btn 1 true", "btn 1 false"}
	if !reflect.DeepEqual(r.events, want) {
		t.Fatalf("got %v", r.events)
	}
	if _, ok := parseMouse(keyPacket(1)); ok {
		t.Fatal("key packet parsed as mouse")
	}
}

func TestEveryAppKeyCanBeInjected(t *testing.T) {
	// 앱의 Keys.kt 에 있는 usage 범위
	var app []int
	for u := 0x04; u < 0x47; u++ {
		if u != 0x32 {
			app = append(app, u)
		}
	}
	for u := 0x49; u < 0x64; u++ {
		app = append(app, u)
	}
	for u := 0xE0; u < 0xE7; u++ {
		app = append(app, u)
	}
	app = append(app, 0x90, 0x91)
	for _, u := range app {
		_, s := scancodes[u]
		_, v := vkeys[u]
		if !s && !v {
			t.Errorf("no mapping for %02X", u)
		}
	}
}

func TestInputStructLayoutMatchesWin64(t *testing.T) {
	// inject_windows.go 의 구조체와 같은 배치. INPUT 은 64비트에서 40바이트여야 한다.
	type kb struct {
		typ   uint32
		_     uint32
		vk    uint16
		scan  uint16
		flags uint32
		time  uint32
		_     uint32
		extra uintptr
		_     [8]byte
	}
	type ms struct {
		typ       uint32
		_         uint32
		dx, dy    int32
		mouseData uint32
		flags     uint32
		time      uint32
		_         uint32
		extra     uintptr
	}
	if unsafe.Sizeof(kb{}) != 40 || unsafe.Sizeof(ms{}) != 40 {
		t.Fatalf("sizes %d %d", unsafe.Sizeof(kb{}), unsafe.Sizeof(ms{}))
	}
	if unsafe.Offsetof(kb{}.extra) != 24 || unsafe.Offsetof(ms{}.extra) != 32 || unsafe.Offsetof(ms{}.flags) != 20 {
		t.Fatal("offsets differ from Win64 INPUT")
	}
}

func TestFetchUpdate(t *testing.T) {
	exe := []byte("MZ new receiver")
	sum := sha256.Sum256(exe)
	meta := fmt.Sprintf(`{"version": 7, "sha256": "%s"}`, hex.EncodeToString(sum[:]))
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/receiver.json":
			w.Write([]byte(meta))
		case "/ControlPadReceiver.exe":
			w.Write(exe)
		default:
			w.WriteHeader(404)
		}
	}))
	defer srv.Close()

	got, info, err := fetchUpdate(srv.URL+"/", 6)
	if err != nil || string(got) != string(exe) || info.Version != 7 {
		t.Fatalf("update not fetched: %v %q", err, got)
	}
	if got, _, err := fetchUpdate(srv.URL+"/", 7); err != nil || got != nil {
		t.Fatalf("same version should not download: %v %q", err, got)
	}
	meta = `{"version": 8, "sha256": "00"}` // 해시가 맞지 않으면 받지 않는다
	if got, _, err := fetchUpdate(srv.URL+"/", 6); err == nil || got != nil {
		t.Fatal("corrupt download accepted")
	}
	if _, _, err := fetchUpdate(srv.URL+"/missing/", 6); err == nil {
		t.Fatal("missing file should be an error")
	}
}
