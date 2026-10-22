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
import static org.junit.Assert.assertNull;
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
 * A trace of snow on warm soil under a strong sun, as on Matsch_P2_Ref_007 on
 * 12 October: the single layer melts away in the step. In the fidelity
 * configuration it goes to the soil and the step is solved as bare soil, as
 * GEOtop ({@code sux = -6}); it is not kept as an all-liquid "snow" cell that
 * evaporates as a wet surface.
 */
public class MeltedLayerMergeTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);

	private static SnowSoilSolver solver(SnowProcesses processes, List<SnowCell> snow) {
		UniformSoil soil = new UniformSoil(20, 1.0, -1.0, 280.15, "Weighted Harmonic Mean",
				UniformSoil.Hydraulics.sandyLoam());
		return new SnowSoilSolver(soil.freezingSetup(), SoilWaterProperties.vanGenuchtenFreeDrainage(),
				new SnowHeatProperties(EOS, SnowThermalConductivity.COSENZA, 10),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(), processes, snow, 0.0);
	}

	private static StepForcing forcing() {
		SurfaceForcing sunny = new SurfaceForcing(500.0, 120.0, 0.7, 300.0, 277.15, 0.8, 5.0, 84445.0);
		return new StepForcing(sunny, 0.0, 0.0, 0.15, 0.25, new TopBoundary.SurfaceTemperature(281.15), 280.15,
				900.0);
	}

	private static List<SnowCell> trace() {
		return List.of(SnowCell.atTemperature(EOS, 0.008, 0.0000358, 273.15 - 0.1));
	}

	@Test
	public void aTraceThatMeltsGoesToTheSoil() {
		SnowSoilSolver solver = solver(SnowProcesses.geotop(EOS, 2.5, 2.0), trace());
		SnowSoilStepResult r = solver.step(forcing());
		assertTrue(r.snow().isEmpty());
		assertNull("the step is a bare-soil step", r.surface());
		assertTrue(Double.isNaN(r.surfaceTemperature()));
		assertEquals(0.0, r.budget().vapour(), 0.0);
		assertEquals(0.008, r.budget().toSoilMass(), 1e-15);
		assertEquals(0.008, r.soilInputMass(), 1e-15);
		assertEquals(0.0, r.budget().massClosure(), 1e-15);
		assertEquals(0.0, r.budget().energyClosure(), 1e-9);
		assertEquals(0.0, r.soilWater().balanceError(), 1e-6);
	}

	@Test
	public void withoutTheRegimeTheMeltedCellStays() {
		SnowSoilSolver solver = solver(SnowProcesses.recommended(EOS, 2.5, 2.0), trace());
		SnowSoilStepResult r = solver.step(forcing());
		assertFalse("the snow surface balance was computed", r.surface() == null);
	}

	@Test
	public void aThickerPackIsNotMerged() {
		SnowSoilSolver solver = solver(SnowProcesses.geotop(EOS, 2.5, 2.0),
				List.of(SnowCell.atTemperature(EOS, 5.0, 0.02, 273.15 - 2.0)));
		SnowSoilStepResult r = solver.step(forcing());
		assertFalse(r.snow().isEmpty());
		assertFalse(r.surface() == null);
	}
}
