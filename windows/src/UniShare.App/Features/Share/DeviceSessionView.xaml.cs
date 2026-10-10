using System.Collections.Specialized;
using System.ComponentModel;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using UniShare.App.Controls;
using UniShare.App.Presentation;
using UniShare.Application.Models;
namespace UniShare.App.Features.Share;

public sealed partial class DeviceSessionView : UserControl, IDisposable
{
    private readonly ShellProjection model;
    private readonly ViewSubscriptions subscriptions;
    private readonly DeviceDetailsPane details;
    private readonly ActivityCardReconciler<RenderedCard> cards;
    private readonly ActivityAutoScrollTracker autoScroll = new();
    private readonly Dictionary<ActivityProjection, PropertyChangedEventHandler> activitySubscriptions = new(ReferenceEqualityComparer.Instance);
    private bool disposed;
    public DeviceSessionView(ShellProjection model)
    {
        InitializeComponent();
        this.model = model;
        DataContext = model;
        details = new DeviceDetailsPane(model);
        cards = new(CreateCard, card => card.Dispose());
        DetailsFlyout.Content = details;
        subscriptions = new(model, property => { if (property is nameof(model.SelectedDevice) or nameof(model.Notice)) Render(); },
            model.Activities, ActivitiesChanged, RetireCards);
        ActivityScroll.LayoutUpdated += ActivityScrollLayoutUpdated;
        Render();
    }
    private void ActivitiesChanged(NotifyCollectionChangedEventArgs change)
    {
        autoScroll.Observe(change);
        Render();
    }
    private void ActivityScrollLayoutUpdated(object? sender, object args)
    {
        if (autoScroll.ConsumeAfterLayout())
            ActivityScroll.ChangeView(null, ActivityScroll.ScrollableHeight, null, disableAnimation: false);
    }
    private void Render()
    {
        if (disposed)
            return;
        OnlineStatus.Text = model.SelectedDevice?.StatusText ?? "";
        var hintKey = PresentationState.SessionHintResourceKey(
            model.SelectedDevice is not null, model.CanPair, model.Activities.Count != 0);
        StateHint.Text = hintKey is null ? "" : Strings.Get(hintKey);
        StateHint.Visibility = string.IsNullOrEmpty(StateHint.Text) ? Visibility.Collapsed : Visibility.Visible;
        NoticeBar.Message = model.Notice ?? (model.SelectedDevice is { Online: false } ? Strings.Get("Offline") : "");
        NoticeBar.IsOpen = !string.IsNullOrEmpty(NoticeBar.Message);
        ReconcileActivities();
    }
    private void NoticeClosed(InfoBar sender, InfoBarClosedEventArgs args) => model.Notice = null;
    private void ReconcileActivities()
    {
        if (disposed)
            return;
        SynchronizeActivitySubscriptions();
        var desired = cards.Reconcile(model.Activities, item => string.IsNullOrWhiteSpace(SearchBox.Text) ||
            (item.Name + " " + item.Text).Contains(SearchBox.Text, StringComparison.CurrentCultureIgnoreCase))
            .Select(card => card.View).ToArray();
        for (var index = ActivityHost.Children.Count - 1; index >= 0; index--)
        {
            var child = ActivityHost.Children[index];
            if (!desired.Any(card => ReferenceEquals(card, child)))
                ActivityHost.Children.RemoveAt(index);
        }
        for (var index = 0; index < desired.Length; index++)
        {
            var card = desired[index];
            if (index < ActivityHost.Children.Count && ReferenceEquals(ActivityHost.Children[index], card))
                continue;
            var existingIndex = -1;
            for (var childIndex = 0; childIndex < ActivityHost.Children.Count; childIndex++)
                if (ReferenceEquals(ActivityHost.Children[childIndex], card))
                {
                    existingIndex = childIndex;
                    break;
                }
            if (existingIndex >= 0)
                ActivityHost.Children.RemoveAt(existingIndex);
            ActivityHost.Children.Insert(index, card);
        }
    }
    private RenderedCard CreateCard(ActivityProjection item, ActivityCardKind kind)
    {
        UserControl card = kind switch
        {
            ActivityCardKind.Offer => new IncomingOfferCard(model, item),
            ActivityCardKind.Image => new ImageActivityCard(model, item),
            ActivityCardKind.File => new FileActivityCard(model, item),
            _ => new TextActivityCard(model, item)
        };
        card.HorizontalAlignment = item.Direction == ShareDirection.Outgoing ? HorizontalAlignment.Right : HorizontalAlignment.Left;
        card.MaxWidth = item.Kind == ContentKind.Image ? 400 : 380;
        return new(card);
    }
    private void SynchronizeActivitySubscriptions()
    {
        var active = new HashSet<ActivityProjection>(model.Activities, ReferenceEqualityComparer.Instance);
        foreach (var obsolete in activitySubscriptions.Keys.Where(item => !active.Contains(item)).ToArray())
        {
            obsolete.PropertyChanged -= activitySubscriptions[obsolete];
            activitySubscriptions.Remove(obsolete);
        }
        foreach (var item in active.Where(item => !activitySubscriptions.ContainsKey(item)))
        {
            PropertyChangedEventHandler handler = (_, args) =>
            {
                if (args.PropertyName is nameof(ActivityProjection.Status) or nameof(ActivityProjection.Name) or nameof(ActivityProjection.Text))
                    ReconcileActivities();
            };
            activitySubscriptions.Add(item, handler);
            item.PropertyChanged += handler;
        }
    }
    private void RetireCards()
    {
        foreach (var (item, handler) in activitySubscriptions)
            item.PropertyChanged -= handler;
        activitySubscriptions.Clear();
        cards.Dispose();
        ActivityHost.Children.Clear();
    }
    public void Dispose()
    {
        if (disposed)
            return;
        disposed = true;
        ActivityScroll.LayoutUpdated -= ActivityScrollLayoutUpdated;
        subscriptions.Dispose();
        details.Dispose();
        DetailsFlyout.Content = null;
        DataContext = null;
    }
    private void SearchClick(object sender, RoutedEventArgs e)
    {
        SearchBox.Visibility = SearchBox.Visibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;
        if (SearchBox.Visibility == Visibility.Visible)
            SearchBox.Focus(FocusState.Programmatic);
    }
    private void SearchChanged(object sender, TextChangedEventArgs e)
    {
        if (model is not null)
            ReconcileActivities();
    }
    private void SendAccelerator(KeyboardAccelerator sender, KeyboardAcceleratorInvokedEventArgs args)
    {
        if (model.SendTextCommand.CanExecute(null))
            model.SendTextCommand.Execute(null);
        args.Handled = true;
    }
    private void ComposerDragOver(object sender, DragEventArgs e)
    {
        e.AcceptedOperation = model.CanSend && e.DataView.Contains(Windows.ApplicationModel.DataTransfer.StandardDataFormats.StorageItems) ? Windows.ApplicationModel.DataTransfer.DataPackageOperation.Copy : Windows.ApplicationModel.DataTransfer.DataPackageOperation.None;
        e.Handled = true;
    }
    private async void ComposerDrop(object sender, DragEventArgs e)
    {
        await model.DropOneFileAsync(e.DataView);
        e.Handled = true;
    }

    private sealed class RenderedCard(UserControl view) : IDisposable
    {
        public UserControl View { get; } = view;
        public void Dispose() => ((IDisposable)View).Dispose();
    }
}
