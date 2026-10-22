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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.surface.MoninObukhov;
import org.geoframe.whetgeo1d.snow.surface.SurfaceEnergyBalance;
import org.junit.Test;

/** GEOtop's thin-snow regime: soil roughness and soil emissivity under a thin pack. */
public class ThinSnowTest {

	private static final SnowProcesses FIDELITY = SnowProcesses.geotop(new SnowEquationOfState(1e5), 10.0, 10.0);
	private static final SnowProcesses RECOMMENDED = SnowProcesses.recommended(new SnowEquationOfState(1e5), 10.0,
			10.0);

	@Test
	public void geotopThresholdsAreStrictAsSnowDGreaterThanThreshold() {
		ThinSnow t = ThinSnow.geotop();
		// GEOtop: snowD > thres uses the snow; equal or below, the soil (update_roughness_soil, snowD > 10)
		assertTrue(t.soilRoughnessAt(0.010));
		assertFalse(t.soilRoughnessAt(0.0100001));
		assertTrue(t.soilEmissivityAt(0.005));
		assertFalse(t.soilEmissivityAt(0.011));
	}

	@Test
	public void fidelityHasTheRegimeRecommendedDoesNot() {
		assertEquals(ThinSnow.geotop(), FIDELITY.thinSnow());
		assertEquals(ThinSnow.none(), RECOMMENDED.thinSnow());
		ThinSnow none = RECOMMENDED.thinSnow();
		assertSame(RECOMMENDED.turbulence(), none.turbulence(RECOMMENDED.turbulence(), 1e-4));
		assertSame(RECOMMENDED.surfaceBalance(), none.surfaceBalance(RECOMMENDED.surfaceBalance(), 1e-4));
	}

	@Test
	public void thinPackTakesTheSoilProperties() {
		ThinSnow t = FIDELITY.thinSnow();
		MoninObukhov snow = FIDELITY.turbulence();
		MoninObukhov thin = t.turbulence(snow, 0.005);
		assertEquals(0.010, thin.parameters().roughnessLength(), 0.0);
		assertSame(snow, t.turbulence(snow, 0.05));
		SurfaceEnergyBalance.Parameters b = t.surfaceBalance(FIDELITY.surfaceBalance(), 0.005);
		assertEquals(0.96, b.emissivity(), 0.0);
		assertSame(FIDELITY.surfaceBalance(), t.surfaceBalance(FIDELITY.surfaceBalance(), 0.02));

		// a rougher surface exchanges more: in stable air (snow 5 K colder) the heat resistance falls
		double ta = 263.15;
		double ts = 258.15;
		double qa = 1.5e-3;
		double qs = 1.0e-3;
		double rSnow = snow.resistances(ta, ts, qa, qs, 3.0).heatFlux();
		double rThin = thin.resistances(ta, ts, qa, qs, 3.0).heatFlux();
		assertTrue("r_h snow " + rSnow + " thin " + rThin, rThin < rSnow);
	}
}
