import unittest

import gridmaster_snapshot as snapshot


class GridMasterSnapshotTests(unittest.TestCase):
    def test_generates_every_valid_four_character_grid_once(self):
        grids = snapshot.all_four_char_grids()
        self.assertEqual(32_400, len(grids))
        self.assertEqual(32_400, len(set(grids)))
        self.assertEqual("AA00", grids[0])
        self.assertEqual("RR99", grids[-1])
        self.assertTrue(all(snapshot.normalize_grid(grid) == grid for grid in grids))

    def test_invalid_grid_is_rejected(self):
        self.assertIsNone(snapshot.normalize_grid("ZZ99"))
        self.assertIsNone(snapshot.normalize_grid("JO31OL"))
        self.assertEqual("JO31", snapshot.normalize_grid(" jo31 "))

    def test_filters_missing_callsigns_before_taking_six(self):
        rows = [
            {"call": "DL1AAA", "date": "2026-11-06"},
            {"date": "2026-11-05"},
            {"call": "DL2BBB", "date": "2026-11-04"},
            {"call": "DL3CCC", "date": "2026-11-03"},
            {"call": "DL4DDD", "date": "2026-11-02"},
            {"call": "DL5EEE", "date": "2026-11-01"},
            {"call": "DL6FFF", "date": "2026-10-31"},
            {"call": "DL7GGG", "date": "2026-10-30"},
        ]
        result = snapshot.clean_operators(rows)
        self.assertEqual(
            ["DL1AAA", "DL2BBB", "DL3CCC", "DL4DDD", "DL5EEE", "DL6FFF"],
            [row["call"] for row in result],
        )

    def test_retry_after_seconds_and_fallback(self):
        self.assertEqual(12.0, snapshot.parse_retry_after("12"))
        self.assertEqual(
            snapshot.TOKEN_REFILL_SECONDS,
            snapshot.parse_retry_after(None),
        )


if __name__ == "__main__":
    unittest.main()
