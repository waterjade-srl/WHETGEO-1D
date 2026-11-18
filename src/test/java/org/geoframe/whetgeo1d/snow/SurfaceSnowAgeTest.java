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
package org.geoframe.whetgeo1d.snow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Reference rates computed by hand from GEOtop's formula: (r1 + r2 + 0.3)·1e-6 s-1. */
public class SurfaceSnowAgeTest {

	private static final double DAY = 86400.0;

	@Test
	public void growsFastestAtTheMeltingPoint() {
		// r1 = 1, r2 = 1: 2.3e-6 per second
		assertEquals(2.3e-6 * DAY, SurfaceSnowAge.advance(0.0, 0.0, 273.15, DAY), 1e-12);
	}

	@Test
	public void growsSlowerInColdSnow() {
		// -10 degC: r1 = 0.4988, r2 = 9.6e-4
		assertEquals(0.5 + 7.997232295062976e-07 * DAY, SurfaceSnowAge.advance(0.5, 0.0, 263.15, DAY), 1e-12);
	}

	@Test
	public void snowfallRestoresTheAge() {
		double grown = 2.0 + 2.3e-6 * 3600.0;
		assertEquals(0.0, SurfaceSnowAge.advance(2.0, 10.0, 273.15, 3600.0), 0.0);
		assertEquals(0.5 * grown, SurfaceSnowAge.advance(2.0, 5.0, 273.15, 3600.0), 1e-12);
		assertEquals(0.7 * grown, SurfaceSnowAge.advance(2.0, 3.0, 273.15, 3600.0), 1e-12);
		assertEquals("more than the restoring snowfall never makes it negative", 0.0,
				SurfaceSnowAge.advance(2.0, 15.0, 273.15, 3600.0), 0.0);
	}

	@Test
	public void restoringSnowfallIsAParameter() {
		assertEquals(0.0, SurfaceSnowAge.advance(2.0, 4.0, 273.15, 3600.0, 4.0), 0.0);
	}

	@Test
	public void initialAgeUsesGeotopsConversion() {
		// GEOtop converts AgeSnow0 with 273.16 as reference: at -10 degC the rate differs from the update's
		assertEquals(7.997496096018048e-07 * DAY, SurfaceSnowAge.fromSeconds(DAY, 263.15), 1e-12);
		assertEquals(2.3e-6 * DAY, SurfaceSnowAge.fromSeconds(DAY, 273.15), 1e-6);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonPositiveRestoringSnowfall() {
		SurfaceSnowAge.advance(1.0, 1.0, 270.0, 3600.0, 0.0);
	}
}
