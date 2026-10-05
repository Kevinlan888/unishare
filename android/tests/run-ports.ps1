param([string]$Sdk = "$PSScriptRoot/../../build-deps/android-sdk", [string]$NativeBuild = "", [string]$Serial = "")
$ErrorActionPreference='Stop'
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$out=Join-Path $root 'build-android-port-tests'
New-Item -ItemType Directory -Force "$out/classes","$out/dex" | Out-Null
$sdkPath=(Resolve-Path $Sdk).Path
$tools=Join-Path $sdkPath 'build-tools/36.0.0'
$androidJar=Join-Path $sdkPath 'platforms/android-36/android.jar'
function checked { param([string]$exe,[string[]]$arguments) & $exe @arguments; if($LASTEXITCODE -ne 0){throw "$exe failed ($LASTEXITCODE)"} }
$sources=@(Get-ChildItem "$root/android/share-sdk/src/main/java","$root/android/share-sdk/src/androidTest/java" -Recurse -Filter '*.java' | ForEach-Object FullName)
checked 'javac' (@('-Xlint:-options','-encoding','UTF-8','-source','17','-target','17','-classpath',$androidJar,'-d',"$out/classes")+$sources)
checked 'jar' @('cf',"$out/classes.jar",'-C',"$out/classes",'.')
checked "$tools/d8.bat" @('--min-api','26','--lib',$androidJar,'--output',"$out/dex","$out/classes.jar")
checked "$tools/aapt2.exe" @('link','-I',$androidJar,'--manifest',"$PSScriptRoot/AndroidManifest.xml",'-o',"$out/unsigned.apk")
$zip=[IO.Compression.ZipFile]::Open("$out/unsigned.apk",'Update')
try {
 [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$out/dex/classes.dex",'classes.dex') | Out-Null
 if($NativeBuild) { foreach($name in @('unishare_android','unishare_android_probe')){[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,"$NativeBuild/lib$name.so","lib/arm64-v8a/lib$name.so") | Out-Null} }
} finally {$zip.Dispose()}
if(!(Test-Path "$out/test.jks")){checked 'keytool' @('-genkeypair','-keystore',"$out/test.jks",'-storepass','android','-keypass','android','-alias','test','-dname','CN=UniShare port tests','-keyalg','RSA','-validity','30')}
checked "$tools/zipalign.exe" @('-f','-p','4',"$out/unsigned.apk","$out/aligned.apk")
checked "$tools/apksigner.bat" @('sign','--ks',"$out/test.jks",'--ks-pass','pass:android','--out',"$out/test.apk","$out/aligned.apk")
$deviceArgs=@(); if($Serial){$deviceArgs=@('-s',$Serial)}
checked "$sdkPath/platform-tools/adb.exe" ($deviceArgs+@('install','-r',"$out/test.apk"))
$result=& "$sdkPath/platform-tools/adb.exe" @deviceArgs shell am instrument -w -r com.unishare.sdk.porttest/com.unishare.sdk.internal.PortTests
$result | Write-Output
if($LASTEXITCODE -ne 0 -or ($result -join "`n") -notmatch 'INSTRUMENTATION_CODE: -1'){throw 'Android port tests failed'}
