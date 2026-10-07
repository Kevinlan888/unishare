# Windows native application

The WinUI 3 client runs one executable and one application-scoped SDK session. Closing its window hides it to the tray; tray Exit stops the SDK and releases the process. Startup defaults to disabled. The app is x64, requires Windows 10 build 17763 or later, and packages English and Simplified Chinese resources.

## Prerequisites and reproducible verification

Install .NET SDK **10.0.400**, Visual Studio 2026 C++ desktop tools (MSVC and CMake >= 3.24 for fresh-cache configuration), Windows SDK x64 MakeAppx/MakePri/SignTool, Windows App SDK build prerequisites, and PowerShell 7.4+ for offline JSON schema validation. NuGet restore uses the versions pinned in `windows/Directory.Packages.props`. Run scripts using Windows PowerShell; they resolve repository paths from their own location and quote tool arguments independently.

Provide an absolute **x64 OpenSSL 3** development prefix with `include/openssl`, `lib/libssl.lib`, `lib/libcrypto.lib`, exactly one corresponding `bin/libssl-3*.dll` and `bin/libcrypto-3*.dll`, and redistribution notices at `share/openssl/copyright` or `LICENSE.txt`. Only that prefix supplies runtime DLLs. No script downloads dependencies or searches arbitrary PATH/application installs for OpenSSL.

As a separate, explicit development prerequisite, the root `vcpkg.json` pins baseline `19780d9cdf84d0944cf9a318666703b89ab6629c`. With installed vcpkg, provision it into the ignored workspace directory (network permission is required for registry/tool downloads):

```powershell
& 'C:\Program Files\Microsoft Visual Studio\18\Insiders\VC\vcpkg\vcpkg.exe' install --triplet x64-windows --x-install-root="$PWD/windows/artifacts/deps/installed" --x-buildtrees-root="$PWD/windows/artifacts/deps/buildtrees" --x-packages-root="$PWD/windows/artifacts/deps/packages" --downloads-root="$PWD/windows/artifacts/deps/downloads" --disable-metrics
$env:OPENSSL_ROOT_DIR = (Resolve-Path windows/artifacts/deps/installed/x64-windows).Path
powershell -ExecutionPolicy Bypass -File windows/scripts/test.ps1 -Configuration Release -OpenSslRoot $env:OPENSSL_ROOT_DIR
powershell -ExecutionPolicy Bypass -File windows/scripts/package.ps1 -Configuration Release -Platform x64 -Unsigned
```

For this baseline the pinned dependency resolves to OpenSSL **3.6.5**, triplet `x64-windows`; the report records exact registry commit and ABI hash. Staging preserves its copyright/license notices under `windows/artifacts/native/licenses`, and the MSIX redistributes those notices. Use the root manifest unchanged when provisioning.

`build-native.ps1` validates a fully qualified drive-rooted or UNC prefix (not drive-relative paths such as `D:foo`), configures the existing CMake project with a fresh cache, builds all targets, runs all native CTest cases with a 120-second per-case timeout, installs headers/import library and the DLL, and checks matching OpenSSL hashes. Both the CTest inventory and JUnit report must contain exactly the independent 14 required test identities, without removals, renames, duplicates, failures, or skips. `test.ps1` also runs every .NET project, including the real DLL test, and rejects failed, empty, missing, or skipped reports. TRX reports remain in `windows/artifacts/test-results/<run-id>`.

The real DLL smoke loads the absolute staged DLL, verifies ABI v1, uses a unique temporary absolute identity directory, initializes/stops/destroys once, and checks exactly one completion per operation after destroy quiesces callbacks. Start/live mDNS is deliberately opt-in: set `UNISHARE_LIVE_MDNS=1` before running the smoke. Physical peers are a separate operator-driven acceptance category, never inferred from that smoke or prior SDK interoperability results.

## Package, signing, install, and uninstall

`package.ps1 -Unsigned` builds Release x64 with the `win10-x64` publish profile and `GenerateAppxPackageOnBuild=true`. Each run produces exactly one `.msix` in its own ignored `windows/artifacts/packages/<run-id>` directory. For Windows 11 local testing, the unsigned path appends Microsoft's required unsigned-namespace marker as the final Publisher field before MakeAppx repacks the final artifact; the signed path preserves `CN=UniShare`. MakeAppx validates schema and semantics, and MakePri dumps the compiled resources. The inspection checks the mode-specific Publisher identity, app/native DLLs, native SHA256 equality, both languages, minimum Windows version, one executable, startup off, and excludes PDBs, test binaries, source, identities, settings, SQLite files, logs, and signing keys. `contents.txt` and `resources.xml` retain inspection evidence outside the package.

An unsigned CI artifact is **not a distributable trusted install**. On the configured signing workstation, `package.ps1` reads `windows/signing/package-signing-thumbprint.txt`, resolves the matching `CN=UniShare` certificate with private key from the current user's `My` certificate store, signs by thumbprint, and verifies the result. The repository contains only the public CER and thumbprint; the private key never enters source control. For CI or another authorized signing machine, provide a PFX through a secret store and pass `-CertificatePath <external.pfx>` plus `-CertificatePassword` through a secure operator session. The script keeps CI mode unsigned and refuses a certificate with `-Unsigned`.

