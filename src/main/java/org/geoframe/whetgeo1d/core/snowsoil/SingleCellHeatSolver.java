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

import org.geoframe.closureequation.equationstate.EquationState;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition;

/**
 * The heat step of a column with one active cell, which neither
 * NestedNewtonThomas nor Diffusion1DKernel can take: the solver reads
 * {@code x[KMAX-2]} = {@code x[-1]} (NestedNewtonThomas.java:143) and the
 * kernel overwrites the bottom row with the top row (Diffusion1DKernel.java:151-171).
 *
 * <p>
 * The single row is assembled with the same boundary-condition objects as the
 * kernel, giving each one only its own face (kP = 0 for the bottom one, kM = 0
 * for the top one):
 *
 * <pre>
 * U(T) + a T = b,   a = a_bottom + a_top ≥ 0,   b = U^n + b_bottom + b_top
 * </pre>
 *
 * U is strictly increasing, so the root is unique: Newton safeguarded by
 * bisection on an expanding bracket.
 */
final class SingleCellHeatSolver {

	private static final int MAX_ITERATIONS = 200;
	private static final double EPSILON = Math.ulp(1.0);
	private static final double MAX_UNBRACKETED_STEP = 20.0;

	private int iterations;

	/** Newton iterations of the last solve. */
	int iterations() {
		return iterations;
	}

	/**
	 * @return the temperature of the cell at the end of the step [K]
	 */
	double solve(EquationState closure, double suction, int id, double oldEnergy, double startTemperature,
			IBoundaryCondition bottom, double bottomValue, double bottomConductivity, double bottomDistance,
			IBoundaryCondition top, double topValue, double topConductivity, double topDistance, double timeDelta,
			double absoluteTolerance, double roundOffSafety) {

		double a = bottom.mainDiagonal(bottomValue, 0.0, bottomConductivity, 1.0, bottomDistance, timeDelta)
				+ top.mainDiagonal(topValue, topConductivity, 0.0, topDistance, 1.0, timeDelta);
		double b = oldEnergy
				+ bottom.rightHandSide(bottomValue, 0.0, bottomConductivity, 1.0, bottomDistance, timeDelta)
				+ top.rightHandSide(topValue, topConductivity, 0.0, topDistance, 1.0, timeDelta);

		double t = startTemperature;
		double low = Double.NaN;
		double high = Double.NaN;
		iterations = 0;
		for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
			iterations = iteration;
			double u = closure.equationState(t, suction, id, 0);
			double residual = u + a * t - b;
			double slope = closure.dEquationState(t, suction, id, 0) + a;
			double bound = absoluteTolerance
					+ roundOffSafety * EPSILON * (Math.abs(u) + Math.abs(a * t) + Math.abs(b) + slope * Math.abs(t));
			if (Math.abs(residual) <= bound) {
				return t;
			}
			if (residual > 0.0) {
				high = Double.isNaN(high) ? t : Math.min(high, t);
			} else {
				low = Double.isNaN(low) ? t : Math.max(low, t);
			}
			double next = t - residual / slope;
			boolean bracketed = !Double.isNaN(low) && !Double.isNaN(high);
			if (bracketed && !(next > low && next < high)) {
				next = 0.5 * (low + high);
			} else if (!bracketed) {
				// until the root is bracketed, steps are capped: a Newton step from the
				// liquid side of the freezing curve could otherwise leave the physical range
				next = t + Math.max(-MAX_UNBRACKETED_STEP, Math.min(MAX_UNBRACKETED_STEP, next - t));
			}
			if (bracketed && (next == low || next == high)) {
				return t;
			}
			t = next;
		}
		return t;
	}
}
