using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using Microsoft.Windows.ApplicationModel.Resources;
using UniShare.Application.Models;
namespace UniShare.App;

// Task 9 uses explicit deterministic projections. The production lifecycle/native
// adapter is composed in the following integration task, never by a page.
public static class CompositionRoot
{
    public static ShellProjection Create(string[] args)
    {
        var model = new ShellProjection(args.Contains("--demo"), args.Contains("--states"));
        if (!model.IsDemo) return model;
        if (args.Contains("--offline")) { model.Recent[0] = model.Recent[0] with { Online = false }; model.SelectedDevice = model.Recent[0]; }
        if (args.Contains("--empty")) { model.Recent.Clear(); model.Nearby.Clear(); model.SelectedDevice = null; }
        if (args.Contains("--no-selection")) model.SelectedDevice = null;
        if (args.Contains("--loading")) model.Notice = Strings.Get("Loading");
        if (args.Contains("--error")) model.Notice = Strings.Get("Error");
        if (args.Contains("--offer") || args.Contains("--failed"))
        {
            model.Activities.Clear();
            model.Activities.Add(new(ContentKind.File, args.Contains("--offer") ? ShareDirection.Incoming : ShareDirection.Outgoing,
                "report.pdf", "", "2.4 MB", "19:26", args.Contains("--offer") ? ActivityStatus.AwaitingDecision : ActivityStatus.Failed));
        }
        return model;
    }
}
public static class Strings
{
    public static string Get(string key) => new ResourceLoader().GetString(key.Replace('.', '/'));
}
public sealed record DeviceProjection(string Name, string Glyph, string Summary, string Time, bool Trusted, bool Online, string Platform);
public sealed class ActivityProjection : ObservableObject
{
    private ActivityStatus status;
    public ActivityProjection(ContentKind kind, ShareDirection direction, string name, string text, string size, string time, ActivityStatus status, ShareOrigin origin = ShareOrigin.Direct)
    { Kind = kind; Direction = direction; Name = name; Text = text; Size = size; Time = time; this.status = status; Origin = origin; }
    public ContentKind Kind { get; }
    public ShareDirection Direction { get; }
    public ShareOrigin Origin { get; }
    public string Name { get; }
    public string Text { get; }
    public string Size { get; }
    public string Time { get; }
    public ActivityStatus Status { get => status; set { if (SetProperty(ref status, value)) OnPropertyChanged(nameof(StatusText)); } }
    public string StatusText => Strings.Get("Activity." + Status);
    public double Progress => 72;
}
public sealed class ShellProjection : ObservableObject
{
    private DeviceProjection? selected;
    private string composerText = "";
    private string? notice;
    public ShellProjection(bool demo, bool states)
    {
        IsDemo = demo;
        SendTextCommand = new RelayCommand(SendText, () => SelectedDevice?.Online == true && !string.IsNullOrWhiteSpace(ComposerText));
        SendPhotoCommand = new RelayCommand(() => DemoSend(ContentKind.Image), () => SelectedDevice?.Online == true);
        SendFileCommand = new RelayCommand(() => DemoSend(ContentKind.File), () => SelectedDevice?.Online == true);
        SendClipboardCommand = new RelayCommand(() => Notice = Strings.Get("DemoClipboard"), () => SelectedDevice?.Online == true);
        if (!demo) { Notice = Strings.Get("Loading"); return; }
        Recent.Add(new("Kevin’s iPhone", "\uE8EA", "IMG_3281.jpg", "19:22", true, true, "iOS"));
        Recent.Add(new("Pixel 10", "\uE8EA", "project.zip · 72%", "18:40", true, true, "Android"));
        Recent.Add(new("Office PC", "\uE7F4", Strings.Get("DemoHello"), Strings.Get("Yesterday"), true, true, "Windows"));
        Nearby.Add(new("Tom’s MacBook", "\uE7F4", Strings.Get("Online"), "", false, true, "macOS"));
        Nearby.Add(new("Alice’s iPhone", "\uE8EA", Strings.Get("Online"), "", false, true, "iOS"));
        Nearby.Add(new("Pixel 9", "\uE8EA", Strings.Get("Online"), "", false, true, "Android"));
        Nearby.Add(new("DESKTOP-7FI3K", "\uE7F4", Strings.Get("Online"), "", false, true, "Windows"));
        SelectedDevice = Recent[0];
        if (states)
        {
            Activities.Add(new(ContentKind.File, ShareDirection.Incoming, "report.pdf", "", "2.4 MB", "19:26", ActivityStatus.AwaitingDecision));
            Activities.Add(new(ContentKind.File, ShareDirection.Outgoing, "notes.txt", "", "12 KB", "19:27", ActivityStatus.Failed));
            Activities.Add(new(ContentKind.File, ShareDirection.Outgoing, "archive.zip", "", "24 MB", "19:28", ActivityStatus.Cancelled));
            Activities.Add(new(ContentKind.Image, ShareDirection.Incoming, "photo.jpg", "", "4 MB", "19:29", ActivityStatus.Rejected));
            Activities.Add(new(ContentKind.Text, ShareDirection.Outgoing, Strings.Get("Content.Text"), "", "", "19:30", ActivityStatus.AwaitingPeer));
        }
    }
    public bool IsDemo { get; }
    public ObservableCollection<DeviceProjection> Recent { get; } = [];
    public ObservableCollection<DeviceProjection> Nearby { get; } = [];
    public ObservableCollection<ActivityProjection> Activities { get; } = [];
    public DeviceProjection? SelectedDevice
    {
        get => selected;
        set
        {
            if (!SetProperty(ref selected, value)) return;
            Activities.Clear();
            if (value?.Name == "Kevin’s iPhone")
            {
                Activities.Add(new(ContentKind.Text, ShareDirection.Outgoing, "", Strings.Get("DemoGreeting"), "", "19:20", ActivityStatus.Completed));
                Activities.Add(new(ContentKind.Text, ShareDirection.Incoming, "", Strings.Get("DemoHi"), "", "19:21", ActivityStatus.Completed));
                Activities.Add(new(ContentKind.Image, ShareDirection.Incoming, "IMG_3281.jpg", "", "8.4 MB", "19:22", ActivityStatus.Completed));
                Activities.Add(new(ContentKind.File, ShareDirection.Outgoing, "project.zip", "", "1.8 GB", "19:24", ActivityStatus.Transferring));
                Activities.Add(new(ContentKind.Text, ShareDirection.Incoming, Strings.Get("Clipboard"), "dotnet publish -c Release", "", "19:25", ActivityStatus.Completed, ShareOrigin.Clipboard));
            }
            OnPropertyChanged(nameof(CanSend)); NotifyCommands();
        }
    }
    public bool CanSend => SelectedDevice?.Online == true;
    public string ComposerText { get => composerText; set { if (SetProperty(ref composerText, value)) SendTextCommand.NotifyCanExecuteChanged(); } }
    public string? Notice { get => notice; set => SetProperty(ref notice, value); }
    public RelayCommand SendTextCommand { get; }
    public RelayCommand SendPhotoCommand { get; }
    public RelayCommand SendFileCommand { get; }
    public RelayCommand SendClipboardCommand { get; }
    public void Decide(ActivityProjection activity, ActivityStatus result) => activity.Status = result;
    public void Retry(ActivityProjection activity)
    {
        if (CanSend && activity.Direction == ShareDirection.Outgoing && activity.Status == ActivityStatus.Failed)
            Activities.Add(new(activity.Kind, activity.Direction, activity.Name, activity.Text, activity.Size, "19:31", ActivityStatus.AwaitingPeer, activity.Origin));
    }
    public async Task DropOneFileAsync(Windows.ApplicationModel.DataTransfer.DataPackageView data)
    {
        if (!CanSend || !data.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems)) return;
        try
        {
            var items = await data.GetStorageItemsAsync();
            if (items.Count != 1 || items[0] is not Windows.Storage.StorageFile file) { Notice = Strings.Get("DropOneFile"); return; }
            var kind = file.ContentType.StartsWith("image/", StringComparison.Ordinal) ? ContentKind.Image : ContentKind.File;
            Activities.Add(new(kind, ShareDirection.Outgoing, file.Name, "", "", "19:31", ActivityStatus.AwaitingPeer));
            Notice = Strings.Get("DemoOnly");
        }
        catch (Exception) { Notice = Strings.Get("Error"); }
    }
    private void SendText()
    {
        Activities.Add(new(ContentKind.Text, ShareDirection.Outgoing, "", ComposerText, "", "19:31", ActivityStatus.Completed));
        ComposerText = "";
    }
    private void DemoSend(ContentKind kind)
    {
        Activities.Add(new(kind, ShareDirection.Outgoing, kind == ContentKind.Image ? "IMG_3281.jpg" : "notes.txt", "", kind == ContentKind.Image ? "8.4 MB" : "12 KB", "19:31", ActivityStatus.AwaitingPeer));
        Notice = Strings.Get("DemoOnly");
    }
    private void NotifyCommands() { SendTextCommand.NotifyCanExecuteChanged(); SendPhotoCommand.NotifyCanExecuteChanged(); SendFileCommand.NotifyCanExecuteChanged(); SendClipboardCommand.NotifyCanExecuteChanged(); }
}
