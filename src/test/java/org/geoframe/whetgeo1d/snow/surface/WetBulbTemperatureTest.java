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
package org.geoframe.whetgeo1d.snow.surface;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wet-bulb temperature from the psychrometric balance. */
public class WetBulbTemperatureTest {

	private static final double P = 101325.0;

	@Test
	public void saturatedAirIsAtItsWetBulb() {
		double ta = MELTING_TEMPERATURE + 3.0;
		assertEquals(ta, WetBulbTemperature.of(ta, 1.0, P), 1e-9);
	}

	/** Stull (2011): 20 °C and 50 % at sea level give 13.7 °C. */
	@Test
	public void matchesStullAtTwentyDegrees() {
		assertEquals(MELTING_TEMPERATURE + 13.7, WetBulbTemperature.of(MELTING_TEMPERATURE + 20.0, 0.5, P), 0.3);
	}

	@Test
	public void solvesThePsychrometricBalance() {
		double ta = MELTING_TEMPERATURE + 1.5;
		double rh = 0.6;
		double p = 85000.0;
		double tw = WetBulbTemperature.of(ta, rh, p);
		double qa = AirProperties.specificHumidityFromRelative(ta, rh, p);
		double qs = AirProperties.specificHumidity(SaturationCurve.WATER.pressure(tw, p), p);
		double sensible = AirProperties.specificHeat(ta) * (ta - tw);
		double latent = AirProperties.latentHeatOfVaporisation(tw) * (qs - qa);
		assertEquals(sensible, latent, 1e-9 * sensible);
		// dry air above 0 °C can bring the precipitation below 0 °C
		assertTrue(tw < MELTING_TEMPERATURE);
	}

	@Test
	public void decreasesWithDrierAir() {
		double ta = MELTING_TEMPERATURE + 2.0;
		double previous = Double.POSITIVE_INFINITY;
		for (double rh = 1.0; rh >= 0.0; rh -= 0.1) {
			double tw = WetBulbTemperature.of(ta, Math.max(rh, 0.0), P);
			assertTrue(tw < previous);
			assertTrue(tw <= ta);
			previous = tw;
		}
	}
}
