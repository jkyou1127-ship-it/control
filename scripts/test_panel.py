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

    def test_relayed_requests_count_as_the_owner_but_are_still_validated(self):
        title, author, owner = panel.request_from_env({"REQUEST_KIND": "lab", "REQUEST_TARGET": "kps", "REPO_OWNER": "Owner"})
        self.assertEqual((title, author, owner), ("[정식 요청] 실험실 kps", "Owner", "Owner"))
        self.assertIsNone(panel.evaluate(title, author, owner, self.dir)[2])
        for kind, target in (("build", "1.21; echo x"), ("lab", "../x"), ("other", "1.21"), ("build", "")):
            t, a, o = panel.request_from_env({"REQUEST_KIND": kind, "REQUEST_TARGET": target, "REPO_OWNER": "Owner"})
            self.assertIsNotNone(panel.evaluate(t, a, o, self.dir)[2], (kind, target))
        # 중계 서버를 거치지 않은 이슈 요청은 예전처럼 제목과 작성자로 판단한다
        self.assertEqual(panel.request_from_env({"ISSUE_TITLE": "t", "ISSUE_AUTHOR": "a", "REPO_OWNER": "o"}), ("t", "a", "o"))

    def test_relay_address_survives_graduation(self):
        self.write("labs.json", {"features": ["kps"], "graduated": [], "relay": "https://relay.example"})
        panel.apply("lab", "kps", self.dir)
        with open(os.path.join(self.dir, "labs.json")) as f:
            self.assertEqual(json.load(f), {"features": ["kps"], "graduated": ["kps"], "hidden": [], "alpha": [], "relay": "https://relay.example"})

    def test_hide_and_restore(self):
        self.assertEqual(panel.parse_title("[숨김 요청] 실험실 kps"), ("hide", "kps"))
        self.assertEqual(panel.parse_title("[복귀 요청] 실험실 kps"), ("restore", "kps"))
        self.assertIsNone(panel.parse_title("[숨김 요청] 빌드 1.21"))
        self.assertIn("이미 실험실", self.evaluate("[복귀 요청] 실험실 kps")[2])     # 숨기지도 올리지도 않은 것

        self.assertIsNone(self.evaluate("[숨김 요청] 실험실 kps")[2])
        panel.apply("hide", "kps", self.dir)
        self.assertEqual(panel.load(os.path.join(self.dir, "labs.json"))["hidden"], ["kps"])
        self.assertIn("이미 숨긴", self.evaluate("[숨김 요청] 실험실 kps")[2])
        self.assertIn("숨긴 기능", self.evaluate("[정식 요청] 실험실 kps")[2])     # 숨긴 채로는 정식에 올리지 않는다

        panel.apply("restore", "kps", self.dir)
        labs = panel.load(os.path.join(self.dir, "labs.json"))
        self.assertEqual((labs["hidden"], labs["graduated"]), ([], []))

    def test_restore_takes_a_graduated_feature_back_to_labs_and_keeps_graduation_when_unhiding(self):
        panel.apply("lab", "kps", self.dir)
        panel.apply("hide", "kps", self.dir)
        panel.apply("restore", "kps", self.dir)                                   # 숨김만 풀리고 정식은 그대로
        labs = panel.load(os.path.join(self.dir, "labs.json"))
        self.assertEqual((labs["hidden"], labs["graduated"]), ([], ["kps"]))
        panel.apply("restore", "kps", self.dir)                                   # 이번엔 정식에서 실험실로
        self.assertEqual(panel.load(os.path.join(self.dir, "labs.json"))["graduated"], [])

    def test_relayed_hide_and_restore(self):
        for kind, word in (("hide", "[숨김 요청]"), ("restore", "[복귀 요청]")):
            title, _, _ = panel.request_from_env({"REQUEST_KIND": kind, "REQUEST_TARGET": "kps", "REPO_OWNER": "o"})
            self.assertEqual(title, word + " 실험실 kps")

    def test_snapshot_keeps_the_requested_build_when_a_newer_alpha_arrives(self):
        snap = os.path.join(self.dir, "snap")
        os.mkdir(snap)
        for name in ("version.json", "ControlPad.apk"):
            with open(os.path.join(self.dir, "alpha", name), "rb") as a, open(os.path.join(snap, name), "wb") as b:
                b.write(a.read())
        # 기다리는 사이에 새 알파(1.22)가 올라옴
        self.write("alpha/version.json", {"versionCode": 48, "versionName": "1.22"})
        self.assertIn("지금 알파 빌드", self.evaluate("[정식 요청] 빌드 1.21")[2])                # 떠 둔 것이 없으면 거절
        self.assertIsNone(panel.evaluate("[정식 요청] 빌드 1.21", "owner", "Owner", self.dir, snap)[2])
        panel.apply("build", "1.21", self.dir, snap)
        self.assertEqual(panel.load(os.path.join(self.dir, "version.json"))["versionName"], "1.21")
        with open(os.path.join(self.dir, "ControlPad.apk"), "rb") as f:
            self.assertEqual(f.read(), b"alpha")
        # 정식이 이미 더 새 빌드면 올리지 않는다
        self.write("version.json", {"versionCode": 48, "versionName": "1.22"})
        self.assertIn("이미 정식", panel.evaluate("[정식 요청] 빌드 1.21", "owner", "Owner", self.dir, snap)[2])

    def test_lab_goes_to_all_alpha_users_first_then_stable(self):
        self.assertEqual(panel.WAITS["lab"], (600, 1200))                   # 10분 뒤 알파, 그로부터 20분 뒤 정식
        self.assertEqual(panel.WAITS["build"], (1800, 0))
        self.assertEqual(panel.WAITS["hide"], (0, 0))                       # 숨김은 바로
        self.assertEqual(panel.WAITS["restore"], (0, 0))                    # 복귀도 바로
        self.assertIn("바로", panel.plan("restore", "kps"))
        self.assertIn("바로", panel.plan("hide", "kps"))
        self.assertIn("10분 뒤", panel.plan("lab", "kps"))
        self.assertIn("30분 뒤", panel.plan("build", "1.21"))
        panel.apply("lab", "kps", self.dir, stage="alpha")
        labs = panel.load(os.path.join(self.dir, "labs.json"))
        self.assertEqual((labs["alpha"], labs["graduated"]), (["kps"], []))
        self.assertIsNone(self.evaluate("[정식 요청] 실험실 kps")[2])        # 2단계도 그대로 진행할 수 있다
        panel.apply("lab", "kps", self.dir)
        labs = panel.load(os.path.join(self.dir, "labs.json"))
        self.assertEqual((labs["alpha"], labs["graduated"]), ([], ["kps"]))

    def test_hiding_between_the_two_stages_stops_the_promotion(self):
        panel.apply("lab", "kps", self.dir, stage="alpha")
        panel.apply("hide", "kps", self.dir)
        self.assertIn("숨긴 기능", self.evaluate("[정식 요청] 실험실 kps")[2])
        panel.apply("restore", "kps", self.dir)                              # 숨김만 풀면 알파 단계로 돌아온다
        self.assertEqual(panel.load(os.path.join(self.dir, "labs.json"))["alpha"], ["kps"])
        panel.apply("restore", "kps", self.dir)                              # 한 번 더 복귀하면 실험실로
        self.assertEqual(panel.load(os.path.join(self.dir, "labs.json"))["alpha"], [])

    def test_needs_panel_flag_is_dropped_on_promotion(self):
        self.write("alpha/version.json", {"versionCode": 47, "versionName": "2.0", "needsPanel": True})
        self.assertIsNone(self.evaluate("[정식 요청] 빌드 2.0")[2])
        panel.apply("build", "2.0", self.dir)
        stable = panel.load(os.path.join(self.dir, "version.json"))
        self.assertEqual(stable["versionName"], "2.0")
        self.assertNotIn("needsPanel", stable)

    def test_real_release_files_are_consistent(self):
        release = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "release")
        labs = panel.load(os.path.join(release, "labs.json"))
        self.assertTrue(set(labs["graduated"]) <= set(labs["features"]))
        self.assertTrue(set(labs.get("hidden", [])) <= set(labs["features"]))
        self.assertTrue(os.path.exists(os.path.join(release, "panel.txt")))


if __name__ == "__main__":
    unittest.main()
