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
    public ActivityProjection(ContentKind kind, ShareDirection direction, string name, string text, string size, string time,
        ActivityStatus status, ShareOrigin origin = ShareOrigin.Direct, double progress = 0)
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
                OnPropertyChanged(nameof(StatusText));
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
    public static double ProgressPercentage(ulong transferredBytes, ulong byteCount) =>
        byteCount == 0 ? 0 : Math.Clamp((double)transferredBytes / byteCount * 100, 0, 100);
    public static string FormatSize(ulong byteCount)
    {
        const double kilobyte = 1024;
        const double megabyte = kilobyte * 1024;
        const double gigabyte = megabyte * 1024;
        if (byteCount < kilobyte)
            return $"{byteCount.ToString(CultureInfo.InvariantCulture)} B";
        var (value, unit) = byteCount >= gigabyte
            ? (byteCount / gigabyte, "GB")
            : byteCount >= megabyte
                ? (byteCount / megabyte, "MB")
                : (byteCount / kilobyte, "KB");
        return $"{value.ToString("0.#", CultureInfo.InvariantCulture)} {unit}";
    }
    internal void UpdateFrom(ActivityProjection value)
    {
        if (value.Id != Id)
            throw new ArgumentException("Activity identity must remain stable.", nameof(value));
        Name = value.Name;
        Text = value.Text;
        Size = value.Size;
        Time = value.Time;
        var statusChanged = Status != value.Status;
        Status = value.Status;
        if (!statusChanged)
            OnPropertyChanged(nameof(StatusText));
        Progress = Math.Max(Progress, value.Progress);
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
