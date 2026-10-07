using System.Globalization;
using Microsoft.Data.Sqlite;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Infrastructure.Storage;

public sealed class SqliteActivityRepository : IActivityRepository
{
    private readonly string directory;
    private readonly string connectionString;
    private readonly SemaphoreSlim gate;
    private readonly IClock? clock;
    private readonly int maxPerDevice;
    private readonly TimeSpan maxAge;

    public SqliteActivityRepository(string? storageDirectory = null, IClock? clock = null,
        int maxPerDevice = 500, TimeSpan? maxAge = null)
    {
        ValidateRetention(maxPerDevice, maxAge ?? TimeSpan.FromDays(30));
        directory = AppStorage.DirectoryPath(storageDirectory);
        var database = Path.Combine(directory, "activity.db");
        gate = AppStorage.Gate(database);
        connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = database,
            Mode = SqliteOpenMode.ReadWriteCreate,
            ForeignKeys = true,
            Pooling = false,
            DefaultTimeout = 30
        }.ToString();
        this.clock = clock;
        this.maxPerDevice = maxPerDevice;
        this.maxAge = maxAge ?? TimeSpan.FromDays(30);
    }

    public async Task UpsertAsync(ActivityRecord record, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(record);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var connection = await OpenAsync(cancellationToken).ConfigureAwait(false);
            using var transaction = connection.BeginTransaction(deferred: false);
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = """
                INSERT INTO activities (id, device_id, transfer_id, operation_id, retry_of,
                    direction, kind, origin, display_name, mime_type, byte_count, created_at,
                    created_ticks, updated_at, completed_at, status, transferred_bytes, error_kind, received_file_path)
                VALUES ($id, $device, $transfer, $operation, $retry, $direction, $kind, $origin,
                    $name, $mime, $bytes, $created, $ticks, $updated, $completed, $status, $transferred, $error, $path)
                ON CONFLICT(id) DO UPDATE SET device_id=excluded.device_id,
                    transfer_id=excluded.transfer_id, operation_id=excluded.operation_id,
                    retry_of=excluded.retry_of, direction=excluded.direction, kind=excluded.kind,
                    origin=excluded.origin, display_name=excluded.display_name, mime_type=excluded.mime_type,
                    byte_count=excluded.byte_count, created_at=excluded.created_at, created_ticks=excluded.created_ticks,
                    updated_at=excluded.updated_at, completed_at=excluded.completed_at,
                    status=excluded.status, transferred_bytes=excluded.transferred_bytes,
                    error_kind=excluded.error_kind, received_file_path=excluded.received_file_path
                """;
            Add(command, "$id", record.Id.Value.ToString("D"));
            Add(command, "$device", record.DeviceId.Value.ToString("D"));
            Add(command, "$transfer", record.TransferId?.Value.ToString("D"));
            Add(command, "$operation", record.OperationId?.Value.ToString(CultureInfo.InvariantCulture));
            Add(command, "$retry", record.RetryOf?.Value.ToString("D"));
            Add(command, "$direction", (int)record.Direction);
            Add(command, "$kind", (int)record.Kind);
            Add(command, "$origin", (int)record.Origin);
            Add(command, "$name", record.DisplayName);
            Add(command, "$mime", record.MimeType);
            Add(command, "$bytes", record.ByteCount.ToString(CultureInfo.InvariantCulture));
            Add(command, "$created", Timestamp(record.CreatedAt));
            Add(command, "$ticks", record.CreatedAt.UtcTicks);
            Add(command, "$updated", Timestamp(record.UpdatedAt));
            Add(command, "$completed", record.CompletedAt is { } completed ? Timestamp(completed) : null);
            Add(command, "$status", (int)record.Status);
            Add(command, "$transferred", record.TransferredBytes.ToString(CultureInfo.InvariantCulture));
            Add(command, "$error", record.ErrorKind is { } error ? (int)error : null);
            Add(command, "$path", record.Direction == ShareDirection.Incoming && record.Kind is ContentKind.File or ContentKind.Image &&
                record.Status == ActivityStatus.Completed && record.ErrorKind is null ? record.ReceivedFilePath : null);
            await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
            await PruneCoreAsync(connection, transaction, clock?.UtcNow ?? DateTimeOffset.UtcNow,
                maxPerDevice, maxAge, record.DeviceId, cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            transaction.Commit();
        }
        finally { gate.Release(); }
    }

    public Task<IReadOnlyList<ActivityRecord>> LoadDeviceAsync(DeviceId device, CancellationToken cancellationToken) =>
        SearchAsync(new ActivityQuery(null, device, null, null), cancellationToken);

    public async Task<IReadOnlyList<ActivityRecord>> SearchAsync(ActivityQuery query, CancellationToken cancellationToken)
    {
        ArgumentNullException.ThrowIfNull(query);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var connection = await OpenAsync(cancellationToken).ConfigureAwait(false);
            using var command = connection.CreateCommand();
            command.CommandText = """
                SELECT id, device_id, transfer_id, operation_id, retry_of, direction, kind, origin,
                    display_name, mime_type, byte_count, created_at, updated_at, completed_at,
                    status, transferred_bytes, error_kind, received_file_path
                FROM activities
                WHERE ($device IS NULL OR device_id=$device)
                    AND ($status IS NULL OR status=$status)
                    AND ($direction IS NULL OR direction=$direction)
                    AND ($text IS NULL OR instr(lower(display_name), lower($text)) > 0)
                ORDER BY created_ticks DESC, id
                """;
            Add(command, "$device", query.Device?.Value.ToString("D"));
            Add(command, "$status", query.Status is { } status ? (int)status : null);
            Add(command, "$direction", query.Direction is { } direction ? (int)direction : null);
            Add(command, "$text", string.IsNullOrEmpty(query.Text) ? null : query.Text);
            using var reader = await command.ExecuteReaderAsync(cancellationToken).ConfigureAwait(false);
            var records = new List<ActivityRecord>();
            while (await reader.ReadAsync(cancellationToken).ConfigureAwait(false))
                records.Add(new(new(Guid.Parse(reader.GetString(0))), new(Guid.Parse(reader.GetString(1))),
                    reader.IsDBNull(2) ? null : new TransferId(Guid.Parse(reader.GetString(2))),
                    reader.IsDBNull(3) ? null : new OperationId(Unsigned(reader.GetString(3))),
                    reader.IsDBNull(4) ? null : new ActivityId(Guid.Parse(reader.GetString(4))),
                    (ShareDirection)reader.GetInt32(5), (ContentKind)reader.GetInt32(6), (ShareOrigin)reader.GetInt32(7),
                    reader.GetString(8), reader.GetString(9), Unsigned(reader.GetString(10)),
                    ParseTimestamp(reader.GetString(11)), ParseTimestamp(reader.GetString(12)),
                    reader.IsDBNull(13) ? null : ParseTimestamp(reader.GetString(13)),
                    (ActivityStatus)reader.GetInt32(14), Unsigned(reader.GetString(15)),
                    reader.IsDBNull(16) ? null : (ShareErrorKind)reader.GetInt32(16),
                    reader.IsDBNull(17) ? null : reader.GetString(17)));
            return records;
        }
        finally { gate.Release(); }
    }

    public async Task PruneAsync(DateTimeOffset now, int maxPerDevice, TimeSpan maxAge, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        ValidateRetention(maxPerDevice, maxAge);
        await gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            using var connection = await OpenAsync(cancellationToken).ConfigureAwait(false);
            using var transaction = connection.BeginTransaction(deferred: false);
            await PruneCoreAsync(connection, transaction, now, maxPerDevice, maxAge, null, cancellationToken).ConfigureAwait(false);
            cancellationToken.ThrowIfCancellationRequested();
            transaction.Commit();
        }
        finally { gate.Release(); }
    }

    private async Task<SqliteConnection> OpenAsync(CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(directory);
        var connection = new SqliteConnection(connectionString);
        try
        {
            await connection.OpenAsync(cancellationToken).ConfigureAwait(false);
            await ActivitySchema.InitializeAsync(connection, cancellationToken).ConfigureAwait(false);
            return connection;
        }
        catch { connection.Dispose(); throw; }
    }

    private static async Task PruneCoreAsync(SqliteConnection connection, SqliteTransaction transaction,
        DateTimeOffset now, int maxPerDevice, TimeSpan maxAge, DeviceId? device, CancellationToken cancellationToken)
    {
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = """
            DELETE FROM activities WHERE ($device IS NULL OR device_id=$device)
                AND (created_ticks < $cutoff OR id IN (
                    SELECT id FROM (
                        SELECT id, ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY created_ticks DESC, id) AS position
                        FROM activities WHERE ($device IS NULL OR device_id=$device)
                    ) WHERE position > $max
                ))
            """;
        Add(command, "$device", device?.Value.ToString("D"));
        Add(command, "$cutoff", maxAge.Ticks > now.UtcTicks ? 0 : now.UtcTicks - maxAge.Ticks);
        Add(command, "$max", maxPerDevice);
        await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
    }

    private static void ValidateRetention(int maxPerDevice, TimeSpan maxAge)
    {
        ArgumentOutOfRangeException.ThrowIfNegative(maxPerDevice);
        ArgumentOutOfRangeException.ThrowIfLessThan(maxAge, TimeSpan.Zero);
    }
    private static void Add(SqliteCommand command, string name, object? value) => command.Parameters.AddWithValue(name, value ?? DBNull.Value);
    private static string Timestamp(DateTimeOffset value) => value.ToString("O", CultureInfo.InvariantCulture);
    private static DateTimeOffset ParseTimestamp(string value) => DateTimeOffset.ParseExact(value, "O", CultureInfo.InvariantCulture);
    private static ulong Unsigned(string value) => ulong.Parse(value, CultureInfo.InvariantCulture);
}
