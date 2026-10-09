/*
 * GNU GPL v3 License
 *
 * Copyright 2026 WHETGEO-1D contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.geoframe.whetgeo1d.core.state;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class SubSteppingTest {

	private static final double EPS = 1e-12;

	@Test
	public void exactDivision() {
		double[] steps = SubStepping.subStepLengths(120.0, 60.0);
		assertArrayEquals(new double[] { 60.0, 60.0 }, steps, EPS);
	}

	@Test
	public void clippedLastStep() {
		double[] steps = SubStepping.subStepLengths(150.0, 60.0);
		assertArrayEquals(new double[] { 60.0, 60.0, 30.0 }, steps, EPS);
	}

	@Test
	public void baseLargerThanInterval() {
		assertArrayEquals(new double[] { 30.0 }, SubStepping.subStepLengths(30.0, 3600.0), EPS);
	}

	@Test
	public void singleStep() {
		assertArrayEquals(new double[] { 60.0 }, SubStepping.subStepLengths(60.0, 60.0), EPS);
	}

	@Test
	public void sumsExactlyToInterval() {
		for (double total : new double[] { 86400.0, 3600.0, 0.5, 12345.678 }) {
			for (double dt : new double[] { 900.0, 60.0, 3600.0, 7.0 }) {
				double sum = 0;
				for (double s : SubStepping.subStepLengths(total, dt)) {
					sum += s;
				}
				assertEquals(total, sum, 1e-9);
			}
		}
	}

	@Test
	public void noZeroLengthTrailingStep() {
		for (double s : SubStepping.subStepLengths(240.0, 60.0)) {
			if (s <= 0) {
				fail("zero-length sub-step produced");
			}
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonPositiveInterval() {
		SubStepping.subStepLengths(0.0, 60.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonPositiveDt() {
		SubStepping.subStepLengths(3600.0, -1.0);
	}

	@Test
	public void floatingPointNoiseDoesNotSpawnExtraStep() {
		double[] steps = SubStepping.subStepLengths(0.1 + 0.2, 0.1);
		assertEquals(3, steps.length, 0);
		double sum = 0;
		for (double s : steps) {
			sum += s;
		}
		assertEquals(0.3, sum, 1e-9);
	}
}
