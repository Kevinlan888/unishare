using System.Collections.Immutable;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using UniShare.Application.Models;
using UniShare.Application.Services;
using UniShare.Application.Tests.Fakes;

namespace UniShare.Application.Tests;

[TestClass]
public sealed class DeviceStoreTests
{
    private static DeviceSnapshot Device(string name = "Phone", bool trusted = true) => new(
        new(Guid.NewGuid()), name, DevicePlatform.Android, 1, 0,
        ImmutableArray.Create("192.0.2.10"), 9999, trusted, DateTimeOffset.Parse("2026-10-05T00:00:00Z"));

    [TestMethod]
    public void OnlineUpdatesAndOfflinePreserveEntryAndSelection()
    {
        var sdk = new FakeShareSdkClient();
        using var store = new DeviceStore(sdk);
        var device = Device();
        sdk.Emit(new ShareSdkEvent.DeviceOnline(device));
        var entry = store.Devices.Single();
        store.SelectedDevice = entry;
        var updated = device with
        {
            Name = "Renamed",
            LastSeen = device.LastSeen.AddMinutes(1)
        };
        sdk.Emit(new ShareSdkEvent.DeviceUpdated(updated));
        sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        Assert.AreSame(entry, store.Devices.Single());
        Assert.AreSame(entry, store.SelectedDevice);
        Assert.AreEqual("Renamed", entry.Name);
        Assert.AreEqual(updated.LastSeen, entry.Snapshot!.LastSeen);
        Assert.IsFalse(entry.IsOnline);
        Assert.IsTrue(entry.IsTrusted);
    }

    [TestMethod]
    public void TrustedDevicesSortBeforeNearbyAndOfflineNearbyReturnsToSameEntry()
    {
        var sdk = new FakeShareSdkClient();
        using var store = new DeviceStore(sdk);
        var nearby = Device("A nearby", false);
        var trusted = Device("Z trusted");
        sdk.Emit(new ShareSdkEvent.DeviceOnline(nearby));
        var entry = store.NearbyDevices.Single();
        sdk.Emit(new ShareSdkEvent.DeviceOnline(trusted));
        CollectionAssert.AreEqual(new[] { trusted.Id, nearby.Id }, store.Devices.Select(value => value.Id).ToArray());
        Assert.AreEqual(trusted.Id, store.TrustedDevices.Single().Id);
        sdk.Emit(new ShareSdkEvent.DeviceOffline(nearby.Id));
        Assert.AreEqual(0, store.NearbyDevices.Count);
        sdk.Emit(new ShareSdkEvent.DeviceOnline(nearby));
        Assert.AreSame(entry, store.NearbyDevices.Single());
    }

    [TestMethod]
    public void RefreshIncludesTrustedDevicesMissingFromDiscoveryAndPreservesIdentityBlock()
    {
        var sdk = new FakeShareSdkClient();
        var online = Device();
        var offline = new DeviceId(Guid.NewGuid());
        sdk.Devices = [online];
        sdk.Trusted = new HashSet<DeviceId> { online.Id, offline };
        using var store = new DeviceStore(sdk);
        store.Refresh();
        Assert.AreEqual(2, store.TrustedDevices.Count);
        Assert.IsFalse(store.TrustedDevices.Single(value => value.Id == offline).IsOnline);
        sdk.Emit(new ShareSdkEvent.IdentityChanged(online.Id));
        store.Refresh();
        Assert.IsTrue(store.Devices.Single(value => value.Id == online.Id).IsBlocked);
    }

    [TestMethod]
    public async Task IdentityChangeRemainsBlockedThroughUpdatesAndPairingUntilForgotten()
    {
        var sdk = new FakeShareSdkClient();
        using var store = new DeviceStore(sdk);
        var device = Device();
        sdk.Emit(new ShareSdkEvent.DeviceOnline(device));
        var entry = store.Devices.Single();
        sdk.Emit(new ShareSdkEvent.IdentityChanged(device.Id));
        sdk.Emit(new ShareSdkEvent.DeviceUpdated(device));
        sdk.Emit(new ShareSdkEvent.PairingCompleted(new(1), device.Id, null));
        Assert.IsTrue(entry.IsBlocked);
        Assert.IsFalse(entry.CanTransfer);
        await store.ForgetDeviceAsync(device.Id);
        Assert.IsFalse(entry.IsBlocked);
        Assert.IsFalse(entry.IsTrusted);
        Assert.AreSame(entry, store.NearbyDevices.Single());
    }

