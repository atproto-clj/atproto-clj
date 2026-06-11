#!/usr/bin/env bash
#
# Refresh the bundled Lexicon schemas from a bluesky-social/atproto checkout.
#
# Copies lexicons/{com/atproto,app/bsky} from the reference checkout into
# resources/lexicons/ and regenerates resources/lexicons/manifest.edn with
# provenance (:source/:commit/:fetched) and a :files list globbed from the
# resulting on-disk tree (so JSON files vendored by other workstreams are
# always enumerated).
#
# Usage: script/update-lexicons.sh REF_CHECKOUT_DIR

set -euo pipefail

ref_dir="${1:?usage: script/update-lexicons.sh REF_CHECKOUT_DIR}"
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
dest="$repo_root/resources/lexicons"

rm -rf "$dest/com/atproto" "$dest/app/bsky"
mkdir -p "$dest/com" "$dest/app"
cp -R "$ref_dir/lexicons/com/atproto" "$dest/com/atproto"
cp -R "$ref_dir/lexicons/app/bsky" "$dest/app/bsky"

commit="$(git -C "$ref_dir" rev-parse HEAD)"
fetched="$(date -u +%F)"

{
  echo "{:source \"https://github.com/bluesky-social/atproto\""
  echo " :commit \"$commit\""
  echo " :fetched \"$fetched\""
  echo " :files"
  echo " ["
  (cd "$dest" && find . -name '*.json' | sed 's|^\./||' | LC_ALL=C sort | sed 's|.*|  "&"|')
  echo " ]}"
} > "$dest/manifest.edn"

echo "Vendored $(cd "$dest" && find . -name '*.json' | wc -l | tr -d ' ') lexicon files at commit $commit"
