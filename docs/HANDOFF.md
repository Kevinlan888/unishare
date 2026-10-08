# UniShare project handoff

Snapshot date: **2026-10-07**. This handoff describes the state immediately after `feature/windows-native-ui` was fast-forwarded into `master`. The signing persistence baseline is commit `abe81f7`; this document is added by a later documentation commit.

Start with the repository [README](../README.md), then use this document for operational details.

## Current state

- Active branch: `master`. The Windows feature worktree and feature branch were removed after merge.
- At handoff time the local repository had no Git remote configured.
- Windows package version: `1.0.0.10`, x64, publisher `CN=UniShare`.
- Windows solution configurations: `Debug|x64` and `Release|x64`; there is no supported `Any CPU` configuration.
- The WinUI 3 client includes discovery, pairing, trusted-device sessions, text/image/file transfer, explicit clipboard actions, history, settings, tray lifecycle, notifications, English/Chinese resources, and privacy-filtered diagnostics.
- The C++ SDK exposes C++ and stable C ABI v1 surfaces used by the Windows, Android, and iOS adapters.

The local `windows/.vs/` directory is Visual Studio user state. It is intentionally untracked and must not be committed.

## Last verified results

The merged `master` tree was verified on Windows on 2026-10-07:

- 14/14 required native CTest identities passed.
- 337/337 managed tests passed: App 43, Application 176, Infrastructure 35, Native 69, Integration 14.
- Visual Studio rebuilt all nine solution projects with 0 failures and 0 skipped projects.
- A Release x64 MSIX was built, inspected, signed with SHA-256, and accepted by `signtool verify /pa`.
- C# formatting and repository whitespace checks passed.

These are automated results, not release acceptance. `design-qa.md` and the Windows UI acceptance record remain blocked pending current physical-device evidence.

## Canonical Windows workflow

Prerequisites are documented in [integration/windows-app.md](integration/windows-app.md). The root `vcpkg.json` pins OpenSSL; provision an x64 Windows prefix into the ignored artifact tree, then run from the repository root:

```powershell
$openSslRoot = (Resolve-Path windows/artifacts/deps/installed/x64-windows).Path

# Builds the native DLL, runs all 14 native tests, stages runtime DLLs,
# and runs all managed test projects including the real-DLL smoke test.
powershell -ExecutionPolicy Bypass -File windows/scripts/test.ps1 `
    -Configuration Release -OpenSslRoot $openSslRoot
