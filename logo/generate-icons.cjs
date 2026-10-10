// Install the pinned renderer in an ignored build directory:
// npm install --prefix windows/artifacts/icon-tools --no-package-lock --no-save @resvg/resvg-js@2.6.2
// Run from anywhere: node logo/generate-icons.cjs [--check]
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const { Resvg } = require(path.join(root, 'windows/artifacts/icon-tools/node_modules/@resvg/resvg-js'));
const source = fs.readFileSync(path.join(__dirname, 'logo.svg'), 'utf8');
const artwork = source.match(/<svg\b[^>]*>([\s\S]*)<\/svg>/)?.[1];
const viewBox = source.match(/\bviewBox="([^"]+)"/)?.[1];
if (!artwork || !viewBox) throw new Error('Expected a self-contained SVG with a viewBox.');

function png(width, height = width) {
  const size = Math.min(width, height);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">
    <svg x="${(width - size) / 2}" y="${(height - size) / 2}" width="${size}" height="${size}" viewBox="${viewBox}">
      ${artwork}
    </svg>
  </svg>`;
  return new Resvg(svg).render().asPng();
}

function ico(sizes) {
  const images = sizes.map(size => png(size));
  const directory = Buffer.alloc(6 + sizes.length * 16);
  directory.writeUInt16LE(1, 2);
  directory.writeUInt16LE(sizes.length, 4);
  let offset = directory.length;
  for (let index = 0; index < sizes.length; index++) {
    const entry = 6 + index * 16;
    directory[entry] = directory[entry + 1] = sizes[index] === 256 ? 0 : sizes[index];
    directory.writeUInt16LE(1, entry + 4);
    directory.writeUInt16LE(32, entry + 6);
    directory.writeUInt32LE(images[index].length, entry + 8);
    directory.writeUInt32LE(offset, entry + 12);
    offset += images[index].length;
  }
  return Buffer.concat([directory, ...images]);
}

const assets = 'windows/src/UniShare.App/Assets/';
// Android notification icons use alpha only; omit the colored circular background.
const notificationPaths = [...artwork.matchAll(/<path\b[^>]*>/g)]
  .filter(match => /fill="#FFFFFF"/i.test(match[0]))
  .map(match => `    <path android:fillColor="#FFFFFFFF" android:pathData="${match[0].match(/\bd="([^"]+)"/)[1]}" />`);
if (!notificationPaths.length) throw new Error('Expected white foreground paths for the notification icon.');
const notificationVector = `<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="1024" android:viewportHeight="1024">
${notificationPaths.join('\n')}
</vector>
`;
const outputs = new Map([
  ['android/app/src/main/res/drawable-nodpi/unishare_logo.png', png(1024)],
  ['android/app/src/main/res/drawable/ic_notification_logo.xml', Buffer.from(notificationVector)],
  [assets + 'Square44x44Logo.png', png(44)],
  [assets + 'Square150x150Logo.png', png(150)],
  [assets + 'Wide310x150Logo.png', png(310, 150)],
  [assets + 'SplashScreen.png', png(620, 300)],
  [assets + 'StoreLogo.png', png(50)],
  [assets + 'TrayIcon.ico', ico([16, 20, 24, 32, 48, 64])],
  [assets + 'AppIcon.ico', ico([16, 20, 24, 32, 48, 64, 128, 256])],
]);
for (const [relative, bytes] of outputs) {
  const target = path.join(root, relative);
  if (process.argv.includes('--check')) {
    if (!fs.existsSync(target) || !fs.readFileSync(target).equals(bytes))
      throw new Error(`Icon differs from logo.svg: ${relative}`);
  } else {
    fs.writeFileSync(target, bytes);
  }
}
console.log(`${process.argv.includes('--check') ? 'Verified' : 'Generated'} ${outputs.size} icon assets from logo/logo.svg.`);
