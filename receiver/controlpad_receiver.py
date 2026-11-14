#!/usr/bin/env python3
"""ControlPad PC 수신 프로그램.

태블릿 앱이 UDP 로 보내는 키 상태를 받아 PC 키보드 입력으로 바꾼다.
앱의 '연결 설정 > Wi-Fi / USB 테더링' 방식에서만 필요하다(블루투스 방식은 필요 없음).

    python controlpad_receiver.py            # Windows 에서 실행
    python controlpad_receiver.py --dry-run  # 입력을 넣지 않고 받은 키만 출력

키 패킷: 'C' 'P' 버전(1) 순번(4바이트 big-endian) 리포트(16바이트)
리포트: [0] 수정키 비트(HID usage 0xE0~0xE7), [1..15] usage 0x00~0x77 비트맵
마우스 패킷(노브): 'C' 'M' 버전(1) 순번(4바이트) dx(2바이트) dy(2바이트), 부호 있는 big-endian
"""
import argparse
import socket
import sys
import time

DEFAULT_PORT = 34555
REPORT_SIZE = 16
TIMEOUT = 1.0  # 이 시간 동안 패킷이 없으면 눌린 키를 모두 뗀다(앱 종료·연결 끊김 대비)

# HID usage -> (PS/2 set 1 스캔코드, 확장 키 여부)
SCANCODES = {}
for _i, _sc in enumerate([0x1E, 0x30, 0x2E, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
                          0x31, 0x18, 0x19, 0x10, 0x13, 0x1F, 0x14, 0x16, 0x2F, 0x11, 0x2D, 0x15, 0x2C]):
    SCANCODES[0x04 + _i] = (_sc, False)                      # A-Z
for _i in range(10):
    SCANCODES[0x1E + _i] = (0x02 + _i, False)                # 1-9, 0
for _i in range(10):
    SCANCODES[0x3A + _i] = (0x3B + _i, False)                # F1-F10
for _i, _sc in enumerate([0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49, 0x52, 0x53]):
    SCANCODES[0x59 + _i] = (_sc, False)                      # 숫자패드 1-9, 0, .
SCANCODES.update({
    0x28: (0x1C, False), 0x29: (0x01, False), 0x2A: (0x0E, False), 0x2B: (0x0F, False),
    0x2C: (0x39, False), 0x2D: (0x0C, False), 0x2E: (0x0D, False), 0x2F: (0x1A, False),
    0x30: (0x1B, False), 0x31: (0x2B, False), 0x33: (0x27, False), 0x34: (0x28, False),
    0x35: (0x29, False), 0x36: (0x33, False), 0x37: (0x34, False), 0x38: (0x35, False),
    0x39: (0x3A, False), 0x44: (0x57, False), 0x45: (0x58, False),
    0x46: (0x37, True), 0x49: (0x52, True), 0x4A: (0x47, True), 0x4B: (0x49, True),
    0x4C: (0x53, True), 0x4D: (0x4F, True), 0x4E: (0x51, True),
    0x4F: (0x4D, True), 0x50: (0x4B, True), 0x51: (0x50, True), 0x52: (0x48, True),
    0x53: (0x45, False), 0x54: (0x35, True), 0x55: (0x37, False), 0x56: (0x4A, False),
    0x57: (0x4E, False), 0x58: (0x1C, True),
    0xE0: (0x1D, False), 0xE1: (0x2A, False), 0xE2: (0x38, False), 0xE3: (0x5B, True),
    0xE4: (0x1D, True), 0xE5: (0x36, False), 0xE6: (0x38, True), 0xE7: (0x5C, True),
})


