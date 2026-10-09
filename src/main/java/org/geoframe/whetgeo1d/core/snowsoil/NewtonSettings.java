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

/**
 * Iteration settings of the heat step.
 *
 * <p>
 * NestedNewtonThomas stops when the Euclidean norm of the residual drops below
 * an absolute tolerance [J m-2]. Near 0 °C a snow cell cannot be resolved
 * below κ ε |x| dU/dT (≈ 0,1 J m-2 per ulp of T for 80 kg m-2, document 002),
 * so the driver passes the larger of {@code absoluteTolerance} and that
 * round-off floor, summed in quadrature over the snow cells. After the solve it
 * checks every cell with the criterion of specification §3.6,
 * |F<sub>i</sub>| ≤ τ + κ ε (S<sub>i</sub> + |∂F<sub>i</sub>/∂x<sub>i</sub>| |x<sub>i</sub>|).
 * With no snow the tolerance is exactly {@code absoluteTolerance}, as in
 * WHETGEO's soil solvers.
 *
 * @param absoluteTolerance τ [J m-2]
 * @param roundOffSafety    κ [-]
 * @param maxIterations     cap of both the outer and the inner Newton loops
 * @param picardIterations  solves per step with conductivities refreshed from
 *                          the last temperatures (1 = semi-implicit, as
 *                          WHETGEO)
 */
public record NewtonSettings(double absoluteTolerance, double roundOffSafety, int maxIterations,
		int picardIterations) {

	public NewtonSettings {
		if (!(absoluteTolerance > 0.0) || Double.isInfinite(absoluteTolerance)) {
			throw new IllegalArgumentException("absoluteTolerance must be positive, got " + absoluteTolerance);
		}
		if (!(roundOffSafety >= 0.0) || Double.isInfinite(roundOffSafety)) {
			throw new IllegalArgumentException("roundOffSafety must be >= 0, got " + roundOffSafety);
		}
		if (maxIterations < 1 || picardIterations < 1) {
			throw new IllegalArgumentException("iteration counts must be >= 1");
		}
	}

	/** τ = 10⁻³ J m-2, κ = 64 (specification §3.6), 50 iterations, 1 Picard. */
	public static NewtonSettings defaults() {
		return new NewtonSettings(1e-3, 64.0, 50, 1);
	}

	/** Same settings with another absolute tolerance. */
	public NewtonSettings withAbsoluteTolerance(double tolerance) {
		return new NewtonSettings(tolerance, roundOffSafety, maxIterations, picardIterations);
	}

	/** Same settings with another number of Picard iterations. */
	public NewtonSettings withPicardIterations(int picard) {
		return new NewtonSettings(absoluteTolerance, roundOffSafety, maxIterations, picard);
	}
}
