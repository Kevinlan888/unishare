using CommunityToolkit.Mvvm.Input;
using UniShare.Application.Abstractions;
using UniShare.Application.Models;

namespace UniShare.Application.ViewModels;

public sealed class HistoryViewModel : CommandViewModel
{
    private string query = string.Empty;
    private ActivityStatus? statusFilter;
    private ShareDirection? directionFilter;
    private IReadOnlyList<ActivityItemViewModel> activities = [];

    public HistoryViewModel(IActivityRepository repository, IViewModelFiles files, IViewModelDispatcher dispatcher) : base(dispatcher)
    {
        RefreshCommand = Command(async () =>
        {
            var filter = new ActivityQuery(string.IsNullOrWhiteSpace(Query) ? null : Query, null, StatusFilter, DirectionFilter);
            var results = await repository.SearchAsync(filter, CancellationToken.None).ConfigureAwait(false);
            await Dispatcher.InvokeAsync(() => Activities = results.Select(record => new ActivityItemViewModel(record, files, Dispatcher)).ToArray()).ConfigureAwait(false);
        }, () => true, "History.LoadFailed");
    }
    public string Query { get => query; set => SetProperty(ref query, value); }
    public ActivityStatus? StatusFilter { get => statusFilter; set => SetProperty(ref statusFilter, value); }
    public ShareDirection? DirectionFilter { get => directionFilter; set => SetProperty(ref directionFilter, value); }
    public IReadOnlyList<ActivityItemViewModel> Activities { get => activities; private set => SetProperty(ref activities, value); }
    public IAsyncRelayCommand RefreshCommand { get; }
}
