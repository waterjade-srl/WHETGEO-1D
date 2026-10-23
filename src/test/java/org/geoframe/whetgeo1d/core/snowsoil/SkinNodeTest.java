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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver.SurfaceMode;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver.SurfaceSolution;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.junit.Test;

/**
 * The skin node of the surface balance (SURF-1/2, {@link SkinNode}): on by
 * default in both configurations, budgets closed, the 0 °C rule over snow of
 * each configuration, and the elimination equal to a solve with an explicit
 * skin node.
 */
public class SkinNodeTest {

	private static final SyntheticSeason SEASON = SyntheticSeason.standard();

	/** The defaults: GEOtop's skin in fidelity, the skin held at 0 °C in recommended. */
	@Test
	public void skinIsOnByDefault() {
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		assertEquals(SkinNode.GEOTOP, SnowProcesses.geotop(eos, 2.0, 2.0).skin());
		assertEquals(SkinNode.CAPPED, SnowProcesses.recommended(eos, 2.0, 2.0).skin());
		assertEquals(SkinNode.NONE, SnowProcesses.geotop(eos, 2.0, 2.0).withSkin(SkinNode.NONE).skin());
	}

	/**
	 * Over the synthetic season, in both configurations: every step closes the
	 * pack budgets and converges; over snow the skin never rises above 0 °C,
	 * the skin is colder than the top cell on cold nights, and the melt season
	 * meets the 0 °C rule of the configuration: re-solved without the skin in
	 * fidelity, the skin held at exactly 0 °C in recommended.
	 */
	@Test
	public void budgetsCloseAndTheZeroDegreeRuleHolds() {
		for (Configuration configuration : Configuration.values()) {
			SnowSoilSolver solver = SEASON.solver(configuration);
			Map<SurfaceMode, Integer> modes = new EnumMap<>(SurfaceMode.class);
			int nights = 0;
			int colderNights = 0;
			for (int k = 0; k < SEASON.steps(); k++) {
				double[] soil = solver.soilTemperatures();
				SnowSoilStepResult r = solver.step(SEASON.forcing(k * SEASON.timeDelta(), soil[soil.length - 1]));
				SurfaceSolution s = solver.lastSurface();
				assertNotNull(s);
				modes.merge(s.mode(), 1, Integer::sum);
				String at = configuration + " step " + k;
				assertEquals(at, 0.0, r.budget().massClosure(), 1e-11);
				assertEquals(at, 0.0, r.budget().energyClosure(), 1e-6);
				assertTrue(at, r.heat().newton().converged());
				assertTrue(at, r.surfaceConverged());
				// GEOtop's λ switches from sublimation to evaporation at 0 °C: a top cell converged onto the switch
				// leaves the jump of λE (< 1 W m-2) as linearisation energy, recorded in the balance
				boolean onTheSwitch = Math.abs(r.surfaceTemperature() - MELTING_TEMPERATURE) < 1e-5;
				assertEquals(at, 0.0, r.linearisationEnergy(), onTheSwitch ? SEASON.timeDelta() : 1e-1);
				if (r.surface() != null) {
					if (s.mode() == SurfaceMode.SKIN || s.mode() == SurfaceMode.SKIN_AT_ZERO) {
						// the skin over snow never above 0 °C (a top cell that melts out may be, STA-5)
						assertTrue(at + " Ts " + r.surfaceTemperature(), r.surfaceTemperature() <= MELTING_TEMPERATURE);
					}
					if (s.mode() == SurfaceMode.SKIN_AT_ZERO) {
						assertEquals(at, MELTING_TEMPERATURE, r.surfaceTemperature(), 0.0);
					}
					double hour = (k + 1) % 24;
					if (k < 80 * 24 && (hour < 5 || hour > 21) && s.mode() == SurfaceMode.SKIN) {
						nights++;
						if (s.surfaceTemperature() < s.topCellTemperature()) {
							colderNights++;
						}
					}
				}
			}
			assertTrue(configuration + " skin colder in " + colderNights + " of " + nights + " nights",
					colderNights > 0.9 * nights && nights > 100);
			assertTrue(configuration + " " + modes, modes.getOrDefault(SurfaceMode.SKIN, 0) > 1000);
			if (configuration == Configuration.FIDELITY) {
				assertTrue(modes.toString(), modes.getOrDefault(SurfaceMode.SKIN_ABOVE_ZERO_RESOLVED, 0) > 0);
				assertEquals(modes.toString(), 0, (int) modes.getOrDefault(SurfaceMode.SKIN_AT_ZERO, 0));
			} else {
				assertTrue(modes.toString(), modes.getOrDefault(SurfaceMode.SKIN_AT_ZERO, 0) > 0);
				assertEquals(modes.toString(), 0, (int) modes.getOrDefault(SurfaceMode.SKIN_ABOVE_ZERO_RESOLVED, 0));
			}
		}
	}

