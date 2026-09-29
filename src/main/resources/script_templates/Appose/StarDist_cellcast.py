#@script (language="appose-python")

# /// script
# requires-python = ">=3.12,<3.13"
# dependencies = ["cellcast>=0.3.0,<0.4", "appose>=0.12.0,<0.13"]
# ///

#@ Img image
#@output Img labels

#@ Double (value=1.0, description="minimum percentile value for normalization") pmin
#@ Double (value=99.8, description="maximum percentile value for normalization") pmax
#@ Double (value=0.479, description="Polygon probability threshold") prob_threshold
#@ Double (value=0.3, description="Non-Maximum Suppression threshold") nms_threshold
#@ Boolean gpu (value=true)

import numpy
import cellcast

def stardist2d(data):
	return cellcast.models.StarDist2D.init_fluo(gpu=gpu).predict_fluo(
	    data=data,
	    pmin=pmin,
	    pmax=pmax,
	    prob_threshold=prob_threshold,
	    nms_threshold=nms_threshold
	).astype("uint16")

dims = len(image.shape)

if dims == 2:
	labels = stardist2d(image)

elif dims == 3:
	label_slices = []
	slice_count = image.shape[0]
	for i in range(slice_count):
		task.update(f"Processing slice {i} of {slice_count}...", current=i, maximum=slice_count)
		slice = image[i,:,:]
		label_slices.append(stardist2d(slice))
	labels = numpy.stack(label_slices)

else:
	raise RuntimeError(f"Expected 2D or 3D image, but got {dims}-dimensional image")
