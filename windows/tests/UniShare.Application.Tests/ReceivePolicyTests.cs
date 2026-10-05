using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Application.Policies;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class ReceivePolicyTests
{
    [TestMethod]
    public void TrustedTextIsAutoAccepted()
    {
        Assert.AreEqual(ReceiveDecision.AutoAccept, ReceivePolicy.Decide(true, ContentKind.Text));
    }

    [TestMethod]
    public void TrustedImageAndFilePrompt()
    {
        Assert.AreEqual(ReceiveDecision.Prompt, ReceivePolicy.Decide(true, ContentKind.Image));
        Assert.AreEqual(ReceiveDecision.Prompt, ReceivePolicy.Decide(true, ContentKind.File));
    }

    [TestMethod]
    public void UntrustedContentAlwaysPrompts()
    {
        Assert.AreEqual(ReceiveDecision.Prompt, ReceivePolicy.Decide(false, ContentKind.Text));
        Assert.AreEqual(ReceiveDecision.Prompt, ReceivePolicy.Decide(false, ContentKind.Image));
        Assert.AreEqual(ReceiveDecision.Prompt, ReceivePolicy.Decide(false, ContentKind.File));
    }
}
