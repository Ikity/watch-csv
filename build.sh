#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(dirname "$(realpath "$0")")"
exec python "$ROOT/build_apks.py" "$@"
