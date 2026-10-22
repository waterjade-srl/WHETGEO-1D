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
package org.geoframe.whetgeo1d.solvers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.junit.Test;

/**
 * One test per boundary condition of COL-6 through the OMS3 component, on the
 * 2 m sandy loam of the synthetic-season case (20 cells of 10 cm, ψ = -1 m,
 * 5 °C), with constant meteorology: analytic profiles or budgets of energy and
 * water against the imposed boundary values, given as constants or as time
 * series.
 *
 * <p>
 * Already covered in {@link SnowSoilSolver1DMainTest}: bare-soil Dirichlet top
 * as a time series (with a missing value falling back to the constant) with a
 * constant bottom Dirichlet (linear steady profile), and a constant bottom
 * Neumann flux under an insulated top (energy gained).
 */
public class SnowSoilBoundaryConditionsTest {

	private static final double T0 = 273.15;
	private static final double DT = 3600.0;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
	private static final long START = LocalDateTime.of(2026, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);

	private static Map<Integer, double[]> series(double value) {
		Map<Integer, double[]> m = new HashMap<>();
		m.put(1, new double[] { value });
		return m;
	}

	/** Snow-free meteorology at 8 °C, no radiation balance needed (imposed LW and P). */
	private static SnowSoilSolver1DMain component(String soilWater) {
		SnowSoilSolver1DMain c = new SnowSoilSolver1DMain();
		c.configuration = "recommended";
		c.soilWaterMode = soilWater;
		c.stationID = 1;
		c.latitude = 46.5;
		c.longitude = 11.35;
		c.standardTime = 1.0;
		c.elevation = 1500.0;
		c.tTimeStep = DT;
		c.soilGridFile = SnowSoilSolver1DMainTest.CASE + "/grid";
		c.writers = List.of();
		c.inAirTemperature = series(8.0);
		c.inRelativeHumidity = series(70.0);
		c.inWindSpeed = series(2.0);
		c.inShortwaveGlobal = series(0.0);
		c.inLongwaveIn = series(300.0);
		c.inPressure = series(85000.0);
		c.inRainfall = series(0.0);
		c.inSnowfall = series(0.0);
		return c;
	}

	/** Runs hourly steps; {@code before} sets the inputs of step k (1-based) before it runs. */
	private static void run(SnowSoilSolver1DMain c, int steps, IntConsumer before, IntConsumer after)
			throws Exception {
		for (int k = 1; k <= steps; k++) {
			c.inCurrentDate = LocalDateTime.ofEpochSecond(START + 3600L * k, 0, ZoneOffset.UTC).format(DATE);
			before.accept(k);
			c.solve();
			after.accept(k);
		}
		c.close();
	}

	private static double totalEnergy(SnowSoilSolver1DMain c) {
		return SnowColumn.internalEnergy(c.solver().snow()) + c.solver().soilInternalEnergy();
	}

	/* ------------------------------------------------------------ heat, top */

	/** Bare-soil Neumann top as a time series over a no-gradient bottom: the soil gains Σ q Δt. */
	@Test
	public void bareSoilNeumannTopAndNoGradientBottom() throws Exception {
		SnowSoilSolver1DMain c = component("Fixed");
		c.bareSoilTopType = "Neumann";
		c.bottomBCType = "Bottom No Gradient";
		double[] u = new double[2];
		double[] imposed = { 0.0 };
		run(c, 240, k -> {
			double q = k <= 120 ? 20.0 : -10.0;
			c.inTopBC = series(q);
			if (k > 1) {
				imposed[0] += q * DT;
			}
		}, k -> {
			if (k == 1) {
				u[0] = c.solver().soilInternalEnergy();
			}
		});
		u[1] = c.solver().soilInternalEnergy();
		assertEquals(imposed[0], u[1] - u[0], 1e-6 * Math.abs(imposed[0]) + 1.0);
	}

	/**
	 * Bulk transfer h (T_a - T_top), implicit in the top temperature: every
	 * step the soil gains h (T_a - T_top) Δt with T_top at the end of the step.
	 */
	@Test
	public void bareSoilBulkTransferTop() throws Exception {
		SnowSoilSolver1DMain c = component("Fixed");
		c.bareSoilTopType = "Bulk Transfer";
		c.bareSoilTransfer = 10.0;
		c.bottomBCType = "Bottom No Gradient";
		double[] previous = new double[1];
		run(c, 120, k -> previous[0] = k == 1 ? Double.NaN : c.solver().soilInternalEnergy(), k -> {
			if (k > 1) {
				double[] t = c.outSoilTemperature;
				double expected = 10.0 * (T0 + 8.0 - t[t.length - 1]) * DT;
				assertEquals("step " + k, expected, c.solver().soilInternalEnergy() - previous[0],
						1e-6 * Math.abs(expected) + 0.1);
			}
		});
		// the soil, at 5 °C, warms towards the air at 8 °C
		double[] t = c.outSoilTemperature;
		assertTrue(t[t.length - 1] > T0 + 5.0 && t[t.length - 1] < T0 + 8.0);
	}

