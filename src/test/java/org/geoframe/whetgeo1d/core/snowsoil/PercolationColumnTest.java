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
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.LiquidWaterPercolation;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.junit.Test;

/**
 * VAL-4: percolation through the pack at the level of the column (heat solve,
 * percolation, compaction and restratification together), recommended
 * configuration, heat only. Each case has a result computed by hand from the
 * retention capacity W_r = S_r (1 - θ_i) h ρ_w and the cold content of the
 * cells; the module's own exact numbers are in {@code LiquidWaterPercolationTest}.
 */
public class PercolationColumnTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final SnowProcesses PROCESSES = SnowProcesses.recommended(EOS, 2.0, 2.0);
	private static final LiquidWaterPercolation PERCOLATION = PROCESSES.percolation();
	/** Isothermal at the melting point: -1 mK, as at exactly 0 °C the regularised curve makes the cell all liquid. */
	private static final double AT_MELTING = MELTING_TEMPERATURE - 1e-3;

	/** A pack of cells of 10 kg m-2 at 250 kg m-3, at the given temperature, dry. */
	private static List<SnowCell> pack(int cells, double temperature) {
		List<SnowCell> snow = new ArrayList<>();
		for (int i = 0; i < cells; i++) {
			snow.add(SnowCell.atTemperature(EOS, 10.0, 0.04, temperature));
		}
		return snow;
	}

	/** Soil at 0 °C under the pack, heat only. */
	private static SnowSoilSolver solver(List<SnowCell> snow) {
		UniformSoil soil = new UniformSoil(20, 1.0, -1.0, MELTING_TEMPERATURE, "Weighted Harmonic Mean");
		return new SnowSoilSolver(soil.freezingSetup(), new SnowHeatProperties(EOS, SnowThermalConductivity.JORDAN_1991, 12),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(), PROCESSES, snow, 0.0);
	}

	/** Air at 0 °C, saturated, with its own longwave, no sun: the surface of a pack at 0 °C exchanges nothing. */
	private static StepForcing rain(double rate, double dt) {
		SurfaceForcing neutral = new SurfaceForcing(0.0, 0.0, 0.0, AirProperties.emissivePower(MELTING_TEMPERATURE),
				MELTING_TEMPERATURE, 1.0, 1.0, 85000.0);
		return new StepForcing(neutral, 0.0, rate * dt / 3600.0, 0.2, 0.2,
				new TopBoundary.SurfaceTemperature(MELTING_TEMPERATURE), MELTING_TEMPERATURE, dt, null,
				MELTING_TEMPERATURE, 0.0);
	}

	private static double retention(List<SnowCell> snow) {
		return snow.stream().mapToDouble(PERCOLATION::retentionCapacity).sum();
	}

	/** Time of the first outflow at the base [h], and the outflow over the last hour of a 10 h rain. */
	private record Outflow(double first, double lastHour) {
	}

	private static Outflow steadyRain(double dt) {
		SnowSoilSolver solver = solver(pack(4, AT_MELTING));
		double first = Double.NaN;
		double lastHour = 0.0;
		int steps = (int) Math.round(10 * 3600.0 / dt);
		int perHour = (int) Math.round(3600.0 / dt);
		for (int k = 0; k < steps; k++) {
			SnowSoilStepResult r = solver.step(rain(1.0, dt));
			if (Double.isNaN(first) && r.soilInputMass() > 1e-6) {
				first = (k + 1) * dt / 3600.0;
			}
			if (k >= steps - perHour) {
				lastHour += r.soilInputMass();
			}
		}
		return new Outflow(first, lastHour);
	}

	/**
	 * Steady rain of 1 kg m-2 h-1 on an isothermal pack at 0 °C: nothing leaves
	 * the base until the pack holds its retention capacity (2.33 kg m-2 for
	 * the four cells), then the outflow equals the rain. Wet compaction lowers
	 * the porosity and the capacity on the way, so the first outflow comes no
	 * later than the capacity divided by the rain.
	 */
	@Test
	public void isothermalPackFillsItsRetentionThenPassesTheRain() {
		double capacity = retention(pack(4, AT_MELTING));
		Outflow o = steadyRain(3600.0);
		assertTrue("capacity " + capacity + " kg m-2, first outflow at " + o.first() + " h",
				o.first() <= Math.ceil(capacity / 1.0) && o.first() >= Math.floor(capacity / 1.0) - 1.0);
		assertEquals("the outflow equals the rain", 1.0, o.lastHour(), 0.05);
	}

	/** The time of the first outflow converges with the step. */
	@Test
	public void firstOutflowConvergesWithTheStep() {
		Outflow hour = steadyRain(3600.0);
		Outflow half = steadyRain(1800.0);
		Outflow quarter = steadyRain(900.0);
		assertTrue(hour + " " + half + " " + quarter,
				Math.abs(half.first() - quarter.first()) <= Math.abs(hour.first() - quarter.first())
						&& Math.abs(half.first() - quarter.first()) <= 0.5);
		assertEquals(quarter.lastHour(), half.lastHour(), 0.02);
	}

	/**
	 * Rain on a cold pack: 3 kg m-2 in one hour on five cells at -2 °C. Each
	 * cell takes, top down, the rain its cold content refreezes, c_i m ΔT / L_f
	 * = 0.127 kg m-2, and its retention capacity, about 0.58 kg m-2; the front
	 * stops where the rain runs out, nothing reaches the soil.
	 */
	@Test
	public void rainOnAColdPackStopsWhereTheColdContentAndRetentionRunOut() {
		List<SnowCell> start = pack(5, MELTING_TEMPERATURE - 2.0);
		double perCellRefreeze = 10.0 * SPECIFIC_HEAT_ICE * 2.0 / LATENT_HEAT_FUSION;
		double perCellRetention = PERCOLATION.retentionCapacity(SnowCell.atTemperature(EOS, 10.0, 0.04, AT_MELTING));
		int reached = (int) Math.ceil(3.0 / (perCellRefreeze + perCellRetention));
		SnowSoilSolver solver = solver(start);
		SnowSoilStepResult r = solver.step(rain(3.0, 3600.0));
		assertEquals("nothing reaches the soil", 0.0, r.soilInputMass(), 1e-9);
		// the pack is restratified in the same step (the surface cell is split to the cap): the front is the
		// ice mass wetted from the top, against the cells of 10 kg m-2 reached by hand
		List<SnowCell> after = r.snow();
		double wetIce = 0.0;
		for (int i = after.size() - 1; i >= 0 && after.get(i).liquidMass() > 1e-6; i--) {
			wetIce += after.get(i).iceMass();
		}
		assertTrue("ice wetted " + wetIce + " kg m-2, cells reached by hand " + reached,
				wetIce > (reached - 1) * 10.0 && wetIce <= reached * 10.0 + 1.0);
		assertEquals("refrozen", (reached - 1) * perCellRefreeze, r.refrozen(), perCellRefreeze);
		assertEquals(0.0, r.budget().massClosure(), 1e-12);
		assertEquals(0.0, r.budget().energyClosure(), 1e-6);
	}

	/** Water crossing a merge or a split of cells in the step: the pack closes its budgets in every such step. */
	@Test
	public void waterAcrossRemapsIsConserved() {
		SyntheticSeason season = SyntheticSeason.standard();
		SnowSoilSolver solver = season.solver(Configuration.RECOMMENDED);
		int checked = 0;
		for (int k = 0; k < season.steps(); k++) {
			double[] soil = solver.soilTemperatures();
			double liquidBefore = solver.snow().stream().mapToDouble(SnowCell::liquidMass).sum();
			SnowSoilStepResult r = solver.step(season.forcing(k * season.timeDelta(), soil[soil.length - 1]));
			boolean water = liquidBefore > 0.0 || r.budget().rain() > 0.0 || r.melt() > 0.0;
			if (water && r.merges() + r.splits() > 0) {
				assertEquals("mass, step " + k, 0.0, r.budget().massClosure(), 1e-9);
				assertEquals("energy, step " + k, 0.0, r.budget().energyClosure(), 1e-3);
				checked++;
			}
		}
		assertTrue("steps with water and remaps " + checked, checked > 20);
	}
}
