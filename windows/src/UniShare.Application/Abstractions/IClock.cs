namespace UniShare.Application.Abstractions;

public interface IClock
{
    DateTimeOffset UtcNow { get; }
}