	/** Three hours of snowfall at -5 °C: the pack of the snow-top tests. */
	private static void snowfallFirstHours(SnowSoilSolver1DMain c, int k) {
		boolean snowing = k <= 3;
		c.inAirTemperature = series(snowing ? -5.0 : -2.0);
		c.inRelativeHumidity = series(snowing ? 95.0 : 70.0);
		c.inSnowfall = series(snowing ? 10.0 : 0.0);
	}

	/**
	 * Dirichlet over snow (COL-6): capped at 0 °C, so +5 °C gives bit for bit
	 * the run with 0 °C; a cold imposed temperature, here a time series, cools
	 * the top of the pack towards it.
	 */
	@Test
	public void snowTopDirichletIsCappedAtZero() throws Exception {
		double[][] results = new double[2][];
		double[] swe = new double[2];
		for (int r = 0; r < 2; r++) {
			SnowSoilSolver1DMain c = component("Fixed");
			c.bareSoilTopType = "Neumann";
			c.bareSoilTopValue = 0.0;
			c.bottomBCType = "Bottom No Gradient";
			c.snowTopType = "Dirichlet";
			c.snowTopValue = r == 0 ? 5.0 : 0.0;
			run(c, 48, k -> snowfallFirstHours(c, k), k -> {
			});
			results[r] = c.outSoilTemperature;
			swe[r] = c.outSnowWaterEquivalent;
			assertTrue("the pack holds", c.outSnowCells > 0);
			assertTrue("top cell at most 0 °C", c.outSurfaceTemperature <= T0);
		}
		assertArrayEquals(results[0], results[1], 0.0);
		assertEquals(swe[0], swe[1], 0.0);

		SnowSoilSolver1DMain cold = component("Fixed");
		cold.bareSoilTopType = "Neumann";
		cold.bareSoilTopValue = 0.0;
		cold.bottomBCType = "Bottom No Gradient";
		cold.snowTopType = "Dirichlet";
		run(cold, 72, k -> {
			snowfallFirstHours(cold, k);
			cold.inSnowTopBC = series(-15.0);
		}, k -> {
		});
		assertTrue("top cell " + (cold.outSurfaceTemperature - T0), cold.outSurfaceTemperature < T0 - 8.0);
		// no vapour exchange with an imposed top; only the base melts on the 5 °C soil
		assertTrue(cold.outSnowWaterEquivalent > 0.0 && cold.outSnowWaterEquivalent <= 30.0);
	}

	/**
	 * Neumann over snow (COL-6): a constant flux out of a cold pack, no melt and
	 * no vapour exchange; snow and soil together lose exactly q Δt, the bottom
	 * being insulated.
	 */
	@Test
	public void snowTopNeumannBudget() throws Exception {
		SnowSoilSolver1DMain c = component("Fixed");
		c.bareSoilTopType = "Neumann";
		c.bareSoilTopValue = 0.0;
		c.bottomBCType = "Bottom No Gradient";
		c.snowTopType = "Neumann";
		c.snowTopValue = -30.0;
		double[] e = new double[1];
		int steps = 72;
		run(c, steps, k -> snowfallFirstHours(c, k), k -> {
			if (k == 3) {
				e[0] = totalEnergy(c);
			}
		});
		assertTrue(c.outSnowCells > 0);
		assertEquals(30.0, c.outSnowWaterEquivalent, 1e-9);
		double expected = -30.0 * DT * (steps - 3);
		assertEquals(expected, totalEnergy(c) - e[0], 1e-6 * Math.abs(expected) + 1.0);
	}

	/* --------------------------------------------------------- heat, bottom */

	/** Bottom Neumann as a time series under an insulated top: the soil gains Σ q Δt. */
	@Test
	public void bottomNeumannTimeSeries() throws Exception {
		SnowSoilSolver1DMain c = component("Fixed");
		c.bareSoilTopType = "Neumann";
		c.bareSoilTopValue = 0.0;
		c.bottomBCType = "Bottom Neumann";
		double[] u = new double[1];
		double[] imposed = { 0.0 };
		run(c, 96, k -> {
			double q = 2.0 + (k % 24) * 0.5;
			c.inBottomBC = series(q);
			if (k > 1) {
				imposed[0] += q * DT;
			}
		}, k -> {
			if (k == 1) {
				u[0] = c.solver().soilInternalEnergy();
			}
		});
		assertEquals(imposed[0], c.solver().soilInternalEnergy() - u[0], 1e-6 * imposed[0] + 1.0);
	}

