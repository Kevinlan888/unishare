using System.Collections.Concurrent;
using Microsoft.Data.Sqlite;

namespace UniShare.Infrastructure.Storage;

public static class ActivitySchema
{
    public static async Task InitializeAsync(SqliteConnection connection, CancellationToken cancellationToken)
    {
        using var configuration = connection.CreateCommand();
        // SQLite PRAGMAs and DDL do not accept bound parameters. These are fixed SQL,
        // never constructed from application data.
        configuration.CommandText = "PRAGMA foreign_keys=ON; PRAGMA journal_mode=WAL;";
        await configuration.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        using var transaction = connection.BeginTransaction(deferred: false);
        using var command = connection.CreateCommand();
        command.Transaction = transaction;
        command.CommandText = "PRAGMA user_version";
        var version = Convert.ToInt64(await command.ExecuteScalarAsync(cancellationToken).ConfigureAwait(false));
        if (version > 3)
            throw new InvalidOperationException("Activity database schema is newer than supported.");
        command.CommandText = version == 1 ? """
            ALTER TABLE activities RENAME TO activities_v1;
            CREATE TABLE activities (
                id TEXT PRIMARY KEY NOT NULL,
                device_id TEXT NOT NULL,
                transfer_id TEXT,
                operation_id TEXT,
                retry_of TEXT,
                direction INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                origin INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                byte_count TEXT NOT NULL,
                created_at TEXT NOT NULL,
                created_ticks INTEGER NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT,
                status INTEGER NOT NULL,
                transferred_bytes TEXT NOT NULL,
                error_kind INTEGER,
                received_file_path TEXT,
                CHECK (received_file_path IS NULL OR
                    (direction = 0 AND kind IN (2, 3) AND status = 3 AND error_kind IS NULL))
            );
            INSERT INTO activities (id, device_id, transfer_id, operation_id, retry_of,
                direction, kind, origin, display_name, mime_type, byte_count, created_at,
                created_ticks, updated_at, completed_at, status, transferred_bytes, error_kind, received_file_path)
            SELECT id, device_id, transfer_id, operation_id, retry_of,
                direction, kind, origin, display_name, mime_type, byte_count, created_at,
                created_ticks, updated_at, completed_at, status, transferred_bytes, error_kind, received_file_path
            FROM activities_v1;
            DROP TABLE activities_v1;
            CREATE INDEX IF NOT EXISTS activities_device_created ON activities(device_id, created_ticks DESC, id);
            PRAGMA user_version=2;
            """ : version == 0 ? """
            CREATE TABLE activities (
                id TEXT PRIMARY KEY NOT NULL,
                device_id TEXT NOT NULL,
                transfer_id TEXT,
                operation_id TEXT,
                retry_of TEXT,
                direction INTEGER NOT NULL,
                kind INTEGER NOT NULL,
                origin INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                byte_count TEXT NOT NULL,
                created_at TEXT NOT NULL,
                created_ticks INTEGER NOT NULL,
                updated_at TEXT NOT NULL,
                completed_at TEXT,
                status INTEGER NOT NULL,
                transferred_bytes TEXT NOT NULL,
                error_kind INTEGER,
                received_file_path TEXT,
                CHECK (received_file_path IS NULL OR
                    (direction = 0 AND kind IN (2, 3) AND status = 3 AND error_kind IS NULL))
            );
            CREATE INDEX activities_device_created ON activities(device_id, created_ticks DESC, id);
            PRAGMA user_version=2;
            """ : string.Empty;
        if (command.CommandText.Length != 0)
            await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        if (version < 3)
        {
            command.CommandText = """
                ALTER TABLE activities ADD COLUMN text_body TEXT;
                ALTER TABLE activities ADD COLUMN image_source_path TEXT;
                ALTER TABLE activities ADD COLUMN image_preview_bytes BLOB;
                PRAGMA user_version=3;
                """;
            await command.ExecuteNonQueryAsync(cancellationToken).ConfigureAwait(false);
        }
        cancellationToken.ThrowIfCancellationRequested();
        transaction.Commit();
    }
}

internal static class AppStorage
{
    private static readonly ConcurrentDictionary<string, SemaphoreSlim> Gates = new(StringComparer.OrdinalIgnoreCase);
    internal static string DirectoryPath(string? directory) => Path.GetFullPath(directory ??
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "UniShare", "App"));
    internal static SemaphoreSlim Gate(string path) => Gates.GetOrAdd(path, _ => new SemaphoreSlim(1, 1));
}
