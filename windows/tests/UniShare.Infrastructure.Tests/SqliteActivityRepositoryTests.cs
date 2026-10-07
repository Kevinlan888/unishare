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
        var expected = Record() with
        {
            DisplayName = "a%_' OR 1=1 -- Report.txt",
            Status = ActivityStatus.Failed,
            ErrorKind = ShareErrorKind.Timeout
        };
        await repository.UpsertAsync(expected, default);
        await repository.UpsertAsync(Record(), default);
        await repository.UpsertAsync(expected with
        {
            Id = new(Guid.NewGuid()),
            DeviceId = Other
        }, default);
        await repository.UpsertAsync(expected with
        {
            Id = new(Guid.NewGuid()),
            Direction = ShareDirection.Incoming
        }, default);
        var rows = await repository.SearchAsync(new("%_' OR 1=1 -- report", Device, ActivityStatus.Failed, ShareDirection.Outgoing), default);
        Assert.AreEqual(expected, rows.Single());
        Assert.AreEqual(4, (await repository.SearchAsync(new(null, null, null, null), default)).Count);
    }

    [TestMethod]
    public async Task UpsertAndReopenRoundTripAllMetadataIncludingUnsignedValuesAndOffsets()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var record = Record() with
        {
            CreatedAt = Now.ToOffset(TimeSpan.FromHours(8)),
            ErrorKind = ShareErrorKind.NetworkLost
        };
        await repository.UpsertAsync(record with
        {
            Status = ActivityStatus.Transferring
        }, default);
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
        Assert.AreEqual(2L, command.ExecuteScalar());
        command.CommandText = "PRAGMA journal_mode";
        Assert.AreEqual("wal", command.ExecuteScalar());
        command.CommandText = "PRAGMA foreign_keys";
        Assert.AreEqual(1L, command.ExecuteScalar());
    }

    [TestMethod]
    public async Task VersionOneDatabaseMigratesWithoutLosingActivitiesAndAcceptsImagePaths()
    {
        using var directory = new TemporaryDirectory();
        Directory.CreateDirectory(directory.Path);
        var database = System.IO.Path.Combine(directory.Path, "activity.db");
        var existing = Record() with
        {
            Direction = ShareDirection.Incoming,
            ReceivedFilePath = "C:\\Downloads\\report.txt"
        };
        using (var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = database, Pooling = false }.ToString()))
        {
            await connection.OpenAsync();
            using var command = connection.CreateCommand();
            command.CommandText = """
                CREATE TABLE activities (
                    id TEXT PRIMARY KEY NOT NULL, device_id TEXT NOT NULL, transfer_id TEXT, operation_id TEXT,
                    retry_of TEXT, direction INTEGER NOT NULL, kind INTEGER NOT NULL, origin INTEGER NOT NULL,
                    display_name TEXT NOT NULL, mime_type TEXT NOT NULL, byte_count TEXT NOT NULL,
                    created_at TEXT NOT NULL, created_ticks INTEGER NOT NULL, updated_at TEXT NOT NULL,
                    completed_at TEXT, status INTEGER NOT NULL, transferred_bytes TEXT NOT NULL, error_kind INTEGER,
                    received_file_path TEXT,
                    CHECK (received_file_path IS NULL OR
                        (direction = 0 AND kind = 3 AND status = 3 AND error_kind IS NULL))
                );
                CREATE INDEX activities_device_created ON activities(device_id, created_ticks DESC, id);
                PRAGMA user_version=1;
                """;
            await command.ExecuteNonQueryAsync();
            command.CommandText = """
                INSERT INTO activities (id, device_id, transfer_id, operation_id, retry_of,
                    direction, kind, origin, display_name, mime_type, byte_count, created_at,
                    created_ticks, updated_at, completed_at, status, transferred_bytes, error_kind, received_file_path)
                VALUES ($id, $device, $transfer, $operation, $retry, $direction, $kind, $origin,
                    $name, $mime, $bytes, $created, $ticks, $updated, $completed, $status, $transferred, $error, $path)
                """;
            command.Parameters.AddWithValue("$id", existing.Id.Value.ToString("D"));
            command.Parameters.AddWithValue("$device", existing.DeviceId.Value.ToString("D"));
            command.Parameters.AddWithValue("$transfer", existing.TransferId!.Value.Value.ToString("D"));
            command.Parameters.AddWithValue("$operation", existing.OperationId!.Value.Value.ToString());
            command.Parameters.AddWithValue("$retry", existing.RetryOf!.Value.Value.ToString("D"));
            command.Parameters.AddWithValue("$direction", (int)existing.Direction);
            command.Parameters.AddWithValue("$kind", (int)existing.Kind);
            command.Parameters.AddWithValue("$origin", (int)existing.Origin);
            command.Parameters.AddWithValue("$name", existing.DisplayName);
            command.Parameters.AddWithValue("$mime", existing.MimeType);
            command.Parameters.AddWithValue("$bytes", existing.ByteCount.ToString());
            command.Parameters.AddWithValue("$created", existing.CreatedAt.ToString("O"));
            command.Parameters.AddWithValue("$ticks", existing.CreatedAt.UtcTicks);
            command.Parameters.AddWithValue("$updated", existing.UpdatedAt.ToString("O"));
            command.Parameters.AddWithValue("$completed", existing.CompletedAt!.Value.ToString("O"));
            command.Parameters.AddWithValue("$status", (int)existing.Status);
            command.Parameters.AddWithValue("$transferred", existing.TransferredBytes.ToString());
            command.Parameters.AddWithValue("$error", DBNull.Value);
            command.Parameters.AddWithValue("$path", existing.ReceivedFilePath!);
            await command.ExecuteNonQueryAsync();
        }

        var repository = Repository(directory);
        var image = existing with
        {
            Id = new(Guid.NewGuid()),
            Direction = ShareDirection.Incoming,
            Kind = ContentKind.Image,
            MimeType = "image/png",
            ReceivedFilePath = "C:\\Downloads\\photo.png"
        };
        await repository.UpsertAsync(image, default);

        var rows = await repository.LoadDeviceAsync(Device, default);
        Assert.AreEqual(existing, rows.Single(row => row.Id == existing.Id));
        Assert.AreEqual(image.ReceivedFilePath, rows.Single(row => row.Id == image.Id).ReceivedFilePath);
        using var reopened = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = database, Pooling = false }.ToString());
        await reopened.OpenAsync();
        using var version = reopened.CreateCommand();
        version.CommandText = "PRAGMA user_version";
        Assert.AreEqual(2L, version.ExecuteScalar());
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
    public async Task OnlySuccessfulReceivedFilesAndImagesRetainFinalPathsInRawDatabase()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var path = "C:\\Downloads\\private-file.txt";
        var successful = Record() with
        {
            Direction = ShareDirection.Incoming,
            ReceivedFilePath = path
        };
        await repository.UpsertAsync(successful, default);
        var successfulImage = successful with { Id = new(Guid.NewGuid()), Kind = ContentKind.Image };
        await repository.UpsertAsync(successfulImage, default);
        foreach (var record in new[] {
            successful with { Direction = ShareDirection.Outgoing },
            successful with { Kind = ContentKind.Text },
            successful with { Status = ActivityStatus.Failed },
            successful with { Status = ActivityStatus.Transferring },
            successful with { ErrorKind = ShareErrorKind.StorageFailed } })
            await repository.UpsertAsync(record with
            {
                Id = new(Guid.NewGuid()),
                ReceivedFilePath = "C:\\secret\\payload.txt"
            }, default);
        var rows = await repository.LoadDeviceAsync(Device, default);
        Assert.AreEqual(path, rows.Single(row => row.Id == successful.Id).ReceivedFilePath);
        Assert.AreEqual(path, rows.Single(row => row.Id == successfulImage.Id).ReceivedFilePath);
        Assert.IsTrue(rows.Where(row => row.Id != successful.Id && row.Id != successfulImage.Id).All(row => row.ReceivedFilePath is null));
        using var connection = new SqliteConnection(new SqliteConnectionStringBuilder { DataSource = System.IO.Path.Combine(directory.Path, "activity.db"), Pooling = false }.ToString());
        await connection.OpenAsync();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT received_file_path FROM activities WHERE received_file_path IS NOT NULL";
        using var reader = command.ExecuteReader();
        var retained = new List<string>();
        while (reader.Read())
            retained.Add(reader.GetString(0));
        CollectionAssert.AreEqual(new[] { path, path }, retained);
    }

    [TestMethod]
    public async Task RealTrackerReloadsCanonicalIncomingHistoryWithoutPayload()
    {
        using var directory = new TemporaryDirectory();
        var repository = Repository(directory);
        var record = Record() with
        {
            Direction = ShareDirection.Incoming,
            ReceivedFilePath = "C:\\Downloads\\report.txt"
        };
        await repository.UpsertAsync(record, default);
        var tracker = new ActivityTracker(repository, new FixedClock(Now));
        var incoming = tracker.AddIncomingOffer(new(new(1), record.TransferId!.Value, Device, ContentKind.File, "report.txt", "text/plain", 1));
        await tracker.SavePendingAsync();
        Assert.AreEqual(record.Id, incoming.Id);
        Assert.AreEqual(1, (await repository.LoadDeviceAsync(Device, default)).Count);
        Assert.AreEqual(record.ReceivedFilePath, tracker.GetDeviceSnapshot(Device).Single().ReceivedFilePath);
    }

    private sealed class FixedClock(DateTimeOffset now) : IClock
    {
        public DateTimeOffset UtcNow => now;
    }
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
