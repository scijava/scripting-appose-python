/*
 * #%L
 * Python scripting language plugin backed by Appose.
 * %%
 * Copyright (C) 2026 SciJava developers.
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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.File;

import net.imglib2.appose.ShmImg;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.planar.PlanarImg;
import net.imglib2.type.numeric.integer.UnsignedByteType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import org.apposed.appose.NDArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.scijava.Context;
import org.scijava.convert.ConvertService;

/**
 * Tests {@link RAIToNDArrayConverter} and {@link NDArrayToImgConverter}.
 *
 * @author Curtis Rueden
 */
public class ConvertersTest {

	private Context context;
	private ConvertService convertService;

	@Before
	public void setUp() {
		context = new Context();
		convertService = context.service(ConvertService.class);
	}

	@After
	public void tearDown() {
		context.dispose();
	}

	@Test
	public void testRoundTrip() {
		final byte[] data = { 1, 2, 3, 4, 5, 6 };
		final Img<UnsignedByteType> img = ArrayImgs.unsignedBytes(data, 3, 2);
		assertTrue(convertService.supports(img, NDArray.class));

		try (final NDArray nd = convertService.convert(img, NDArray.class)) {
			assertNotNull(nd);
			assertEquals(NDArray.DType.UINT8, nd.dType());
			assertArrayEquals(new int[] { 3, 2 },
				nd.shape().toIntArray(NDArray.Shape.Order.F_ORDER));

			assertTrue(convertService.supports(nd, Img.class));
			final Img<?> result = convertService.convert(nd, Img.class);
			assertTrue(result instanceof PlanarImg);
			assertTrue(result.firstElement() instanceof UnsignedByteType);
			assertArrayEquals(new long[] { 3, 2 }, Intervals.dimensionsAsLongArray(result));
			@SuppressWarnings("unchecked")
			final Img<UnsignedByteType> typed = (Img<UnsignedByteType>) result;
			int i = 0;
			for (final UnsignedByteType t : Views.flatIterable(typed)) {
				assertEquals(data[i++], t.get());
			}
		}
	}

	@Test
	public void testConvertedImgOutlivesNDArray() {
		final NDArray nd = convertService.convert(ArrayImgs.floats(
			new float[] { 1.5f, -2 }, 2), NDArray.class);
		@SuppressWarnings("unchecked")
		final Img<FloatType> result = convertService.convert(nd, Img.class);
		nd.close();
		// The result is a copy, so it must remain usable after closing.
		assertEquals(1.5f, result.firstElement().get(), 0);
	}

	@Test
	public void testShmImgIsNotCopied() {
		final ShmImg<UnsignedByteType> img =
			ShmImg.copyOf(ArrayImgs.unsignedBytes(4, 4));
		try (final NDArray nd = img.ndArray()) {
			assertSame(nd, convertService.convert(img, NDArray.class));
		}
	}

	@Test
	public void testNonImagesAreNotConverted() {
		for (final Object o : new Object[] { "text", 5, 2.5, true,
			new File("x.tif"), new int[] { 1, 2, 3 } })
		{
			assertFalse(o.getClass().getName(),
				convertService.supports(o, NDArray.class));
		}
	}
}
