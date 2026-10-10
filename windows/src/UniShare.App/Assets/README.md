# Packaging assets

Square44x44Logo.png, Square150x150Logo.png, Wide310x150Logo.png,
SplashScreen.png and StoreLogo.png are derived from the UniShare artwork at
`../../../../logo/logo.svg`. Square assets use the artwork directly; wide and
splash assets center the same artwork without stretching it horizontally.
TrayIcon.ico contains 16, 20, 24, 32, 48 and 64 pixel variants of the same
artwork for the Windows notification area.
AppIcon.ico adds 128 and 256 pixel variants for the executable, window and taskbar.

Regenerate Windows assets and the Android launcher artwork from the repository root:

```powershell
npm install --prefix windows/artifacts/icon-tools --no-package-lock --no-save @resvg/resvg-js@2.6.2
node logo/generate-icons.cjs
node logo/generate-icons.cjs --check
```

The generator preserves transparency and centers the SVG on wide assets without stretching.
Android notification artwork uses the SVG's white foreground paths without the circular background,
because the system renders small notification icons using their alpha channel only.

DemoMountainLake.png is the supplied generated raster reference for the demo
image activity and recent file thumbnail.
