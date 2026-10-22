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

import org.geoframe.whetgeo1d.core.snowsoil.StepController.ControlledStep;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.junit.Test;

/**
 * The whole-column balance of VAL-7 on the synthetic season: the closures
 * against the external fluxes are what the solver residuals and the reported
 * corrections say they are, step by step and over the run, and a correction
 * shows up in the closure instead of being subtracted from it.
 */
public class ColumnBalanceTest {

	private static final SyntheticSeason SEASON = SyntheticSeason.standard();
	private static final double PRECIPITATION = 7 * 30.0 + 24.0;

	/**
	 * Soil water, recommended configuration: the column closes mass to the
	 * Richards error and energy to the Newton residuals, at every step; over the
	 * season the precipitation is all accounted for and the pack is gone.
	 */
	@Test
	public void columnWithSoilWaterCloses() {
		StepController controller = run(SEASON.solverWithSoilWater(Configuration.RECOMMENDED), 1e-5);
		ColumnBalance total = controller.diagnostics().total();
		ColumnBalance max = controller.diagnostics().maxAbs();
		assertEquals(PRECIPITATION, total.snowfall() + total.rainfall(), 1e-9);
		assertEquals(0.0, total.snowMassChange(), 1e-12);
		// water: precipitation = soil storage + vapour + drainage + runoff - closure
		assertEquals(PRECIPITATION, total.soilWaterChange() + total.vapour() + total.drainage() + total.runoff()
				+ total.evapotranspiration() - total.massClosure(), 1e-9);
		assertTrue("max mass closure " + max.massClosure(), max.massClosure() < 1e-6);
		assertTrue("season mass closure " + total.massClosure(), Math.abs(total.massClosure()) < 1e-5);
		assertTrue("max energy closure " + max.energyClosure(), max.energyClosure() < 1.0);
		assertEquals(0.0, total.soilStorageHeat(), 0.0);
		assertTrue(total.drainage() > 10.0);
		assertTrue(Math.abs(total.surfaceEnergy()) > 1e7);
		assertTrue("the vapour phase mismatch is deferred and enters again", total.pendingApplied() > 1e4);
	}

	/**
	 * Frozen soil, recommended configuration: the energy created by the water
	 * above saturation (document 012) is in the closure, about 8.6 kJ m-2 over
	 * the season, and the closure is that correction plus the Newton residuals:
	 * reported, not subtracted (VAL-7).
	 */
	@Test
	public void storageHeatShowsInTheClosure() {
		StepController controller = run(SEASON.solverWithFrozenSoil(Configuration.RECOMMENDED), 1e-2);
		ColumnBalance total = controller.diagnostics().total();
		assertTrue("storage heat " + total.soilStorageHeat(), total.soilStorageHeat() > 1e3);
		assertTrue("closure " + total.energyClosure(), total.energyClosure() > 0.9 * total.soilStorageHeat());
		assertEquals(total.explainedEnergyClosure(), total.energyClosure(), 0.1);
		assertTrue(Math.abs(total.heatNewtonClosure()) < 10.0);
		assertTrue("season mass closure " + total.massClosure(), Math.abs(total.massClosure()) < 1e-4);
	}

	/**
	 * Semi-implicit surface (one solve per step): the linearisation error is
	 * large and reported on its own; the closure, against the flux the solver
	 * applied, stays at the level of the Newton residuals. Heat only, fixed
	 * suction: the water handed to the soil leaves the column.
	 */
	@Test
	public void linearisationIsReportedApart() {
		StepController controller = run(SEASON.solver(Configuration.FIDELITY, SurfaceCoupling.semiImplicit()), 1e-5);
		ColumnBalance total = controller.diagnostics().total();
		assertTrue("linearisation " + total.linearisationEnergy(), total.linearisationEnergy() > 1e6);
		assertTrue("closure " + total.energyClosure(), Math.abs(total.energyClosure()) < 10.0);
		assertEquals(PRECIPITATION, total.unmodelledSoilInput() + total.vapour(), 1e-9);
		assertEquals(0.0, total.massClosure(), 1e-9);
		assertEquals(0.0, total.soilWaterChange(), 0.0);
	}

	/**
	 * Runs the season and checks every step: mass closure = the operator
	 * closure + Richards error + hand-over, to round-off; energy closure = the
	 * explained part within {@code energyTolerance} (the inversion of the soil
	 * closures in the transport of the soil water).
	 */
	private static StepController run(SnowSoilSolver solver, double energyTolerance) {
		StepController controller = new StepController(solver);
		for (int k = 0; k < SEASON.steps(); k++) {
			double[] soil = solver.soilTemperatures();
			ControlledStep s = controller.advance(SEASON.forcing(k * SEASON.timeDelta(), soil[soil.length - 1]));
			ColumnBalance b = s.balance();
			assertEquals("mass, step " + k, b.explainedMassClosure(), b.massClosure(), 1e-12);
			assertEquals("energy, step " + k, b.explainedEnergyClosure(), b.energyClosure(), energyTolerance);
			assertEquals("top flux, step " + k, s.result().heat().energy().topInput(),
					b.surfaceEnergy() + b.linearisationEnergy() + b.pendingApplied(), 1e-6);
		}
		assertEquals(SEASON.steps(), controller.diagnostics().intervals());
		assertEquals(SEASON.steps(), controller.diagnostics().substeps());
		return controller;
	}
}
