using CommunityToolkit.Mvvm.Input;
using UniShare.Application.Models;

namespace UniShare.Application.ViewModels;

public sealed class ActivityItemViewModel : CommandViewModel
{
    private readonly IViewModelFiles files;
    private ActivityRecord record;
    private double speed;
    private DateTimeOffset sampleTime;
    private ulong sampleBytes;

    public ActivityItemViewModel(ActivityRecord record, IViewModelFiles files, IViewModelDispatcher dispatcher) : base(dispatcher)
    {
        this.record = record;
        this.files = files;
        sampleTime = record.UpdatedAt;
        sampleBytes = record.TransferredBytes;
        OpenCommand = Command(() => OpenAsync(false), () => FileAvailable, "Activity.OpenFailed");
        OpenLocationCommand = Command(() => OpenAsync(true), () => FileAvailable, "Activity.OpenLocationFailed");
    }

    public ActivityRecord Record => record;
    public ActivityId Id => record.Id;
    public string StatusResourceKey => "Activity." + record.Status;
    public double ProgressFraction => record.ByteCount == 0 ? 0 : Math.Clamp((double)record.TransferredBytes / record.ByteCount, 0, 1);
    public double BytesPerSecond => speed;
    public TimeSpan? EstimatedRemaining
    {
        get
        {
            if (speed <= 0 || record.ByteCount == 0 || record.Status != ActivityStatus.Transferring)
                return null;
            var seconds = (record.ByteCount - Math.Min(record.ByteCount, record.TransferredBytes)) / speed;
            return TimeSpan.FromSeconds(Math.Min(seconds, TimeSpan.MaxValue.TotalSeconds - 1));
        }
    }
    public bool FileAvailable => record is { Direction: ShareDirection.Incoming, Kind: ContentKind.File, Status: ActivityStatus.Completed, ReceivedFilePath: { } path } && files.FileExists(path);
    public IAsyncRelayCommand OpenCommand { get; }
    public IAsyncRelayCommand OpenLocationCommand { get; }

    public void Update(ActivityRecord value)
    {
        if (value.Id != Id)
            throw new ArgumentException("Activity identity must remain stable.", nameof(value));
        if (value.UpdatedAt < record.UpdatedAt || value.TransferredBytes < record.TransferredBytes)
            return;
        var elapsed = (value.UpdatedAt - sampleTime).TotalSeconds;
        if (value.TransferredBytes > sampleBytes)
        {
            if (elapsed > 0)
                speed = (value.TransferredBytes - sampleBytes) / elapsed;
            sampleTime = value.UpdatedAt;
            sampleBytes = value.TransferredBytes;
        }
        record = value;
        OnPropertyChanged(nameof(Record));
        OnPropertyChanged(nameof(StatusResourceKey));
        OnPropertyChanged(nameof(ProgressFraction));
        OnPropertyChanged(nameof(BytesPerSecond));
        OnPropertyChanged(nameof(EstimatedRemaining));
        RefreshFileAvailability();
    }
    public void RefreshFileAvailability()
    {
        OnPropertyChanged(nameof(FileAvailable));
        NotifyCommands();
    }
    private Task OpenAsync(bool location) => FileAvailable ? files.OpenAsync(record.ReceivedFilePath!, location, CancellationToken.None) : Task.CompletedTask;
}