	/** At convergence the eliminated skin satisfies its own equation, EB(T_s) = K (T_s - T_top). */
	@Test
	public void skinEquationHoldsAtConvergence() {
		SnowSoilSolver solver = SEASON.solver(Configuration.FIDELITY);
		int checked = 0;
		for (int k = 0; k < 75 * 24; k++) {
			double[] soil = solver.soilTemperatures();
			solver.step(SEASON.forcing(k * SEASON.timeDelta(), soil[soil.length - 1]));
			SurfaceSolution s = solver.lastSurface();
			if (s.mode() == SurfaceMode.SKIN && s.overSnow()) {
				double conducted = s.conductance() * (s.surfaceTemperature() - s.topCellTemperature());
				// the sweeps stop at 1e-6 K: the balance moves by |EB'| 1e-6 at most
				assertEquals("step " + k, s.fluxes().energyBalance(), conducted,
						1e-6 * Math.abs(s.fluxes().derivative()) * 10 + 1e-9);
				checked++;
			}
		}
		assertTrue(checked > 1000);
	}

	/**
	 * The elimination equals a solve with an explicit skin node. On a soil
	 * column without snow, a nonlinear balance (radiation in, σT⁴ and a
	 * sensible heat out) enters through the skin; the explicit solve finds the
	 * skin temperature T_s whose imposed surface temperature makes the
	 * conductive flux into the column equal EB(T_s) (secant on a heat driver
	 * with the same soil). Skin and column agree to the sweep tolerance.
	 */
	@Test
	public void eliminationEqualsAnExplicitSkinNode() {
		UniformSoil soil = new UniformSoil(20, 2.0, -0.5, MELTING_TEMPERATURE + 5.0, "Weighted Harmonic Mean");
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		SnowHeatProperties snow = new SnowHeatProperties(eos, SnowThermalConductivity.COSENZA, 10);
		SnowProcesses processes = SnowProcesses.geotop(eos, 2.0, 2.0)
				.withSurfaceCoupling(new SurfaceCoupling(50, 1e-10));
		SnowSoilSolver solver = new SnowSoilSolver(soil.setup(), snow, DiffusionBoundaryConditionType.BOTTOM_DIRICHLET,
				NewtonSettings.defaults(), processes, List.of(), 0.0);
		SnowSoilHeatDriver explicit = new SnowSoilHeatDriver(soil.setup(), snow,
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		double airTemperature = MELTING_TEMPERATURE - 10.0;
		for (double absorbed : new double[] { 250.0, 600.0 }) {
			TopBoundary.Balance balance = t -> {
				double h = 15.0;
				double longwave = 0.97 * (280.0 - AirProperties.emissivePower(t));
				double eb = absorbed + longwave - h * (t - airTemperature);
				double slope = -0.97 * AirProperties.dEmissivePower(t) - h;
				return new SurfaceFluxes(absorbed, longwave, h * (t - airTemperature), 0.0, 0.0, 0.0, eb, slope);
			};
			double[] start = solver.soilTemperatures();
			explicit.setSoilTemperatures(start);
			SurfaceForcing surface = new SurfaceForcing(0.0, 0.0, 0.0, 280.0, airTemperature, 0.8, 2.0, 85000.0);
			solver.step(new StepForcing(surface, 0.0, 0.0, 0.2, 0.2, new TopBoundary.SurfaceBalance(balance),
					MELTING_TEMPERATURE + 4.0, 3600.0));
			SurfaceSolution eliminated = solver.lastSurface();
			assertEquals(SurfaceMode.SKIN, eliminated.mode());

			// explicit skin: secant on T_s, the column under a Dirichlet top at T_s
			double t0 = start[start.length - 1];
			double t1 = t0 + 1.0;
			double f0 = explicitResidual(explicit, start, balance, t0);
			double f1 = explicitResidual(explicit, start, balance, t1);
			for (int i = 0; i < 50 && Math.abs(t1 - t0) > 1e-12; i++) {
				double t2 = t1 - f1 * (t1 - t0) / (f1 - f0);
				t0 = t1;
				f0 = f1;
				t1 = t2;
				f1 = explicitResidual(explicit, start, balance, t1);
			}
			double[] column = explicit.soilTemperatures();
			assertEquals("skin, absorbed " + absorbed, t1, eliminated.surfaceTemperature(), 1e-6);
			assertEquals("top cell, absorbed " + absorbed, column[column.length - 1], eliminated.topCellTemperature(),
					1e-6);
			// the next step from the explicit state
			explicit.setSoilTemperatures(solver.soilTemperatures());
		}
	}

	/** EB(T_s) minus the flux into the column with the surface held at T_s. */
	private static double explicitResidual(SnowSoilHeatDriver driver, double[] start, TopBoundary.Balance balance,
			double skin) {
		driver.setSoilTemperatures(start);
		HeatStepResult step = driver.step(List.of(), new TopBoundary.SurfaceTemperature(skin),
				MELTING_TEMPERATURE + 4.0, 3600.0);
		return balance.evaluate(skin).energyBalance() - step.surfaceFlux();
	}
}