```

For a quicker managed-only iteration after native staging already exists:

```powershell
dotnet test windows/UniShare.slnx -c Release -p:Platform=x64 --no-restore
```

Every new worktree has an empty ignored `windows/artifacts` directory. If `NativeAbiSmokeTests` reports a missing `artifacts/native/bin/unishare.dll`, run `build-native.ps1` or the full `test.ps1`; do not weaken or skip the test.

## Packaging and signing

The committed signing material is public metadata only:

- Public certificate: [`windows/signing/UniShare.Package.cer`](../windows/signing/UniShare.Package.cer)
- Configured thumbprint: `B42AB4BA869293DFECA869D398B3882302E30E52`
- Thumbprint file: [`windows/signing/package-signing-thumbprint.txt`](../windows/signing/package-signing-thumbprint.txt)
- Private key location: current Windows user, certificate store `CurrentUser/My`
- Certificate expiry: 2028-10-06 local time

On the configured signing workstation, run:

```powershell
Set-Location windows
.\scripts\package.ps1
```

`resolve-signing-certificate.ps1` requires the configured certificate to be current, have subject `CN=UniShare`, permit code signing, and expose its private key. `package.ps1` signs by store thumbprint and verifies the resulting MSIX.

The public `.cer` allows another machine to trust packages; it does not grant signing access. For CI or another authorized signing machine, supply a PFX through a secret store and call `package.ps1 -CertificatePath <external.pfx> -CertificatePassword <secret>`. Never commit the PFX or password. `.gitignore` excludes common private-key extensions.

For internal distribution, accompany the signed MSIX with the public CER and clear trust/install instructions. A self-signed internal certificate does not eliminate SmartScreen reputation warnings. A production release should use a properly protected trusted publisher certificate and keep the manifest publisher consistent.

## Runtime data and diagnostics

Default Windows locations are:

| Data | Location |
| --- | --- |
| Diagnostics | `%LOCALAPPDATA%\UniShare\App\diagnostics.log` |
| Application settings/history root | `%LOCALAPPDATA%\UniShare\App` |
| SDK identity and trust state | `%LOCALAPPDATA%\UniShare\Identity` |
| Transient outbound content | `%LOCALAPPDATA%\UniShare\Transient` |

NLog rotates `diagnostics.log` above 1 MiB and keeps at most two archive files. Log records contain stable stages, outcomes, directions, content kinds, sequence numbers, categories, platform codes, and exception type names only. Do not add payload text, clipboard bodies, device names, full paths, exception messages, or stack traces.

When discovery fails on a second computer, first check that Windows Firewall allowed the app on the private network and inspect both computers' diagnostics. SDK initialization, socket connect, TLS, identity verification, pairing, transfer streaming, integrity verification, and peer completion are logged independently.

## Architecture map

- `include/unishare` and `src/core`: public API and platform-neutral lifecycle, discovery, pairing, transfer, trust, and diagnostics.
- `src/c_api*.cpp`: stable C ABI used by managed/JNI/Objective-C++ adapters.
- `src/platform/windows`: Windows sockets, DNS-SD, network change, storage, clipboard, and exported DLL surface.
- `windows/src/UniShare.Native`: P/Invoke ABI mapping, callback ownership, native operation registry, streams, and SDK diagnostics.
- `windows/src/UniShare.Application`: application state, transfer orchestration, policies, activity tracking, and view models.
- `windows/src/UniShare.Infrastructure`: SQLite/settings stores, transient files, receive storage, and privacy logging.
- `windows/src/UniShare.App`: WinUI views, composition root, notifications, tray/startup integration, and process lifecycle.

Transfer data must remain streamed and bounded. Do not reintroduce whole-file buffering or log user content. Incoming files use app-owned temporary staging and are exposed only after successful transfer and integrity completion.

## Known gaps and risks

1. The Windows UI physical acceptance matrix is incomplete. Run all required two-Windows-PC scenarios plus Windows/Android and Windows/iOS directions against the exact packaged build.
2. Accessibility, keyboard navigation, screen-reader announcements, high contrast, and the full scaling/resolution matrix still require operator evidence.
3. Firewall consent and private-network classification can prevent SDK startup or discovery on a new computer even when the app itself does not crash.
4. Installing the public certificate establishes trust only for this internal certificate. SmartScreen reputation and enterprise policy may still block execution.
5. SDK interoperability evidence and the Windows GUI release gate are separate. Existing SDK host results must not be reused as proof of current Windows application acceptance.
6. Signing keys, native staging, NuGet outputs, packages, test reports, user data, and `.vs` state are intentionally outside source control.

## Recommended next work

1. Produce a fresh signed MSIX from `master` and record its SHA-256.
2. Install the public CER and package on two clean Windows machines; verify firewall consent and discovery.
3. Complete the operator-driven acceptance JSON with screenshots or sanitized logs tied to the exact package hash.
4. Finish accessibility, theme, resolution, and scaling observations, then update `design-qa.md` only when evidence supports the release gate.
5. Re-run Android and iOS consumer/interoperability suites whenever the C ABI or wire protocol changes.
6. For broader distribution, replace the internal self-signed identity with a protected production signing certificate and update publisher documentation deliberately.

## Android application workflow update (2026-10-08)

The Android app work on `feature/android-client` adds a Compose client,
repository SDK integration, unit/connected tests and repeatable
[`test-app.ps1`](../android/scripts/test-app.ps1) / [`build-app.ps1`](../android/scripts/build-app.ps1).
Use the [app guide](integration/android-app.md) for prerequisites and command
semantics. Outputs are worktree-local and ignored. `-VerifyOnly` reads existing
evidence without running Gradle/adb; connected execution requires an explicitly
selected online serial. Debug APK output is
`android/app/build/outputs/apk/debug/app-debug.apk`; the build script prints its
absolute path/hash/size without signing secrets.

The current available device is `emulator-5554`, API 37, x86_64. Measured counts
and package identity belong to [Android acceptance](../android/tests/app-acceptance.md).
No API 26/33/35/36 or full Android 17 operator gate has passed in this app task.
All physical Android app ↔ Windows app rows are BLOCKED / NOT RUN because a real
Android device, a usable Windows application peer and operator are missing.
Historical SDK host evidence cannot close these rows or the Windows GUI gate.

Known Android release concerns include safe publication on API 26–28 and generic
SAF directories, which the current publisher rejects, and the separately observed
SDK lint debt (8 errors/15 warnings; not passed or part of the Task 12 app gate).
Task 13 must assess these issues and the unchanged physical/version release gates.
The minimal app API lint fix adds an API29 recovery guard and day/night v27
navigation-bar resources without suppressing errors or raising minimum SDK.

Task12 measured210 app unit and91 full app connected tests, zero failures/errors/
skipped, and app lint0 errors/22 warnings. The complete automated gate fails:
SDK PublicApiTests ran7 cases, failed focusedClipboard with a Text-to-Image
ClassCastException, and did not run the eighth acceptance case. The script and
connected VerifyOnly exit1. Task13 must resolve/re-run this blocker. Standalone
build-app/default-skip reruns were blocked by sandbox network access and
interrupted pending approval; no passing command claim. APK hash/size are in the
acceptance matrix.

## Useful references

- [Windows native application](integration/windows-app.md)
- [SDK integration index](integration/README.md)
- [Protocol v1](protocol-v1.md)
- [Interop matrix](../tests/interop/matrix.md)
- [Windows signing notes](../windows/signing/README.md)
- [SDK diagnostics design](superpowers/specs/2026-10-06-sdk-diagnostics-design.md)
