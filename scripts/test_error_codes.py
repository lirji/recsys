#!/usr/bin/env python3
"""保护契约生成入口：拒绝含糊目录，阻止漏生成/手改后的双端漂移。"""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_error_codes as generator


class ErrorCodeContractTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((generator.ROOT / "contracts/errors.json").read_text())

    def test_catalog_contains_all_existing_unknown_result_recovery_messages(self):
        entries = {e["code"]: e for e in generator.validate(self.catalog)}
        for family in ("CONVERSION", "NATURAL_RECEIPT", "CLICK_RECEIPT", "CONVERSION_RECEIPT",
                       "RECEIPT", "DELIVERY", "NATURAL_DELIVERY", "FEED_DELIVERY"):
            entry = entries[family + "_503"]
            self.assertEqual(503, entry["httpStatus"])
            self.assertIn("结果未知", entry["message"])
            self.assertIn("查询", entry["message"])

    def test_ambiguous_or_untrusted_contracts_are_rejected(self):
        mutations = [
            lambda c: c.update(version=True),
            lambda c: c["errors"].append(copy.deepcopy(c["errors"][0])),
            lambda c: c["errors"][0].update(code="BAD<script>"),
            lambda c: c["errors"][0].update(httpStatus=200),
            lambda c: c["errors"][0].update(httpStatus=True),
            lambda c: c["errors"][0].update(httpStatus=0),
            lambda c: c["errors"][0].update(httpDefault="true"),
            lambda c: c["errors"][1].update(httpDefault=True),
            lambda c: c["errors"][0].update(message=""),
            lambda c: c["errors"][0].update(message="bad\nmessage"),
            lambda c: c["errors"].append(None),
        ]
        for mutate in mutations:
            candidate = copy.deepcopy(self.catalog)
            mutate(candidate)
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                generator.validate(candidate)
        with self.assertRaises(ValueError):
            generator.validate([])

    def test_check_fails_on_missing_or_modified_outputs_and_never_overwrites_them(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "contracts").mkdir()
            (root / "contracts/errors.json").write_text(json.dumps(self.catalog))
            with patch.object(generator, "ROOT", root), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                with patch("sys.argv", ["generate", "--check"]):
                    self.assertEqual(1, generator.main())
                with patch("sys.argv", ["generate"]):
                    self.assertEqual(0, generator.main())
                with patch("sys.argv", ["generate", "--check"]):
                    self.assertEqual(0, generator.main())
                    for relative in (generator.JAVA, generator.TYPESCRIPT):
                        path = root / relative
                        original = path.read_text()
                        path.write_text("stale")
                        self.assertEqual(1, generator.main())
                        self.assertEqual("stale", path.read_text())
                        path.write_text(original)


if __name__ == "__main__":
    unittest.main()
