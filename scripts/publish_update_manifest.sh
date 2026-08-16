#!/usr/bin/env bash
#
# Write the manifest a device will actually accept, from a signed APK.
#
# The staging lane produces its manifest in CI, where the APK and its signature
# are both present. Production cannot: CI builds it unsigned, because the
# release key is deliberately not there. So the production manifest has to be
# written wherever signing happens -- and every time it was written by hand it
# was written wrong, with `download_url` instead of `update_url` and no digests
# at all, which made the parse throw before any version was compared.
#
# Nothing here is restated by the operator. Version, digest and signer are read
# back off the artifact, so the manifest cannot describe a different build.
#
# Usage:
#   scripts/publish_update_manifest.sh SIGNED.apk https://host/update/update.apk [CHANGELOG]
#
set -euo pipefail

usage() {
    echo "Usage: $0 /path/to/signed.apk https://host/update/update.apk [changelog]" >&2
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
    usage
    exit 64
fi

apk=$1
update_url=$2
changelog=${3:-}

[[ -f $apk ]] || { echo "not a file: $apk" >&2; exit 1; }
[[ $update_url == https://* ]] || { echo "update URL must be https" >&2; exit 64; }

build_tools=${ANDROID_BUILD_TOOLS:-}
if [[ -z $build_tools ]]; then
    build_tools=$(find "${ANDROID_HOME:-/opt/android-sdk}/build-tools" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1 || true)
fi
[[ -x $build_tools/aapt && -x $build_tools/apksigner ]] || {
    echo "set ANDROID_BUILD_TOOLS to a build-tools directory containing aapt and apksigner" >&2
    exit 1
}

badging=$("$build_tools/aapt" dump badging "$apk")
version_code=$(sed -n "s/.*versionCode='\([0-9]\+\)'.*/\1/p" <<<"$badging" | head -1)
version_name=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -1)

[[ $version_code =~ ^[0-9]+$ ]] || { echo "no versionCode in $apk" >&2; exit 1; }
[[ -n $version_name ]] || { echo "no versionName in $apk" >&2; exit 1; }

# An unsigned APK would produce an empty signer digest and a manifest the device
# rejects after downloading, deleting and silently reporting nothing.
signer=$("$build_tools/apksigner" verify --print-certs "$apk" \
    | sed -n 's/.*certificate SHA-256 digest: *\([0-9a-fA-F]\{64\}\).*/\1/p' \
    | head -1 | tr 'A-F' 'a-f')
[[ $signer =~ ^[0-9a-f]{64}$ ]] || { echo "could not read a signer digest; is $apk signed?" >&2; exit 1; }

digest=$(sha256sum "$apk" | cut -d' ' -f1)

jq -n \
    --argjson version_code "$version_code" \
    --arg version_name "$version_name" \
    --arg update_url "$update_url" \
    --arg sha256 "$digest" \
    --arg signer_sha256 "$signer" \
    --arg changelog "$changelog" \
    '{version_code: $version_code, version_name: $version_name, update_url: $update_url,
      sha256: $sha256, signer_sha256: $signer_sha256, changelog: $changelog}'
