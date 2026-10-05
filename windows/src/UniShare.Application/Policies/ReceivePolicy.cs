using UniShare.Application.Models;

namespace UniShare.Application.Policies;

public static class ReceivePolicy
{
    public static ReceiveDecision Decide(bool isTrusted, ContentKind kind) =>
        isTrusted && kind == ContentKind.Text ? ReceiveDecision.AutoAccept : ReceiveDecision.Prompt;
}
