#!/usr/bin/env python3
"""Build and exercise only the CPU proof with JDK 21; no Minecraft/Gradle downloads."""
import os
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile

import export_world
from test_export_world import region_grid

root = Path(__file__).resolve().parents[2]
classes = root / "build/lod-proof/classes"
classes.mkdir(parents=True, exist_ok=True)
jdk = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
javac = str(jdk / "javac") if jdk else shutil.which("javac")
java = str(jdk / "java") if jdk else shutil.which("java")
if not javac or not java:
    sys.exit("Install/select JDK 21 (JAVA_HOME); a runtime-only JRE is insufficient.")
sources = sorted((root / "src/main/java/me/cortex/voxy/client/core/rendering/lod/bake").glob("*.java"))
sources += sorted((root / "tools/lod").glob("*.java"))
sources += [root / "src/main/java/me/cortex/voxy/client/core/rendering/lod/runtime" / name for name in ("LodWorkQueue.java", "MeshLevelPolicy.java")]
sources += [root / "src/main/java/me/cortex/voxy/client/core/util/ScanMesher2D.java"]
sources += [root / "src/main/java/me/cortex/voxy/client/core/rendering/hybrid/HybridLodPolicy.java"]
sources += [root / "src/main/java/me/cortex/voxy/client/core/rendering/hybrid/CoveragePrefix.java"]
sources += [root / "src/main/java/me/cortex/voxy/client/core/rendering/hybrid/HybridGpuLayout.java"]
subprocess.run([javac, "--release", "21", "-Xlint:all", "-d", str(classes), *map(str, sources)], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "LodBakeCheck", str(root / "build/lod-proof/check")], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "LodComparisonCheck", str(root / "build/lod-proof/comparison")], check=True)
subprocess.run([java, "-ea", "-Xmx256m", "-cp", str(classes), "DirectGeometryCheck"], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "ProgressiveRuntimeCheck"], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "ProgressiveFileCheck"], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "LinkedSeamCheck"], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "HybridLodPolicyCheck"], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "CoveragePrefixCheck"], check=True)
subprocess.run([java, "-Xmx256m", "-cp", str(classes), "HybridGpuLayoutCheck"], check=True)
subprocess.run([java, "-cp", str(classes), "SurfaceDomainsCheck"], check=True)
subprocess.run([java, "-cp", str(classes), "MeshLevelPolicyCheck"], check=True)
subprocess.run([java, "-cp", str(classes), "MeshGpuLayoutCheck"], check=True)
subprocess.run([java, "-cp", str(classes), "PackedQuadGpuCheck"], check=True)
subprocess.run([sys.executable, "-m", "unittest", "discover", "-s", str(root / "tools/lod"), "-p", "test_*.py"], check=True)

with tempfile.TemporaryDirectory(prefix="lod-anvil-check-") as temporary:
    work = Path(temporary)
    region_grid(work)
    snapshot = work / "source.fvvol.gz"
    export_world.export(work, snapshot, (0, 0, 0), (46, 24, 46), [(0, 0, 0, 46, 24, 46)])
    command = [java, "-Xmx256m", "-cp", str(classes), "BakeProofMain", str(snapshot)]
    for name in ("first", "replay"):
        subprocess.run([*command, str(work / name)], check=True)
    first = {p.name: p.read_bytes() for p in (work / "first").iterdir()}
    replay = {p.name: p.read_bytes() for p in (work / "replay").iterdir()}
    assert first == replay, "offline package publication must be deterministic"
    manifest = json.loads(first["manifest.json"])
    assert len(manifest["pages"]) == 3
    assert manifest["algorithm"] == "merged-boundary-edge-collapse-v1"
    assert manifest["palette_sha256"] == hashlib.sha256(first["palette.json"]).hexdigest()
    for page in manifest["pages"]:
        payload = first[page["file"]]
        assert page["bytes"] == len(payload)
        assert page["payload_bytes"] <= page["packed_payload_bytes"] <= page["voxy_quad_payload_bytes"]
        assert page["triangles"] <= page["packed_triangles"]
        if page["representation"] == "mesh":
            assert page["candidate_error"]["within_limit"]
            assert page["candidate_error"]["upper_bound_blocks"] <= page["error_limit_blocks"]
        assert page["triangles"] <= page["voxy_triangles"]
        if page["representation"] == "quads":
            assert len(payload) == 24 + page["payload_bytes"]
        else:
            assert len(payload) == 52 + page["payload_bytes"]
        assert page["sha256"] == hashlib.sha256(payload).hexdigest()
    assert manifest["prepared_unique_pages_bytes"] == sum(len(first[name]) for name in {p["file"] for p in manifest["pages"]})
    refused = subprocess.run([*command, str(work / "first")], capture_output=True, text=True)
    assert refused.returncode != 0 and "Refusing to overwrite" in refused.stderr
    unknown = work / "unknown.fvvol.gz"
    export_world.export(work, unknown, (-1, 0, -1), (46, 24, 46), [])
    refused = subprocess.run([*command[:-1], str(unknown), str(work / "incomplete")], capture_output=True, text=True)
    assert refused.returncode != 0 and "Incomplete source" in refused.stderr
    assert not (work / "incomplete").exists()
    # The Anvil fixture chooses packed quads; also exercise publication of an accepted directly simplified page.
    smooth = work / "smooth"
    subprocess.run([*command[:-1], str(root / "build/lod-proof/comparison/hills.fvvol.gz"), str(smooth)], check=True)
    smooth_replay = work / "direct-replay"
    subprocess.run([*command[:-1], str(root / "build/lod-proof/comparison/hills.fvvol.gz"), str(smooth_replay)], check=True)
    assert {p.name: p.read_bytes() for p in smooth.iterdir()} == {p.name: p.read_bytes() for p in smooth_replay.iterdir()}, "direct mesh publication must be byte-identical too"
    smooth_manifest = json.loads((smooth / "manifest.json").read_text())
    far = smooth_manifest["pages"][2]
    assert far["representation"] == "mesh" and far["candidate_error"]["within_limit"]
    encoded = (smooth / far["file"]).read_bytes()
    assert encoded[:8] == b"FVMSH003" and hashlib.sha256(encoded).hexdigest() == far["sha256"]
    vc, tc, scale, ac = struct.unpack_from(">4i", encoded, 20)
    coordinate_bits = sum(encoded[48:51])
    vertex_bytes = (coordinate_bits + 16 + 7) // 8
    triangle_bytes = (max(1, (vc - 1).bit_length()) * 3 + max(1, (ac - 1).bit_length()) + 7) // 8
    assert scale == 256 and len(encoded) == 52 + vc * vertex_bytes + ac * 4 + tc * triangle_bytes
    assert far["payload_bytes"] == len(encoded) - 52 <= far["packed_payload_bytes"] <= far["voxy_quad_payload_bytes"]
    assert far["triangles"] == tc <= far["packed_triangles"] <= far["voxy_triangles"]
    assert far["candidate_error"]["upper_bound_blocks"] <= far["error_limit_blocks"]
    assert smooth_manifest["prepared_unique_pages_bytes"] == sum((smooth / name).stat().st_size for name in {p["file"] for p in smooth_manifest["pages"]})
    print("Anvil -> snapshot -> Java baker -> checksummed pages/replay/refusal: passed")
