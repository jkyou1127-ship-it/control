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
}

func settingsPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return ""
	}
	return filepath.Join(dir, "ControlPad", "receiver.json")
}

func loadSettings(path string) settings {
	var s settings
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
