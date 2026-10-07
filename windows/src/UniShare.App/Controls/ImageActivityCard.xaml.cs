using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using UniShare.App.Presentation;
using UniShare.Application.Models;
namespace UniShare.App.Controls;

public sealed partial class ImageActivityCard : UserControl, IDisposable
{
    private readonly ShellProjection model;
    private readonly ActivityProjection item;
    private readonly PropertyChangedSubscription subscription;
    public ImageActivityCard(ShellProjection model, ActivityProjection item)
    {
        InitializeComponent();
        this.model = model;
        this.item = item;
        DataContext = item;
        VisualStateManager.GoToState(this, item.Direction == ShareDirection.Outgoing ? "Outgoing" : "Incoming", false);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, Strings.Get("Content." + item.Kind) + " " + item.StatusText);
        subscription = new PropertyChangedSubscription(item, Refresh);
        Refresh();
    }
    public void Dispose() => subscription.Dispose();
    private void Refresh()
    {
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(this, Strings.Get("Content." + item.Kind) + " " + item.StatusText);
        StatusLabel.Visibility = item.Status == ActivityStatus.Completed ? Visibility.Collapsed : Visibility.Visible;
        DecisionPanel.Visibility = item.Status is ActivityStatus.AwaitingDecision or ActivityStatus.AwaitingPeer or ActivityStatus.Transferring or ActivityStatus.Failed ? Visibility.Visible : Visibility.Collapsed;
        AcceptButton.Visibility = RejectButton.Visibility = item.Status == ActivityStatus.AwaitingDecision ? Visibility.Visible : Visibility.Collapsed;
        CancelButton.Visibility = item.Status is ActivityStatus.AwaitingPeer or ActivityStatus.Transferring ? Visibility.Visible : Visibility.Collapsed;
        RetryButton.Visibility = item.Direction == ShareDirection.Outgoing && item.Status == ActivityStatus.Failed && model.CanSend ? Visibility.Visible : Visibility.Collapsed;
    }
    private void AcceptClick(object sender, RoutedEventArgs e) => model.Decide(item, ActivityStatus.Transferring);
    private void RejectClick(object sender, RoutedEventArgs e) => model.Decide(item, ActivityStatus.Rejected);
    private void CancelClick(object sender, RoutedEventArgs e) => model.Decide(item, ActivityStatus.Cancelled);
    private void RetryClick(object sender, RoutedEventArgs e) => model.Retry(item);
}
