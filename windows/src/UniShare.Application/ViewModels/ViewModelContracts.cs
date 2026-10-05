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
    private int busy;
    private string? errorResourceKey;
    protected IViewModelDispatcher Dispatcher { get; } = dispatcher ?? throw new ArgumentNullException(nameof(dispatcher));
    public bool IsBusy => Volatile.Read(ref busy) != 0;
    public string? ErrorResourceKey { get => errorResourceKey; private set => SetProperty(ref errorResourceKey, value); }

    protected IAsyncRelayCommand Command(Func<Task> action, Func<bool> enabled, string errorKey)
    {
        var command = new AsyncRelayCommand(() => RunAsync(action, enabled, errorKey), () => !IsBusy && enabled());
        commands.Add(command); return command;
    }

    protected IAsyncRelayCommand<T> Command<T>(Func<T?, Task> action, Predicate<T?> enabled, string errorKey)
    {
        var command = new AsyncRelayCommand<T>(value => RunAsync(() => action(value), () => enabled(value), errorKey),
            value => !IsBusy && enabled(value));
        commands.Add(command); return command;
    }

    // CanExecute is advisory in ICommand. The atomic gate also protects callers
    // that use ExecuteAsync directly, including repeated/cross-command calls.
    private async Task RunAsync(Func<Task> action, Func<bool> enabled, string errorKey)
    {
        if (!enabled() || Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
        try
        {
            await Dispatcher.InvokeAsync(() => { ErrorResourceKey = null; NotifyCommands(); }).ConfigureAwait(false);
            if (enabled()) await action().ConfigureAwait(false);
        }
        catch (Exception)
        {
            await Dispatcher.InvokeAsync(() => ErrorResourceKey = errorKey).ConfigureAwait(false);
        }
        finally
        {
            Volatile.Write(ref busy, 0);
            await Dispatcher.InvokeAsync(NotifyCommands).ConfigureAwait(false);
        }
    }

    protected void NotifyCommands()
    {
        OnPropertyChanged(nameof(IsBusy));
        foreach (var command in commands) command.NotifyCanExecuteChanged();
    }
}
