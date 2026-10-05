using System.Collections.Specialized;
using System.ComponentModel;

namespace UniShare.App.Presentation;

internal sealed class ViewSubscriptions : IDisposable
{
    private readonly INotifyPropertyChanged model;
    private readonly INotifyCollectionChanged? collection;
    private readonly Action<string?> propertyChanged;
    private readonly Action? collectionChanged;
    private readonly Action? cleanup;
    private bool disposed;
    public ViewSubscriptions(INotifyPropertyChanged model, Action<string?> propertyChanged,
        INotifyCollectionChanged? collection = null, Action? collectionChanged = null, Action? cleanup = null)
    {
        this.model = model; this.propertyChanged = propertyChanged; this.collection = collection;
        this.collectionChanged = collectionChanged; this.cleanup = cleanup;
        model.PropertyChanged += OnPropertyChanged;
        if (collection is not null) collection.CollectionChanged += OnCollectionChanged;
    }
    private void OnPropertyChanged(object? sender, PropertyChangedEventArgs args) { if (!disposed) propertyChanged(args.PropertyName); }
    private void OnCollectionChanged(object? sender, NotifyCollectionChangedEventArgs args) { if (!disposed) collectionChanged?.Invoke(); }
    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        model.PropertyChanged -= OnPropertyChanged;
        if (collection is not null) collection.CollectionChanged -= OnCollectionChanged;
        cleanup?.Invoke();
    }
}
