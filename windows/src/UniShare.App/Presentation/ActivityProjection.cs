using System.Collections.ObjectModel;
using System.Globalization;
using CommunityToolkit.Mvvm.ComponentModel;
using UniShare.Application.Models;

namespace UniShare.App;

public sealed class ActivityProjection : ObservableObject
{
    public ActivityId? Id
    {
        get; init;
    }
    private ActivityStatus status;
    private double progress;
    private string name;
    private string text;
    private string size;
    private string time;
    private ulong transferredBytes;
    private ulong byteCount;
    private DateTimeOffset updatedAt;
    private DateTimeOffset sampleTime;
    private ulong sampleBytes;
    private double bytesPerSecond;
    private string? receivedFilePath;
    private Func<string, bool> fileExists;
    private Func<string, bool, Task> open;
    public ActivityProjection(ContentKind kind, ShareDirection direction, string name, string text, string size, string time,
        ActivityStatus status, ShareOrigin origin = ShareOrigin.Direct, double progress = 0, ulong transferredBytes = 0,
        ulong byteCount = 0, DateTimeOffset? updatedAt = null, string? receivedFilePath = null,
        Func<string, bool>? fileExists = null, Func<string, bool, Task>? open = null)
    {
        Kind = kind;
        Direction = direction;
        this.name = name;
        this.text = text;
        this.size = size;
        this.time = time;
        this.status = status;
        Origin = origin;
        this.progress = Math.Clamp(progress, 0, 100);
        this.transferredBytes = transferredBytes;
        this.byteCount = byteCount;
        this.updatedAt = updatedAt ?? DateTimeOffset.MinValue;
        sampleTime = this.updatedAt;
        sampleBytes = transferredBytes;
        this.receivedFilePath = receivedFilePath;
        this.fileExists = fileExists ?? (_ => false);
        this.open = open ?? ((_, _) => Task.CompletedTask);
    }
    public ContentKind Kind { get; }
    public ShareDirection Direction { get; }
    public ShareOrigin Origin { get; }
    public string Name
    {
        get => name;
        private set => SetProperty(ref name, value);
    }
    public string Text
    {
        get => text;
        private set => SetProperty(ref text, value);
    }
    public string Size
    {
        get => size;
        private set => SetProperty(ref size, value);
    }
    public string Time
    {
        get => time;
        private set => SetProperty(ref time, value);
    }
    public ActivityStatus Status
    {
        get => status; set
        {
            if (SetProperty(ref status, value))
            {
                OnPropertyChanged(nameof(StatusText));
                OnPropertyChanged(nameof(FileAvailable));
                NotifyTransferMetrics();
            }
        }
    }
    public string StatusText => Strings.Get("Activity." + Status);
    public double Progress
    {
        get => progress;
        private set
        {
            if (SetProperty(ref progress, value))
                OnPropertyChanged(nameof(ProgressText));
        }
    }
    public string ProgressText => $"{Progress:0}%";
    public double BytesPerSecond => bytesPerSecond;
    public string SpeedText => bytesPerSecond > 0 ? $"{FormatSize(bytesPerSecond)}/s" : string.Empty;
    public TimeSpan? EstimatedRemaining
    {
        get
        {
            if (bytesPerSecond <= 0 || byteCount == 0 || Status != ActivityStatus.Transferring)
                return null;
            var seconds = (byteCount - Math.Min(byteCount, transferredBytes)) / bytesPerSecond;
            return TimeSpan.FromSeconds(Math.Min(Math.Ceiling(seconds), TimeSpan.MaxValue.TotalSeconds - 1));
        }
    }
    public string EstimatedRemainingText => EstimatedRemaining is { } remaining ? FormatDuration(remaining) : string.Empty;
    public bool FileAvailable => Direction == ShareDirection.Incoming && Kind is ContentKind.File or ContentKind.Image &&
        Status == ActivityStatus.Completed && receivedFilePath is { } path && fileExists(path);
    public async Task OpenAsync(bool location)
    {
        RefreshFileAvailability();
        if (!FileAvailable || receivedFilePath is not { } path)
            return;
        await open(path, location);
        RefreshFileAvailability();
    }
    public void RefreshFileAvailability() => OnPropertyChanged(nameof(FileAvailable));
    public static double ProgressPercentage(ulong transferredBytes, ulong byteCount) =>
        byteCount == 0 ? 0 : Math.Clamp((double)transferredBytes / byteCount * 100, 0, 100);
    public static string FormatSize(ulong byteCount)
        => FormatSize((double)byteCount);
    private static string FormatSize(double byteCount)
    {
        const double kilobyte = 1024;
        const double megabyte = kilobyte * 1024;
        const double gigabyte = megabyte * 1024;
        if (byteCount < kilobyte)
            return $"{byteCount.ToString("0.#", CultureInfo.InvariantCulture)} B";
        var (value, unit) = byteCount >= gigabyte
            ? (byteCount / gigabyte, "GB")
            : byteCount >= megabyte
                ? (byteCount / megabyte, "MB")
                : (byteCount / kilobyte, "KB");
        return $"{value.ToString("0.#", CultureInfo.InvariantCulture)} {unit}";
    }
    private static string FormatDuration(TimeSpan value) => value.TotalHours >= 1
        ? $"{(int)value.TotalHours:00}:{value.Minutes:00}:{value.Seconds:00}"
        : $"{value.Minutes:00}:{value.Seconds:00}";
    internal void UpdateFrom(ActivityProjection value)
    {
        if (value.Id != Id)
            throw new ArgumentException("Activity identity must remain stable.", nameof(value));
        Name = value.Name;
        Text = value.Text;
        Size = value.Size;
        Time = value.Time;
        receivedFilePath = value.receivedFilePath;
        fileExists = value.fileExists;
        open = value.open;
        var statusChanged = Status != value.Status;
        Status = value.Status;
        if (!statusChanged)
        {
            OnPropertyChanged(nameof(StatusText));
            OnPropertyChanged(nameof(FileAvailable));
        }
        Progress = Math.Max(Progress, value.Progress);
        UpdateTransferMetrics(value);
    }
    private void UpdateTransferMetrics(ActivityProjection value)
    {
        if (value.updatedAt < updatedAt || value.transferredBytes < transferredBytes)
            return;
        var elapsed = (value.updatedAt - sampleTime).TotalSeconds;
        if (value.transferredBytes > sampleBytes)
        {
            if (elapsed > 0)
                bytesPerSecond = (value.transferredBytes - sampleBytes) / elapsed;
            sampleTime = value.updatedAt;
            sampleBytes = value.transferredBytes;
        }
        transferredBytes = value.transferredBytes;
        byteCount = value.byteCount;
        updatedAt = value.updatedAt;
        NotifyTransferMetrics();
    }
    private void NotifyTransferMetrics()
    {
        OnPropertyChanged(nameof(BytesPerSecond));
        OnPropertyChanged(nameof(SpeedText));
        OnPropertyChanged(nameof(EstimatedRemaining));
        OnPropertyChanged(nameof(EstimatedRemainingText));
    }
}

public static class ActivityProjectionCollection
{
    public static void Synchronize(ObservableCollection<ActivityProjection> target, IReadOnlyList<ActivityProjection> source)
    {
        ArgumentNullException.ThrowIfNull(target);
        ArgumentNullException.ThrowIfNull(source);
        for (var index = 0; index < source.Count; index++)
        {
            var candidate = source[index];
            var existingIndex = IndexOf(target, candidate.Id);
            if (existingIndex < 0)
            {
                target.Insert(index, candidate);
                continue;
            }
            var existing = target[existingIndex];
            existing.UpdateFrom(candidate);
            if (existingIndex != index)
                target.Move(existingIndex, index);
        }
        while (target.Count > source.Count)
            target.RemoveAt(target.Count - 1);
    }

    private static int IndexOf(IReadOnlyList<ActivityProjection> target, ActivityId? id)
    {
        if (id is null)
            return -1;
        for (var index = 0; index < target.Count; index++)
            if (target[index].Id == id)
                return index;
        return -1;
    }
}
