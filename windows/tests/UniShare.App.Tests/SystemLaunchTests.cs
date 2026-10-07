using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.App.Services;

namespace UniShare.App.Tests;

[TestClass]
public sealed class SystemLaunchTests
{
    [TestMethod]
    public void FailedWindowsLaunchBecomesAnObservableIoFailure()
    {
        SystemLaunch.EnsureSucceeded(true);
        Assert.ThrowsExactly<IOException>(() => SystemLaunch.EnsureSucceeded(false));
    }
}
