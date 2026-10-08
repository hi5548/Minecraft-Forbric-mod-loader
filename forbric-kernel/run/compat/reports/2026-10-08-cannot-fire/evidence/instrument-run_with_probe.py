#!/usr/bin/env python3
"""cannot-fire lane: W7Harness with the mod's own DevAutoTest hook enabled.

`ShootingStarDemoClient.init` reflectively calls `<pkg>.client.DevAutoTest.register()` and swallows only
ClassNotFoundException, so a class at that name shipped OUTSIDE the artifact under test is the mod's own
extension point. The probe jar is appended to the harness's `--libraryPath`, which the kernel composes onto the
game loader's owned classpath (KernelBoot: minecraftLibraries), so the mod's Class.forName finds it.

Everything else — corpus, stage, world, verdict machinery — is sweep_client.py unchanged; this wrapper only
adds the probe jar to the classpath.
"""
import os
import sys
from pathlib import Path

HERE = Path("/Users/charlescai/Desktop/dsh/实验/forbric/w7/harness")
PROBE = Path("/private/tmp/cannotfire/probe/cannotfire-probe.jar")
sys.path.insert(0, str(HERE))

import sweep_client  # noqa: E402

_orig_vanilla_cp = sweep_client.ClientPlan._vanilla_cp


def _vanilla_cp_with_probe(self):
    return _orig_vanilla_cp(self) + os.pathsep + str(PROBE)


sweep_client.ClientPlan._vanilla_cp = _vanilla_cp_with_probe
raise SystemExit(sweep_client.main())
