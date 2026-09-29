[![Build Status](https://github.com/scijava/scripting-appose-python/actions/workflows/build.yml/badge.svg)](https://github.com/scijava/scripting-appose-python/actions/workflows/build.yml)

# Python Scripting with Appose

This library provides a `ScriptLanguage` plugin for the
[SciJava Common](https://github.com/scijava/scijava-common) platform that runs
scripts in [CPython](https://python.org/) (not Jython!), each in its own
Python environment, using [Appose](https://github.com/apposed/appose).

Scripts are ordinary SciJava scripts: they declare
[script parameters](https://imagej.net/scripting/parameters) and can be run
from the Script Editor or menus of ImageJ2/Fiji like any other script.

## Writing a script

```python
#!appose-python
# /// script
# requires-python = ">=3.12"
# dependencies = ["appose", "scipy"]
# ///

#@ Img image
#@ double sigma
#@output Img blurred

from scipy.ndimage import gaussian_filter

print(f"Blurring image of shape {image.shape}")
blurred = gaussian_filter(image, sigma)
```

* The `#!appose-python` line selects this script language, rather than
  another language handling `.py` files, such as Jython.
* The `# /// script` block declares the script's Python environment; see
  below.
* The environment must include the `appose` Python package, plus `numpy` if
  the script uses images.

See the `StarDist_cellcast.py` template for a complete example.

## Environments

A script declares its environment in one of two ways.

**Inline**, with a [PEP 723](https://packaging.python.org/en/latest/specifications/inline-script-metadata/)
metadata block, as above. `requires-python` selects the Python version, and
`dependencies` lists packages from PyPI. The `[tool.pixi.*]` extensions of
[`pixi run --script`](https://pixi.sh/latest/python/scripts/) are supported
too, so conda packages are available, e.g. to get CUDA libraries from
conda-forge:

```python
# /// script
# requires-python = ">=3.12"
# dependencies = ["appose"]
#
# [tool.pixi.dependencies]
# pytorch-gpu = "*"
#
# [tool.pixi.system-requirements]
# cuda = "12"
# ///
```

If `[tool.pixi.workspace]` does not say otherwise, packages come from
conda-forge, for the platform the script runs on. Inline environments are the
way to go for scripts that must stand alone, such as templates.

**In a file**, named by the `env` attribute of the `#@script` directive:

```python
#@script(env="myenv.toml")
```

The file is resolved relative to the script's location. Any format Appose
supports works: `pixi.toml`, `environment.yml`, `requirements.txt` or
`pyproject.toml`. An optional `scheme` attribute (e.g. `scheme="pixi.toml"`)
sets the format explicitly, which is useful when the file name does not reveal
it. Scripts that share a file share an environment.

A script cannot do both.

The environment is built the first time a script needs it, which may take a
while. Afterward, it is reused, both within the running application and across
restarts. Environments are stored in the Appose environments directory
(`~/.local/share/appose` by default), named after the file declaring them: the
environment file, or the script itself for inline environments. Editing the
declaration causes the environment to be updated on the next run.

Builds appear in the application's task list, with their progress, so a
long first build does not look like a hang.

## Python workers

Each environment gets one Python worker process, which stays alive between
script runs. Modules a script imports stay imported, so a second run of a
script that imports e.g. PyTorch starts quickly. Each run still gets a fresh
namespace: variables from one run are not visible to the next.

To keep something expensive, such as a loaded model, across runs, hand it
to `task.export`:

```python
if "model" not in globals():
    model = load_model()
    task.export(model=model)
```

Runs on the same worker execute one at a time; since each environment
currently gets a single worker, so do runs on the same environment, while
runs on different environments proceed in parallel. Each run appears in the
application's task list, where it can be canceled. A script can notice
cancelation by checking `task.cancel_requested`; if it has not stopped a few
seconds after being canceled, its worker process is stopped, and the next
run starts a new one. The worker, and whatever memory (including GPU
memory) it holds, is otherwise released when the application exits.

## Inputs and outputs

* Images (anything convertible to an Appose `NDArray`, such as an ImgLib2
  `Img`) are passed via shared memory and appear in Python as
  NumPy arrays. Axes are in NumPy's usual order, which is the reverse of
  ImgLib2's: an image with X×Y dimensions `512×384` has NumPy shape
  `(384, 512)`.
* Numbers, strings, booleans, lists and maps are passed as the corresponding
  Python values; files are passed as path strings.
* Outputs are read from the Python variables of the same names after the
  script finishes. NumPy arrays are converted back to images; NumPy scalars
  become plain numbers. Outputs the script never assigns are left empty.
* If the script declares no outputs and ends with an expression, its value
  becomes the script's result.

Text printed to stdout and stderr appears in the Script Editor's output
panes. Errors are reported with a Python traceback that refers to the
script's own line numbers.
