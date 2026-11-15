//go:build !windows

package main

import (
	"bufio"
	"fmt"
	"os"
)

// runWindow 는 창이 없는 OS 에서는 받기만 계속한다.
func runWindow(a *app) { select {} }

// fail 은 이유를 보여 주고 Enter 를 기다린 뒤 끝낸다.
func fail(msg string) {
	fmt.Println(msg)
	fmt.Println("Enter 를 누르면 닫힙니다.")
	bufio.NewReader(os.Stdin).ReadString('\n')
	os.Exit(1)
}

func autostartEnabled() bool     { return false }
func setAutostart(on bool) error { return fmt.Errorf("Windows 에서만 됩니다") }
func copyText(text string) bool  { return false }
