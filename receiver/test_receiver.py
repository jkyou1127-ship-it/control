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
        pkt = b"CM\x01" + (9).to_bytes(4, "big") + (-300).to_bytes(2, "big", signed=True) + (7).to_bytes(2, "big")
        self.assertEqual(r.parse_mouse(pkt), (-300, 7))
        self.assertIsNone(r.parse_mouse(packet(1, 0x07)))
        self.assertIsNone(self.rx.feed(pkt))          # 키 처리 쪽은 건드리지 않는다

    def test_every_app_key_has_a_scancode(self):
        # 앱의 Keys.kt 에 있는 usage 범위
        app = list(range(0x04, 0x28)) + list(range(0x28, 0x3A)) + list(range(0x3A, 0x47)) + \
            list(range(0x49, 0x64)) + list(range(0xE0, 0xE7))
        app.remove(0x32)
        self.assertEqual([hex(u) for u in app if u not in r.SCANCODES], [])

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
            s.sendto(b"CM\x01" + (2).to_bytes(4, "big") + (5).to_bytes(2, "big") + (-3).to_bytes(2, "big", signed=True),
                     ("127.0.0.1", port))
            time.sleep(r.TIMEOUT * 2 + 0.3)  # 패킷이 끊기면 스스로 키를 떼야 한다
        finally:
            proc.terminate()
            out = proc.communicate()[0]
        self.assertIn("누름 0x07", out)
        self.assertIn("마우스 +5 -3", out)
        self.assertIn("뗌   0x07", out)


if __name__ == "__main__":
    unittest.main()
