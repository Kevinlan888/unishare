# Building the C++ core

Requires CMake 3.21+, C++17 and OpenSSL 3+. The root `vcpkg.json` pins the
dependency registry baseline. Provision it explicitly before configuring:

```powershell
& 'C:/Program Files/Microsoft Visual Studio/18/Insiders/VC/vcpkg/vcpkg.exe' install --x-manifest-root=. --x-install-root=build-deps/vcpkg_installed --triplet=x64-windows
```

In an x64 Visual Studio developer prompt, configure with the installed prefix:

```text
cmake -S . -B build-msvc -G "NMake Makefiles" -DBUILD_TESTING=ON -DOPENSSL_ROOT_DIR=D:/cs/unishare/.worktrees/lan-share-sdk/build-deps/vcpkg_installed/x64-windows
cmake --build build-msvc
```

Alternatively configure a fresh build directory with the vcpkg toolchain and
the explicit installed prefix. Disable automatic manifest installation so
configure does not download packages:

```text
cmake -S . -B build-vcpkg -G "NMake Makefiles" -DCMAKE_TOOLCHAIN_FILE="C:/Program Files/Microsoft Visual Studio/18/Insiders/VC/vcpkg/scripts/buildsystems/vcpkg.cmake" -DVCPKG_MANIFEST_INSTALL=OFF -DVCPKG_TARGET_TRIPLET=x64-windows -DVCPKG_INSTALLED_DIR=D:/cs/unishare/.worktrees/lan-share-sdk/build-deps/vcpkg_installed
```

Adjust paths for your checkout/toolchain. On this machine the developer prompt
is initialized by `VC/Auxiliary/Build/vcvars64.bat` under the Visual Studio
directory; `cmake.exe` and `ctest.exe` are in
`Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin`.
Add the installed OpenSSL `bin` directory to PATH before running tests (use
`debug/bin` for a Debug configuration):

```powershell
$env:PATH="$PWD/build-deps/vcpkg_installed/x64-windows/bin;"+$env:PATH
ctest --test-dir build-msvc -R identity --output-on-failure
ctest --test-dir build-msvc --output-on-failure
```

Identity private keys are DER PKCS#8, certificates DER X.509. Trust fingerprints
are SHA-256 of DER SubjectPublicKeyInfo. Platform adapters must implement the
atomic, durable, create-once storage contract in `IIdentityStore` and secure
private bytes with their OS key store. Loading corrupt material fails startup;
there is no automatic identity replacement. Certificates currently last ten
years; certificate renewal with the same key is future lifecycle work.

On Windows, the session test target stages OpenSSL 3 runtime DLLs from OPENSSL_ROOT_DIR/bin (or the discovered include prefix's bin) beside the test executables. Build the test target before running CTest; no PATH update is then needed for this configured shared OpenSSL installation. Static OpenSSL builds require no runtime DLLs.
