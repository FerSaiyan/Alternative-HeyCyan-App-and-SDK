import json
import tempfile
import unittest
from pathlib import Path

from assemble_artemis_phone_pool import SOURCES, assemble
from test_audit_artemis_phone_episodes import episode


class AssemblyTest(unittest.TestCase):
    def test_three_independent_sources_still_not_training_ready(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            for i, source in enumerate(SOURCES):
                rows = episode(f"app{i}", root_hash=f"distinct-app-{i}")
                for row in rows:
                    row["appFamily"] = source
                (folder / source).write_text("".join(json.dumps(row) + "\n" for row in rows))
            summary, audit = assemble(folder)
            self.assertEqual(summary["independentScreenFamilies"], 3)
            self.assertFalse(audit["readyForIndependentSplit"])
            self.assertFalse(audit["readyForTaskerTraining"])
            self.assertEqual(len((folder / "pool.jsonl").read_text().splitlines()), 9)
            self.assertFalse(json.loads((folder / "manifest.json").read_text())["splitAssigned"])


if __name__ == "__main__":
    unittest.main()
