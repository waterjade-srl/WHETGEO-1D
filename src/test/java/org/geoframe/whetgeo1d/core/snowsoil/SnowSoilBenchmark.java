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
import static org.junit.Assume.assumeTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.LayeredSoil;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.junit.Test;

/**
 * Speed of the snow+soil column (plan of the prototype, 1.4: years simulated
 * per minute, to decide the permafrost spin-up of phase 2). Not part of the
 * normal test run:
 *
 * <pre>
 * mvn -o test -Dtest=SnowSoilBenchmark -Dsnowsoil.benchmark=true
 * </pre>
 *
 * Every case runs the synthetic season of document 009 (150 days, hourly; snow
 * on the ground about 120 of them) through the {@link StepController}, after a
 * warm-up of the JIT on the same case, and prints the wall time and the years
 * simulated per minute. Cases: the 20-cell, 2 m column of the season (heat
 * only, soil water, frozen soil), and an ARF_1D-like column, the 50 layers of
 * GEOtop's {@code tests/1D/ARF_1D/soil/SoilARF0001.txt} (18 × 1 cm of peat, then
 * 32 layers growing from 2.8 to 58.6 cm, 10 m), frozen soil, bottom at
 * -4.8 °C as in that case. A season is mostly snow-covered: a year with a snow
 * free summer runs faster.
 */
public class SnowSoilBenchmark {

	private static final SyntheticSeason SEASON = SyntheticSeason.standard();
	private static final double ARF_BOTTOM = MELTING_TEMPERATURE - 4.8;

	@Test
	public void yearsPerMinute() {
		assumeTrue("set -Dsnowsoil.benchmark=true to run", Boolean.getBoolean("snowsoil.benchmark"));
		List<String> lines = new ArrayList<>();
		lines.add(String.format(Locale.ROOT, "%-34s %6s %10s %8s %7s %9s", "case", "cells", "season [s]", "yr/min",
				"sweeps", "rejected"));
		for (Configuration c : Configuration.values()) {
			lines.add(time(c + " heat only", () -> SEASON.solver(c), SEASON.bottomTemperature()));
			lines.add(time(c + " soil water", () -> SEASON.solverWithSoilWater(c), SEASON.bottomTemperature()));
			lines.add(time(c + " frozen soil", () -> SEASON.solverWithFrozenSoil(c), SEASON.bottomTemperature()));
			lines.add(time(c + " ARF_1D-like frozen", () -> arf(c), ARF_BOTTOM));
		}
		lines.add(plainOverhead());
		System.out.println("snow+soil benchmark, " + Runtime.getRuntime().availableProcessors() + " cpus, java "
				+ System.getProperty("java.version"));
		lines.forEach(System.out::println);
	}

	private static String time(String name, Supplier<SnowSoilSolver> factory, double bottom) {
		run(new StepController(factory.get()), bottom, 30);
		SnowSoilSolver solver = factory.get();
		long start = System.nanoTime();
		StepController controller = new StepController(solver);
		long sweeps = run(controller, bottom, SEASON.days());
		double seconds = (System.nanoTime() - start) * 1e-9;
		double years = SEASON.days() / 365.25;
		return String.format(Locale.ROOT, "%-34s %6d %10.2f %8.2f %7.2f %9d", name, solver.soilTemperatures().length,
				seconds, years / (seconds / 60.0), sweeps / (double) SEASON.steps(),
				controller.diagnostics().rejections());
	}

	/** The controller against the bare solver, frozen soil, recommended. */
	private static String plainOverhead() {
		SnowSoilSolver warm = SEASON.solverWithFrozenSoil(Configuration.RECOMMENDED);
		for (int k = 0; k < 720; k++) {
			warm.step(forcing(warm, k * SEASON.timeDelta(), SEASON.bottomTemperature()));
		}
		SnowSoilSolver solver = SEASON.solverWithFrozenSoil(Configuration.RECOMMENDED);
		long start = System.nanoTime();
		for (int k = 0; k < SEASON.steps(); k++) {
			solver.step(forcing(solver, k * SEASON.timeDelta(), SEASON.bottomTemperature()));
		}
		double seconds = (System.nanoTime() - start) * 1e-9;
		return String.format(Locale.ROOT, "%-34s %6d %10.2f %8.2f", "RECOMMENDED frozen, no controller",
				solver.soilTemperatures().length, seconds, SEASON.days() / 365.25 / (seconds / 60.0));
	}

	/** Runs {@code days} of the season; returns the surface sweeps done. */
	private static long run(StepController controller, double bottom, double days) {
		SnowSoilSolver solver = controller.solver();
		int steps = (int) Math.round(days * SyntheticSeason.DAY / SEASON.timeDelta());
		long sweeps = 0;
		for (int k = 0; k < steps; k++) {
			sweeps += controller.advance(forcing(solver, k * SEASON.timeDelta(), bottom)).result().surfaceSweeps();
		}
		return sweeps;
	}

	private static StepForcing forcing(SnowSoilSolver solver, double time, double bottom) {
		double[] soil = solver.soilTemperatures();
		StepForcing f = SEASON.forcing(time, soil[soil.length - 1]);
		return new StepForcing(f.surface(), f.snowfall(), f.rainfall(), f.groundAlbedoVisible(),
				f.groundAlbedoNearInfrared(), f.bareSoilTop(), bottom, f.timeDelta(), f.soilWaterSinks());
	}

	/** The soil of GEOtop's ARF_1D, frozen soil at -4.8 °C and ψ = -1 m, under the processes of the season. */
	private static SnowSoilSolver arf(Configuration configuration) {
		double[] thickness = new double[50];
		int[] ids = new int[50];
		for (int i = 0; i < 18; i++) {
			thickness[i] = 0.01;
		}
		for (int i = 18; i < 50; i++) {
			thickness[i] = 1e-3 * (27.9924242424242 + (i - 18) * 17.9924242424242);
			ids[i] = 1;
		}
		LayeredSoil soil = new LayeredSoil(thickness, ids,
				List.of(new LayeredSoil.Layer(1.7e-4, 0.04, 0.84, 12.7, 1.2, 1e-4, 0.25, 2.6e6),
						new LayeredSoil.Layer(1.2e-5, 0.065, 0.41, 7.5, 1.89, 1e-4, 1.62, 2e6)),
				-1.0, ARF_BOTTOM);
		boolean fidelity = configuration == Configuration.FIDELITY;
		SnowEquationOfState eos = new SnowEquationOfState(SyntheticSeason.ALPHA);
		SnowProcesses processes = fidelity ? SnowProcesses.geotop(eos, 2.0, 2.0)
				: SnowProcesses.recommended(eos, 2.0, 2.0);
		SnowHeatProperties heat = new SnowHeatProperties(eos,
				fidelity ? SnowThermalConductivity.COSENZA : SnowThermalConductivity.JORDAN_1991, fidelity ? 10 : 11);
		return new SnowSoilSolver(soil.freezingSetup(), SoilWaterProperties.vanGenuchtenFreeDrainage(), heat,
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(), processes, List.of(), 0.0);
	}
}
