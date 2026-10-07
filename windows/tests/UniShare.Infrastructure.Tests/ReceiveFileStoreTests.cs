using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Infrastructure.Tests;

[TestClass]
public sealed class ReceiveFileStoreTests
{
    private string root = null!;
    [TestInitialize] public void Initialize() => root = Directory.CreateTempSubdirectory("unishare-receive-tests-").FullName;
    [TestCleanup] public void Cleanup() => Directory.Delete(root, true);
    private static ITransferFiles Store()
    {
        var type = typeof(Storage.JsonSettingsStore).Assembly.GetType("UniShare.Infrastructure.Files.ReceiveFileStore");
        Assert.IsNotNull(type, "Receive file store is missing.");
        return (ITransferFiles)Activator.CreateInstance(type)!;
    }
    private static IncomingOffer Offer(string name) => new(new(1), new(Guid.NewGuid()), new(Guid.NewGuid()), ContentKind.File, name, "application/octet-stream", 3);

    [TestMethod]
    public async Task HostileDisplayNamesPublishOnlySingleSafeComponent()
    {
        var store = Store();
        foreach (var name in new[] { "../escape.txt", "C:\\secret\\file.txt", "a/b\\c.txt", "bad\u0001name.txt", ".", "..", "", "CON", "a:stream", "trailing. " })
        {
            await using var receive = await store.CreateReceiveAsync(Offer(name), root, default);
            Assert.AreEqual(root, Path.GetDirectoryName(receive.StagingPath));
            Assert.IsTrue(Path.GetFileName(receive.StagingPath).StartsWith(".unishare-partial-"));
            Assert.AreEqual("", receive.FinalPath, "A final destination must not be selected before publication.");
            await WriteStaging(receive.StagingPath, [1, 2, 3]);
            var path = await store.PublishAsync(receive, default);
            Assert.AreEqual(root, Path.GetDirectoryName(path));
            Assert.IsFalse(Path.GetFileName(path).Any(c => char.IsControl(c) || "<>:\"/\\|?*".Contains(c)));
            Assert.IsFalse(path.EndsWith('.') || path.EndsWith(' '));
            Assert.IsTrue(File.Exists(path));
            if (OperatingSystem.IsWindows())
                Assert.IsFalse(File.GetAttributes(path).HasFlag(FileAttributes.Hidden));
        }
        Assert.AreEqual(0, Directory.GetFiles(root, ".unishare-partial-*").Length);
    }

    [TestMethod]
    public async Task ConcurrentPublishersPreserveExistingContentAndChooseUniqueNamesAtPublication()
    {
        var store = Store();
        var receives = await Task.WhenAll(Enumerable.Range(0, 24).Select(_ => store.CreateReceiveAsync(Offer("report.txt"), root, default)));
        await File.WriteAllTextAsync(Path.Combine(root, "report.txt"), "existing");
        for (var i = 0; i < receives.Length; i++)
            await WriteStaging(receives[i].StagingPath, System.Text.Encoding.UTF8.GetBytes(i.ToString()));
        var paths = await Task.WhenAll(receives.Select(receive => Task.Run(() => store.PublishAsync(receive, default))));
        Assert.AreEqual(24, paths.Distinct(StringComparer.OrdinalIgnoreCase).Count());
        Assert.AreEqual("existing", await File.ReadAllTextAsync(Path.Combine(root, "report.txt")));
        CollectionAssert.AreEquivalent(Enumerable.Range(0, 24).Select(i => i.ToString()).ToArray(), await Task.WhenAll(paths.Select(path => File.ReadAllTextAsync(path))));
        foreach (var receive in receives)
            await receive.DisposeAsync();
        Assert.AreEqual(0, Directory.GetFiles(root, ".unishare-partial-*").Length);
    }

    [TestMethod]
    public async Task CancelledPublicationAbortsPartialAndExposesNoFinalFile()
    {
        var store = Store();
        await using var receive = await store.CreateReceiveAsync(Offer("cancel.txt"), root, default);
        await WriteStaging(receive.StagingPath, [1, 2, 3]);
        await Assert.ThrowsAsync<OperationCanceledException>(() => store.PublishAsync(receive, new CancellationToken(true)));
        Assert.AreEqual(0, Directory.GetFiles(root).Length);
    }

    [TestMethod]
    public async Task AbortAfterSdkFailureIsIdempotentAndRemovesPartial()
    {
        var store = Store();
        var receive = await store.CreateReceiveAsync(Offer("sdk.txt"), root, default);
        await WriteStaging(receive.StagingPath, [1, 2, 3]);
        await store.AbortAsync(receive);
        await store.AbortAsync(receive);
        await receive.DisposeAsync();
        Assert.AreEqual(0, Directory.GetFiles(root).Length);
    }

