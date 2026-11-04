#!/bin/bash
# Build a signed release and lay out the update folder for the server:
#   dist/metro/version.json   what the app polls
#   dist/metro/Metro-X.Y.apk  the update
#   dist/metro/index.html     download page for a first install from the phone's browser
#
# Usage: tools/publish.sh "what changed"
# Optional deploy over SSH (key auth): METRO_DEPLOY=m@192.168.1.18:/var/www/html/metro/ tools/publish.sh "..."
# Optional GitHub release + Pages page:  METRO_GITHUB=mcaruga/metro-ui-download tools/publish.sh "..."
#   (the app's default update URL is that repo's releases/latest/download/)
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f keystore.properties ] || { echo "keystore.properties missing: updates must be signed with the release key" >&2; exit 1; }
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

notes="${1:-}"
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
apk="Metro-$name.apk"

./gradlew -q assembleRelease

out=dist/metro
rm -rf "$out"
mkdir -p "$out"
cp app/build/outputs/apk/release/app-release.apk "$out/$apk"
sha=$(shasum -a 256 "$out/$apk" | cut -d' ' -f1)
size=$(du -k "$out/$apk" | cut -f1)

apkurl="$apk"
[ -n "${METRO_GITHUB:-}" ] && apkurl="https://github.com/$METRO_GITHUB/releases/latest/download/$apk"

python3 - "$out" "$code" "$name" "$apk" "$sha" "$notes" "$size" "$apkurl" <<'PY'
import html, json, sys
out, code, name, apk, sha, notes, size, apkurl = sys.argv[1:]
json.dump({"versionCode": int(code), "versionName": name, "apk": apk, "sha256": sha, "notes": notes},
          open(f"{out}/version.json", "w"), ensure_ascii=False, indent=2)
page = open("tools/download.html", encoding="utf-8").read()
for k, v in {"NAME": name, "APK": apk, "APKURL": apkurl, "SHA": sha, "SIZE": str(round(int(size) / 1024, 1)),
             "NOTES": html.escape(notes) or "&nbsp;"}.items():
    page = page.replace("{{" + k + "}}", v)
open(f"{out}/index.html", "w", encoding="utf-8").write(page)
PY

echo "Metro $name (code $code) -> $out  sha256 $sha"
if [ -n "${METRO_DEPLOY:-}" ]; then
    rsync -av --delete "$out/" "$METRO_DEPLOY"
fi
if [ -n "${METRO_GITHUB:-}" ]; then
    # Download page on GitHub Pages (main branch root).
    path=index.html
    prev=$(gh api "repos/$METRO_GITHUB/contents/$path" --jq '.sha // empty' 2>/dev/null | grep -E '^[0-9a-f]{40}$' || true)
    gh api -X PUT "repos/$METRO_GITHUB/contents/$path" -f message="Metro $name" \
        -f content="$(base64 < "$out/index.html" | tr -d '\n')" ${prev:+-f sha="$prev"} --silent
    # After the page commit: a release needs a commit to tag.
    gh release create "v$name" "$out/$apk" "$out/version.json" --repo "$METRO_GITHUB" \
        --title "Metro $name" --notes "${notes:-Metro $name}" --latest
    echo "released: https://github.com/$METRO_GITHUB/releases/tag/v$name"
fi
