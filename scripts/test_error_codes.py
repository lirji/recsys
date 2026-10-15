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

    def test_families_reject_ambiguous_prefixes_and_inconsistent_statuses(self):
        mutations = [
            lambda c: c.update(families=None),
            lambda c: c["families"].append(None),
            lambda c: c["families"].append(copy.deepcopy(c["families"][0])),
            lambda c: c["families"][0].update(prefix="bad<script>"),
            lambda c: c["families"][0].update(prefix="A" * 61),
            lambda c: c["families"][1].update(prefix=c["families"][0]["prefix"]),
            lambda c: c["families"][0].update(fallback="UNKNOWN"),
            lambda c: c["families"][0].update(fallback=[]),
            lambda c: c["families"][0].update(fallback="BAD_REQUEST"),
            lambda c: next(e for e in c["errors"] if e["code"] == "DELIVERY_503").update(httpStatus=500),
        ]
        for mutate in mutations:
            candidate = copy.deepcopy(self.catalog)
            mutate(candidate)
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                generator.validate(candidate)

    def test_wire_aliases_reject_cycles_wrong_status_and_non_http_messages(self):
        def variant(c):
            return next(e for e in c["errors"] if e["code"] == "BUDGET_RESULT_UNKNOWN")
        mutations = [
            lambda c: variant(c).update(wireCode=None),
            lambda c: variant(c).update(wireCode=[]),
            lambda c: variant(c).update(wireCode="MISSING"),
            lambda c: variant(c).update(wireCode="BUDGET_RESULT_UNKNOWN"),
            lambda c: variant(c).update(wireCode="BUDGET_DEPENDENCY_UNAVAILABLE"),
            lambda c: variant(c).update(wireCode="BAD_REQUEST"),
            lambda c: variant(c).update(httpStatus=0),
            lambda c: next(e for e in c["errors"] if e["code"] == "SERVICE_UNAVAILABLE").update(wireCode="BUDGET_RESULT_UNKNOWN"),
        ]
        for mutate in mutations:
            candidate = copy.deepcopy(self.catalog)
            mutate(candidate)
            with self.subTest(candidate=candidate), self.assertRaises(ValueError):
                generator.validate(candidate)

    def test_family_metadata_does_not_change_flattened_client_contract(self):
        old = copy.deepcopy(self.catalog)
        old.pop("families")
        self.assertEqual(generator.outputs(old)[generator.TYPESCRIPT], generator.outputs(self.catalog)[generator.TYPESCRIPT])
        self.assertEqual(generator.outputs(old)[generator.JAVA], generator.outputs(self.catalog)[generator.JAVA])

    def test_long_cjk_message_wraps_without_changing_its_literal(self):
        long_message = "检查" * 30 + " --skip-existing"
        candidate = copy.deepcopy(self.catalog)
        candidate["errors"][0]["message"] = long_message
        generated = generator.outputs(candidate)[generator.TYPESCRIPT]
        self.assertIn("    message:\n      '" + long_message + "',", generated)
        candidate["errors"][0]["message"] = "检查参数"
        self.assertIn("    message: '检查参数',", generator.outputs(candidate)[generator.TYPESCRIPT])

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
                    for relative in (generator.JAVA, generator.JAVA_FAMILY, generator.TYPESCRIPT):
                        path = root / relative
                        original = path.read_text()
                        path.write_text("stale")
                        self.assertEqual(1, generator.main())
                        self.assertEqual("stale", path.read_text())
                        path.write_text(original)


if __name__ == "__main__":
    unittest.main()
