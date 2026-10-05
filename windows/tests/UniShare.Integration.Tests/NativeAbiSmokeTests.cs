using System.Runtime.InteropServices;
using System.Text;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Native.Interop;

namespace UniShare.Integration.Tests;

[TestClass]
public sealed class NativeAbiSmokeTests
{
    internal static string RepoRoot
    {
        get
        {
            var directory = new DirectoryInfo(AppContext.BaseDirectory);
            while (directory is not null && !File.Exists(Path.Combine(directory.FullName, "windows", "UniShare.slnx")))
                directory = directory.Parent;
            return directory?.FullName ?? throw new InvalidOperationException("Repository root not found.");
        }
    }

    [TestMethod, TestCategory("RealDll")]
    public async Task StagedAbiV1InitializesStopsAndCompletesEachOperationExactlyOnce()
    {
        // Catches a missing/wrong stage, broken ABI, or lost/duplicate completion.
        string dll = Path.Combine(RepoRoot, "windows", "artifacts", "native", "bin", "unishare.dll");
        Assert.IsTrue(File.Exists(dll), $"Missing required staged DLL: {dll}. Run windows/scripts/build-native.ps1 with -OpenSslRoot.");
        nint library = NativeLibrary.Load(dll);
        string identity = Path.Combine(Path.GetTempPath(), "UniShare-real-dll-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(identity);
        ulong sdk = 0, platform = 0;
        nint deviceName = 0;
        var roots = new List<Completion>();
        var counts = new List<Func<int>>();
        try
        {
            T Export<T>(string name) where T : Delegate => Marshal.GetDelegateForFunctionPointer<T>(NativeLibrary.GetExport(library, name));
            Assert.AreEqual(1u, Export<AbiVersion>("usdk_abi_version")());
            Assert.AreEqual(0u, Export<ConfigInit>("usdk_config_init")(1, out var config).Code);
            deviceName = Marshal.StringToCoTaskMemUTF8("UniShare-Smoke");
            config.DeviceName = new NativeSpan { Data = deviceName, Length = 14 };
            byte[] path = Encoding.UTF8.GetBytes(identity);
            var pinned = GCHandle.Alloc(path, GCHandleType.Pinned);
            try
            {
                Assert.AreEqual(0u, Export<PlatformCreate>("usdk_windows_platform_create")(
                    new NativeSpan { Data = pinned.AddrOfPinnedObject(), Length = (ulong)path.Length }, out platform).Code);
            }
            finally { pinned.Free(); }
            (Completion Callback, Task<NativeResult> Result) CompletionForOperation()
            {
                int count = 0;
                var completion = new TaskCompletionSource<NativeResult>(TaskCreationOptions.RunContinuationsAsynchronously);
                Completion callback = (_, pointer) =>
                {
                    Interlocked.Increment(ref count);
                    completion.TrySetResult(Marshal.PtrToStructure<NativeResult>(pointer));
                };
                roots.Add(callback);
                counts.Add(() => Volatile.Read(ref count));
                return (callback, completion.Task);
            }
            var create = CompletionForOperation();
            Assert.AreEqual(0u, Export<Create>("usdk_create")(platform, in config, create.Callback, 0, out sdk, out ulong createOperation).Code);
            Assert.AreNotEqual(0ul, sdk);
            Assert.AreNotEqual(0ul, createOperation);
            Assert.AreEqual(0u, Export<Release>("usdk_platform_release")(platform).Code);
            platform = 0;
            var created = await create.Result.WaitAsync(TimeSpan.FromSeconds(20));
            Assert.AreEqual(createOperation, created.Operation);
            Assert.AreEqual(0u, created.Error.Code);
            if (Environment.GetEnvironmentVariable("UNISHARE_LIVE_MDNS") == "1")
            {
                var start = CompletionForOperation();
                Assert.AreEqual(0u, Export<Command>("usdk_start")(sdk, start.Callback, 0, out ulong operation).Code);
                var started = await start.Result.WaitAsync(TimeSpan.FromSeconds(20));
                Assert.AreEqual(operation, started.Operation);
                Assert.AreEqual(0u, started.Error.Code);
            }
            var stop = CompletionForOperation();
            Assert.AreEqual(0u, Export<Command>("usdk_stop")(sdk, stop.Callback, 0, out ulong stopOperation).Code);
            var stopped = await stop.Result.WaitAsync(TimeSpan.FromSeconds(20));
            Assert.AreEqual(stopOperation, stopped.Operation);
            Assert.AreEqual(0u, stopped.Error.Code);
            Assert.AreEqual(0u, Export<Release>("usdk_destroy")(sdk).Code);
            sdk = 0; // Destroy is the callback quiescence barrier, not a timing delay.
            foreach (var count in counts) Assert.AreEqual(1, count());
        }
        finally
        {
            if (sdk != 0) Marshal.GetDelegateForFunctionPointer<Release>(NativeLibrary.GetExport(library, "usdk_destroy"))(sdk);
            if (platform != 0) Marshal.GetDelegateForFunctionPointer<Release>(NativeLibrary.GetExport(library, "usdk_platform_release"))(platform);
            GC.KeepAlive(roots);
            Marshal.FreeCoTaskMem(deviceName);
            NativeLibrary.Free(library);
            Directory.Delete(identity, recursive: true);
        }
    }

    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate uint AbiVersion();
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate NativeError ConfigInit(uint version, out NativeConfig config);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate NativeError PlatformCreate(NativeSpan directory, out ulong platform);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate void Completion(nint context, nint result);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate NativeError Create(ulong platform, in NativeConfig config, Completion callback, nint context, out ulong sdk, out ulong operation);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate NativeError Command(ulong sdk, Completion callback, nint context, out ulong operation);
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)] private delegate NativeError Release(ulong handle);
}
