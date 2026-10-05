"""The evidence gate must fail closed on an unverified interop direction."""
import unittest
import evidence
import copy
import json
from pathlib import Path


class EvidenceTests(unittest.TestCase):
    def test_missing_peer_cannot_be_a_pass(self):
        with self.assertRaisesRegex(ValueError, "two peers"):
            evidence.verify([], [], [])

    def test_mismatched_sas_cannot_be_a_pass(self):
        peers = [[{"event": "begin", "role": role},
                  {"event": "pairing", "sas": sas}]
                 for role, sas in [("sender", "123456"), ("receiver", "654321")]]
        with self.assertRaisesRegex(ValueError, "SAS"):
            evidence.verify(*peers, [])

    def valid_records(self):
        manifest = json.loads((Path(__file__).parent / "fixtures/manifest.json").read_text())
        logs = [[], []]
        def add(index, event, **fields):
            logs[index].append(dict(event=event, run="unit", role=["sender", "receiver"][index], **fields))
        for index in (0, 1):
            add(index, "begin", platform=["android", "ios-simulator"][index], name=["a", "b"][index])
            add(index, "discovered", id=["peer-b", "peer-a"][index], name=["b", "a"][index], protocol="1.0", addresses="192.0.2.1", port=12345)
            add(index, "pairing", id=["peer-b", "peer-a"][index], sas="012345", decision="confirm")
            add(index, "operation", operation="confirm", error="")
        add(0, "clipboard", decision="explicit_read_send")
        for index, fixture in enumerate(manifest):
            transfer = f"transfer-{index}"
            add(1, "offer", id=transfer, kind=fixture["kind"], size=fixture["size"])
            add(1, "consent", id=transfer, decision="accept")
            for side in (0, 1):
                add(side, "terminal", id=transfer, error="")
            for side, event in ((0, "sent"), (1, "received")):
                add(side, event, id=transfer, **{k: fixture[k] for k in ("name", "size", "sha256")})
        add(1, "offer", id="rejected", kind="text", size=6)
        add(1, "consent", id="rejected", decision="reject")
        for side in (0, 1):
            add(side, "terminal", id="rejected", error="7")
            add(side, "complete", terminals=7)
        return logs, manifest

    def test_complete_evidence_passes(self):
        logs, manifest = self.valid_records()
        self.assertEqual(evidence.verify(*logs, manifest)["successes"], 6)

    def test_evidence_mutations_fail_closed(self):
        # These are concrete omissions/corruptions that would falsely claim an interop pass.
        mutations = {
            "hash": lambda a,b: next(r for r in b if r["event"] == "received").update(sha256="bad"),
            "kind": lambda a,b: next(r for r in b if r["event"] == "offer").update(kind="file"),
            "missing consent": lambda a,b: b.remove(next(r for r in b if r["event"] == "consent")),
            "duplicate terminal": lambda a,b: a.append(copy.deepcopy(next(r for r in a if r["event"] == "terminal"))),
            "missing confirm": lambda a,b: b.remove(next(r for r in b if r["event"] == "operation")),
            "early terminal": lambda a,b: b.insert(0, b.pop(next(i for i,r in enumerate(b) if r["event"] == "terminal"))),
            "mixed run": lambda a,b: b[0].update(run="another-run"),
            "missing completion": lambda a,b: b.pop(),
            "wrong transfer id": lambda a,b: next(r for r in b if r["event"] == "received").update(id="other"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name):
                logs, manifest = self.valid_records(); mutate(*logs)
                with self.assertRaises(ValueError):
                    evidence.verify(*logs, manifest)


if __name__ == "__main__":
    unittest.main()