    [TestMethod]
    public async Task RenameFailureCleansPartialWithoutLeakingPathInException()
    {
        if (!OperatingSystem.IsWindows())
        {
            Assert.Inconclusive("Windows ACL verification.");
            return;
        }
        var store = Store();
        await using var receive = await store.CreateReceiveAsync(Offer("locked.txt"), root, default);
        await WriteStaging(receive.StagingPath, [1, 2, 3]);
        var directory = new DirectoryInfo(root);
        var original = directory.GetAccessControl();
        using var identity = WindowsIdentity.GetCurrent();
        var restricted = new DirectorySecurity();
        restricted.SetAccessRuleProtection(true, false);
        restricted.AddAccessRule(new FileSystemAccessRule(identity.User!, FileSystemRights.ReadAndExecute | FileSystemRights.Delete | FileSystemRights.ChangePermissions, AccessControlType.Allow));
        try
        {
            // The already-created file still grants Delete, but the directory
            // no longer permits the rename's creation of a final entry.
            directory.SetAccessControl(restricted);
            var failure = await Assert.ThrowsAsync<IOException>(() => store.PublishAsync(receive, default));
            Assert.IsFalse(failure.ToString().Contains(root));
            Assert.AreEqual(0, Directory.GetFiles(root).Length);
        }
        finally { directory.SetAccessControl(original); }
    }

    [TestMethod]
    public async Task UnwritableDirectoryFailureLeavesNoPartialAndRedactsDestination()
    {
        var store = Store();
        var blocked = Path.Combine(root, "not-a-directory");
        await File.WriteAllTextAsync(blocked, "file");
        var error = await Assert.ThrowsAsync<IOException>(() => store.CreateReceiveAsync(Offer("file.txt"), blocked, default));
        Assert.IsFalse(error.ToString().Contains(blocked));
        Assert.AreEqual(0, Directory.GetFiles(root, ".unishare-partial-*").Length);
    }

    [TestMethod]
    public async Task PartialIsHiddenAndAclAllowsOnlyCurrentUser()
    {
        if (!OperatingSystem.IsWindows())
        {
            Assert.Inconclusive("Windows ACL verification.");
            return;
        }
        var store = Store();
        await using var receive = await store.CreateReceiveAsync(Offer("private.txt"), root, default);
        Assert.IsTrue(File.GetAttributes(receive.StagingPath).HasFlag(FileAttributes.Hidden));
        var security = new FileInfo(receive.StagingPath).GetAccessControl();
        Assert.IsTrue(security.AreAccessRulesProtected);
        var rules = security.GetAccessRules(true, true, typeof(SecurityIdentifier)).Cast<FileSystemAccessRule>().ToArray();
        Assert.AreEqual(1, rules.Length);
        Assert.AreEqual(WindowsIdentity.GetCurrent().User, rules[0].IdentityReference);
        Assert.AreEqual(AccessControlType.Allow, rules[0].AccessControlType);
    }

    [TestMethod]
    public async Task RealDirectoryDenyCreateFailsWithoutPartialOrPathDisclosure()
    {
        if (!OperatingSystem.IsWindows())
        {
            Assert.Inconclusive("Windows ACL verification.");
            return;
        }
        var directory = new DirectoryInfo(root);
        var original = directory.GetAccessControl();
        using var identity = WindowsIdentity.GetCurrent();
        var restricted = new DirectorySecurity();
        restricted.SetAccessRuleProtection(true, false);
        restricted.AddAccessRule(new FileSystemAccessRule(identity.User!, FileSystemRights.ReadAndExecute | FileSystemRights.Delete | FileSystemRights.ChangePermissions, AccessControlType.Allow));
        try
        {
            directory.SetAccessControl(restricted);
            var error = await Assert.ThrowsAsync<IOException>(() => Store().CreateReceiveAsync(Offer("blocked.txt"), root, default));
            Assert.IsFalse(error.ToString().Contains(root));
            Assert.AreEqual(0, Directory.GetFiles(root).Length);
        }
        finally { directory.SetAccessControl(original); }
    }
    private static async Task WriteStaging(string path, byte[] bytes)
    {
        // SDK destinations use OPEN_ALWAYS, never CREATE_ALWAYS. Windows
        // refuses CREATE_ALWAYS with normal attributes for an existing hidden file.
        await using var stream = new FileStream(path, FileMode.Open, FileAccess.Write, FileShare.ReadWrite | FileShare.Delete);
        await stream.WriteAsync(bytes);
    }
}