    [TestMethod]
    public async Task FailedForgetAndNewIdentityEventDuringForgetDoNotClearBlock()
    {
        var sdk = new FakeShareSdkClient();
        using var store = new DeviceStore(sdk);
        var device = Device();
        sdk.Emit(new ShareSdkEvent.DeviceOnline(device));
        sdk.Emit(new ShareSdkEvent.IdentityChanged(device.Id));
        var entry = store.Devices.Single();
        sdk.Forgetting = () => Task.FromException(new InvalidOperationException("forget failed"));
        await Assert.ThrowsAsync<InvalidOperationException>(() => store.ForgetDeviceAsync(device.Id));
        Assert.IsTrue(entry.IsBlocked);
        Assert.IsTrue(entry.IsTrusted);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        sdk.Forgetting = () => release.Task;
        var forgetting = store.ForgetDeviceAsync(device.Id);
        sdk.Emit(new ShareSdkEvent.IdentityChanged(device.Id));
        sdk.Emit(new ShareSdkEvent.DeviceUpdated(device));
        release.SetResult();
        await forgetting;
        Assert.IsTrue(entry.IsBlocked);
        Assert.IsFalse(entry.IsTrusted);
    }

    [TestMethod]
    public async Task LateTrustedDiscoveryDoesNotUndoForgetButNewPairingRestoresTrust()
    {
        var sdk = new FakeShareSdkClient();
        using var store = new DeviceStore(sdk);
        var device = Device();
        sdk.Emit(new ShareSdkEvent.DeviceOnline(device));
        var entry = store.Devices.Single();
        await store.ForgetDeviceAsync(device.Id);
        sdk.Emit(new ShareSdkEvent.DeviceUpdated(device));
        Assert.IsFalse(entry.IsTrusted);
        sdk.Trusted = new HashSet<DeviceId> { device.Id };
        sdk.Emit(new ShareSdkEvent.PairingCompleted(new(2), device.Id, null));
        Assert.IsTrue(entry.IsTrusted);
        sdk.Emit(new ShareSdkEvent.ConnectionChanged(device.Id, ConnectionState.Connected, null));
        Assert.IsTrue(entry.IsConnected);
        sdk.Emit(new ShareSdkEvent.DeviceOffline(device.Id));
        Assert.IsFalse(entry.IsConnected);
    }

    [TestMethod]
    public async Task ExposedSnapshotReflectsRevokedTrustAfterForgetAndLateDiscovery()
    {
        var sdk = new FakeShareSdkClient();
        using var store = new DeviceStore(sdk);
        var device = Device();
        sdk.Emit(new ShareSdkEvent.DeviceOnline(device));
        var entry = store.Devices.Single();
        await store.ForgetDeviceAsync(device.Id);
        sdk.Emit(new ShareSdkEvent.DeviceUpdated(device));
        Assert.IsFalse(entry.Snapshot!.IsTrusted);
        Assert.IsFalse(entry.IsTrusted);
    }

    [TestMethod]
    public async Task DelayedPairingCompletionCannotRestoreForgottenTrustWithoutSdkTrust()
    {
        var sdk = new FakeShareSdkClient();
        using var store = new DeviceStore(sdk);
        var device = Device();
        sdk.Trusted = new HashSet<DeviceId> { device.Id };
        sdk.Emit(new ShareSdkEvent.DeviceOnline(device));
        var entry = store.Devices.Single();
        var previousPairing = new ShareSdkEvent.PairingCompleted(new(1), device.Id, null);

        await store.ForgetDeviceAsync(device.Id);
        Assert.IsFalse(sdk.GetTrustedDevices().Contains(device.Id));
        // The SDK persisted the pairing before forget, but its managed event
        // queue delivers that completion only after forget's operation completes.
        sdk.Emit(previousPairing);
        Assert.IsFalse(entry.IsTrusted);
        sdk.Emit(new ShareSdkEvent.DeviceUpdated(device));
        Assert.IsFalse(entry.IsTrusted);
        Assert.IsFalse(entry.Snapshot!.IsTrusted);

        // A new pairing has persisted trust before its completion is delivered.
        sdk.Emit(new ShareSdkEvent.PairingRequested(new(2), device with
        {
            IsTrusted = false
        }, "123456"));
        sdk.Trusted = new HashSet<DeviceId> { device.Id };
        sdk.Emit(new ShareSdkEvent.PairingCompleted(new(2), device.Id, null));
        Assert.IsTrue(entry.IsTrusted);
        Assert.IsTrue(entry.Snapshot!.IsTrusted);
        Assert.AreSame(entry, store.TrustedDevices.Single());
    }
}
