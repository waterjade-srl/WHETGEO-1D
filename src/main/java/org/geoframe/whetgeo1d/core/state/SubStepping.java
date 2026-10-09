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
package org.geoframe.whetgeo1d.core.state;

/**
 * Computes the sequence of integration sub-steps covering one forcing interval.
 * The last sub-step is clipped so the sequence sums exactly to the interval,
 * without mutating the caller's base time step (see PIANO.md T0.3).
 */
public final class SubStepping {

	private SubStepping() {
	}

	public static double[] subStepLengths(double tTimeStep, double baseDt) {
		if (!(tTimeStep > 0.0)) {
			throw new IllegalArgumentException("tTimeStep must be positive, got " + tTimeStep);
		}
		if (!(baseDt > 0.0)) {
			throw new IllegalArgumentException("baseDt must be positive, got " + baseDt);
		}

		int n = (int) Math.ceil(tTimeStep / baseDt - 1e-9);
		if (n < 1) {
			n = 1;
		}

		double[] steps = new double[n];
		double remaining = tTimeStep;
		for (int i = 0; i < n; i++) {
			steps[i] = Math.min(baseDt, remaining);
			remaining -= steps[i];
		}
		return steps;
	}
}
