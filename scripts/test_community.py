import json
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import community_index  # noqa: E402


class CommunityIndexTest(unittest.TestCase):
    def test_lists_valid_layouts_newest_first(self):
        with tempfile.TemporaryDirectory() as d:
            os.makedirs(os.path.join(d, "layouts"))
            def put(name, data):
                with open(os.path.join(d, "layouts", name), "w", encoding="utf-8") as f:
                    json.dump(data, f, ensure_ascii=False)
            put("a.json", {"name": "옛것", "author": "가", "created": "2026-11-16T00:00:00Z",
                           "layout": {"format": "controlpad-layout", "buttons": [1, 2]}})
            put("b.json", {"name": "새것", "created": "2026-11-17T00:00:00Z",
                           "layout": {"format": "controlpad-layout", "buttons": [1]}})
            put("c.json", {"name": "가짜", "layout": {"format": "other"}})
            with open(os.path.join(d, "layouts", "d.json"), "w") as f:
                f.write("{")
            idx = community_index.build(d)
            self.assertEqual([e["id"] for e in idx["layouts"]], ["b", "a"])
            self.assertEqual(idx["layouts"][0]["author"], "익명")
            self.assertEqual(idx["layouts"][1]["buttons"], 2)

    def test_real_index_is_well_formed(self):
        with open(os.path.join(os.path.dirname(__file__), "..", "community", "index.json"), encoding="utf-8") as f:
            self.assertIn("layouts", json.load(f))


if __name__ == "__main__":
    unittest.main()
