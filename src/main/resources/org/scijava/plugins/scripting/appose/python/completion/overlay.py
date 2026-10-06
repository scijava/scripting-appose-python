###
# #%L
# Python scripting language plugin backed by Appose.
# %%
# Copyright (C) 2026 SciJava developers.
# %%
# Redistribution and use in source and binary forms, with or without
# modification, are permitted provided that the following conditions are met:
# 
# 1. Redistributions of source code must retain the above copyright notice,
#    this list of conditions and the following disclaimer.
# 2. Redistributions in binary form must reproduce the above copyright notice,
#    this list of conditions and the following disclaimer in the documentation
#    and/or other materials provided with the distribution.
# 
# THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
# AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
# IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
# ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
# LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
# CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
# SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
# INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
# CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
# ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
# POSSIBILITY OF SUCH DAMAGE.
# #L%
###
# Appose task script that installs, for code completion only, the packages a
# script declares but its environment lacks (e.g. ones just added to its
# dependencies), so that Jedi knows them before the environment is rebuilt.
#
# Runs in the completion tooling environment, which includes uv. Installs
# into a separate directory (an "overlay", which Jedi adds to its search
# path), never into the script's environment itself; and only the missing
# packages themselves (--no-deps): their dependencies usually are installed.
#
# Task inputs:
#
#   python       -- the Python executable of the script's environment, or
#                   None if it has not been built (then all dependencies are
#                   missing, installed for the tooling environment's Python)
#   dependencies -- the script's dependencies (PEP 508 requirement strings)
#   overlay      -- the directory to install into
#
# Task outputs:
#
#   path         -- the overlay directory, or None if nothing is missing
#   installed    -- the requirements installed into it

import json as _sj_json
import os as _sj_os
import re as _sj_re
import shutil as _sj_shutil
import subprocess as _sj_subprocess
import sys as _sj_sys

_SJ_INSTALLED = (
    "import importlib.metadata as m, json, re\n"
    "print(json.dumps(sorted({re.sub(r'[-_.]+', '-', (d.metadata['Name'] or '')"
    ".lower()) for d in m.distributions()})))"
)


def _sj_name(requirement):
    """The normalized distribution name of a PEP 508 requirement string."""
    m = _sj_re.match(r"\s*([A-Za-z0-9][A-Za-z0-9._-]*)", requirement)
    return _sj_re.sub(r"[-_.]+", "-", m.group(1).lower()) if m else None


def _sj_overlay():
    target = python or _sj_sys.executable
    installed = set()
    if python:
        result = _sj_subprocess.run(
            [python, "-c", _SJ_INSTALLED],
            capture_output=True,
            text=True,
            timeout=120,
        )
        if result.returncode == 0:
            installed = set(_sj_json.loads(result.stdout))
    missing = [
        r for r in dependencies if _sj_name(r) and _sj_name(r) not in installed
    ]

    task.outputs["installed"] = missing
    if not missing:
        _sj_shutil.rmtree(overlay, ignore_errors=True)
        task.outputs["path"] = None
        return

    marker = _sj_os.path.join(overlay, ".scijava-overlay.json")
    wanted = {"python": target, "requirements": sorted(missing)}
    try:
        with open(marker, encoding="utf-8") as f:
            if _sj_json.load(f) == wanted:
                task.outputs["path"] = overlay  # Already up to date.
                return
    except (OSError, ValueError):
        pass

    from uv import find_uv_bin

    # Install next to the overlay, then swap it in, so that Jedi never sees a
    # half-installed overlay.
    staging = overlay + ".new"
    _sj_shutil.rmtree(staging, ignore_errors=True)
    result = _sj_subprocess.run(
        [find_uv_bin(), "pip", "install", "--target", staging, "--no-deps",
         "--python", target, *missing],
        capture_output=True,
        text=True,
        timeout=600,
    )
    if result.returncode != 0:
        _sj_shutil.rmtree(staging, ignore_errors=True)
        raise RuntimeError("uv pip install failed:\n" + result.stderr)
    with open(_sj_os.path.join(staging, ".scijava-overlay.json"), "w",
              encoding="utf-8") as f:
        _sj_json.dump(wanted, f)
    _sj_shutil.rmtree(overlay, ignore_errors=True)
    _sj_os.replace(staging, overlay)
    task.outputs["path"] = overlay


_sj_overlay()
