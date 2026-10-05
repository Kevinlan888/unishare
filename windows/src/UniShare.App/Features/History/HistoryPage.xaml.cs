using Microsoft.UI.Xaml.Controls;
namespace UniShare.App.Features.History;
public sealed partial class HistoryPage : UserControl
{
 private readonly ShellProjection model;
 public HistoryPage(ShellProjection model) { InitializeComponent(); this.model=model; Results.ItemsSource=model.Activities; }
 private void QueryChanged(object sender, TextChangedEventArgs e) { if(model is not null) Results.ItemsSource=model.Activities.Where(a=>(a.Name+" "+a.Text).Contains(Query.Text,StringComparison.CurrentCultureIgnoreCase)).ToArray(); }
}
