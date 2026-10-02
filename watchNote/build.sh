#!/usr/bin/env bash
set -euo pipefail
python "$(dirname "$(realpath "$0")")/build_apks.py" "$@"
