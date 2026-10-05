"""Fail-closed coordinator for public-host JSONL evidence, never a transport."""
import argparse
import json
from pathlib import Path
import re


def check(value, message):
    if not value:
        raise ValueError(message)


def select(rows, event):
    return [r for r in rows if r.get("event") == event]


def one(rows, event):
    found = select(rows, event)
    check(len(found) == 1, f"exactly one {event} required")
    return found[0]


def error(row):
    value = row.get("error")
    return {"TRANSFER_REJECTED": "7"}.get(str(value), str(value))


def platform(value):
    key = str(value).lower()
    return {"0": "windows", "1": "android", "2": "ios", "ios-simulator": "ios"}.get(key, key)


def verify(sender, receiver, manifest):
    check(sender and receiver, "two peers are required")
    sas = [one(rows, "pairing").get("sas", "") for rows in (sender, receiver)]
    check(sas[0] == sas[1] and re.fullmatch(r"[0-9]{6}", sas[0]), "SAS mismatch or invalid SAS")
    begins = [one(rows, "begin") for rows in (sender, receiver)]
    check(begins[0]["role"] == "sender" and begins[1]["role"] == "receiver", "roles")
    check(begins[0]["run"] == begins[1]["run"], "different runs")
    check(platform(begins[0]["platform"]) != platform(begins[1]["platform"]), "cross-platform peers required")
    for index, rows in enumerate((sender, receiver)):
        local, remote = begins[index], begins[1-index]
        check(local.get("sdkPlatform") in ("windows", "android", "ios") and
              platform(local["platform"]) == local["sdkPlatform"], "local platform metadata")
        check(local.get("expectedPeer") == remote["name"], "reciprocal expected name")
        check(not select(rows, "failure") and not select(rows, "duplicate_operation"), "host failure")
        check(all(r.get("run") == begins[0]["run"] and r.get("role") == begins[index]["role"] for r in rows), "mixed log")
        check(int(one(rows, "complete")["terminals"]) == 7, "host did not complete seven transfers")
        discovered = one(rows, "discovered")
        check(discovered["name"] == begins[1-index]["name"], "wrong discovered name")
        check(platform(discovered.get("platform")) == remote.get("sdkPlatform"), "discovered platform mismatch")
        check(discovered["protocol"] == "1.0" and discovered["addresses"] and 0 < int(discovered["port"]) < 65536, "discovery metadata")
        pairing = one(rows, "pairing")
        check(pairing["id"] == discovered["id"] and pairing["decision"] == "confirm", "pairing peer or decision")
        confirms = [r for r in select(rows, "operation") if r["operation"] == "confirm"]
        check(len(confirms) == 1 and error(confirms[0]) == "", "public confirmation missing")
        for operation in select(rows, "operation"):
            check(error(operation) == ("7" if operation["operation"] == "send_reject" else ""), "operation failed")
        terminals = select(rows, "terminal")
        check(len(terminals) == 7 and len({r["id"] for r in terminals}) == 7, "terminal exact-once violation")
    check(one(sender, "clipboard")["decision"] == "explicit_read_send", "manual clipboard missing")
    sent, received, offers, consent = (select(sender, "sent"), select(receiver, "received"),
                                      select(receiver, "offer"), select(receiver, "consent"))
    check(len(manifest) == len(sent) == len(received) == 6, "six fixtures required")
    check(len(offers) == len(consent) == 7, "seven explicit receiving decisions required")
    check(len({r["id"] for r in offers}) == 7, "duplicate offer")
    expected_ids = set()
    for fixture, tx, rx in zip(manifest, sent, received):
        check(tx["id"] == rx["id"], "transfer ID mismatch")
        expected_ids.add(tx["id"])
        for row in (tx, rx):
            check(row["name"] == fixture["name"] and int(row["size"]) == fixture["size"] and row["sha256"] == fixture["sha256"], "content mismatch")
        offer = next((r for r in offers if r["id"] == tx["id"]), None)
        check(offer is not None and int(offer["size"]) == fixture["size"], "offer size")
        kind = {"1": "text", "2": "image", "3": "file"}.get(str(offer["kind"]), str(offer["kind"]).lower())
        check(kind == fixture["kind"], "offer kind")
    rejects = [r for r in consent if r.get("decision") == "reject"]
    check(len(rejects) == 1 and rejects[0]["id"] not in expected_ids, "explicit rejection required")
    rejected = rejects[0]["id"]
    check({r["id"] for r in offers} == expected_ids | {rejected}, "unexpected offer")
    check({r["id"] for r in consent} == expected_ids | {rejected}, "missing decision")
    completions = [r for r in select(receiver, "operation") if r["operation"] in ("accept", "reject")]
    check(len(completions) == 7, "seven public decision completions required")
    check(len({str(r.get("requestId")) for r in offers}) == 7, "offer request IDs must be unique")
    for decision in consent:
        check(decision["decision"] == ("reject" if decision["id"] == rejected else "accept"), "wrong receiving decision")
        offer = next(r for r in offers if r["id"] == decision["id"])
        request = str(offer.get("requestId", ""))
        check(request.isdigit() and int(request) > 0 and str(decision.get("requestId")) == request, "decision request mismatch")
        completion = [r for r in completions if r.get("id") == decision["id"] and
                      str(r.get("requestId")) == request and r["operation"] == decision["decision"]]
        check(len(completion) == 1 and error(completion[0]) == "", "public decision completion missing or mismatched")
        check(receiver.index(decision) < receiver.index(completion[0]), "completion before public decision")
        check(receiver.index(offer) < receiver.index(decision), "decision before offer")
    for rows in (sender, receiver):
        check({r["id"] for r in select(rows, "terminal")} == expected_ids | {rejected}, "terminal ID mismatch")
        for terminal in select(rows, "terminal"):
            check(error(terminal) == ("7" if terminal["id"] == rejected else ""), "terminal error mismatch")
    for index, row in enumerate(receiver):
        if row.get("event") in ("received", "terminal") or (row.get("event") == "progress" and int(row["bytes"]) > 0):
            decision = next(r for r in consent if r["id"] == row["id"])
            check(receiver.index(decision) < index, "content/terminal before explicit consent")
    return {"status": "PASS", "run": begins[0]["run"], "direction": f'{begins[0]["platform"]}->{begins[1]["platform"]}',
            "sas": sas[0], "successes": 6, "rejections": 1, "transfer_ids": sorted(expected_ids | {rejected}),
            "identity_scope": "remote_pairing_matches_same_host_discovery; no reciprocal local DeviceId binding"}


def read(path):
    # simctl mixes its launch banner with host output; JSON records must be intact.
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8-sig").splitlines() if line.startswith("{")]


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("sender"); parser.add_argument("receiver"); parser.add_argument("manifest")
    args = parser.parse_args()
    try:
        print(json.dumps(verify(read(args.sender), read(args.receiver), json.loads(Path(args.manifest).read_text())), indent=2))
    except (ValueError, KeyError, StopIteration) as failure:
        raise SystemExit("FAIL: " + str(failure))
