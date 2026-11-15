//go:build !windows

package main

import "errors"

func newInjector() (Injector, error) {
	return nil, errors.New("입력은 Windows 에서만 지원합니다. 다른 OS 에서는 --dry-run 으로 수신만 확인할 수 있습니다.")
}

func setupConsole() {}

func isAdmin() bool                      { return true }
func relaunchAsAdmin(args []string) bool { return false }
