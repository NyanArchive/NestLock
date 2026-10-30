#!/usr/bin/env bash
# Fetches the pinned native payloads and verifies their SHA-256 before use.
#
# Usage:
#   BACKASU_PAYLOAD_URL / GHOSTLOCK_PAYLOAD_URL may point at a directory
#   containing the files, an http(s) base URL, or a file:// URL.
#
#   ./payloads/fetch.sh
#   ./payloads/fetch.sh --check      # verify only
#
# Nothing is committed: the files land in .gitignore'd destinations.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/payloads/manifest.json"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

fail() { printf 'payloads: %s\n' "$*" >&2; exit 1; }

command -v python3 >/dev/null || fail "python3 is required"
command -v sha256sum >/dev/null || fail "sha256sum is required"

read_manifest() {
    python3 - "$MANIFEST" <<'PY'
import json, sys
with open(sys.argv[1]) as handle:
    data = json.load(handle)
for group, entry in data["sources"].items():
    env = entry["url_env"]
    for artifact in entry["artifacts"]:
        print(group, env, artifact["file"], artifact["sha256"], artifact["dest"])
PY
}

fetch_one() {
    local url="$1" dest="$2"
    mkdir -p "$(dirname "$dest")"
    case "$url" in
        file://*|http://*|https://*)
            curl -fL --retry 3 --retry-delay 2 "$url" -o "$dest" ;;
        *)
            [ -f "$url" ] || fail "not a file: $url"
            cp "$url" "$dest" ;;
    esac
}

verify_one() {
    local dest="$1" expected="$2"
    [ -f "$dest" ] || fail "missing: $dest"
    local actual
    actual="$(sha256sum "$dest" | awk '{print $1}')"
    [ "$actual" = "$expected" ] || \
        fail "sha256 mismatch for $dest: expected $expected, got $actual"
    printf 'ok  %s  %s\n' "$actual" "$dest"
}

while read -r group _env file sha dest; do
    case "$group" in
        ghostlock) base_var="GHOSTLOCK_PAYLOAD_URL" ;;
        bakasu)    base_var="BAKASU_PAYLOAD_URL" ;;
        *) fail "unknown payload group: $group" ;;
    esac
    target="$ROOT/$dest"
    if [ "$CHECK_ONLY" -eq 1 ]; then
        verify_one "$target" "$sha"
        continue
    fi
    base="${!base_var:-}"
    [ -n "$base" ] || fail "$base_var is not set"
    url="${base%/}/$file"
    printf 'get %s\n' "$url"
    fetch_one "$url" "$target"
    verify_one "$target" "$sha"
done < <(read_manifest)

echo "payloads ready"
