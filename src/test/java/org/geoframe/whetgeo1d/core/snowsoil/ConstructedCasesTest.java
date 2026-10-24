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

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.snow.TopCellThicknessCap;
import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.junit.Test;

/**
 * VAL-4: constructed cases of the snow+soil column, recommended configuration,
 * each with a result that can be checked by hand.
 */
public class ConstructedCasesTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final double STEP = 3600.0;

	private static SnowSoilSolver solver(UniformSoil soil, SoilWaterProperties water) {
		return new SnowSoilSolver(soil.freezingSetup(), water,
				new SnowHeatProperties(EOS, SnowThermalConductivity.JORDAN_1991, 11),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(),
				SnowProcesses.recommended(EOS, 2.0, 2.0), List.of(), 0.0);
	}

	/** Night at the given air temperature, longwave at its emissive power, saturated air: no net exchange at 0 °C. */
	private static SurfaceForcing night(double airTemperature) {
		return new SurfaceForcing(0.0, 0.0, 0.0, AirProperties.emissivePower(airTemperature), airTemperature, 1.0, 1.0,
				85000.0);
	}

	/**
	 * Fresh snow on warm soil: 10 kg m-2 fall on a soil at +5 °C, under air at 0
	 * °C with its own longwave and saturated: the surface takes and gives
	 * almost nothing, so the soil melts the pack from below. The melt is the
	 * heat the soil gives the snow divided by L_f, to the small cold content of
	 * the fresh snow.
	 */
	@Test
	public void freshSnowOnWarmSoilMeltsFromBelow() {
		UniformSoil soil = new UniformSoil(20, 1.0, -1.0, 278.15, "Weighted Harmonic Mean");
		SnowSoilSolver solver = solver(soil, null);
		double fromSoil = 0.0;
		double atSurface = 0.0;
		double melt = 0.0;
		double drained = 0.0;
		int steps = 0;
		for (int k = 0; k < 24 * 20; k++) {
			StepForcing forcing = new StepForcing(night(MELTING_TEMPERATURE), k == 0 ? 10.0 : 0.0, 0.0, 0.2, 0.2,
					new TopBoundary.SurfaceTemperature(MELTING_TEMPERATURE), 278.15, STEP);
			boolean snowBefore = !solver.snow().isEmpty();
			SnowSoilStepResult r = solver.step(forcing);
			if (snowBefore) {
				fromSoil += -STEP * r.heat().snowToSoilFlux();
				atSurface += STEP * r.heat().surfaceFlux();
			}
			melt += r.melt();
			drained += r.soilInputMass() + r.budget().vapour();
			steps++;
			if (k > 0 && r.snow().isEmpty()) {
				break;
			}
		}
		assertTrue("the pack melted out in " + steps + " hours", solver.snow().isEmpty() && steps < 24 * 20);
		assertEquals("all the snow reached the soil, but the vapour", 10.0, drained, 1e-9);
		assertTrue("surface " + atSurface + " J m-2, soil " + fromSoil, Math.abs(atSurface) < 0.05 * fromSoil);
		assertEquals(fromSoil / LATENT_HEAT_FUSION, melt, 0.05 * melt);
	}

	/**
	 * Heavy snowfall on bare ground, then on the pack: 40 kg m-2 in one hour,
	 * twice, at -10 °C. The surface cell passed to the next heat solve is never
	 * thicker than the cap (SURF-1), and the pack holds all the snow.
	 */
	@Test
	public void heavySnowfallKeepsTheSurfaceCellWithinTheCap() {
		UniformSoil soil = new UniformSoil(20, 1.0, -1.0, 270.15, "Weighted Harmonic Mean");
		SnowSoilSolver solver = solver(soil, null);
		double fallen = 0.0;
		for (int k = 0; k < 48; k++) {
			double snowfall = k == 0 || k == 24 ? 40.0 : 0.0;
			fallen += snowfall;
			solver.step(new StepForcing(night(263.15), snowfall, 0.0, 0.2, 0.2,
					new TopBoundary.SurfaceTemperature(263.15), 270.15, STEP));
			List<SnowCell> snow = solver.snow();
			SnowCell top = snow.get(snow.size() - 1);
			assertTrue("step " + k + " surface cell " + top.thickness() + " m",
					top.thickness() <= TopCellThicknessCap.DEFAULT_MAX_THICKNESS * (1.0 + TopCellThicknessCap.TOLERANCE));
			assertTrue("step " + k + " cells " + snow.size(), snow.size() > 1);
		}
		double swe = solver.snow().stream().mapToDouble(SnowCell::totalMass).sum();
		assertEquals("no melt, little vapour", fallen, swe, 0.01 * fallen);
	}

	/**
	 * The same snowfall across a restart: the state written after the first
	 * snowfall and read back into a new solver goes on to the bit, and the
	 * second snowfall keeps the surface cell within the cap in the resumed run.
	 */
	@Test
	public void heavySnowfallAcrossARestart() throws java.io.IOException {
		UniformSoil soil = new UniformSoil(20, 1.0, -1.0, 270.15, "Weighted Harmonic Mean");
		StepController continuous = new StepController(solver(soil, null), StepControlSettings.defaults(), 0.0,
				"recommended");
		StepController resumed = null;
		for (int k = 0; k < 48; k++) {
			if (k == 12) {
				byte[] bytes = continuous.checkpoint().toBytes();
				resumed = StepController.resume(solver(soil, null), StepControlSettings.defaults(),
						SnowSoilRestart.fromBytes(bytes), "recommended");
			}
			double snowfall = k == 0 || k == 24 ? 40.0 : 0.0;
			StepForcing forcing = new StepForcing(night(263.15), snowfall, 0.0, 0.2, 0.2,
					new TopBoundary.SurfaceTemperature(263.15), 270.15, STEP);
			continuous.advance(forcing);
			if (resumed != null) {
				resumed.advance(forcing);
				assertTrue("step " + k, continuous.solver().snapshot().bitwiseEquals(resumed.solver().snapshot()));
				List<SnowCell> snow = resumed.solver().snow();
				assertTrue("step " + k, snow.get(snow.size() - 1).thickness() <= TopCellThicknessCap.DEFAULT_MAX_THICKNESS
						* (1.0 + TopCellThicknessCap.TOLERANCE));
			}
		}
	}

	/**
	 * A pack vanishing over an impermeable soil (SOIL-4): 3 kg m-2 melt out in
	 * the sun on a saturated soil with a negligible conductivity. What leaves the
	 * pack is released to Richards in the same step and, unable to infiltrate,
	 * runs off: nothing is lost or stored.
	 */
	@Test
	public void vanishingPackOverImpermeableSoilRunsOff() {
		UniformSoil.Hydraulics tight = new UniformSoil.Hydraulics(0.41, 0.065, 1.89, 7.5, 1e-14, 1e-4);
		UniformSoil soil = new UniformSoil(20, 1.0, 0.0, 275.15, "Weighted Harmonic Mean", tight);
		SnowSoilSolver solver = new SnowSoilSolver(soil.freezingSetup(), SoilWaterProperties.vanGenuchtenFreeDrainage(),
				new SnowHeatProperties(EOS, SnowThermalConductivity.JORDAN_1991, 11),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(),
				SnowProcesses.recommended(EOS, 2.0, 2.0),
				List.of(SnowCell.atTemperature(EOS, 3.0, 0.01, MELTING_TEMPERATURE - 0.5)), 0.0);
		SurfaceForcing sunny = new SurfaceForcing(500.0, 120.0, 0.7, 320.0, 281.15, 0.6, 3.0, 85000.0);
		double storageBefore = solver.soilWaterStorage();
		double toSoil = 0.0;
		double runoff = 0.0;
		double infiltration = 0.0;
		double vapour = 0.0;
		for (int k = 0; k < 24 && !(k > 0 && solver.snow().isEmpty()); k++) {
			SnowSoilStepResult r = solver.step(new StepForcing(sunny, 0.0, 0.0, 0.2, 0.2,
					new TopBoundary.SurfaceTemperature(281.15), 275.15, STEP));
			toSoil += r.soilInputMass();
			runoff += r.soilWater().runoff();
			infiltration += r.soilWater().infiltration();
			vapour += r.budget().vapour();
		}
		assertTrue("the pack vanished", solver.snow().isEmpty());
		assertEquals("the pack went to the soil", 3.0 - vapour, toSoil, 1e-9);
		assertEquals("it ran off", toSoil, runoff, 1e-6);
		assertEquals(0.0, infiltration, 1e-6);
		assertEquals(0.0, solver.soilWaterStorage() - storageBefore, 1e-5);
	}
}
