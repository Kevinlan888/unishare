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
    return {"TRANSFER_REJECTED": "7", "CANCELLED": "8", "NETWORK_LOST": "10"}.get(str(value), str(value))


def platform(value):
    key = str(value).lower()
    return {"0": "windows", "1": "android", "2": "ios", "ios-simulator": "ios"}.get(key, key)


def verify(sender, receiver, manifest):
    check(sender and receiver, "two peers are required")
    sas = [select(rows, "pairing")[0].get("sas", "") if select(rows, "pairing") else "" for rows in (sender, receiver)]
    check(sas[0] == sas[1] and re.fullmatch(r"[0-9]{6}", sas[0]), "SAS mismatch or invalid SAS")
    begins = [one(rows, "begin") for rows in (sender, receiver)]
    extended = begins[0].get("scenario") == "extended"
    check(not extended or begins[1].get("scenario") == "extended", "extended scenario mismatch")
    if extended:
        check(len(manifest) == 9 and select(receiver, "cleanup"), "extended cancellation cleanup evidence required")
    successes = len(manifest)
    total = successes + (2 if extended else 1)
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
        check(int(one(rows, "complete")["terminals"]) == total, "host did not complete all transfers")
        discovered = one(rows, "discovered")
        check(discovered["name"] == begins[1-index]["name"], "wrong discovered name")
        check(platform(discovered.get("platform")) == remote.get("sdkPlatform"), "discovered platform mismatch")
        check(discovered["protocol"] == "1.0" and discovered["addresses"] and 0 < int(discovered["port"]) < 65536, "discovery metadata")
        pairing = select(rows, "pairing")[0]
        check(pairing["id"] == discovered["id"] and pairing["decision"] == "confirm", "pairing peer or decision")
        confirms = [r for r in select(rows, "operation") if r["operation"] == "confirm"]
        check(len(confirms) == (2 if extended and index == 1 else 1) and all(error(r)=="" for r in confirms), "public confirmation missing")
        check(len(select(rows,"pairing")) == len(confirms) and all(r["decision"]=="confirm" and r["id"]==discovered["id"] for r in select(rows,"pairing")), "pairing peer or decision")
        for operation in select(rows, "operation"):
            expected = ("7",) if operation["operation"] == "send_reject" else ("8","10") if operation["operation"] == "send_cancel" else ("",)
            check(error(operation) in expected, "operation failed")
        terminals = select(rows, "terminal")
        check(len(terminals) == total and len({r["id"] for r in terminals}) == total, "terminal exact-once violation")
    clips=select(sender,"clipboard")
    check(len(clips)==(2 if extended else 1) and all(r["decision"]=="explicit_read_send" for r in clips), "manual clipboard missing")
    sent, received, offers, consent = (select(sender, "sent"), select(receiver, "received"),
                                      select(receiver, "offer"), select(receiver, "consent"))
    check(len(manifest) == len(sent) == len(received), "all fixtures required")
    check(len(offers) == len(consent) == total, "all explicit receiving decisions required")
    check(len({r["id"] for r in offers}) == total, "duplicate offer")
    expected_ids = set()
    for fixture, tx, rx in zip(manifest, sent, received):
        check(tx["id"] == rx["id"], "transfer ID mismatch")
        expected_ids.add(tx["id"])
        for row in (tx, rx):
            if fixture.get("pixel_sha256"):
                check(row["name"]==fixture["name"] and int(row["size"])>0 and row["pixel_sha256"]==fixture["pixel_sha256"],"clipboard pixels mismatch")
                check(tx["sha256"]==rx["sha256"] and int(tx["size"])==int(rx["size"]),"clipboard transport mismatch")
            else:
                check(row["name"] == fixture["name"] and int(row["size"]) == fixture["size"] and row["sha256"] == fixture["sha256"], "content mismatch")
        offer = next((r for r in offers if r["id"] == tx["id"]), None)
        check(offer is not None and int(offer["size"]) == int(tx["size"]), "offer size")
        kind = {"1": "text", "2": "image", "3": "file"}.get(str(offer["kind"]), str(offer["kind"]).lower())
        check(kind == fixture["kind"], "offer kind")
    rejects = [r for r in consent if r.get("decision") == "reject"]
    check(len(rejects) == 1 and rejects[0]["id"] not in expected_ids, "explicit rejection required")
    rejected = rejects[0]["id"]
    cancelled = one(receiver,"cleanup")["id"] if extended else None
    all_ids=expected_ids | {rejected} | ({cancelled} if extended else set())
    check({r["id"] for r in offers} == all_ids, "unexpected offer")
    check({r["id"] for r in consent} == all_ids, "missing decision")
    completions = [r for r in select(receiver, "operation") if r["operation"] in ("accept", "reject")]
    check(len(completions) == total, "all public decision completions required")
    check(len({str(r.get("requestId")) for r in offers}) == total, "offer request IDs must be unique")
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
    for side,rows in enumerate((sender, receiver)):
        check({r["id"] for r in select(rows, "terminal")} == all_ids, "terminal ID mismatch")
        for terminal in select(rows, "terminal"):
            allowed=("7",) if terminal["id"]==rejected else (("8","10") if side==0 else ("8",)) if terminal["id"]==cancelled else ("",)
            check(error(terminal) in allowed, "terminal error mismatch")
    for index, row in enumerate(receiver):
        if row.get("event") in ("received", "terminal") or (row.get("event") == "progress" and int(row["bytes"]) > 0):
            decision = next(r for r in consent if r["id"] == row["id"])
            check(receiver.index(decision) < index, "content/terminal before explicit consent")
    if extended:
        cleanup=one(receiver,"cleanup"); cancel=one(receiver,"cancel")
        check(str(cleanup.get("deleted")).lower()=="true" and cancel["id"]==cancelled and 0<int(cancel["bytes"])<int(cancel["size"]), "extended cancellation cleanup")
        progress=[r for r in select(receiver,"progress") if r["id"]==cancelled and int(r["bytes"])==int(cancel["bytes"]) and int(r["size"])==int(cancel["size"])]
        check(progress and receiver.index(progress[0])<receiver.index(cancel)<receiver.index(cleanup), "extended active cancellation order")
        commands=[r for r in select(receiver,"operation") if r["operation"]=="cancel" and r.get("id")==cancelled]
        check(len(commands)==1 and error(commands[0])=="", "extended public cancel missing")
        check(sender.index(next(r for r in select(sender,"terminal") if r["id"]==cancelled))<sender.index(sent[-1]), "extended failure isolation")
        for rows in (sender,receiver):
            restart=one(rows,"restart"); peer=one(rows,"discovered"); fresh=one(rows,"rediscovered")
            check(str(restart.get("trustStable")).lower()=="true", "extended restart trust")
            check(any(r.get("id")==peer["id"] and str(r.get("paired")).lower()=="true" and 0<int(r.get("port",0))<65536 for r in select(rows,"updated")),"extended peer Updated missing")
            check(fresh.get("id")==peer["id"] and 0<int(fresh.get("port",0))<65536 and int(fresh["port"])!=int(peer["port"]) and str(fresh.get("paired")).lower()=="true" and rows.index(fresh)>rows.index(restart),"extended restart rediscovery missing")
            stopped=next((r for r in select(rows,"operation") if r.get("operation")=="restart_stop"),restart)
            check(any(r.get("id")==fresh["id"] and int(r.get("port",0))==int(fresh["port"]) and rows.index(stopped)<rows.index(r)<rows.index(fresh) for r in select(rows,"discovery")+select(rows,"updated")),"extended public rediscovery event missing")
        for name in ("clipboard_write","clipboard_read"):
            check(len([r for r in select(sender,"operation") if r["operation"]==name])==2,"extended public clipboard completion missing")
        final=next(r for r in select(receiver,"terminal") if r["id"]==received[-1]["id"])
        check(any(r.get("id")==one(receiver,"discovered")["id"] and receiver.index(r)>receiver.index(final) for r in select(receiver,"offline")), "extended offline missing")
    return {"status": "PASS", "run": begins[0]["run"], "direction": f'{begins[0]["platform"]}->{begins[1]["platform"]}',
            "sas": sas[0], "successes": successes, "rejections": 1, "cancellations": int(extended), "transfer_ids": sorted(all_ids),
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
