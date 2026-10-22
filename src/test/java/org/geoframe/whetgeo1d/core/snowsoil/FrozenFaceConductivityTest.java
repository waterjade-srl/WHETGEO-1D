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

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.RichardsBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.junit.Test;

/**
 * Richards under a frozen top cell, against GEOtop 3.0's formulas: water drawn
 * from an unfrozen cell into a frozen one above it (cryosuction), as on the
 * frosty nights of Matsch_P2_Ref_007. The soil is the Matsch one; the top cell
 * holds ice at -0.6 °C with the liquid at the Clapeyron suction, the cells
 * below are unfrozen. Over a short step the flux through the face below the
 * frozen cell is K<sub>face</sub> Δh/Δz, with GEOtop's face conductivity
 * ({@code find_matrix_K_3D}, {@code water.balance.cc:1007-1047};
 * {@code k_hydr_soil}, {@code pedo.funct.h:145-171}) written out here
 * independently.
 */
public class FrozenFaceConductivityTest {

	private static final double THETA_S = 0.53;
	private static final double THETA_R = 0.05;
	private static final double N = 1.5;
	private static final double M = 1.0 - 1.0 / N;
	private static final double ALPHA = 1.31;
	private static final double KS = 2.86e-6;
	private static final double OMEGA = 7.0;
	private static final double DZ = 0.01;
	private static final int CELLS = 4;
	private static final double ICE = 0.35;
	private static final double[] TEMPERATURES = { 273.15 + 1.0, 273.15 + 0.6, 273.15 + 0.2, 273.15 - 0.6 };
	private static final double[] SUCTIONS = { -0.85, -0.88, -0.90, -74.7 };
	private static final double DT = 5.0;

	/** GEOtop's k_hydr_soil (v = 0.5), ψ in m. */
	private static double geotopK(double psi, double ice, double celsius) {
		double psisat = ice > 0.0 ? -Math.pow(Math.pow(1.0 - ice / (THETA_S - THETA_R), -1.0 / M) - 1.0, 1.0 / N) / ALPHA
				: 0.0;
		double p = Math.min(psisat, psi);
		double teta = p >= 0.0 ? 1.0 : 1.0 / Math.pow(1.0 + Math.pow(ALPHA * -p, N), M);
		double k = KS * Math.pow(teta, 0.5) * Math.pow(1.0 - Math.pow(1.0 - Math.pow(teta, 1.0 / M), M), 2);
		k *= celsius >= 0.0 ? 0.000158685828 * celsius * celsius + 0.025263459766 * celsius + 0.731495819
				: 0.731495819;
		return k * Math.pow(10.0, -OMEGA * ice / (THETA_S - THETA_R));
	}

	private static double geotopKmax(double ice, double celsius) {
		double psisat = ice > 0.0 ? -Math.pow(Math.pow(1.0 - ice / (THETA_S - THETA_R), -1.0 / M) - 1.0, 1.0 / N) / ALPHA
				: 0.0;
		return geotopK(psisat, ice, celsius);
	}

	private static SoilWaterStepResult step(String interfaceModel) {
		UniformSoil soil = new UniformSoil(CELLS, CELLS * DZ, -0.9, 273.15, "Weighted Harmonic Mean",
				new UniformSoil.Hydraulics(THETA_S, THETA_R, N, ALPHA, KS, 5e-4));
		SoilWaterProperties water = new SoilWaterProperties(new String[] { "Van Genuchten" },
				new String[] { "Van Genuchten" }, new String[] { "Mualem Van Genuchten" }, "notemperature",
				interfaceModel, RichardsBoundaryConditionType.BOTTOM_IMPERVIOUS, 1e-11, 50, 1, DT, OMEGA);
		SoilWaterDriver driver = new SoilWaterDriver(soil.setup(), water);
		driver.setSuctions(SUCTIONS);
		return driver.step(0.0, 0.0, TEMPERATURES, new double[] { 0.0, 0.0, 0.0, ICE }, DT);
	}

	@Test
	public void waterDrawnIntoTheFrozenCellAsGeotop() {
		SoilWaterStepResult geotop = step(SoilWaterDriver.GEOTOP_INTERFACE);
		int below = CELLS - 2;
		int frozen = CELLS - 1;
		double upwind = geotopK(SUCTIONS[below], 0.0, TEMPERATURES[below] - 273.15);
		double cap = Math.min(geotopKmax(0.0, TEMPERATURES[below] - 273.15),
				geotopKmax(ICE, TEMPERATURES[frozen] - 273.15));
		double kFace = Math.min(upwind, cap);
		double gradient = ((SUCTIONS[below] - SUCTIONS[frozen]) - DZ) / DZ;
		double expected = kFace * gradient * DT * 1000.0;
		double face = geotop.faceWater()[frozen];
		System.out.printf("frozen face: upwind K %.3e, cap %.3e (frozen cell %.3e), flux %.4e kg m-2, expected %.4e%n",
				upwind, cap, geotopKmax(ICE, -0.6), face, expected);
		assertTrue(face > 0.0);
		assertEquals(expected, face, 0.02 * expected);
		// the cap of the frozen cell is what limits the flux
		assertEquals(geotopKmax(ICE, -0.6), kFace, 0.0);
	}

	@Test
	public void averagedFacesDrawMuchMoreWater() {
		double geotop = step(SoilWaterDriver.GEOTOP_INTERFACE).faceWater()[CELLS - 1];
		double max = step("max").faceWater()[CELLS - 1];
		System.out.printf("into the frozen cell in %.0f s: GEOtop face %.3e, WHETGEO max %.3e kg m-2%n", DT, geotop, max);
		assertTrue(max > 50.0 * geotop);
	}

	@Test
	public void viscosityCorrection() {
		assertEquals(0.731495819, SoilWaterDriver.viscosity(273.15 - 3.0), 0.0);
		assertEquals(0.731495819, SoilWaterDriver.viscosity(273.15), 1e-15);
		assertEquals(1.0, SoilWaterDriver.viscosity(273.15 + 10.0), 0.002);
	}
}
