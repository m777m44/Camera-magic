from pathlib import Path
import zipfile

import yaml

from camera_magic.core import write_json

ORIGINAL_ID = "com.vendor.camera"
MANIFEST = """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.vendor.camera">
  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="34"/>
  <uses-feature android:name="android.hardware.camera.level.full" android:required="true"/>
  <uses-feature android:glEsVersion="0x00030000" android:required="true"/>
  <permission android:name="com.vendor.camera.PRIVATE" android:protectionLevel="signature"/>
  <uses-permission android:name="com.vendor.camera.PRIVATE"/>
  <uses-permission android:name="android.permission.CAMERA"/>
  <application android:name=".App" android:appComponentFactory="androidx.core.app.CoreComponentFactory"
     android:label="@string/app_name" android:icon="@mipmap/old" android:taskAffinity="com.vendor.camera">
    <activity android:name="MainActivity" android:exported="true" android:parentActivityName=".Parent">
      <intent-filter><action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/></intent-filter>
    </activity>
    <activity android:name=".Settings" android:label="@string/settings"/>
    <activity-alias android:name=".Alias" android:targetActivity=".MainActivity" android:exported="false"/>
    <service android:name=".RecordingService" android:foregroundServiceType="camera|microphone" android:process=":record"/>
    <receiver android:name="other.vendor.Receiver" android:process="com.vendor.camera:sync"/>
    <provider android:name=".Files" android:authorities="com.vendor.camera.files;other.authority"
       android:permission="com.vendor.camera.PRIVATE" android:exported="false"/>
    <meta-data android:name="com.vendor.camera.KEY" android:value="com.vendor.camera.NotAClass"/>
  </application>
</manifest>"""


def decoded_fixture(root: Path, version: str = "3.0.3") -> Path:
    root.mkdir(parents=True)
    (root / "AndroidManifest.xml").write_text(MANIFEST, encoding="utf-8")
    data = {
        "version": version,
        "apkFileName": "original.apk",
        "sdkInfo": {"minSdkVersion": "29", "targetSdkVersion": "34"},
        "doNotCompress": ["resources.arsc", "lib/arm64-v8a/libcamera.so"],
        "resourcesInfo": {"packageId": 127},
    }
    if version.startswith("2."):
        data.pop("resourcesInfo")
        data["packageInfo"] = {"forcedPackageId": 127, "renameManifestPackage": None}
    (root / "apktool.yml").write_text(yaml.safe_dump(data), encoding="utf-8")
    for locale in ("values", "values-ar", "values-en"):
        path = root / "res" / locale
        path.mkdir(parents=True)
        (path / "strings.xml").write_text(
            '<resources><string name="app_name">Old</string>'
            '<string name="settings">Settings</string></resources>',
            encoding="utf-8",
        )
    smali = root / "smali/com/vendor/camera"
    smali.mkdir(parents=True)
    (smali / "App.smali").write_text(
        ".class public Lcom/vendor/camera/App;\n"
        'const-string v0, "com.vendor.camera.files"\n'
        "sget-object v0, Landroid/os/Build;->MODEL:Ljava/lang/String;\n",
        encoding="utf-8",
    )
    write_json(
        root / ".camera-magic/source.json",
        {"full_decode": True, "inventory": {"kind": "apk", "sha256": "a" * 64}},
    )
    return root


def tiny_apk(path: Path) -> Path:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"binary fixture, not an installable APK")
        archive.writestr("classes.dex", b"dex\n039\x00fixture")
    return path
