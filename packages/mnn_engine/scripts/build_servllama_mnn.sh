#!/usr/bin/env bash
# Build the vendored wrapper's compatible MNN 3.6.2 runtime, without model downloads.
set -euo pipefail
plugin_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Set ANDROID_HOME to the installed Android SDK}}"
export ANDROID_NDK="${sdk}/ndk/27.0.12077973"
export MNN_CMAKE="${sdk}/cmake/3.22.1/bin/cmake"
export MNN_NINJA="${sdk}/cmake/3.22.1/bin/ninja"
export MNN_BUILD_JOBS="${MNN_BUILD_JOBS:-3}"
[[ "${MNN_BUILD_JOBS}" =~ ^[1-3]$ ]] || { echo 'Use 1 to 3 native build jobs for this workspace.' >&2; exit 1; }
export MNN_HEXAGON=OFF
unset MNN_HEXAGON_ARTIFACTS
bash "${plugin_root}/scripts/prepare_mnn_source.sh"
bash "${plugin_root}/scripts/build_mnn_android.sh" "${plugin_root}"
bash "${plugin_root}/scripts/package_mnn_artifacts.sh" "${plugin_root}"
bash "${plugin_root}/scripts/verify_mnn_artifacts.sh" "${plugin_root}"
python3 - "${plugin_root}/native/android-arm64-v8a.json" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]))
assert m['source']['mnnVersion']=='3.6.2'
assert m['source']['mnnCommit']=='7e3c6fb5225d3ccf22807ee641332669df34650d'
assert m['runtime']['compiledBackends']==['cpu','opencl','vulkan']
assert m['target']['minimumAndroidApi']==28
print('MNN 3.6.2 native runtime ready; device/model execution remains unverified.')
PY
