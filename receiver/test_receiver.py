import socket
import subprocess
import sys
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import controlpad_receiver as r


def packet(seq, *down):
    rep = bytearray(r.REPORT_SIZE)
    for u in down:
        if u >= 0xE0:
            rep[0] |= 1 << (u - 0xE0)
        else:
            rep[1 + u // 8] |= 1 << (u % 8)
    return b"CP\x01" + seq.to_bytes(4, "big") + bytes(rep)


def mouse_packet(seq, dx, dy, buttons, wheel):
    return (b"CM\x01" + seq.to_bytes(4, "big") + dx.to_bytes(2, "big", signed=True) +
            dy.to_bytes(2, "big", signed=True) + bytes([buttons]) + wheel.to_bytes(1, "big", signed=True))


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.rx = r.Receiver(lambda u, d: self.events.append((u, d)))

    def test_modifier_pressed_first_and_released_last(self):
        self.rx.feed(packet(1, 0x06, 0xE0))
        self.rx.feed(packet(2))
        self.assertEqual(self.events, [(0xE0, True), (0x06, True), (0x06, False), (0xE0, False)])

    def test_repeated_state_and_stale_packets_do_nothing(self):
        self.rx.feed(packet(5, 0x07))
        self.rx.feed(packet(6, 0x07))
        self.rx.feed(packet(4))
        self.assertEqual(self.events, [(0x07, True)])

    def test_app_restart_resets_sequence(self):
        self.rx.feed(packet(5000, 0x07))
        self.rx.feed(packet(1, 0x09))
        self.assertEqual(self.rx.down, {0x09})

    def test_garbage_is_ignored(self):
        self.assertIsNone(self.rx.feed(b"hello"))
        self.assertIsNone(self.rx.feed(packet(1)[:-1]))
        self.assertEqual(self.events, [])

    def test_mouse_packet(self):
        pkt = mouse_packet(9, -300, 7, 0b101, -2)
        self.assertEqual(r.parse_mouse(pkt), (9, -300, 7, 0b101, -2))
        self.assertIsNone(r.parse_mouse(packet(1, 0x07)))
        self.assertIsNone(self.rx.feed(pkt))          # 키 처리 쪽은 건드리지 않는다

    def test_mouse_buttons_follow_state_and_ignore_stale_packets(self):
        ev = []
        m = r.Mouse(lambda dx, dy: ev.append(("move", dx, dy)), lambda i, d: ev.append(("btn", i, d)),
                    lambda w: ev.append(("wheel", w)))
        m.feed(*r.parse_mouse(mouse_packet(1, 4, -2, 0b001, 0)))
        m.feed(*r.parse_mouse(mouse_packet(2, 0, 0, 0b001, 0)))      # 상태 유지용 재전송
        m.feed(*r.parse_mouse(mouse_packet(4, 0, 0, 0b010, 3)))
        m.feed(*r.parse_mouse(mouse_packet(3, 0, 0, 0b001, 0)))      # 늦게 도착한 패킷
        m.release_all()
        self.assertEqual(ev, [("move", 4, -2), ("btn", 0, True), ("wheel", 3), ("btn", 0, False),
                              ("btn", 1, True), ("btn", 1, False)])

    def test_discovery_reply(self):
        self.assertEqual(r.discovery_reply(b"CD\x01", "거실-PC"), b"CR\x01" + "거실-PC".encode())
        self.assertIsNone(r.discovery_reply(packet(1), "pc"))
        self.assertIsNone(r.discovery_reply(b"CD\x02", "pc"))

    def test_every_app_key_has_a_scancode(self):
        # 앱의 Keys.kt 에 있는 usage 범위
        app = list(range(0x04, 0x28)) + list(range(0x28, 0x3A)) + list(range(0x3A, 0x47)) + \
            list(range(0x49, 0x64)) + list(range(0xE0, 0xE7)) + [0x90, 0x91]
        app.remove(0x32)
        self.assertEqual([hex(u) for u in app if u not in r.SCANCODES and u not in r.VKEYS], [])

    def test_hangul_key_is_in_the_last_report_byte(self):
        self.rx.feed(packet(1, 0x90, 0x04))
        self.assertEqual(self.rx.down, {0x90, 0x04})
        self.assertEqual(len(packet(1)), 7 + 20)

    def test_end_to_end_over_udp(self):
        port = 34999
        proc = subprocess.Popen([sys.executable, "-u", r.__file__, "--dry-run", "--port", str(port)],
                                stdout=subprocess.PIPE, text=True)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(1)
            for _ in range(30):  # 수신 프로그램이 뜰 때까지 재시도
                s.sendto(packet(1, 0x07), ("127.0.0.1", port))
                try:
                    ack = s.recv(16)
                    break
                except (socket.timeout, ConnectionError):
                    time.sleep(0.1)
            else:
                self.fail("no ack")
            self.assertEqual(ack, b"CA" + (1).to_bytes(4, "big"))
            s.sendto(mouse_packet(2, 5, -3, 0b001, 0), ("127.0.0.1", port))
            time.sleep(r.TIMEOUT * 2 + 0.3)  # 패킷이 끊기면 스스로 키를 떼야 한다
        finally:
            proc.terminate()
            out = proc.communicate()[0]
        self.assertIn("누름 0x07", out)
        self.assertIn("마우스 +5 -3", out)
        self.assertIn("마우스 버튼 0 누름", out)
        self.assertIn("마우스 버튼 0 뗌", out)       # 패킷이 끊기면 마우스 버튼도 뗀다
        self.assertIn("뗌   0x07", out)


if __name__ == "__main__":
    unittest.main()
