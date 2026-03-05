/*
 * #%L
 * Python scripting language plugin to be used via scyjava.
 * %%
 * Copyright (C) 2021 - 2025 SciJava developers.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package org.scijava.plugins.scripting.appose.python;

import org.apposed.appose.NDArray;
import org.scijava.convert.Converter;
import org.scijava.plugin.Plugin;

import net.imagej.convert.ConciseConverter;
import net.imglib2.img.Img;
import net.imglib2.img.planar.PlanarImgFactory;
import net.imglib2.appose.ShmImg;
import net.imglib2.type.NativeType;
import net.imglib2.util.ImgUtil;

/**
 * Converts an Appose {@link NDArray} to an ImgLib2 {@link Img}.
 * <p>
 * The {@code NDArray} is first wrapped as a {@link ShmImg}, then copied into a
 * {@link net.imglib2.img.planar.PlanarImg}. The copy step is necessary because
 * {@code ShmImg} is backed by a direct NIO buffer (not a Java primitive array),
 * which is incompatible with ImageJ1's {@code ImageProcessorUtils}.
 * </p>
 *
 * @author Curtis Rueden
 */
@SuppressWarnings("rawtypes")
@Plugin(type = Converter.class)
public class NDArrayToImgConverter extends ConciseConverter<NDArray, Img> {

	public NDArrayToImgConverter() {
		super(NDArray.class, Img.class, NDArrayToImgConverter::doConvert);
	}

	private static <T extends NativeType<T>> Img<T> doConvert(
		final NDArray ndArray)
	{
		final ShmImg<T> shmImg = new ShmImg<>(ndArray);
		final PlanarImgFactory<T> factory =
			new PlanarImgFactory<>(shmImg.getType());
		final Img<T> result = factory.create(shmImg);
		ImgUtil.copy(shmImg, result);
		return result;
	}
}
