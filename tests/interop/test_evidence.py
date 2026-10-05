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
            add(index, "begin", platform=["android", "ios-simulator"][index], sdkPlatform=["android", "ios"][index], name=["a", "b"][index], expectedPeer=["b", "a"][index])
            add(index, "discovered", id=["peer-b", "peer-a"][index], name=["b", "a"][index], platform=["IOS", 1][index], protocol="1.0", addresses="192.0.2.1", port=12345)
            add(index, "pairing", id=["peer-b", "peer-a"][index], sas="012345", decision="confirm")
            add(index, "operation", operation="confirm", error="")
        add(0, "clipboard", decision="explicit_read_send")
        for index, fixture in enumerate(manifest):
            transfer = f"transfer-{index}"
            add(1, "offer", id=transfer, requestId=index+10, kind=fixture["kind"], size=fixture["size"])
            add(1, "consent", id=transfer, requestId=index+10, decision="accept")
            add(1, "operation", operation="accept", id=transfer, requestId=index+10, error="")
            for side in (0, 1):
                add(side, "terminal", id=transfer, error="")
            for side, event in ((0, "sent"), (1, "received")):
                add(side, event, id=transfer, **{k: fixture[k] for k in fixture if k != "kind"})
        add(1, "offer", id="rejected", requestId=20, kind="text", size=6)
        add(1, "consent", id="rejected", requestId=20, decision="reject")
        add(1, "operation", operation="reject", id="rejected", requestId=20, error="")
        for side in (0, 1):
            add(side, "terminal", id="rejected", error="7")
            add(side, "complete", terminals=len(manifest)+1)
        return logs, manifest

    def test_complete_evidence_passes(self):
        logs, manifest = self.valid_records()
        self.assertEqual(evidence.verify(*logs, manifest)["successes"], len(manifest))

    def test_extended_scenario_cannot_claim_pass_without_cancellation_cleanup(self):
        logs, manifest = self.valid_records()
        for rows in logs: rows[0]["scenario"] = "extended"
        with self.assertRaisesRegex(ValueError, "extended"):
            evidence.verify(*logs, manifest)

    def extended_records(self):
        logs,manifest=self.valid_records()
        for index,rows in enumerate(logs):
            rows[0]["scenario"]="extended"
            rows[-1]["terminals"]=len(manifest)+2
            isolation=[r for r in rows if r.get("id")=="transfer-8"]
            rows[:]=[r for r in rows if r not in isolation]
            def add(event,**fields):
                rows.insert(-1,dict(event=event,run="unit",role=["sender","receiver"][index],**fields))
            if index==0:
                add("clipboard",decision="explicit_read_send")
                for _ in range(2):
                    add("operation",operation="clipboard_write",error="")
                    add("operation",operation="clipboard_read",error="")
                add("operation",operation="send_cancel",error="10")
                add("terminal",id="cancelled",error="10")
            else:
                add("offer",id="cancelled",requestId=21,kind="file",size=8388608)
                add("consent",id="cancelled",requestId=21,decision="accept")
                add("operation",operation="accept",id="cancelled",requestId=21,error="")
                add("progress",id="cancelled",bytes=262144,size=8388608)
                add("cancel",id="cancelled",bytes=262144,size=8388608)
                add("terminal",id="cancelled",error="8")
                add("operation",operation="cancel",id="cancelled",error="")
                add("cleanup",id="cancelled",deleted=True)
                add("pairing",id="peer-a",sas="999999",decision="confirm")
                add("operation",operation="confirm",error="")
            add("restart",trustStable=True)
            add("updated",id=["peer-b","peer-a"][index],port=12345,paired=True)
            add("discovery",id=["peer-b","peer-a"][index],port=23456,paired=True)
            add("rediscovered",id=["peer-b","peer-a"][index],port=23456,paired=True)
            rows[-1:-1]=isolation
            if index==1:add("offline",id="peer-a")
        return logs,manifest

    def test_restart_requires_peer_update_and_fresh_public_rediscovery(self):
        mutations={
            "missing update":lambda rows:rows.remove(next(r for r in rows if r["event"]=="updated")),
            "wrong update peer":lambda rows:next(r for r in rows if r["event"]=="updated").update(id="other"),
            "missing rediscovery":lambda rows:rows.remove(next(r for r in rows if r["event"]=="rediscovered")),
            "old port":lambda rows:next(r for r in rows if r["event"]=="rediscovered").update(port=12345),
            "wrong rediscovery peer":lambda rows:next(r for r in rows if r["event"]=="rediscovered").update(id="other"),
            "rediscovery before restart":lambda rows:rows.insert(0,rows.pop(next(i for i,r in enumerate(rows) if r["event"]=="rediscovered"))),
            "no public rediscovery event":lambda rows:rows.remove(next(r for r in rows if r["event"]=="discovery")),
        }
        for side in (0,1):
            for name,mutate in mutations.items():
                with self.subTest(side=side,mutation=name):
                    logs,manifest=self.extended_records();mutate(logs[side])
                    with self.assertRaises(ValueError):evidence.verify(*logs,manifest)

    def test_extended_valid_and_corruption_checks(self):
        logs,manifest=self.extended_records()
        self.assertEqual(evidence.verify(*logs,manifest)["cancellations"],1)
        mutations={
            "cleanup omission":lambda a,b:b.remove(next(r for r in b if r["event"]=="cleanup")),
            "staging remains":lambda a,b:next(r for r in b if r["event"]=="cleanup").update(deleted=False),
            "zero byte cancel":lambda a,b:next(r for r in b if r["event"]=="cancel").update(bytes=0),
            "completed cancel":lambda a,b:next(r for r in b if r["event"]=="cancel").update(bytes=8388608),
            "cancel command omitted":lambda a,b:b.remove(next(r for r in b if r.get("operation")=="cancel")),
            "restart trust changed":lambda a,b:next(r for r in a if r["event"]=="restart").update(trustStable=False),
            "offline omitted":lambda a,b:b.remove(next(r for r in b if r["event"]=="offline")),
            "early offline":lambda a,b:b.insert(0,b.pop(next(i for i,r in enumerate(b) if r["event"]=="offline"))),
            "clipboard read completion omitted":lambda a,b:a.remove(next(r for r in a if r.get("operation")=="clipboard_read")),
            "receiver wrong cancel outcome":lambda a,b:next(r for r in b if r["event"]=="terminal" and r["id"]=="cancelled").update(error="10"),
            "cancel progress mismatch":lambda a,b:next(r for r in b if r["event"]=="cancel").update(bytes=123),
            "isolation before cancel":lambda a,b:a.insert(0,a.pop(next(i for i,r in enumerate(a) if r["event"]=="sent" and r["name"]=="isolation.txt"))),
        }
        for name,mutate in mutations.items():
            with self.subTest(name=name):
                logs,manifest=self.extended_records();mutate(*logs)
                with self.assertRaises(ValueError):evidence.verify(*logs,manifest)

    def test_clipboard_reencoding_preserves_pixels_and_actual_transport_hash(self):
        logs,manifest=self.extended_records()
        fixture=next(r for r in manifest if r["name"]=="clipboard.png")
        fixture["pixel_sha256"]="80bd4e68af2714bf9b5b3fe21e22163ebd5cd6727001de0dc4ee4d630d8a3bc2"
        for rows in logs:
            for row in rows:
                if row.get("name")=="clipboard.png":row.update(size=99,sha256="a"*64,pixel_sha256=fixture["pixel_sha256"])
                if row.get("event")=="offer" and row["id"]=="transfer-7":row["size"]=99
        self.assertEqual(evidence.verify(*logs,manifest)["successes"],9)
        next(r for r in logs[1] if r.get("name")=="clipboard.png")["pixel_sha256"]="bad"
        with self.assertRaises(ValueError):evidence.verify(*logs,manifest)

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

    def test_public_decision_completion_is_required_and_correlated(self):
        for operation in ("accept", "reject"):
            for mutation in ("omit", "duplicate", "request", "transfer", "error"):
                with self.subTest(operation=operation, mutation=mutation):
                    logs, manifest = self.valid_records()
                    row = next(r for r in logs[1] if r["event"] == "operation" and r["operation"] == operation)
                    if mutation == "omit": logs[1].remove(row)
                    elif mutation == "duplicate": logs[1].append(copy.deepcopy(row))
                    elif mutation == "request": row["requestId"] = 999
                    elif mutation == "transfer": row["id"] = "unrelated"
                    else: row["error"] = "11"
                    with self.assertRaises(ValueError): evidence.verify(*logs, manifest)

    def test_reciprocal_host_metadata_and_discovered_platform(self):
        for mutation in ("expected name", "local platform", "remote platform", "both remote platforms", "same SDK platform"):
            with self.subTest(mutation=mutation):
                logs, manifest = self.valid_records()
                if mutation == "expected name": logs[0][0]["expectedPeer"] = "unrelated"
                elif mutation == "local platform": logs[0][0]["platform"] = "windows"
                elif mutation == "remote platform": logs[0][1]["platform"] = "WINDOWS"
                elif mutation == "both remote platforms":
                    logs[0][1]["platform"] = "WINDOWS"
                    logs[1][1]["platform"] = "WINDOWS"
                else:
                    logs[0][0].update(platform="ios", sdkPlatform="ios")
                    logs[1][1]["platform"] = "IOS"
                with self.assertRaises(ValueError): evidence.verify(*logs, manifest)

    def test_identity_assertion_is_limited_to_same_host_remote_observation(self):
        logs, manifest = self.valid_records()
        logs[0][2]["id"] = "unrelated"
        with self.assertRaisesRegex(ValueError, "pairing peer"):
            evidence.verify(*logs, manifest)
        # No public local-ID accessor exists. Coherently modified remote observations
        # cannot be detected; the report must explicitly preserve this limitation.
        logs[0][1]["id"] = "unrelated"
        self.assertIn("no reciprocal local DeviceId binding", evidence.verify(*logs, manifest)["identity_scope"])


if __name__ == "__main__":
    unittest.main()
