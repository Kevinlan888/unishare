# Interoperability evidence

Task 11a, 2026-10-05, SDK source base `8416880` / package version 0.1.0.
This is partial acceptance, not a release claim.

| Direction | Discovery | Pairing/consent | Six content cases + rejection | Evidence |
| --- | --- | --- | --- | --- |
| Windows → Android | FAIL: Windows browse timeout; Pixel discovered Windows | Not reached | Not run | `wa1` |
| Android → Windows | Not run | Not run | Not run | Task 11b |
| Windows → iOS | Not run | Not run | Not run | Task 11b |
| iOS → Windows | Not run | Not run | Not run | Task 11b |
| Android → iOS Simulator | PASS | PASS: bilateral SAS/ConfirmPairing and per-offer decisions | PASS | `ai1`, `ai2`, strict verifier |
| iOS Simulator → Android | Not run | Not run | Not run | Task 11b |

`wa1`: Windows 10.0.26200 at 192.168.2.160; physical Pixel 10a Android 17/API37
at 192.168.2.178. Windows Start/publication succeeded, no discovery-error event,
then no matching peer in 60 seconds. Pixel resolved the Windows DeviceId and
protocol1.0, addresses including 192.168.2.160, and advertised port14006. It then
timed out waiting for an offer. This proves Windows publication→Pixel discovery;
it does not establish Windows inbound firewall or Windows browse behavior.
No address injection, firewall edit or SDK workaround was used.

`ai1`: physical Pixel 10a Android17/API37, release AAR SHA256
`d3961d95c2a459ccd3b0bea1e96e6c148d3d8453b8df2529ef361f9fbc5bebca`, and
iOS Simulator on Mac Wi-Fi en1 192.168.2.233, Xcode26.3, production Task10b
XCFramework. Host OS reports Version26.3.1 (Build23D8133); selected runtime is
iPhone17Pro iOS26.3. Both hosts printed SAS022715 and explicitly confirmed.
Six exact byte/hash matches (text, PNG, JPEG, empty file, 700001-byte file, actual
manual clipboard text) and one TransferRejected produced seven distinct terminal
IDs on each peer. The coordinator returned PASS, both hosts exited successfully.
The iOS host is a Simulator peer, not a signed physical iPhone. Final launch-wrapper
rerun `ai2` also passed the strict coordinator with SAS340844, six exact contents
and one rejection, and successful bounded host exits.

Raw logs and verifier summary are retained in ignored `build-interop-wa1/` and
`build-interop-ai1/` and `build-interop-ai2/`; commands and platform/package limits are in the ignored
Task11a implementation report. Transfer IDs observed in ai1 use the inherited
`75000000-...0001` through `...0007` sequence; this harness records actual public
values and does not replace them. Random-ID conformance remains a release review
concern for Task11b. Full six-direction runs, active cancellation/staging cleanup,
network interruption, offline/update, changed identity and malicious-wire failure
coverage remain pending. Earlier same-device acceptance does not fill these cells.
