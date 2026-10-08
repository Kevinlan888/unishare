# UniShare

UniShare is a local-first LAN sharing SDK with Windows and Android clients. The shared C++17 core provides discovery, authenticated pairing, trusted-device management, and bounded streaming for text, images, files, and explicit clipboard content. Platform adapters expose the SDK to Windows, Android, and iOS.

The repository currently includes:

- a C++ SDK with C and C++ APIs;
- Windows, Android, and iOS platform adapters;
- a WinUI 3 Windows client with English and Simplified Chinese resources;
- a native Android Compose client with English and Simplified Chinese resources;
- native, managed, packaging, and interoperability test suites.

## Project handoff

Read [docs/HANDOFF.md](docs/HANDOFF.md) before continuing development or preparing a release. It records the current validated state, Windows signing setup, diagnostic locations, known release blockers, and the next recommended work.

## Repository layout

| Path | Purpose |
| --- | --- |
| `include/unishare`, `src/core`, `src/c_api*` | Public native API, core services, and C ABI |
| `src/platform` | Windows and Android native platform ports |
| `android` | Compose application, Kotlin SDK, JNI adapter, packaging, and tests |
| `ios` | Swift/Objective-C++ adapter and XCFramework tooling |
| `windows` | WinUI 3 application, managed SDK bridge, tests, and MSIX scripts |
| `tests/interop` | Cross-platform hosts, matrices, and evidence |
| `docs` | Build, protocol, integration, design, and handoff documentation |

## Windows quick start

The supported solution configurations are `Debug|x64` and `Release|x64`. Open [windows/UniShare.slnx](windows/UniShare.slnx) in Visual Studio, or use the scripts below. The Windows workflow requires .NET SDK 10.0.400, Visual Studio C++/CMake tools, Windows SDK packaging tools, and an x64 OpenSSL 3 development prefix.

After provisioning OpenSSL with the pinned root `vcpkg.json` manifest:

```powershell
$openSslRoot = (Resolve-Path windows/artifacts/deps/installed/x64-windows).Path
powershell -ExecutionPolicy Bypass -File windows/scripts/test.ps1 `
    -Configuration Release -OpenSslRoot $openSslRoot
```

Create a signed MSIX on the configured signing workstation:

```powershell
Set-Location windows
.\scripts\package.ps1
```

For CI-only validation without a signing identity:

```powershell
.\scripts\package.ps1 -Unsigned
```

Build output is written under the ignored `windows/artifacts` directory. Native runtime staging is worktree-local, so run `build-native.ps1` or `test.ps1` once in every new worktree before running the real-DLL integration test or packaging.

Detailed prerequisites and acceptance procedures are in the [Windows application guide](docs/integration/windows-app.md). General native build notes are in [docs/building.md](docs/building.md).

## Android quick start

With the Android/JDK/native dependencies configured, run from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File android/scripts/test-app.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File android/scripts/build-app.ps1
```

The build prints the debug APK path, SHA-256 and size. Select an online adb serial
with `test-app.ps1 -DeviceSerial <serial>` to run connected tests. Without an
explicit selection, connected execution is skipped. `-VerifyOnly` reads static
contracts and existing artifacts/reports without building. See the
[Android app guide](docs/integration/android-app.md) and
[acceptance matrix](android/tests/app-acceptance.md) for prerequisites and gates.

## Platform integration

- [SDK integration index](docs/integration/README.md)
- [Windows C++ integration](docs/integration/windows.md)
- [Android Kotlin integration](docs/integration/android.md)
- [Android application build and acceptance](docs/integration/android-app.md)
- [iOS Swift integration](docs/integration/ios.md)
- [Protocol v1](docs/protocol-v1.md)
- [Interoperability hosts and evidence](tests/interop/README.md)

## Security and privacy

Pairing requires explicit six-digit verification, peer identities are authenticated, and transfers are encrypted in transit. Receiving hosts must use locally generated staging destinations and publish content only after successful integrity verification.

Diagnostic logging intentionally excludes content bodies, private keys, device names, exception messages, stack traces, and full file paths. Never commit identity stores, activity databases, signing private keys, PFX/PEM files, diagnostic logs, or received user content.

The repository contains only the Windows package-signing **public** certificate. The private key remains in the configured Windows user's certificate store; see [windows/signing/README.md](windows/signing/README.md).

## Release status

Automated native, managed, and MSIX checks are implemented. They do not replace physical-device, firewall, installation, accessibility, display-scaling, and cross-platform acceptance. The Windows release gate remains blocked until the operator-driven matrix described in [docs/integration/windows-app.md](docs/integration/windows-app.md) is completed with current-package evidence.

The Android release gate also remains blocked: API 26/33/35/36 and full Android
17 compatibility/operator evidence plus the physical Android app ↔ Windows app
matrix are incomplete. API 37 emulator tests do not satisfy these physical gates;
see [Android acceptance](android/tests/app-acceptance.md).
