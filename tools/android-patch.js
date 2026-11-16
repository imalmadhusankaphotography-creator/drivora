// Run after `npx cap add android`. Adds the native OBD link, permissions and a fixed signing key.
// Safe to run more than once.
var fs=require('fs'),path=require('path');
var root=path.join(__dirname,'..'),app=path.join(root,'android/app');
var pkgDir=path.join(app,'src/main/java/com/imal/drivora');
function must(c,m){if(!c){console.error('PATCH FAILED: '+m);process.exit(1);}}
must(fs.existsSync(pkgDir),'android project not found - run "npx cap add android" first');

// 1. native sources
['MainActivity.java','ObdLinkPlugin.java'].forEach(function(f){fs.copyFileSync(path.join(root,'native',f),path.join(pkgDir,f));});

// 2. manifest permissions
var mf=path.join(app,'src/main/AndroidManifest.xml'),m=fs.readFileSync(mf,'utf8');
if(m.indexOf('ACCESS_FINE_LOCATION')<0){
  var perms=[
    '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />',
    '    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />',
    '    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />',
    '    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />',
    '    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />',
    '    <uses-feature android:name="android.hardware.location.gps" android:required="false" />',
    '    <uses-feature android:name="android.hardware.bluetooth" android:required="false" />',
    '    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />',
    ''].join('\n');
  must(m.indexOf('</manifest>')>0,'manifest end tag not found');
  m=m.replace('</manifest>',perms+'</manifest>');
  fs.writeFileSync(mf,m);
}

// 3. same signing key on every build, so a new APK installs over the old one and keeps its data
var gf=path.join(app,'build.gradle'),g=fs.readFileSync(gf,'utf8');
if(g.indexOf('drivora-debug.keystore')<0){
  must(g.indexOf('    buildTypes {')>0,'buildTypes block not found in app/build.gradle');
  g=g.replace('    buildTypes {',
    "    signingConfigs {\n        debug {\n            storeFile file('../../native/drivora-debug.keystore')\n            storePassword 'android'\n            keyAlias 'androiddebugkey'\n            keyPassword 'android'\n        }\n    }\n    buildTypes {");
  var ver=require(path.join(root,'package.json')).version,parts=ver.split('.');
  g=g.replace(/versionCode \d+/,'versionCode '+(+parts[0]*10000+ +parts[1]*100+ +parts[2])).replace(/versionName "[^"]*"/,'versionName "'+ver+'"');
  fs.writeFileSync(gf,g);
}
console.log('android project patched');
