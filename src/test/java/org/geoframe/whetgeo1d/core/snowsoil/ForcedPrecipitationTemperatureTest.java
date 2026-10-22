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
package org.geoframe.whetgeo1d.core.snowsoil;

import static org.junit.Assert.assertEquals;

import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.junit.Test;

/** SNOW-5: a precipitation temperature from the forcing replaces the wet bulb, in recommended only. */
public class ForcedPrecipitationTemperatureTest {

	private static final double T0 = 273.15;
	private static final SurfaceForcing AIR = SurfaceForcing.withoutBeam(50.0, 280.0, T0 + 4.0, 0.6, 2.0, 85000.0);

	@Test
	public void forcedTemperatureReplacesTheWetBulbWithItsLimits() {
		PrecipitationTemperature r = PrecipitationTemperature.WET_BULB;
		assertEquals(T0 + 1.5, r.rain(AIR, T0 - 2.0, T0 + 1.5), 0.0);
		assertEquals(T0, r.rain(AIR, T0 - 2.0, T0 - 1.0), 0.0);
		assertEquals(T0 - 1.0, r.snow(AIR, T0 - 1.0), 0.0);
		assertEquals(T0, r.snow(AIR, T0 + 1.0), 0.0);
		assertEquals(r.rain(AIR, T0 - 2.0), r.rain(AIR, T0 - 2.0, Double.NaN), 0.0);
		assertEquals(r.snow(AIR), r.snow(AIR, Double.NaN), 0.0);
	}

	@Test
	public void fidelityIgnoresIt() {
		PrecipitationTemperature g = PrecipitationTemperature.GEOTOP;
		assertEquals(T0 - 2.0, g.rain(AIR, T0 - 2.0, T0 + 1.5), 0.0);
		assertEquals(g.snow(AIR), g.snow(AIR, T0 - 5.0), 0.0);
	}
}
