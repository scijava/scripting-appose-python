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
# Appose task script that asks Jedi for code completions in a script, or for
# the documentation of one of them.
#
# Runs in the completion tooling environment (Python + Jedi), and analyzes
# the script against its own environment's interpreter, if given, so that
# the packages installed there are known.
#
# Task inputs:
#
#   op             -- "complete" (the default), "describe" or "signatures"
#   key            -- identifies the query: "describe" looks up the
#                     completions of a "complete" with the same key
#   source         -- the script's source code (with a preamble typing its
#                     SciJava parameters)
#   line, column   -- the caret position (line 1-based, column 0-based)
#   path           -- the script's file path, or None if unsaved
#   python         -- the Python executable of the script's environment, or
#                     None to use Jedi's own interpreter
#   max_signatures -- for "complete": how many completions to give
#                     signatures (they are expensive: give few, or none)
#   max_overloads  -- for "complete": how many signatures to give each
#   name           -- for "describe": which completion to describe
#   overlay        -- a directory of packages the environment lacks, which
#                     Jedi should search too; or None
#
# Task outputs:
#
#   completions -- for "complete": list of dicts: name, type, description,
#                  and signatures: a list of dicts with params (list of
#                  [name, annotation or None]) and returns (or None)
#   docstring   -- for "describe": the completion's documentation, or None
#   signatures  -- for "signatures": the signatures of the call around the
#                  caret: list of dicts with name, params and returns
#   index       -- for "signatures": index of the argument the caret is in,
#                  or None if Jedi cannot tell

import os as _sj_os
import sys as _sj_sys


def _sj_cache():
    # Note: Each task gets fresh globals, so keep state that is slow to
    # recompute in a module that persists in the worker: Jedi's environments,
    # and the latest completions (to describe them on request).
    cache = _sj_sys.modules.get("_scijava_jedi_cache")
    if cache is None:
        import types

        cache = types.ModuleType("_scijava_jedi_cache")
        cache.environments = {}
        cache.completions = {}  # key -> {name: jedi Completion}
        _sj_sys.modules["_scijava_jedi_cache"] = cache
    return cache


def _sj_script(cache):
    import jedi

    environment = None
    if python:
        environment = cache.environments.get(python)
        if environment is None:
            try:
                environment = jedi.create_environment(python, safe=False)
            except Exception:  # noqa: BLE001 -- e.g. a Python Jedi cannot use
                environment = jedi.InterpreterEnvironment()
            cache.environments[python] = environment
    else:
        environment = jedi.InterpreterEnvironment()

    extra = [overlay] if globals().get("overlay") else []
    project = None
    if path or extra:
        root = _sj_os.path.dirname(_sj_os.path.abspath(path)) if path else None
        project = jedi.Project(root or _sj_os.getcwd(), added_sys_path=extra)
    return jedi.Script(
        source, path=path, environment=environment, project=project
    )


def _sj_completions(cache):
    completions = _sj_script(cache).complete(line, column)
    # Remember them, to describe them on request; only the latest few.
    cache.completions[key] = {c.name: c for c in completions}
    while len(cache.completions) > 8:
        cache.completions.pop(next(iter(cache.completions)))
    return completions


def _sj_signature(sig):
    params = []
    for p in sig.params:
        text = p.to_string()
        annotation = None
        if ":" in text:
            annotation = text.split(":", 1)[1].split("=", 1)[0].strip()
        params.append([p.name, annotation])
    sig_text = sig.to_string()
    returns = sig_text.rsplit("->", 1)[1].strip() if "->" in sig_text else None
    return {"name": sig.name, "params": params, "returns": returns}


def _sj_signatures(c):
    return [_sj_signature(sig) for sig in c.get_signatures()[:max_overloads]]


def _sj_main():
    cache = _sj_cache()
    if globals().get("op") == "signatures":
        signatures = _sj_script(cache).get_signatures(line, column)
        task.outputs["index"] = signatures[0].index if signatures else None
        task.outputs["signatures"] = [
            _sj_signature(s) for s in signatures[:max_overloads]
        ]
        return
    if globals().get("op") == "describe":
        known = cache.completions.get(key)
        if known is None:
            # Note: E.g. the worker restarted since; ask Jedi again.
            known = {c.name: c for c in _sj_completions(cache)}
        c = known.get(name)
        task.outputs["docstring"] = (c.docstring(raw=True) or None) if c else None
        return

    results = []
    for i, c in enumerate(_sj_completions(cache)):
        signatures = []
        if i < max_signatures and c.type in ("function", "class"):
            try:
                signatures = _sj_signatures(c)
            except Exception:  # noqa: BLE001,S110 -- best effort
                pass
        results.append(
            {
                "name": c.name,
                "type": c.type,
                "description": c.description,
                "signatures": signatures,
            }
        )
    task.outputs["completions"] = results


_sj_main()
