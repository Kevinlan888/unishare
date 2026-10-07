using CommunityToolkit.Mvvm.ComponentModel;
using UniShare.Application.Services;

namespace UniShare.Application.ViewModels;

public sealed class DeviceListViewModel(DeviceStore store) : ObservableObject
{
    private readonly Dictionary<UniShare.Application.Models.DeviceId, DeviceListItemViewModel> rows = [];
    private IReadOnlyList<DeviceListItemViewModel> trusted = [];
    private IReadOnlyList<DeviceListItemViewModel> nearby = [];
    public IReadOnlyList<DeviceListItemViewModel> Trusted
    {
        get => trusted; private set => SetProperty(ref trusted, value);
    }
    public IReadOnlyList<DeviceListItemViewModel> Nearby
    {
        get => nearby; private set => SetProperty(ref nearby, value);
    }
    // Called on the binding dispatcher by the owning shell. Entries stay stable.
    public void Refresh()
    {
        DeviceListItemViewModel Row(DeviceEntry entry)
        {
            if (!rows.TryGetValue(entry.Id, out var row))
                rows.Add(entry.Id, row = new(entry));
            row.Refresh();
            return row;
        }
        Trusted = store.TrustedDevices.Select(Row).ToArray();
        Nearby = store.NearbyDevices.Select(Row).ToArray();
    }
}

public sealed class DeviceListItemViewModel(DeviceEntry entry) : ObservableObject
{
    public DeviceEntry Entry { get; } = entry;
    public string Name => Entry.Name;
    public bool IsOnline => Entry.IsOnline;
    public bool IsTrusted => Entry.IsTrusted;
    public bool IdentityChanged => Entry.IsBlocked;
    public void Refresh()
    {
        OnPropertyChanged(nameof(Name));
        OnPropertyChanged(nameof(IsOnline));
        OnPropertyChanged(nameof(IsTrusted));
        OnPropertyChanged(nameof(IdentityChanged));
    }
}
