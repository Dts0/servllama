#!/usr/bin/env bash
set -euo pipefail

plugin_root="$(realpath "${1:?Plugin root is required}")"
dsp_arch="${2:-all}"
image_ref="${MNN_HEXAGON_DOCKER_IMAGE:-$(cat "${plugin_root}/scripts/hexagon/toolchain-image.txt")}"
docker_bin="${MNN_DOCKER:-docker}"
case "${dsp_arch}" in
    all|v73|v75|v79|v81) ;;
    *) printf 'Supported DSP targets: all, v73, v75, v79, v81.\n' >&2; exit 61 ;;
esac
"${docker_bin}" info >/dev/null
if ! "${docker_bin}" image inspect "${image_ref}" >/dev/null 2>&1; then
    "${docker_bin}" pull --platform linux/amd64 "${image_ref}"
fi

output="${plugin_root}/.native/hexagon"
mkdir -p "${output}"
owner="$(stat -c '%u:%g' "${plugin_root}")"
if [[ "$(id -u)" == 0 ]]; then
    chown "${owner}" "${output}"
fi
mounts=(--mount "type=bind,source=${plugin_root},target=/workspace,readonly"
        --mount "type=bind,source=${output},target=/workspace/.native/hexagon")
options=(--env MNN_HEXAGON_WORK_ROOT=/tmp/mnn-hexagon-build
         --env "MNN_BUILD_JOBS=${MNN_BUILD_JOBS:-4}"
         --env "MNN_HEXAGON_BUILD_IMAGE=${image_ref}"
         --env "MNN_HEXAGON_BUILD_IMAGE_ID=$("${docker_bin}" image inspect --format '{{.Id}}' "${image_ref}")")
# Reuse the main build's pinned NDK when supplied; otherwise use the image's NDK.
if [[ -n "${ANDROID_NDK:-}" ]]; then
    mounts+=(--mount "type=bind,source=$(realpath "${ANDROID_NDK}"),target=/mnn-android-ndk,readonly")
    options+=(--env ANDROID_NDK=/mnn-android-ndk)
fi
printf 'Building MNN Hexagon %s using %s\n' "${dsp_arch}" "${image_ref}"
"${docker_bin}" run --rm --platform linux/amd64 --network none --user "${owner}" \
    "${mounts[@]}" "${options[@]}" --entrypoint /bin/bash "${image_ref}" \
    /workspace/scripts/build_hexagon_android.sh /workspace "${dsp_arch}"
