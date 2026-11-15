package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"time"
)

// 이 프로그램의 버전. 올릴 때는 scripts/build-receiver.sh 를 다시 실행해 release/receiver.json 도 맞춘다.
const version = 4

const updateBase = "https://raw.githubusercontent.com/jkyou1127-ship-it/control/claude/vibrant-ramanujan-pdzsly/release/"

type updateInfo struct {
	Version int    `json:"version"`
	Sha256  string `json:"sha256"`
}

func get(url string, limit int64) ([]byte, error) {
	client := &http.Client{Timeout: 60 * time.Second}
	// 캐시된 옛 파일을 받지 않도록 주소에 시각을 붙인다.
	resp, err := client.Get(fmt.Sprintf("%s?t=%d", url, time.Now().UnixMilli()))
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, limit))
}

// fetchUpdate 는 current 보다 새 버전이 있으면 그 실행 파일을 내려받아 돌려준다. 없으면 nil.
// 내려받은 파일은 receiver.json 에 적힌 SHA-256 과 맞아야 한다.
func fetchUpdate(base string, current int) ([]byte, updateInfo, error) {
	var info updateInfo
	meta, err := get(base+"receiver.json", 1<<16)
	if err != nil {
		return nil, info, err
	}
	if err := json.Unmarshal(meta, &info); err != nil {
		return nil, info, err
	}
	if info.Version <= current {
		return nil, info, nil
	}
	exe, err := get(base+"ControlPadReceiver.exe", 64<<20)
	if err != nil {
		return nil, info, err
	}
	sum := sha256.Sum256(exe)
	if hex.EncodeToString(sum[:]) != info.Sha256 {
		return nil, info, errors.New("내려받은 파일이 손상되었습니다")
	}
	return exe, info, nil
}

// selfUpdate 는 새 버전이 있으면 자기 자신을 바꾸고 새 프로그램을 실행한다.
// 실행 중인 exe 는 덮어쓸 수 없지만 이름은 바꿀 수 있으므로, 옛 파일을 .old 로 옮기고 새 파일을 그 자리에 둔다.
func selfUpdate() {
	path, err := os.Executable()
	if err != nil {
		return
	}
	os.Remove(path + ".old") // 지난번 업데이트가 남긴 파일

	exe, info, err := fetchUpdate(updateBase, version)
	if err != nil {
		fmt.Println("업데이트를 확인하지 못했습니다. 지금 버전으로 계속합니다.")
		return
	}
	if exe == nil {
		return
	}
	fmt.Printf("새 버전 %d 으로 업데이트합니다…\n", info.Version)
	if err := os.WriteFile(path+".new", exe, 0o755); err != nil {
		fmt.Println("업데이트 파일을 쓸 수 없습니다. 지금 버전으로 계속합니다.")
		return
	}
	if err := os.Rename(path, path+".old"); err != nil {
		os.Remove(path + ".new")
		fmt.Println("업데이트하지 못했습니다. 지금 버전으로 계속합니다.")
		return
	}
	if err := os.Rename(path+".new", path); err != nil {
		os.Rename(path+".old", path) // 되돌린다
		fmt.Println("업데이트하지 못했습니다. 지금 버전으로 계속합니다.")
		return
	}
	// 같은 창에서 새 버전을 이어서 실행하고, 그것이 끝나면 함께 끝낸다.
	cmd := exec.Command(path, append([]string{"-no-update"}, os.Args[1:]...)...)
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := cmd.Run(); err != nil {
		fail("새 버전을 실행하지 못했습니다. 프로그램을 다시 실행해 주세요.")
	}
	os.Exit(0)
}
