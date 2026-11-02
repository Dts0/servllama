#!/usr/bin/env python3
"""Check HTP exports, dependencies and unresolved DSP symbols against the SDK."""
import json
import pathlib
import re
import subprocess
import sys

from hexagon_artifacts import DSP_FILES, STUB

ANDROID_LIBS = {"libcdsprpc.so", "libc.so", "libm.so", "libdl.so", "liblog.so"}
DSP_SYSTEM_LIBS = {"libc.so", "libgcc.so"}
# QuRT/FastRPC provide these APIs at load time, including the POSIX/unwinder
# imports of the SDK's C++ runtime. Do not allow arbitrary C++ or application
# symbols: shared-library linking alone does not catch omissions.
FIRMWARE_SYMBOL = re.compile(
    r"^(?:HAP_|qurt_|dspqueue_|_Unwind_|pthread_)"
    r"|^(?:clock_gettime|nanosleep|__wrap_(?:malloc|calloc|realloc|free|memalign))$"
)


def inspect(path, readelf, require_soname=True):
    output = subprocess.check_output([str(readelf), "--wide", "--dynamic", "--dyn-syms", str(path)], text=True)
    dependencies = set(re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", output))
    sonames = re.findall(r"\(SONAME\).*?\[([^\]]+)\]", output)
    if require_soname and sonames != [path.name]:
        raise ValueError(f"Unexpected SONAME in {path.name}: {sonames}")
    search_paths = re.findall(r"\((?:RPATH|RUNPATH)\).*?\[([^\]]+)\]", output)
    if any(not part.startswith("$ORIGIN") for entry in search_paths for part in entry.split(":")):
        raise ValueError(f"Non-portable runtime search path in {path.name}: {search_paths}")
    defined, undefined, weak = set(), set(), set()
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 8 or not fields[0].rstrip(":").isdigit():
            continue
        name = fields[7].split("@", 1)[0]
        if fields[6] == "UND":
            (weak if fields[4] == "WEAK" else undefined).add(name)
        elif fields[4] in ("GLOBAL", "WEAK"):
            defined.add(name)
    return dependencies, defined, undefined, weak


def unresolved_symbols(required, available):
    firmware = required - available
    unexpected = sorted(name for name in firmware if not FIRMWARE_SYMBOL.match(name))
    if unexpected:
        raise ValueError(f"Unresolved DSP symbols not provided by the SDK/firmware APIs: {', '.join(unexpected)}")
    return sorted(firmware)


def verify(directory, system_lib_dir, readelf):
    stub_deps, stub_exports, _, _ = inspect(directory / STUB, readelf)
    if "libcdsprpc.so" not in stub_deps or stub_deps - ANDROID_LIBS:
        raise ValueError(f"Unexpected Android stub dependencies: {sorted(stub_deps)}")
    required_exports = {"open_dsp_session", "close_dsp_session", "init_htp_backend", "mnn_engine_query_hexagon_arch"}
    if not required_exports <= stub_exports:
        raise ValueError(f"Missing Android stub exports: {sorted(required_exports - stub_exports)}")

    inspected = {name: inspect(directory / name, readelf) for name in DSP_FILES}
    allowed_deps = set(DSP_FILES) | DSP_SYSTEM_LIBS
    for name, (dependencies, _, _, _) in inspected.items():
        if dependencies - allowed_deps:
            raise ValueError(f"Unpackaged DSP dependencies in {name}: {sorted(dependencies - allowed_deps)}")
    skel = inspected["libMNN_htpops_skel.so"]
    if not {"libc++.so.1", "libc++abi.so.1"} <= skel[0]:
        raise ValueError("DSP skeleton is missing its matching C++ runtime dependencies")
    if "htp_ops_skel_handle_invoke" not in skel[1]:
        raise ValueError("DSP skeleton is missing the FastRPC invoke entry point")

    available = set().union(*(item[1] for item in inspected.values()))
    for name in DSP_SYSTEM_LIBS:
        # SDK link-time stubs for firmware libraries do not carry a SONAME.
        available.update(inspect(system_lib_dir / name, readelf, require_soname=False)[1])
    report = {STUB: {"needed": sorted(stub_deps)}}
    for name, (dependencies, _, required, weak) in inspected.items():
        report[name] = {
            "needed": sorted(dependencies),
            "firmwareSymbols": unresolved_symbols(required, available),
            "weakSymbols": sorted(weak - available),
        }
    return report


if __name__ == "__main__":
    try:
        result = verify(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3])
        pathlib.Path(sys.argv[4]).write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
        print("Hexagon exports/dependencies verified; remaining DSP imports are firmware APIs (device verification required).")
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Hexagon link verification failed: {error}") from error
