#!appose-python
# /// script
# requires-python = ">=3.14.3,<3.15"
# dependencies = ["cellcast>=0.1.2,<0.2", "appose>=0.12.0,<0.13"]
# ///

#@ Img image
#@output Img labels

#@ Double (value=1.0, description="minimum percentile value for normalization") pmin
#@ Double (value=99.8, description="maximum percentile value for normalization") pmax
#@ Double (value=0.479, description="Polygon probability threshold") prob_threshold
#@ Double (value=0.3, description="Non-Maximum Suppression threshold") nms_threshold
#@ Boolean (value=true, description="Set True for GPU inference via WebGPU, False for CPU inference") gpu

import cellcast.models as ccm

labels = ccm.stardist_2d_versatile_fluo.predict(
    image,
    pmin,
    pmax,
    prob_threshold,
    nms_threshold,
    gpu,
)
