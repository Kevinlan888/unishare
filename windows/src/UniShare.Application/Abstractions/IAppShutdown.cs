namespace UniShare.Application.Abstractions;

public interface IAppShutdown
{
    void BlockNewWork();
    Task RetireAsync();
}