	/** Insulated top and no-gradient bottom: the energy of the soil does not change. */
	@Test
	public void bottomNoGradientConservesEnergy() throws Exception {
		SnowSoilSolver1DMain c = component("Fixed");
		c.bareSoilTopType = "Neumann";
		c.bareSoilTopValue = 0.0;
		c.bottomBCType = "Bottom No Gradient";
		double[] u = new double[1];
		run(c, 48, k -> {
		}, k -> {
			if (k == 1) {
				u[0] = c.solver().soilInternalEnergy();
			}
		});
		assertEquals(u[0], c.solver().soilInternalEnergy(), 1.0);
	}

	/** Bottom Dirichlet as a time series under an insulated top: the column goes to the bottom temperature. */
	@Test
	public void bottomDirichletTimeSeries() throws Exception {
		SnowSoilSolver1DMain c = component("Fixed");
		c.bareSoilTopType = "Neumann";
		c.bareSoilTopValue = 0.0;
		c.bottomBCType = "Bottom Dirichlet";
		run(c, 24 * 200, k -> c.inBottomBC = series(k <= 24 ? 10.0 : 3.0), k -> {
		});
		for (double t : c.outSoilTemperature) {
			assertEquals(T0 + 3.0, t, 0.05);
		}
	}

	/* ---------------------------------------------------- water, Richards bottom */

	private static double storage(SnowSoilSolver1DMain c) {
		return c.solver().soilWaterStorage();
	}

	/** Impervious bottom, no rain: the soil keeps its water, nothing drains. */
	@Test
	public void richardsBottomImpervious() throws Exception {
		SnowSoilSolver1DMain c = component("Richards");
		c.soilWaterBottomType = "Bottom Impervious";
		double[] s = new double[1];
		double[] drained = { 0.0 };
		run(c, 240, k -> {
		}, k -> {
			if (k == 1) {
				s[0] = storage(c);
			} else {
				drained[0] += c.outStepResult.soilWater().drainage();
			}
		});
		assertEquals(0.0, drained[0], 0.0);
		assertEquals(s[0], storage(c), 1e-6);
	}

	/** Free drainage, no rain: the soil loses exactly what drains, and something drains. */
	@Test
	public void richardsBottomFreeDrainage() throws Exception {
		SnowSoilSolver1DMain c = component("Richards");
		c.soilWaterBottomType = "Bottom Free Drainage";
		double[] s = new double[1];
		double[] drained = { 0.0 };
		run(c, 240, k -> {
		}, k -> {
			if (k == 1) {
				s[0] = storage(c);
			} else {
				drained[0] += c.outStepResult.soilWater().drainage();
			}
		});
		assertTrue("drainage " + drained[0], drained[0] > 0.1);
		assertEquals(-drained[0], storage(c) - s[0], 1e-5);
	}

	/**
	 * Bottom Neumann, a constant inflow q [m s-1] through the bottom face with
	 * nothing at the top: the soil gains 1000 q Δt per step.
	 */
	@Test
	public void richardsBottomNeumann() throws Exception {
		SnowSoilSolver1DMain c = component("Richards");
		c.soilWaterBottomType = "Bottom Neumann";
		c.soilWaterBottomValue = 2e-8;
		double[] s = new double[1];
		int steps = 240;
		run(c, steps, k -> {
		}, k -> {
			if (k == 1) {
				s[0] = storage(c);
			}
		});
		assertEquals(1000.0 * 2e-8 * DT * (steps - 1), storage(c) - s[0], 1e-5);
	}

	/**
	 * Bottom Dirichlet as a time series, a water table at the bottom face
	 * (ψ = 0 after a first day at -0.5 m), no flux at the top: the lower part
	 * of the column becomes hydrostatic, ψ = -z above the bottom face (the
	 * drier top equilibrates much more slowly), and the soil gains exactly
	 * what enters through the bottom.
	 */
	@Test
	public void richardsBottomDirichletTimeSeries() throws Exception {
		SnowSoilSolver1DMain c = component("Richards");
		c.soilWaterBottomType = "Bottom Dirichlet";
		double[] s = new double[1];
		double[] drained = { 0.0 };
		run(c, 24 * 150, k -> c.inSoilWaterBottomBC = series(k <= 24 ? -0.5 : 0.0), k -> {
			if (k == 1) {
				s[0] = storage(c);
			} else {
				drained[0] += c.outStepResult.soilWater().drainage();
			}
		});
		double[] psi = c.outSoilSuction;
		for (int i = 0; i < 5; i++) {
			double z = 0.05 + 0.1 * i;
			assertEquals("cell " + i, -z, psi[i], 0.02);
		}
		assertTrue("water enters from the table", drained[0] < 0.0);
		assertEquals(-drained[0], storage(c) - s[0], 1e-5);
	}
}
