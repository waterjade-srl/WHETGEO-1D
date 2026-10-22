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
package org.geoframe.whetgeo1d.core.snowsoil.forcing;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The averaged cloud transmissivity that enters GEOtop's longwave (LINK-1).
 *
 * <p>
 * <b>GEOtop's series</b> ({@link #geotopSeries}): GEOtop 3.0 (commit 1f1fc5af)
 * fills the τ<sub>c</sub> column of the meteorological input before the run
 * ({@code cloudiness}, {@code clouds.cc:80-181}). Each daylight period, from
 * the first to the last record with the sun at least 12° above a flat horizon
 * ({@code find_sunset}, {@code clouds.cc:323-366}; {@code shadows_point},
 * {@code radiation.cc:757-826}, {@code Tol_h_flat = 12}), is divided into
 * {@code ndivday} parts (keyword {@code ndivdaycloud}, default 3,
 * {@code parameters.cc:2152}); every record of a part gets the mean of the
 * step transmissivities of the part ({@code average_cloudiness},
 * {@code clouds.cc:286-313}, undefined if any is undefined). Through the night
 * the value is interpolated linearly from the last part of the previous day to
 * the first part of the day. It needs the records ahead of the current one,
 * so it is a whole-series computation, for preparing the forcing.
 *
 * <p>
 * <b>Causal mean</b> ({@link Running}): the OMS3 component receives the
 * forcing one step at a time and cannot look ahead. It uses the mean of the
 * step transmissivities of the daylight records (sun at least 12° high) of
 * the last 24 hours, held through the night. It is a declared approximation of
 * GEOtop's series: same step values and same daylight rule, a trailing window
 * instead of the centred parts. Where GEOtop's value is wanted, compute it
 * with {@link #geotopSeries} and give it to the component as an input.
 *
 * <p>
 * GEOtop clamps the average to [0, 1] when it uses it
 * ({@code find_actual_cloudiness}, {@code radiation.cc:1065-1160}); both forms
 * here do the same.
 */
public final class DailyCloudiness {

	/** Solar height above which a record is daylight for the averages [rad] (12°). */
	public static final double DAYLIGHT_HEIGHT = Math.toRadians(12.0);
	/** GEOtop's default number of daylight parts. */
	public static final int GEOTOP_DIVISIONS = 3;

	private DailyCloudiness() {
	}

	/**
	 * GEOtop's averaged cloud transmissivity of a whole series.
	 *
	 * @param stepTransmissivity τ<sub>c</sub> of each record, NaN where undefined
	 * @param daylight           whether the sun is at least 12° high at each
	 *                           record
	 * @param divisions          parts of the daylight period
	 * @return the averaged τ<sub>c</sub> of each record in [0, 1], NaN where GEOtop
	 *         has none
	 */
	public static double[] geotopSeries(double[] stepTransmissivity, boolean[] daylight, int divisions) {
		int lines = stepTransmissivity.length;
		if (daylight.length != lines || divisions < 1) {
			throw new IllegalArgumentException("series of different length or no division");
		}
		double[] result = new double[lines];
		java.util.Arrays.fill(result, Double.NaN);
		if (lines == 0) {
			return result;
		}
		long[] ndiv = new long[divisions + 1];
		int n00 = 0;
		double tc = Double.NaN;
		do {
			double tc0 = tc;
			int[] sunset = findSunset(n00, daylight);
			int n0 = sunset[0];
			int n1 = sunset[1];
			ndiv[0] = n0;
			for (int k = 1; k <= divisions - 1; k++) {
				ndiv[k] = (long) (n0 + k * (n1 - n0) / (double) divisions);
			}
			ndiv[divisions] = n1;
			for (int k = 1; k <= divisions; k++) {
				tc = average((int) ndiv[k - 1], (int) ndiv[k], stepTransmissivity);
				if (k == 1) {
					for (int n = n00; n < n0; n++) {
						result[n] = !Double.isNaN(tc0) && !Double.isNaN(tc) ? tc0 + (tc - tc0) * (n - n00) / (n0 - n00)
								: Double.NaN;
					}
				}
				for (int n = (int) ndiv[k - 1]; n < ndiv[k]; n++) {
					result[n] = tc;
				}
			}
			n00 = n1;
		} while (n00 < lines - 1);
		result[n00] = daylight[n00] ? stepTransmissivity[n00] : Double.NaN;
		for (int n = 0; n < lines; n++) {
			if (!Double.isNaN(result[n])) {
				result[n] = Math.max(0.0, Math.min(1.0, result[n]));
			}
		}
		return result;
	}

	/** {@code find_sunset}: first daylight record n0 and the record n1 after the sunset, from nist. */
	private static int[] findSunset(int nist, boolean[] daylight) {
		boolean shadow = true;
		int n0 = -1;
		int n = nist;
		boolean set = false;
		do {
			boolean shadow0 = shadow;
			shadow = !daylight[n];
			if (shadow0 && !shadow) {
				n0 = n;
			}
			if (!shadow0 && shadow) {
				set = true;
			}
			n++;
		} while (!set && n < daylight.length);
		int n1 = n - 1;
		if (n0 == -1) {
			n0 = n1;
		}
		return new int[] { n0, n1 };
	}

	private static double average(int n0, int n1, double[] values) {
		if (n0 == n1) {
			return Double.NaN;
		}
		double sum = 0.0;
		for (int n = n0; n < n1; n++) {
			if (Double.isNaN(values[n])) {
				return Double.NaN;
			}
			sum += values[n] / (n1 - n0);
		}
		return sum;
	}

	/**
	 * The causal mean of the component: daylight step transmissivities of the
	 * last 24 hours, held through the night. Mutable, one per column.
	 */
	public static final class Running {

		private static final double WINDOW = 86400.0;
		private final Deque<double[]> window = new ArrayDeque<>();
		private double sum;
		private double held = Double.NaN;

		/**
		 * Adds the record of a step and returns the averaged τ<sub>c</sub>.
		 *
		 * @param time               end of the step [s], increasing
		 * @param stepTransmissivity τ<sub>c</sub> of the step, NaN where undefined
		 * @param daylight           the sun is at least 12° high
		 * @return the mean in [0, 1], NaN before the first daylight record
		 */
		public double add(double time, double stepTransmissivity, boolean daylight) {
			while (!window.isEmpty() && window.peekFirst()[0] <= time - WINDOW) {
				sum -= window.removeFirst()[1];
			}
			if (daylight && !Double.isNaN(stepTransmissivity)) {
				window.addLast(new double[] { time, stepTransmissivity });
				sum += stepTransmissivity;
			}
			if (!window.isEmpty()) {
				held = Math.max(0.0, Math.min(1.0, sum / window.size()));
			}
			return held;
		}

		/** Last averaged value, NaN before the first daylight record. */
		public double value() {
			return held;
		}

		/**
		 * The whole state, records, running sum and held value, as raw doubles:
		 * {held, sum, t₁, τ₁, t₂, τ₂, …}. The sum is kept as accumulated, not
		 * recomputed, so that a run carried on from it is bitwise the continuous
		 * one (ARCH-5).
		 */
		public double[] state() {
			double[] s = new double[2 + 2 * window.size()];
			s[0] = held;
			s[1] = sum;
			int k = 2;
			for (double[] r : window) {
				s[k++] = r[0];
				s[k++] = r[1];
			}
			return s;
		}

		/** A running mean in the state given by {@link #state()}. */
		public static Running of(double[] state) {
			if (state.length < 2 || state.length % 2 != 0) {
				throw new IllegalArgumentException("not a state of the running cloudiness: " + state.length + " values");
			}
			Running r = new Running();
			r.held = state[0];
			r.sum = state[1];
			for (int k = 2; k < state.length; k += 2) {
				r.window.addLast(new double[] { state[k], state[k + 1] });
			}
			return r;
		}
	}
}
