//go:build windows

package main

import (
	"os"
	"strings"
	"syscall"
	"unsafe"
)

var (
	user32        = syscall.NewLazyDLL("user32.dll")
	procSendInput = user32.NewProc("SendInput")
	kernel32      = syscall.NewLazyDLL("kernel32.dll")
	shell32       = syscall.NewLazyDLL("shell32.dll")
)

// isAdmin 은 관리자 권한으로 실행 중인지 알려 준다.
func isAdmin() bool {
	r, _, _ := shell32.NewProc("IsUserAnAdmin").Call()
	return r != 0
}

// relaunchAsAdmin 은 같은 프로그램을 관리자 권한으로 다시 실행한다(권한 요청 창이 뜬다).
// 새로 실행되면 참을 돌려주고, 이 프로그램은 끝내면 된다. 사용자가 거절하면 거짓.
func relaunchAsAdmin(args []string) bool {
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	quoted := make([]string, len(args))
	for i, a := range args {
		quoted[i] = syscall.EscapeArg(a)
	}
	verb, _ := syscall.UTF16PtrFromString("runas")
	file, _ := syscall.UTF16PtrFromString(exe)
	params, _ := syscall.UTF16PtrFromString(strings.Join(quoted, " "))
	r, _, _ := shell32.NewProc("ShellExecuteW").Call(0, uintptr(unsafe.Pointer(verb)), uintptr(unsafe.Pointer(file)),
		uintptr(unsafe.Pointer(params)), 0, 1) // SW_SHOWNORMAL
	return r > 32
}

// 64비트 Windows 의 INPUT 구조체(40바이트)를 종류별로 풀어 쓴 것.
type keybdInput struct {
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

type mouseInput struct {
	typ       uint32
	_         uint32
	dx, dy    int32
	mouseData uint32
	flags     uint32
	time      uint32
	_         uint32
	extra     uintptr
}

type winInjector struct{}

func newInjector() (Injector, error) { return winInjector{}, nil }

// setupConsole 은 한글이 깨지지 않게 콘솔 출력을 UTF-8 로 바꾼다.
// 그리고 다른 프로그램에 밀려 입력이 늦어지지 않게 우선순위를 높인다.
func setupConsole() {
	kernel32.NewProc("SetConsoleOutputCP").Call(65001)
	if h, _, _ := kernel32.NewProc("GetCurrentProcess").Call(); h != 0 {
		kernel32.NewProc("SetPriorityClass").Call(h, 0x00000080) // HIGH_PRIORITY_CLASS
	}
}

func (winInjector) Key(usage int, down bool) {
	in := keybdInput{typ: 1}
	if vk, ok := vkeys[usage]; ok {
		in.vk = vk
	} else if s, ok := scancodes[usage]; ok {
		in.scan = s.code
		in.flags = 0x0008 // KEYEVENTF_SCANCODE
		if s.extended {
			in.flags |= 0x0001 // KEYEVENTF_EXTENDEDKEY
		}
	} else {
		return
	}
	if !down {
		in.flags |= 0x0002 // KEYEVENTF_KEYUP
	}
	procSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

func sendMouse(dx, dy int, data uint32, flags uint32) {
	in := mouseInput{typ: 0, dx: int32(dx), dy: int32(dy), mouseData: data, flags: flags}
	procSendInput.Call(1, uintptr(unsafe.Pointer(&in)), unsafe.Sizeof(in))
}

func (winInjector) Move(dx, dy int) { sendMouse(dx, dy, 0, 0x0001) } // MOUSEEVENTF_MOVE

func (winInjector) Button(index int, down bool) {
	// LEFTDOWN/UP, RIGHTDOWN/UP, MIDDLEDOWN/UP
	flags := [3][2]uint32{{0x0002, 0x0004}, {0x0008, 0x0010}, {0x0020, 0x0040}}
	if down {
		sendMouse(0, 0, 0, flags[index][0])
	} else {
		sendMouse(0, 0, 0, flags[index][1])
	}
}

func (winInjector) Wheel(steps int) {
	sendMouse(0, 0, uint32(int32(steps*120)), 0x0800) // MOUSEEVENTF_WHEEL, 한 칸 = 120
}
