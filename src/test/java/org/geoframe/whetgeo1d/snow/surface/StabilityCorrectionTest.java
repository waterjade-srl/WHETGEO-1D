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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StabilityCorrectionTest {

	@Test
	public void functionsAreGeotops() {
		// Psim(-0.5), Psih(-0.5), PsiStab(0.3) of turbulence.cc
		assertEquals(0.7663497599956968, StabilityCorrection.psiMomentumUnstable(-0.5), 1e-14);
		assertEquals(1.3435794234333192, StabilityCorrection.psiScalarUnstable(-0.5), 1e-14);
		assertEquals(1.4800973806830076, StabilityCorrection.psiStable(0.3), 1e-14);
		assertEquals("Holtslag-De Bruin vanishes at neutrality", 0.0, StabilityCorrection.psiStable(0.0), 1e-14);
		assertEquals(0.0, StabilityCorrection.psiMomentumUnstable(0.0), 1e-14);
	}

	@Test
	public void eachOptionSwitchesItsSide() {
		double z = 2.0;
		double z0 = 1e-4;
		double neutral = Math.log(z / z0);
		double stable = neutral + StabilityCorrection.psiStable(z / 5.0) - StabilityCorrection.psiStable(z0 / 5.0);
		double unstable = neutral - StabilityCorrection.psiScalarUnstable(-z / 5.0)
				+ StabilityCorrection.psiScalarUnstable(-z0 / 5.0);
		assertEquals(stable, StabilityCorrection.BOTH.scalarProfile(z, z0, 0.0, 5.0), 1e-14);
		assertEquals(unstable, StabilityCorrection.BOTH.scalarProfile(z, z0, 0.0, -5.0), 1e-14);
		assertEquals(neutral, StabilityCorrection.UNSTABLE_ONLY.scalarProfile(z, z0, 0.0, 5.0), 1e-14);
		assertEquals(unstable, StabilityCorrection.UNSTABLE_ONLY.scalarProfile(z, z0, 0.0, -5.0), 1e-14);
		assertEquals(stable, StabilityCorrection.STABLE_ONLY.scalarProfile(z, z0, 0.0, 5.0), 1e-14);
		assertEquals(neutral, StabilityCorrection.STABLE_ONLY.scalarProfile(z, z0, 0.0, -5.0), 1e-14);
		assertEquals(neutral, StabilityCorrection.NEUTRAL.scalarProfile(z, z0, 0.0, 5.0), 1e-14);
		assertEquals(neutral, StabilityCorrection.NEUTRAL.momentumProfile(z, z0, 0.0, -5.0), 1e-14);
		assertEquals("infinite L is neutral", neutral,
				StabilityCorrection.BOTH.momentumProfile(z, z0, 0.0, Double.POSITIVE_INFINITY), 1e-14);
	}

	@Test
	public void geotopCodes() {
		assertEquals(StabilityCorrection.BOTH, StabilityCorrection.ofGeotopCode(1));
		assertEquals(StabilityCorrection.UNSTABLE_ONLY, StabilityCorrection.ofGeotopCode(2));
		assertEquals(StabilityCorrection.STABLE_ONLY, StabilityCorrection.ofGeotopCode(3));
		assertEquals(StabilityCorrection.NEUTRAL, StabilityCorrection.ofGeotopCode(4));
		assertEquals(2, StabilityCorrection.UNSTABLE_ONLY.geotopCode());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsUnknownCode() {
		StabilityCorrection.ofGeotopCode(0);
	}
}
