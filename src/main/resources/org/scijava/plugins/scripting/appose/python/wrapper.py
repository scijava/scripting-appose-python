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
# Appose task script that runs a SciJava appose-python script.
#
# The Java side passes the following task inputs, alongside the script's own
# declared inputs (which Appose injects as global variables):
#
#   _appose_script       -- source code of the user script
#   _appose_script_path  -- file name to report in tracebacks
#   _appose_array_inputs -- names of inputs passed as appose.NDArray
#   _appose_outputs      -- names of declared script outputs
#   _appose_end_marker   -- line to write to stderr once the script is done
#
# The worker process is reused across runs, but each run gets a fresh
# namespace. Objects passed to task.export(...) remain available to later
# runs, e.g. to keep a loaded model around:
#
#   if "model" not in globals():
#       model = load_model()
#       task.export(model=model)
#
# Note: The user script is compiled with its real file name, so tracebacks
# point to the correct file and line number.

import ast as _appose_ast
import sys as _appose_sys


def _appose_pack(value):
    """Converts numpy values into something Appose can send back to Java."""
    # Note: Avoid importing numpy unless the value came from numpy anyway.
    if type(value).__module__ != "numpy":
        return value
    import numpy

    if isinstance(value, numpy.ndarray):
        from appose import NDArray

        nd = NDArray(str(value.dtype), list(value.shape))
        nd.ndarray()[...] = value
        return nd
    if isinstance(value, numpy.generic):
        return value.item()
    return value


def _appose_run():
    g = globals()

    # Unwrap each array input from NDArray to numpy array.
    for name in _appose_array_inputs:
        g[name] = g[name].ndarray()

    # Execute the user script. If its last statement is an expression,
    # evaluate it separately to obtain the script's return value.
    block = _appose_ast.parse(_appose_script, _appose_script_path, "exec")
    last = None
    if block.body and isinstance(block.body[-1], _appose_ast.Expr):
        last = _appose_ast.Expression(block.body.pop().value)
    exec(compile(block, _appose_script_path, "exec"), g)  # noqa: S102
    if last is not None:
        result = eval(compile(last, _appose_script_path, "eval"), g)  # noqa: S307
        if result is not None:
            task.outputs["_appose_return_value"] = _appose_pack(result)

    # Capture declared outputs; undefined ones are left unset.
    for name in _appose_outputs:
        if name in g:
            task.outputs[name] = _appose_pack(g[name])


try:
    _appose_run()
finally:
    # Note: The worker's stderr is read separately from its stdout, so this
    # tells the Java side when all of this run's stderr output has arrived.
    _appose_sys.stdout.flush()
    print(_appose_end_marker, file=_appose_sys.stderr, flush=True)
