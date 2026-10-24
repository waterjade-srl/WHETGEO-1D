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

import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Series;
import org.junit.Test;

/**
 * VAL-4, STA-2: convergence in the time step over the synthetic season
 * (accumulation, rain on snow, melt-out of every cell), steps of 3600, 1800 and
 * 900 s against a 450 s run. The forcing is the same function of time at every
 * step length, the precipitation the same per hour.
 * <p>
 * Measured (SWE RMSE on the hourly values): recommended 1.26 / 0.52 / 0.21 mm,
 * fidelity 15.5 / 3.95 / 0.94 mm. The fidelity error at hourly steps is
 * GEOtop's shortwave penetration (SNOW-7), absorbed in the surface layer while
 * the skin gets none: 2.9 mm without it.
 */
public class TimeStepConvergenceTest {

	private static final double[] STEPS = { 3600.0, 1800.0, 900.0 };
	private static final double REFERENCE = 450.0;

	/** SWE at the end of every hour [kg m-2]. */
	private static double[] hourly(Series s, double dt) {
		int perHour = (int) Math.round(3600.0 / dt);
		double[] h = new double[s.swe().length / perHour];
		for (int i = 0; i < h.length; i++) {
			h[i] = s.swe()[(i + 1) * perHour - 1];
		}
		return h;
	}

	/** Day of the melt-out: the first step without snow after the peak. */
	private static double meltOut(Series s) {
		int peak = 0;
		for (int k = 0; k < s.swe().length; k++) {
			if (s.swe()[k] > s.swe()[peak]) {
				peak = k;
			}
		}
		for (int k = peak; k < s.swe().length; k++) {
			if (s.swe()[k] == 0.0) {
				return s.time()[k] / SyntheticSeason.DAY;
			}
		}
		return Double.NaN;
	}

	private record Convergence(double[] errors, double[] meltOutErrors) {
	}

	private static Convergence convergence(Configuration configuration) {
		Series reference = new SyntheticSeason(150, REFERENCE).run(configuration);
		double[] exact = hourly(reference, REFERENCE);
		double exactMeltOut = meltOut(reference);
		double[] errors = new double[STEPS.length];
		double[] meltOut = new double[STEPS.length];
		for (int i = 0; i < STEPS.length; i++) {
			Series s = new SyntheticSeason(150, STEPS[i]).run(configuration);
			double[] swe = hourly(s, STEPS[i]);
			assertEquals(exact.length, swe.length);
			double sum = 0.0;
			for (int k = 0; k < exact.length; k++) {
				sum += (swe[k] - exact[k]) * (swe[k] - exact[k]);
			}
			errors[i] = Math.sqrt(sum / exact.length);
			meltOut[i] = Math.abs(meltOut(s) - exactMeltOut);
		}
		return new Convergence(errors, meltOut);
	}

	@Test
	public void recommendedConvergesAtFirstOrder() {
		Convergence c = convergence(Configuration.RECOMMENDED);
		String detail = java.util.Arrays.toString(c.errors()) + " " + java.util.Arrays.toString(c.meltOutErrors());
		assertTrue(detail, c.errors()[0] < 2.0);
		assertTrue(detail, c.errors()[0] > 2.0 * c.errors()[1] && c.errors()[1] > 2.0 * c.errors()[2]);
		assertTrue(detail, c.meltOutErrors()[0] < 1.0 && c.meltOutErrors()[2] < 0.1);
	}

	@Test
	public void fidelityConverges() {
		Convergence c = convergence(Configuration.FIDELITY);
		String detail = java.util.Arrays.toString(c.errors()) + " " + java.util.Arrays.toString(c.meltOutErrors());
		assertTrue(detail, c.errors()[0] > 2.0 * c.errors()[1] && c.errors()[1] > 2.0 * c.errors()[2]);
		assertTrue(detail, c.errors()[2] < 2.0 && c.meltOutErrors()[2] < 0.25);
	}
}
