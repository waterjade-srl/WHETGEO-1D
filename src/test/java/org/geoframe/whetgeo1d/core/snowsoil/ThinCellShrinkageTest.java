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

import java.util.List;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.junit.Test;

/**
 * A thin snow layer melting on warm soil under rain, as on Matsch_P2_Ref_007
 * on 2 November. In the fidelity configuration a cell with at most 0.1 W_c of
 * ice (0.5 kg m-2 with GEOtop's defaults) keeps its thickness while it melts,
 * as GEOtop ({@code snow.cc:1093-1110}); in the recommended one it shrinks
 * with its ice (SNOW-6). The rule changes thicknesses only: the test checks,
 * step by step, that the snow and the whole column conserve mass and energy
 * (VAL-7), that the thin cell holds no liquid, and how low its density goes.
 */
public class ThinCellShrinkageTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final double SWE = 0.4;
	private static final double THICKNESS = 0.003;

	private record Run(double[] swe, double[] thickness, double[] liquid, double minDensity, double maxMassClosure,
			double maxEnergyClosure, double maxSnowMassClosure, double maxSnowEnergyClosure) {
	}

	private static Run run(SnowProcesses processes) {
		UniformSoil soil = new UniformSoil(20, 1.0, -1.0, 273.65, "Weighted Harmonic Mean",
				UniformSoil.Hydraulics.sandyLoam());
		SnowSoilSolver solver = new SnowSoilSolver(soil.freezingSetup(), SoilWaterProperties.vanGenuchtenFreeDrainage(),
				new SnowHeatProperties(EOS, SnowThermalConductivity.COSENZA, 10),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(), processes,
				List.of(SnowCell.atTemperature(EOS, SWE, THICKNESS, 273.15 - 0.1)), 0.0);
		SurfaceForcing overcast = new SurfaceForcing(0.0, 30.0, 0.3, 315.0, 273.65, 1.0, 2.0, 84445.0);
		StepForcing forcing = new StepForcing(overcast, 0.0, 0.05, 0.15, 0.25, new TopBoundary.SurfaceTemperature(273.65),
				273.65, 300.0);
		int steps = 8;
		double[] swe = new double[steps];
		double[] thickness = new double[steps];
		double[] liquid = new double[steps];
		double minDensity = Double.MAX_VALUE;
		double mass = 0.0;
		double energy = 0.0;
		double snowMass = 0.0;
		double snowEnergy = 0.0;
		for (int k = 0; k < steps; k++) {
			ColumnBalance.Storage before = ColumnBalance.Storage.of(solver);
			SnowSoilStepResult r = solver.step(forcing);
			ColumnBalance b = ColumnBalance.of(before, ColumnBalance.Storage.of(solver), forcing, r,
					solver.lastStepTerms());
			mass = Math.max(mass, Math.abs(b.massClosure() - b.explainedMassClosure()));
			energy = Math.max(energy, Math.abs(b.energyClosure() - b.explainedEnergyClosure()));
			snowMass = Math.max(snowMass, Math.abs(r.budget().massClosure()));
			snowEnergy = Math.max(snowEnergy, Math.abs(r.budget().energyClosure()));
			swe[k] = r.snowWaterEquivalent();
			thickness[k] = r.snowDepth();
			for (SnowCell c : r.snow()) {
				liquid[k] += c.liquidMass();
				minDensity = Math.min(minDensity, c.density());
			}
		}
		return new Run(swe, thickness, liquid, minDensity, mass, energy, snowMass, snowEnergy);
	}

	@Test
	public void fidelityKeepsTheThicknessAndConserves() {
		Run r = run(SnowProcesses.geotop(EOS, 2.5, 2.0));
		System.out.printf("fidelity:    SWE %s%n             HS  %s%n             min density %.2f kg m-3,"
				+ " column closures %.2e kg m-2 / %.2e J m-2, snow closures %.2e / %.2e%n",
				java.util.Arrays.toString(r.swe()), java.util.Arrays.toString(r.thickness()), r.minDensity(),
				r.maxMassClosure(), r.maxEnergyClosure(), r.maxSnowMassClosure(), r.maxSnowEnergyClosure());
		assertTrue("the layer melts", r.swe()[0] < SWE && r.swe()[r.swe().length - 1] < r.swe()[0]);
		for (int k = 0; k < r.swe().length; k++) {
			if (r.swe()[k] > 0.0) {
				assertEquals("thickness kept at step " + k, THICKNESS, r.thickness()[k], 1e-15);
				// all the melt leaves; what is left is the liquid of the smooth
				// equation of state at the cell temperature, a few g m-2 at most
				assertEquals("a thin cell holds no free liquid", 0.0, r.liquid()[k], 1e-5);
			}
		}
		assertTrue(r.minDensity() > 0.0 && Double.isFinite(r.minDensity()));
		assertEquals(0.0, r.maxMassClosure(), 1e-9);
		assertEquals(0.0, r.maxEnergyClosure(), 1e-3);
		assertEquals(0.0, r.maxSnowMassClosure(), 1e-12);
		assertEquals(0.0, r.maxSnowEnergyClosure(), 1e-6);
	}

	@Test
	public void recommendedShrinksAndConserves() {
		Run r = run(SnowProcesses.recommended(EOS, 2.5, 2.0));
		System.out.printf("recommended: SWE %s%n             HS  %s%n             min density %.2f kg m-3,"
				+ " column closures %.2e kg m-2 / %.2e J m-2%n", java.util.Arrays.toString(r.swe()),
				java.util.Arrays.toString(r.thickness()), r.minDensity(), r.maxMassClosure(), r.maxEnergyClosure());
		assertTrue(r.thickness()[0] < THICKNESS);
		assertEquals(0.0, r.maxMassClosure(), 1e-9);
		assertEquals(0.0, r.maxEnergyClosure(), 1e-3);
	}
}
