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

import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.snow.MassCriterionRestratification;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.TopCellThicknessCap;
import org.junit.Assume;
import org.junit.Test;

/**
 * SNOW-7: the shortwave penetration schemes compared over the synthetic season
 * in the recommended configuration. For each scheme: SWE error at hourly
 * values against a 450 s run of the same scheme (VAL-4), melt-out error,
 * surface iterations not converged, the contrast between the top cell and the
 * skin in sunny hours, energy closure, and the SWE peak and melt-out of the
 * 450 s run. Prints a table; the assertions only guard against broken runs.
 * Run with {@code -Dtest=ShortwaveBandsExperimentTest -Dexperiment}; {@code -Dschemes=NONE,VISIBLE}
 * restricts the schemes, {@code -Dskin=NONE} and {@code -DtopCell=0.01} change the skin
 * and the cap on the surface cell.
 */
public class ShortwaveBandsExperimentTest {

	private static final double[] STEPS = { 3600.0, 1800.0, 900.0 };
	private static final double REFERENCE = 450.0;
	private static final double DAYS = 150.0;
	private static final SnowEquationOfState EOS = new SnowEquationOfState(SyntheticSeason.ALPHA);

	private record Run(double[] hourlySwe, double meltOutDay, double peakSwe, int surfaceNotConverged,
			double meanContrast, double maxContrast, double maxEnergyClosure) {
	}

	private static Run run(ShortwavePenetration penetration, double dt) {
		SyntheticSeason season = new SyntheticSeason(DAYS, dt);
		SkinNode skin = SkinNode.valueOf(System.getProperty("skin", "CAPPED"));
		double topCell = Double.parseDouble(System.getProperty("topCell", "0.02"));
		SnowSoilSolver solver = season.solver(Configuration.RECOMMENDED,
				p -> p.withShortwavePenetration(penetration).withSkin(skin)
						.withRestratification(new TopCellThicknessCap(
								new MassCriterionRestratification(
										MassCriterionRestratification.Parameters.geotopDefaults(), EOS),
								topCell, EOS)));
		int n = season.steps();
		int perHour = (int) Math.round(3600.0 / dt);
		double[] swe = new double[n];
		double[] hourly = new double[n / perHour];
		int notConverged = 0;
		double contrastSum = 0.0;
		int contrastCount = 0;
		double maxContrast = 0.0;
		double maxClosure = 0.0;
		for (int k = 0; k < n; k++) {
			double time = k * dt;
			double[] soil = solver.soilTemperatures();
			StepForcing forcing = season.forcing(time, soil[soil.length - 1]);
			SnowSoilStepResult r = solver.step(forcing);
			swe[k] = r.snowWaterEquivalent();
			if ((k + 1) % perHour == 0) {
				hourly[(k + 1) / perHour - 1] = swe[k];
			}
			if (!r.surfaceConverged()) {
				notConverged++;
			}
			maxClosure = Math.max(maxClosure, Math.abs(r.budget().energyClosure()));
			boolean sunny = forcing.surface().shortwaveDirect() + forcing.surface().shortwaveDiffuse() > 200.0;
			if (sunny && r.snow().size() > 1 && Double.isFinite(r.surfaceTemperature())) {
				double contrast = EOS.temperature(r.snow().get(r.snow().size() - 1)) - r.surfaceTemperature();
				contrastSum += contrast;
				contrastCount++;
				maxContrast = Math.max(maxContrast, contrast);
			}
		}
		int peak = 0;
		for (int k = 0; k < n; k++) {
			if (swe[k] > swe[peak]) {
				peak = k;
			}
		}
		double meltOut = Double.NaN;
		for (int k = peak; k < n; k++) {
			if (swe[k] == 0.0) {
				meltOut = (k + 1) * dt / SyntheticSeason.DAY;
				break;
			}
		}
		return new Run(hourly, meltOut, swe[peak], notConverged,
				contrastCount == 0 ? 0.0 : contrastSum / contrastCount, maxContrast, maxClosure);
	}

	private static double rmse(double[] a, double[] b) {
		double sum = 0.0;
		for (int k = 0; k < a.length; k++) {
			sum += (a[k] - b[k]) * (a[k] - b[k]);
		}
		return Math.sqrt(sum / a.length);
	}

	@Test
	public void compareSchemes() {
		Assume.assumeTrue("an experiment: run with -Dexperiment", System.getProperty("experiment") != null);
		List<String> rows = new ArrayList<>();
		rows.add(String.format(Locale.ROOT, "%-17s %8s %8s %8s %8s %7s %7s %8s %8s %9s %8s", "scheme", "rmse3600",
				"rmse1800", "rmse900", "dMelt1h", "noConv", "dT_mean", "dT_max", "peakSWE", "meltOut", "maxClos"));
		String only = System.getProperty("schemes", "");
		List<ShortwavePenetration> schemes = only.isBlank()
				? List.of(ShortwavePenetration.NONE, ShortwavePenetration.COMPOUNDED, ShortwavePenetration.VISIBLE)
				: java.util.Arrays.stream(only.split(",")).map(String::trim).map(ShortwavePenetration::valueOf).toList();
		for (ShortwavePenetration p : schemes) {
			Run reference = run(p, REFERENCE);
			double[] errors = new double[STEPS.length];
			Run hourly = null;
			for (int i = 0; i < STEPS.length; i++) {
				Run r = run(p, STEPS[i]);
				if (i == 0) {
					hourly = r;
				}
				errors[i] = rmse(r.hourlySwe(), reference.hourlySwe());
			}
			assertTrue(p + " melts out", Double.isFinite(reference.meltOutDay()));
			rows.add(String.format(Locale.ROOT, "%-17s %8.3f %8.3f %8.3f %8.3f %7d %7.2f %8.2f %8.1f %9.3f %8.1e",
					p.name(), errors[0], errors[1], errors[2],
					Math.abs(hourly.meltOutDay() - reference.meltOutDay()), hourly.surfaceNotConverged(),
					hourly.meanContrast(), hourly.maxContrast(), reference.peakSwe(), reference.meltOutDay(),
					hourly.maxEnergyClosure()));
		}
		System.out.println("SNOW-7 shortwave bands, synthetic season, recommended configuration, skin "
				+ System.getProperty("skin", "CAPPED") + ", top cell " + System.getProperty("topCell", "0.02") + " m");
		System.out.println("rmse: SWE [kg m-2] at hourly values against 450 s; dMelt1h: melt-out error at 3600 s [d];");
		System.out.println("noConv, dT (top cell - skin, sunny hours, K), maxClos [J m-2]: at 3600 s; peakSWE, meltOut: 450 s");
		rows.forEach(System.out::println);
	}
}
