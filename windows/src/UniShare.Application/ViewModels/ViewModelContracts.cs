using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace UniShare.Application.ViewModels;

// The host must execute both methods on one serialized binding thread, in FIFO
// order. InvokeAsync completes after the action runs. No captured context is used.
public interface IViewModelDispatcher
{
    void Post(Action action);
    Task InvokeAsync(Action action);
}

// The host resolves null to the application's default receive directory and
// tests actual writability (including create/write/delete), rather than existence.
public interface IViewModelFiles
{
    bool FileExists(string path);
    Task OpenAsync(string path, bool location, CancellationToken cancellationToken);
    Task<bool> ValidateReceiveDirectoryAsync(string? path, CancellationToken cancellationToken);
}

public abstract class CommandViewModel(IViewModelDispatcher dispatcher) : ObservableObject
{
    private readonly List<IRelayCommand> commands = [];
    private readonly CommandLane defaultLane = new();
    private readonly List<CommandLane> additionalLanes = [];
    private string? errorResourceKey;
    protected IViewModelDispatcher Dispatcher { get; } = dispatcher ?? throw new ArgumentNullException(nameof(dispatcher));
    public bool IsBusy => defaultLane.IsBusy || additionalLanes.Any(lane => lane.IsBusy);
    public string? ErrorResourceKey
    {
        get => errorResourceKey; private set => SetProperty(ref errorResourceKey, value);
    }

    protected void ClearError() => ErrorResourceKey = null;

    protected sealed class CommandLane
    {
        internal int Busy;
        internal bool IsBusy => Volatile.Read(ref Busy) != 0;
    }

    // A separate lane is only for explicitly safe operations such as cancelling
    // a receive whose acceptance is still awaiting native completion.
    protected CommandLane CreateCommandLane()
    {
        var lane = new CommandLane();
        additionalLanes.Add(lane);
        return lane;
    }

    protected IAsyncRelayCommand Command(Func<Task> action, Func<bool> enabled, string errorKey)
    {
        var command = new AsyncRelayCommand(() => RunAsync(action, enabled, errorKey, defaultLane), () => !defaultLane.IsBusy && enabled());
        commands.Add(command);
        return command;
    }

    protected IAsyncRelayCommand<T> Command<T>(Func<T?, Task> action, Predicate<T?> enabled, string errorKey, CommandLane? lane = null)
    {
        var admissionLane = lane ?? defaultLane;
        var command = new AsyncRelayCommand<T>(value => RunAsync(() => action(value), () => enabled(value), errorKey, admissionLane),
            value => !admissionLane.IsBusy && enabled(value));
        commands.Add(command);
        return command;
    }

    // Capture must return an immutable, thread-safe snapshot at user invocation.
    // Delayed dispatcher admission validates that same input, rather than
    // silently substituting binding state changed by an earlier queued event.
    protected IAsyncRelayCommand SnapshotCommand<T>(Func<T> capture, Func<T, Task> action, Predicate<T> enabled, string errorKey)
    {
        var command = new AsyncRelayCommand(() =>
        {
            var input = capture();
            return RunAsync(() => action(input), () => enabled(input), errorKey, defaultLane);
        }, () => !defaultLane.IsBusy && enabled(capture()));
        commands.Add(command);
        return command;
    }

    // Eligibility, lane admission, and the synchronous start of the operation
    // share one dispatcher action. Async methods capture their binding inputs
    // before their first await; a later event cannot replace those inputs while
    // a dispatch completion/continuation is pending. Await only the resulting
    // operation outside the binding thread. CanExecute itself is a UI query.
    private async Task RunAsync(Func<Task> action, Func<bool> enabled, string errorKey, CommandLane lane)
    {
        var admitted = false;
        Task? operation = null;
        try
        {
            await Dispatcher.InvokeAsync(() =>
            {
                if (!enabled() || Interlocked.CompareExchange(ref lane.Busy, 1, 0) != 0)
                    return;
                admitted = true;
                ErrorResourceKey = null;
                operation = action();
                NotifyCommands();
            }).ConfigureAwait(false);
            if (operation is not null)
                await operation.ConfigureAwait(false);
        }
        catch (Exception)
        {
            await Dispatcher.InvokeAsync(() => ErrorResourceKey = errorKey).ConfigureAwait(false);
        }
        finally
        {
            if (admitted)
                await Dispatcher.InvokeAsync(() => { Volatile.Write(ref lane.Busy, 0); NotifyCommands(); }).ConfigureAwait(false);
        }
    }

    protected void NotifyCommands()
    {
        OnPropertyChanged(nameof(IsBusy));
        foreach (var command in commands)
            command.NotifyCanExecuteChanged();
    }
}
