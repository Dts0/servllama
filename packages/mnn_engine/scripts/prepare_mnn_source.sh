#!/usr/bin/env bash
set -euo pipefail
plugin_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readarray -t pin < <(python3 - "${plugin_root}/native/source-pin.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))['upstream']
for key in ('repository','tag','commit','tagObject'): print(p[key])
PY
)
if [[ ! -e "${plugin_root}/MNN" ]]; then
    git clone --depth 1 --branch "${pin[1]}" -- "${pin[0]}" "${plugin_root}/MNN"
fi
[[ "$(git -C "${plugin_root}/MNN" rev-parse HEAD)" == "${pin[2]}" ]] || { echo 'Unexpected MNN source commit' >&2; exit 1; }
[[ "$(git -C "${plugin_root}/MNN" rev-parse "refs/tags/${pin[1]}")" == "${pin[3]}" ]] || { echo 'Unexpected MNN tag object' >&2; exit 1; }
[[ -z "$(git -C "${plugin_root}/MNN" status --porcelain)" ]] || { echo 'MNN source must remain unmodified; compatibility edits apply to build copies only.' >&2; exit 1; }
printf 'Verified clean MNN %s source: %s\n' "${pin[1]}" "${pin[2]}"