Install a verified, appropriately trusted signed package with `Add-AppxPackage -Path <package.msix>`. On Windows 11, install the OID-marked unsigned test package from an elevated PowerShell session with `Add-AppxPackage -Path <package.msix> -AllowUnsigned`; unsigned executable packages require administrator privilege and are for local testing only. Use the Start menu entry to launch it. Uninstall with `Get-AppxPackage -Name UniShare | Remove-AppxPackage`. Verify the package identity before removal; user package data may be removed by uninstall, so preserve any desired activity summaries and user files first. Document the actual installation method and never claim installation from a successful build alone.

## Physical acceptance and provenance

Generate a complete matrix without asserting observations:

```powershell
powershell -ExecutionPolicy Bypass -File windows/scripts/run-acceptance.ps1 -Output tests/interop/windows-ui-results-2026-10-05.json -Package <package.msix>
```

Exit **2** means the release gate is BLOCKED and all unobserved rows are NOT_RUN with reasons; **1** means an observed failure; **0** requires every mandatory row to pass. No operator means no PASS. JSON schema validation requires PowerShell 7.4+. The recorder never launches peers or changes Windows display/theme/clipboard settings.

An operator copies the generated JSON to an evidence file and records actual observations for every row. Each PASS/FAIL requires an operator, nonzero package SHA256, timestamp, distinct referenced device IDs, and nonempty, non-whitespace evidence references (screenshots, sanitized transcripts, or integrity/clipboard hashes). `provenance.devices` records unique opaque ID, physical model, platform, OS version, and app version. Windows↔Windows rows require two distinct referenced Windows devices; Android/iOS rows require a referenced Windows device and the named peer platform; Local UI rows require a referenced Windows device. Preserve unavailable rows as BLOCKED/NOT_RUN with reasons; do not reuse simulator or previous native-host results as client acceptance. Import the evidence with:

```powershell
powershell -ExecutionPolicy Bypass -File windows/scripts/run-acceptance.ps1 -Output tests/interop/windows-ui-results-2026-10-05.json -Evidence <operator-evidence.json> -Package <tested-package.msix>
```

Any import containing PASS or FAIL must supply `-Package` pointing to the actual tested MSIX file. The recorder checks its MSIX footprint and exact recomputed SHA256 against the declared nonzero 64-hex hash. Package binding is optional only when there are no observed results. It validates schema, the exact complete matrix, unique rows, and device provenance, and recomputes the release gate instead of trusting an input PASS. Device models/platforms and evidence references are operator attestations, not automated verification of physical devices or reference contents; reviewers must inspect them.

For Windows↔Windows, Windows↔Android, and Windows↔iOS, perform every scenario in **both incoming and outgoing directions**. Observe discovery/offline/rediscovery; pairing match, mismatch, reject and timeout; trust after stop/restart, forget and identity changes; trusted text automatic acceptance, untrusted confirmation, image/file integrity, and local clipboard text/image sources; receiver clipboard unchanged; accept/reject, active cancellation, timeout, disconnect/reconnect; collision names and temporary cleanup; unwritable destination; hidden-window notification reject/view; close-to-tray, pause while transferring, resume (including failure), and exit with no orphan process.

Check privacy using sentinel text/clipboard content and file paths: inspect SQLite rows and diagnostic logs after transfer/restart; neither content bodies nor image/file bytes may appear in history, and diagnostics must omit full paths. Formal received files are user-saved output, not history copies. Verify history limits (500/device and 30 days), missing-file actions, and metadata-only restart behavior. Use a database viewer on the actual package data, not just unit-test fixtures.

For each `en-US`/`zh-CN`, light/dark/high-contrast combination, inspect **960×640 and 1440×900** at **100/125/150/175/200% OS scaling**. Traverse every action by keyboard, inspect visible focus, listen with a screen reader, verify terminal progress announcements, no clipped Send/Accept/Reject, and right-pane collapse before navigation compaction. Capture both whole-window and focused decision states against the approved reference.

## Diagnostics and current release limits

The app's diagnostic sink records stable error categories and platform codes only. Inspect package user data and its diagnostic output after actual operation; reports must not include text/clipboard bodies, private keys, or full diagnostic file paths. Reproduce discovery errors after controlled network interruption, keep the history usable, and verify tray warning/error notification behavior.

At Task 11 recording time there is no verified operator/physical-peer session, package installation, keyboard/screen reader, Windows high-contrast control, or display scaling observation. The acceptance JSON explicitly leaves these gates NOT_RUN/BLOCKED. `design-qa.md` remains `final result: blocked` until the full physical accessibility/scaling matrix passes. Automated CTest/.NET/MSIX success does not establish overall release acceptance.
