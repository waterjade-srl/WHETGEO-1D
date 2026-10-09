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
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Expected values are GEOtop's formulas in GEOtop units (°C, mbar), computed
 * independently in python ({@code meteo.cc}, {@code turbulence.cc},
 * {@code radiation.cc} of commit 1f1fc5af).
 */
public class AirPropertiesTest {

	private static final double P = 85000.0;

	@Test
	public void saturationOverWaterIsGeotops() {
		// SatVapPressure(-10, 850) = 2.8752627243101165 mbar; SatVapPressure_2 derivative 0.22730921519666687 mbar/K
		assertEquals(287.52627243101165, SaturationCurve.WATER.pressure(263.15, P), 1e-9);
		assertEquals(22.730921519666687, SaturationCurve.WATER.derivative(263.15, P), 1e-10);
	}

	@Test
	public void saturationOverIceBelowMeltingOnly() {
		// Buck (1981) over ice at -10 degC, 850 hPa
		assertEquals(260.873763272768, SaturationCurve.ICE_BELOW_MELTING.pressure(263.15, P), 1e-9);
		assertEquals(SaturationCurve.WATER.pressure(275.15, P), SaturationCurve.ICE_BELOW_MELTING.pressure(275.15, P),
				0.0);
		double below = SaturationCurve.ICE_BELOW_MELTING.pressure(273.15 - 1e-9, P);
		double above = SaturationCurve.ICE_BELOW_MELTING.pressure(273.15, P);
		assertTrue("continuous at 0 degC to 0.01 %", Math.abs(below / above - 1.0) < 1.2e-4);
	}

	@Test
	public void saturationDerivativesMatchFiniteDifferences() {
		for (SaturationCurve curve : SaturationCurve.values()) {
			for (double t : new double[] { 233.15, 253.15, 263.15, 272.0, 278.15 }) {
				double h = 1e-4;
				double fd = (curve.pressure(t + h, P) - curve.pressure(t - h, P)) / (2 * h);
				assertEquals(curve + " at " + t, fd, curve.derivative(t, P), 1e-7 * Math.abs(fd));
			}
		}
	}

	@Test
	public void specificHumidityAndItsInverse() {
		double e = 287.52627243101165;
		// SpecHumidity(2.875..., 850); dQ_de 7.3363963756650605e-4 per mbar
		assertEquals(0.0021067095180076064, AirProperties.specificHumidity(e, P), 1e-15);
		assertEquals(7.3363963756650605e-06, AirProperties.dSpecificHumidity(e, P), 1e-18);
		assertEquals(e, AirProperties.vapourPressure(AirProperties.specificHumidity(e, P), P), 1e-10);
	}

	@Test
	public void densityAndSpecificHeatAreGeotops() {
		// air_density(-3.15, 0.003, 850), air_cp(-10)
		assertEquals(1.0947668504386188, AirProperties.density(270.0, 0.003, P), 1e-13);
		assertEquals(1005.0514038347205, AirProperties.specificHeat(263.15), 1e-10);
	}

	@Test
	public void latentHeatOfVaporisation() {
		assertEquals(2.501e6, AirProperties.latentHeatOfVaporisation(263.15), 0.0);
		assertEquals(2.501e6, AirProperties.latentHeatOfVaporisation(273.15), 0.0);
		assertEquals(2489125.0, AirProperties.latentHeatOfVaporisation(278.15), 1e-6); // Levap(5)
		assertEquals(2.501e6 + 333700.0, VapourPhase.ICE.latentHeat(263.15), 0.0);
		assertEquals(2.501e6, VapourPhase.LIQUID.latentHeat(273.15), 0.0);
	}

	@Test
	public void emissivePower() {
		// SB(0), dSB_dT(0)
		assertEquals(315.63697918226694, AirProperties.emissivePower(273.15), 1e-11);
		assertEquals(4.622177985462449, AirProperties.dEmissivePower(273.15), 1e-13);
	}

	@Test
	public void airHumidityFromRelativeHumidityUsesWater() {
		double expected = AirProperties.specificHumidity(0.7 * SaturationCurve.WATER.pressure(268.15, P), P);
		assertEquals(expected, AirProperties.specificHumidityFromRelative(268.15, 0.7, P), 0.0);
		SurfaceForcing forcing = SurfaceForcing.withoutBeam(0.0, 250.0, 268.15, 0.7, 2.0, P);
		assertEquals(expected, forcing.airHumidity(), 0.0);
	}

	@Test
	public void phaseEnergiesAddUpToTheSameVapourEnergyAtMelting() {
		double t = 273.15;
		assertEquals(VapourPhase.ICE.specificEnergy(t) + VapourPhase.ICE.latentHeat(t),
				VapourPhase.LIQUID.specificEnergy(t) + VapourPhase.LIQUID.latentHeat(t), 1e-9);
		assertEquals(VapourPhase.ICE, VapourPhase.at(273.1499));
		assertEquals(VapourPhase.LIQUID, VapourPhase.at(273.15));
	}

	@Test(expected = IllegalArgumentException.class)
	public void forcingRejectsRelativeHumidityAboveOne() {
		SurfaceForcing.withoutBeam(0.0, 250.0, 268.15, 1.2, 2.0, P);
	}
}
