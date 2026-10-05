using Microsoft.Data.Sqlite;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Infrastructure.Storage;

namespace UniShare.Infrastructure.Tests;

[TestClass]
public sealed class SqliteActivityRepositoryTests
{
    private static readonly DateTimeOffset Now = new(2026, 10, 5, 12, 0, 0, TimeSpan.Zero);
    private static readonly DeviceId Device = new(Guid.Parse("10000000-0000-0000-0000-000000000001"));
    private static readonly DeviceId Other = new(Guid.Parse("10000000-0000-0000-0000-000000000002"));
    private static SqliteActivityRepository Repository(TemporaryDirectory directory) => new(directory.Path, new FixedClock(Now));
    private static ActivityRecord Record(DeviceId? device = null, DateTimeOffset? created = null) => new(
        new(Guid.NewGuid()), device ?? Device, new(Guid.NewGuid()), new(ulong.MaxValue), new(Guid.NewGuid()),
        ShareDirection.Outgoing, ContentKind.File, ShareOrigin.Direct, "report.txt", "text/plain", ulong.MaxValue,
        created ?? Now, Now, Now, ActivityStatus.Completed, ulong.MaxValue, null, null);

    [TestMethod]
    public async Task UpsertRetainsNewestFiveHundredWithoutChangingAnotherDevice()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var other = Record(Other);
        await repository.UpsertAsync(other, default);
        var oldest = Record(created: Now.AddMinutes(-501));
        await repository.UpsertAsync(oldest, default);
        for (var i = 0; i < 500; i++)
            await repository.UpsertAsync(Record(created: Now.AddMinutes(-i)), default);
        var history = await repository.LoadDeviceAsync(Device, default);
        Assert.AreEqual(500, history.Count);
        Assert.IsFalse(history.Any(row => row.Id == oldest.Id));
        Assert.AreEqual(other, (await repository.LoadDeviceAsync(Other, default)).Single());
    }

    [TestMethod]
    public async Task AgeRetentionUsesCreationTimeAndIncludesExactThirtyDayBoundary()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var boundary = Record(created: Now.AddDays(-30));
        await repository.UpsertAsync(boundary, default);
        await repository.UpsertAsync(Record(created: Now.AddDays(-30).AddTicks(-1)), default);
        Assert.AreEqual(boundary, (await repository.LoadDeviceAsync(Device, default)).Single());
    }

    [TestMethod]
    public async Task ExplicitPruneAppliesIndependentLimitsToEveryDevice()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        foreach (var device in new[] { Device, Other })
        {
            await repository.UpsertAsync(Record(device, Now.AddDays(-10)), default);
            await repository.UpsertAsync(Record(device, Now.AddMinutes(-2)), default);
            await repository.UpsertAsync(Record(device, Now.AddMinutes(-1)), default);
            await repository.UpsertAsync(Record(device), default);
        }
        await repository.PruneAsync(Now, 2, TimeSpan.FromDays(1), default);
        foreach (var device in new[] { Device, Other })
        {
            var rows = await repository.LoadDeviceAsync(device, default);
            Assert.AreEqual(2, rows.Count);
            Assert.IsTrue(rows.All(row => row.CreatedAt >= Now.AddMinutes(-1)));
        }
    }

    [TestMethod]
    public async Task SearchCombinesNameDeviceStatusDirectionAndTreatsSqlTextLiterally()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var expected = Record() with { DisplayName = "a%_' OR 1=1 -- Report.txt", Status = ActivityStatus.Failed, ErrorKind = ShareErrorKind.Timeout };
        await repository.UpsertAsync(expected, default);
        await repository.UpsertAsync(Record(), default);
        await repository.UpsertAsync(expected with { Id = new(Guid.NewGuid()), DeviceId = Other }, default);
        await repository.UpsertAsync(expected with { Id = new(Guid.NewGuid()), Direction = ShareDirection.Incoming }, default);
        var rows = await repository.SearchAsync(new("%_' OR 1=1 -- report", Device, ActivityStatus.Failed, ShareDirection.Outgoing), default);
        Assert.AreEqual(expected, rows.Single());
        Assert.AreEqual(4, (await repository.SearchAsync(new(null, null, null, null), default)).Count);
    }

    [TestMethod]
    public async Task UpsertAndReopenRoundTripAllMetadataIncludingUnsignedValuesAndOffsets()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var record = Record() with { CreatedAt = Now.ToOffset(TimeSpan.FromHours(8)), ErrorKind = ShareErrorKind.NetworkLost };
        await repository.UpsertAsync(record with { Status = ActivityStatus.Transferring }, default);
        await repository.UpsertAsync(record, default);
        Assert.AreEqual(record, (await Repository(directory).LoadDeviceAsync(Device, default)).Single());
    }

    [TestMethod]
    public async Task SchemaInitializationIsIdempotentWithWalAndForeignKeys()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        await repository.LoadDeviceAsync(Device, default);
        await Repository(directory).LoadDeviceAsync(Device, default);
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = System.IO.Path.Combine(directory.Path, "activity.db"), Pooling = false }.ToString());
        await connection.OpenAsync();
        await ActivitySchema.InitializeAsync(connection, default);
        await ActivitySchema.InitializeAsync(connection, default);
        using var command = connection.CreateCommand();
        command.CommandText = "PRAGMA user_version";
        Assert.AreEqual(1L, command.ExecuteScalar());
        command.CommandText = "PRAGMA journal_mode";
        Assert.AreEqual("wal", command.ExecuteScalar());
        command.CommandText = "PRAGMA foreign_keys";
        Assert.AreEqual(1L, command.ExecuteScalar());
    }

    [TestMethod]
    public async Task ConcurrentInstancesInitializeAndWriteWithoutLostRows()
    {
        using var directory = new TemporaryDirectory();
        var records = Enumerable.Range(0, 60).Select(_ => Record()).ToArray();
        await Task.WhenAll(records.Select(record => Task.Run(() => Repository(directory).UpsertAsync(record, default))));
        var rows = await Repository(directory).LoadDeviceAsync(Device, default);
        Assert.AreEqual(60, rows.Count);
        CollectionAssert.AreEquivalent(records.Select(r => r.Id).ToArray(), rows.Select(r => r.Id).ToArray());
    }

    [TestMethod]
    public async Task CancelledAndInvalidPruneCannotChangeExistingRows()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        await repository.UpsertAsync(Record(), default);
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAsync<OperationCanceledException>(() => repository.PruneAsync(Now, 0, TimeSpan.Zero, cancellation.Token));
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => repository.PruneAsync(Now, -1, TimeSpan.FromDays(1), default));
        Assert.AreEqual(1, (await repository.LoadDeviceAsync(Device, default)).Count);
    }

    [TestMethod]
    public async Task OnlySuccessfulReceivedFilesRetainFinalPathsInRawDatabase()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var path = "C:\\Downloads\\private-file.txt";
        var successful = Record() with { Direction = ShareDirection.Incoming, ReceivedFilePath = path };
        await repository.UpsertAsync(successful, default);
        foreach (var record in new[] {
            successful with { Direction = ShareDirection.Outgoing },
            successful with { Kind = ContentKind.Text },
            successful with { Kind = ContentKind.Image },
            successful with { Status = ActivityStatus.Failed },
            successful with { Status = ActivityStatus.Transferring },
            successful with { ErrorKind = ShareErrorKind.StorageFailed } })
            await repository.UpsertAsync(record with { Id = new(Guid.NewGuid()), ReceivedFilePath = "C:\\secret\\payload.txt" }, default);
        var rows = await repository.LoadDeviceAsync(Device, default);
        Assert.AreEqual(path, rows.Single(row => row.Id == successful.Id).ReceivedFilePath);
        Assert.IsTrue(rows.Where(row => row.Id != successful.Id).All(row => row.ReceivedFilePath is null));
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = System.IO.Path.Combine(directory.Path, "activity.db"), Pooling = false }.ToString());
        await connection.OpenAsync();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT received_file_path FROM activities WHERE received_file_path IS NOT NULL";
        using var reader = command.ExecuteReader();
        Assert.IsTrue(reader.Read());
        Assert.AreEqual(path, reader.GetString(0));
        Assert.IsFalse(reader.Read());
    }

    [TestMethod]
    public async Task RealTrackerReloadsCanonicalIncomingHistoryWithoutPayload()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var record = Record() with { Direction = ShareDirection.Incoming, ReceivedFilePath = "C:\\Downloads\\report.txt" };
        await repository.UpsertAsync(record, default);
        var tracker = new ActivityTracker(repository, new FixedClock(Now));
        var incoming = tracker.AddIncomingOffer(new(new(1), record.TransferId!.Value, Device, ContentKind.File, "report.txt", "text/plain", 1));
        await tracker.SavePendingAsync();
        Assert.AreEqual(record.Id, incoming.Id);
        Assert.AreEqual(1, (await repository.LoadDeviceAsync(Device, default)).Count);
        Assert.AreEqual(record.ReceivedFilePath, tracker.GetDeviceSnapshot(Device).Single().ReceivedFilePath);
    }

    private sealed class FixedClock(DateTimeOffset now) : IClock { public DateTimeOffset UtcNow => now; }
}

internal sealed class TemporaryDirectory : IDisposable
{
    public string Path { get; } = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "UniShare.Infrastructure.Tests", Guid.NewGuid().ToString("N"));
    public TemporaryDirectory() => Directory.CreateDirectory(Path);
    public void Dispose()
    {
        Directory.Delete(Path, recursive: true);
        Assert.IsFalse(Directory.Exists(Path), "Test temporary directory must be removed.");
    }
}
