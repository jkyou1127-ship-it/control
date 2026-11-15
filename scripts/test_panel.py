import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import panel


class PanelTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        os.mkdir(os.path.join(self.dir, "alpha"))
        self.write("version.json", {"versionCode": 46, "versionName": "1.20"})
        self.write("alpha/version.json", {"versionCode": 47, "versionName": "1.21"})
        self.write("labs.json", {"features": ["kps", "press-pop"], "graduated": []})
        for name, data in (("ControlPad.apk", b"stable"), ("alpha/ControlPad.apk", b"alpha")):
            with open(os.path.join(self.dir, name), "wb") as f:
                f.write(data)
        with open(os.path.join(self.dir, "panel.txt"), "w") as f:
            f.write("# 평가단\nTester-One\n")

    def write(self, name, data):
        with open(os.path.join(self.dir, name), "w") as f:
            json.dump(data, f)

    def evaluate(self, title, author="owner"):
        return panel.evaluate(title, author, "Owner", self.dir)

    def test_titles(self):
        self.assertEqual(panel.parse_title("[정식 요청] 빌드 1.21"), ("build", "1.21"))
        self.assertEqual(panel.parse_title(" [정식 요청] 빌드 BETA 1.9 "), ("build", "BETA 1.9"))
        self.assertEqual(panel.parse_title("[정식 요청] 실험실 press-pop"), ("lab", "press-pop"))
        for bad in ("정식 요청 빌드 1.21", "[정식 요청] 빌드 $(rm -rf /)", "[정식 요청] 빌드 1.21; echo x",
                    "[정식 요청] 실험실 ../../x", "[정식 요청] 빌드 1.21\nextra", "[정식 요청] 실험실 KPS", "[정식 요청]"):
            self.assertIsNone(panel.parse_title(bad), bad)

    def test_only_panel_members_and_owner(self):
        self.assertIsNone(self.evaluate("[정식 요청] 빌드 1.21")[2])                      # 저장소 주인
        self.assertIsNone(self.evaluate("[정식 요청] 빌드 1.21", "tester-one")[2])        # 대소문자 무시
        self.assertIn("평가단", self.evaluate("[정식 요청] 빌드 1.21", "stranger")[2])
        with open(os.path.join(self.dir, "panel.txt"), "a") as f:
            f.write("*\n")
        self.assertIsNone(self.evaluate("[정식 요청] 빌드 1.21", "stranger")[2])          # 모두에게 열어 둔 경우

    def test_build_must_be_the_current_alpha_and_newer_than_stable(self):
        self.assertIn("지금 알파 빌드", self.evaluate("[정식 요청] 빌드 1.19")[2])
        panel.apply("build", "1.21", self.dir)
        with open(os.path.join(self.dir, "ControlPad.apk"), "rb") as f:
            self.assertEqual(f.read(), b"alpha")
        with open(os.path.join(self.dir, "version.json")) as f:
            self.assertEqual(json.load(f)["versionName"], "1.21")
        self.assertIn("이미 정식", self.evaluate("[정식 요청] 빌드 1.21")[2])               # 두 번 올리지 않는다

    def test_lab_feature_graduates_once(self):
        self.assertIn("없는 기능", self.evaluate("[정식 요청] 실험실 nope")[2])
        self.assertIsNone(self.evaluate("[정식 요청] 실험실 kps")[2])
        panel.apply("lab", "kps", self.dir)
        with open(os.path.join(self.dir, "labs.json")) as f:
            self.assertEqual(json.load(f)["graduated"], ["kps"])
        self.assertIn("이미 정식", self.evaluate("[정식 요청] 실험실 kps")[2])

    def test_real_release_files_are_consistent(self):
        release = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "release")
        labs = panel.load(os.path.join(release, "labs.json"))
        self.assertTrue(set(labs["graduated"]) <= set(labs["features"]))
        self.assertTrue(os.path.exists(os.path.join(release, "panel.txt")))


if __name__ == "__main__":
    unittest.main()