def usages(report):
    """리포트에서 눌린 HID usage 집합을 꺼낸다."""
    down = set()
    for bit in range(8):
        if report[0] & (1 << bit):
            down.add(0xE0 + bit)
    for u in range(4, (REPORT_SIZE - 1) * 8):  # 0~3 은 키가 아닌 예약 값
        if report[1 + u // 8] & (1 << (u % 8)):
            down.add(u)
    return down


def parse(packet):
    """(순번, 리포트) 또는 형식이 다르면 None."""
    if len(packet) != 7 + REPORT_SIZE or packet[:2] != b"CP" or packet[2] != 1:
        return None
    return int.from_bytes(packet[3:7], "big"), packet[7:]


def parse_mouse(packet):
    """노브가 보낸 마우스 이동 (dx, dy) 또는 형식이 다르면 None."""
    if len(packet) != 11 or packet[:2] != b"CM" or packet[2] != 1:
        return None
    return (int.from_bytes(packet[7:9], "big", signed=True),
            int.from_bytes(packet[9:11], "big", signed=True))


class Receiver:
    """패킷을 받아 이전 상태와의 차이만큼 inject(usage, down) 을 부른다."""

    def __init__(self, inject):
        self.inject = inject
        self.down = set()
        self.seq = 0
        self.sender = None

    def feed(self, packet, sender=None):
        parsed = parse(packet)
        if parsed is None:
            return None
        seq, report = parsed
        if sender != self.sender or seq < self.seq - 1000:  # 새 기기이거나 앱이 다시 시작됨
            self.sender, self.seq = sender, 0
        if seq > self.seq:  # 늦게 도착한 옛 패킷은 무시
            self.seq = seq
            self.apply(usages(report))
        return seq

    def apply(self, now):
        # 수정키는 먼저 누르고 나중에 떼야 Ctrl+C 같은 조합이 제대로 들어간다.
        for u in sorted(now - self.down, key=lambda u: (u < 0xE0, u)):
            self.inject(u, True)
        for u in sorted(self.down - now, key=lambda u: (u >= 0xE0, u)):
            self.inject(u, False)
        self.down = now

    def release_all(self):
        self.apply(set())


def windows_injector():
    import ctypes
    from ctypes import wintypes

    ULONG_PTR = ctypes.c_size_t

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = [("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class MOUSEINPUT(ctypes.Structure):  # INPUT 공용체 크기를 맞추기 위한 것
        _fields_ = [("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR)]

    class UNION(ctypes.Union):
        _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT)]

    class INPUT(ctypes.Structure):
        _fields_ = [("type", wintypes.DWORD), ("u", UNION)]

    send_input = ctypes.windll.user32.SendInput
    send_input.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
    send_input.restype = wintypes.UINT

    def inject(usage, down):
        entry = SCANCODES.get(usage)
        if entry is None:
            return
        scan, extended = entry
        flags = 0x0008 | (0x0001 if extended else 0) | (0 if down else 0x0002)  # SCANCODE | EXTENDED | KEYUP
        inp = INPUT(type=1, u=UNION(ki=KEYBDINPUT(0, scan, flags, 0, 0)))
        send_input(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    def move(dx, dy):
        inp = INPUT(type=0, u=UNION(mi=MOUSEINPUT(dx, dy, 0, 0x0001, 0, 0)))  # MOUSEEVENTF_MOVE
        send_input(1, ctypes.byref(inp), ctypes.sizeof(INPUT))

    return inject, move


def local_addresses():
    found = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(info[4][0])
    except OSError:
        pass
    try:  # 실제로 보내지는 않고, 바깥으로 나갈 때 쓰는 주소만 알아낸다
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("192.0.2.1", 9))
            found.add(s.getsockname()[0])
    except OSError:
        pass
    return sorted(a for a in found if not a.startswith("127."))


def main(argv=None):
    ap = argparse.ArgumentParser(description="ControlPad PC 수신 프로그램")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--dry-run", action="store_true", help="키를 입력하지 않고 화면에만 출력")
    args = ap.parse_args(argv)

    if args.dry_run:
        def inject(usage, down):
            print(("누름 " if down else "뗌   ") + "0x%02X" % usage, flush=True)

        def move(dx, dy):
            print("마우스 %+d %+d" % (dx, dy), flush=True)
    elif sys.platform == "win32":
        inject, move = windows_injector()
    else:
        sys.exit("키 입력은 Windows 에서만 지원합니다. 다른 OS 에서는 --dry-run 으로 수신만 확인할 수 있습니다.")

    rx = Receiver(inject)
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", args.port))
    sock.settimeout(TIMEOUT)
    print("ControlPad 수신 대기 중. 태블릿 앱에 아래 주소를 입력하세요 (포트 %d):" % args.port)
    for a in local_addresses():
        print("   ", a)
    print("끝내려면 Ctrl+C", flush=True)

    last = time.monotonic()
    try:
        while True:
            try:
                packet, sender = sock.recvfrom(64)
            except socket.timeout:
                packet = None
            if packet is not None:
                delta = parse_mouse(packet)
                if delta is not None:
                    move(*delta)
                    last = time.monotonic()
                    continue
                seq = rx.feed(packet, sender)
                if seq is not None:
                    last = time.monotonic()
                    sock.sendto(b"CA" + seq.to_bytes(4, "big"), sender)
            if rx.down and time.monotonic() - last > TIMEOUT:
                rx.release_all()
    except KeyboardInterrupt:
        pass
    finally:
        rx.release_all()


if __name__ == "__main__":
    main()
