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
import static org.junit.Assert.assertTrue;

import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.junit.Test;

/** SNOW-3: GEOtop's conductivity of the snow faces, the mean of the arguments, against GEOtop's code. */
public class GeotopFaceConductanceTest {

	/** GEOtop's {@code k_thermal} (energy.balance.cc:2743-2765), Cosenza branch. */
	private static double kThermal(double thLiq, double thIce, double thSat, double kSolid) {
		double r = (1. - thSat) * Math.sqrt(kSolid) + thLiq * Math.sqrt(0.567) + thIce * Math.sqrt(2.290)
				+ (thSat - thLiq - thIce) * Math.sqrt(0.023);
		return r * r;
	}

	/** GEOtop's {@code Arithmetic_Mean(D1, D2, K1, K2)} (pedo.funct.h:203-206). */
	private static double arithmeticMean(double d1, double d2, double k1, double k2) {
		return (d1 * k2 + d2 * k1) / (d1 + d2);
	}

	@Test
	public void snowSoilFaceAsGeotop() {
		// GEOtop's loop: l the lower layer (soil), l-1 the upper (snow), Arithmetic_Mean(D_l, D_l-1, x_l, x_l-1)
		double dSoil = 0.02, dSnow = 0.05;
		double[] soil = { 0.30, 0.40, 0.80, 2.5 };
		double[] snow = { 0.01, 0.30, 1.0, 0.0 };
		double expected = kThermal(arithmeticMean(dSoil, dSnow, soil[0], snow[0]),
				arithmeticMean(dSoil, dSnow, soil[1], snow[1]), arithmeticMean(dSoil, dSnow, soil[2], snow[2]),
				arithmeticMean(dSoil, dSnow, soil[3], snow[3]));
		double k = SnowSoilHeatDriver.geotopFaceConductivity(soil, snow, dSoil, dSnow, null);
		assertEquals(expected, k, 1e-15 * expected);

		// GEOtop's face conducts much more than the series conductance of the two half cells
		double kSnow = SnowThermalConductivity.COSENZA.conductivity(snow[0], snow[1]);
		double kSoil = kThermal(soil[0], soil[1], soil[2], soil[3]);
		double harmonic = (dSoil + dSnow) / (dSoil / kSoil + dSnow / kSnow);
		assertTrue("GEOtop " + k + " harmonic " + harmonic, k > 2 * harmonic);
	}

	@Test
	public void snowSnowFaceWithTheSnowLaw() {
		double d1 = 0.03, d2 = 0.01;
		double[] lower = { 0.02, 0.35, 1.0, 0.0 };
		double[] upper = { 0.00, 0.12, 1.0, 0.0 };
		for (SnowThermalConductivity law : SnowThermalConductivity.values()) {
			double expected = law.conductivity(arithmeticMean(d1, d2, lower[0], upper[0]),
					arithmeticMean(d1, d2, lower[1], upper[1]));
			assertEquals(law.name(), expected, SnowSoilHeatDriver.geotopFaceConductivity(lower, upper, d1, d2, law),
					1e-15 * expected);
		}
		// Cosenza on the means is GEOtop's k_thermal with θ_sat = 1 and no solid
		double cosenza = SnowSoilHeatDriver.geotopFaceConductivity(lower, upper, d1, d2,
				SnowThermalConductivity.COSENZA);
		assertEquals(kThermal(arithmeticMean(d1, d2, lower[0], upper[0]), arithmeticMean(d1, d2, lower[1], upper[1]),
				1.0, 0.0), cosenza, 1e-15);
	}

	@Test
	public void configurationsChooseTheRule() {
		org.geoframe.whetgeo1d.snow.SnowEquationOfState eos = new org.geoframe.whetgeo1d.snow.SnowEquationOfState(1e5);
		assertEquals(SnowHeatProperties.FaceConductance.HARMONIC_MEAN,
				new SnowHeatProperties(eos, SnowThermalConductivity.JORDAN_1991, 11).faceConductance());
	}
}
