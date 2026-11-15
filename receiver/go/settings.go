package main

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// settings 는 창에서 바꾸는 설정 중 이 프로그램이 직접 기억하는 것들이다.
// (Windows 시작 시 자동 실행은 Windows 작업 스케줄러에 들어 있는지를 그때그때 확인한다.)
type settings struct {
	OnTop bool  `json:"onTop"` // 창을 항상 위에 표시
	X     int32 `json:"x"`     // 창을 닫을 때의 위치(다음에 같은 자리에 연다)
	Y     int32 `json:"y"`
	Saved bool  `json:"saved"` // 위치를 기억해 둔 적이 있는지
	Theme int   `json:"theme"` // 창 테마 번호(windowThemes)
	Total int   `json:"total"` // 지금까지 누른 횟수(누적)
	Beep  bool  `json:"beep"`  // 태블릿이 연결되거나 끊기면 알림음
	Mouse int   `json:"mouse"` // 마우스 감도 단계(mouseScales 의 번호, 기본 3 = 1배)

	Mini    bool   `json:"mini"`    // 미니 모드(지금 누르는 키만 크게)
	Opacity int    `json:"opacity"` // 창 투명도 단계(opacities 의 번호, 0 = 불투명)
	LockIP  string `json:"lockIP"`  // '이 태블릿만 받기'로 고정한 기기 주소(비어 있으면 모두 받음)
}

// opacities 는 고를 수 있는 창 불투명도(%)다.
var opacities = []int{100, 90, 80, 70, 60}

func opacityAt(i int) int {
	if i < 0 || i >= len(opacities) {
		return 100
	}
	return opacities[i]
}

// mouseScales 는 고를 수 있는 마우스 감도 배율이다.
var mouseScales = []float64{0.25, 0.5, 0.75, 1, 1.25, 1.5, 2, 3}

// mouseScale 은 단계 번호를 배율로 바꾼다(범위를 벗어나면 1배).
func mouseScale(i int) float64 {
	if i < 0 || i >= len(mouseScales) {
		return 1
	}
	return mouseScales[i]
}

func settingsPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(dir, "ControlPad", "receiver.json")
}

func loadSettings(path string) settings {
	s := settings{Mouse: 3}
	if b, err := os.ReadFile(path); err == nil {
		json.Unmarshal(b, &s)
	}
	return s
}

func saveSettings(path string, s settings) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	b, _ := json.MarshalIndent(s, "", "  ")
	return os.WriteFile(path, b, 0o644)
}
