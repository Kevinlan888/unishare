using System.Runtime.InteropServices.WindowsRuntime;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics.Imaging;
using Windows.Storage;

namespace UniShare.App;

// Only the demo host invokes this service. The artifact is the real loaded
// WinUI client tree, excluding OS window borders/caption buttons and flyouts.
internal static class DemoSnapshotService
{
    public static void WriteFailure(Exception error)
    {
        var args = Environment.GetCommandLineArgs();
        var index = Array.IndexOf(args, "--snapshot");
        if (args.Contains("--demo") && index >= 0 && index + 1 < args.Length)
            File.WriteAllText(args[index + 1] + ".error.txt", error.ToString());
    }
    public static async Task CaptureAsync(FrameworkElement root, string path)
    {
        var nativeDllLoaded = System.Diagnostics.Process.GetCurrentProcess().Modules.Cast<System.Diagnostics.ProcessModule>()
            .Any(module => string.Equals(module.ModuleName, "unishare.dll", StringComparison.OrdinalIgnoreCase));
        if (nativeDllLoaded)
            throw new InvalidOperationException("Demo capture must not load unishare.dll.");
        root.UpdateLayout();
        var bitmap = new RenderTargetBitmap();
        await bitmap.RenderAsync(root);
        var pixels = (await bitmap.GetPixelsAsync()).ToArray();
        if (bitmap.PixelWidth <= 0 || bitmap.PixelHeight <= 0 || pixels.Length == 0)
            throw new InvalidOperationException("WinUI returned no visual-tree pixels.");
        var absolute = Path.GetFullPath(path);
        var folder = await StorageFolder.GetFolderFromPathAsync(Path.GetDirectoryName(absolute)!);
        var file = await folder.CreateFileAsync(Path.GetFileName(absolute), CreationCollisionOption.ReplaceExisting);
        using var stream = await file.OpenAsync(FileAccessMode.ReadWrite);
        var encoder = await BitmapEncoder.CreateAsync(BitmapEncoder.PngEncoderId, stream);
        encoder.SetPixelData(BitmapPixelFormat.Bgra8, BitmapAlphaMode.Premultiplied,
            (uint)bitmap.PixelWidth, (uint)bitmap.PixelHeight, 96 * root.XamlRoot.RasterizationScale,
            96 * root.XamlRoot.RasterizationScale, pixels);
        await encoder.FlushAsync();
        await File.WriteAllTextAsync(absolute + ".txt", $"WinUI RenderTargetBitmap; pixels={bitmap.PixelWidth}x{bitmap.PixelHeight}; logical={root.ActualWidth}x{root.ActualHeight}; rasterizationScale={root.XamlRoot.RasterizationScale}; requestedTheme={root.RequestedTheme}; nativeDllLoaded={nativeDllLoaded}; native caption/borders and popups excluded; command={Environment.CommandLine}");
    }
}
