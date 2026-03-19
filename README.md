[![Build Status](https://github.com/scijava/scripting-appose-python/actions/workflows/build.yml/badge.svg)](https://github.com/scijava/scripting-appose-python/actions/workflows/build.yml)

# Python Scripting with Appose

This library provides a
[JSR-223-compliant](https://en.wikipedia.org/wiki/Scripting_for_the_Java_Platform)
scripting plugin for the [Python](https://python.org/) language, built on
the [appose](https://github.com/apposed/appose-python) Python package.

It is implemented as a `ScriptLanguage` plugin for the [SciJava
Common](https://github.com/scijava/scijava-common) platform, which means that
in addition to being usable directly as a `javax.script.ScriptEngineFactory`,
it also provides some functionality on top.

For a complete list of scripting languages available as part of the SciJava
platform, see the
[Scripting](https://github.com/scijava/scijava-common/wiki/Scripting) page on
the SciJava Common wiki.

See also:
* [Python Scripting](https://imagej.net/scripting/python) on the ImageJ wiki.

## Example

```python
#@script (language="appose-python", pypi=["cellcast"])

#@ Img image

#@ Double (value=1.0, description="minimum percentile value for normalization") pmin
#@ Double (value=99.8, description="maximum percentile value for normalization") pmax
#@ Double (value=0.479, description="Polygon probability threshold") prob_threshold
#@ Double (value=0.3, description="Non-Maximum Suppression threshold") nms_threshold
#@ Boolean (value=true, description="Set True for GPU inference via WebGPU, False for CPU inference") gpu

#@output Img labels

import cellcast.models.stardist_2d as stardist
labels = stardist.predict_versatile_fluo(
    image,
    pmin,
    pmax,
    prob_threshold,
    nms_threshold,
    gpu,
)
```
