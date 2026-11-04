# Metro

Windows Phone 8.1 style launcher, live tiles and keyboard for Android (Samsung S25 / One UI).

## Build

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease
```

Release builds are signed with the key in `keystore.properties` / `metro-release.jks`, which are
not in this repository. Keep a backup: OTA updates only install over a build signed with the same key.

## Publish an update

`tools/publish.sh "notes"` builds the release and writes `dist/metro/` (`version.json`, APK,
`index.html` download page). The app polls `version.json` from the URL set in
Settings → aggiornamenti → server aggiornamenti.
