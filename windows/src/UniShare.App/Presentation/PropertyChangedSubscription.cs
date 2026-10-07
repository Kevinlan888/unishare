using System.ComponentModel;

namespace UniShare.App.Presentation;

// The binding thread owns creation and disposal. Detach explicitly before a
// rendered card is replaced; the live activity must not root removed controls.
internal sealed class PropertyChangedSubscription : IDisposable
{
    private INotifyPropertyChanged? source;
    private Action? refresh;

    public PropertyChangedSubscription(INotifyPropertyChanged source, Action refresh)
    {
        ArgumentNullException.ThrowIfNull(source);
        ArgumentNullException.ThrowIfNull(refresh);
        this.source = source;
        this.refresh = refresh;
        source.PropertyChanged += OnPropertyChanged;
    }

    private void OnPropertyChanged(object? sender, PropertyChangedEventArgs args) => refresh?.Invoke();

    public void Dispose()
    {
        if (source is null)
            return;
        source.PropertyChanged -= OnPropertyChanged;
        source = null;
        refresh = null;
    }
}
