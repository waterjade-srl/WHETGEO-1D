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
 * How the surface energy balance enters the heat step over snow.
 *
 * <p>
 * NestedNewtonThomas takes only a linear boundary row (the nonlinear boundary
 * term is the library change B2). The solver therefore passes the balance as a
 * flux linearised around a surface temperature T<sub>k</sub>,
 * G(T) = EB(T<sub>k</sub>) + EB'(T<sub>k</sub>) (T - T<sub>k</sub>), and can
 * repeat the heat step from the same start, linearising around the surface
 * temperature of the last solve, until it changes by less than
 * {@code temperatureTolerance}. At convergence G(T<sup>n+1</sup>) =
 * EB(T<sup>n+1</sup>): the balance is implicit in the surface temperature, as
 * GEOtop solves it, with the resistances still frozen at the start of the step.
 * Each sweep is Newton's step on the boundary term, so it converges
 * quadratically where EB is smooth; across GEOtop's jump of λ at 0 °C it may not
 * converge, and the last sweep is kept.
 *
 * @param maxSweeps            heat solves per step, at least 1; 1 is the
 *                             semi-implicit flux linearised at the start of the
 *                             step
 * @param temperatureTolerance change of the surface temperature between sweeps
 *                             below which the sweeps stop [K]
 */
public record SurfaceCoupling(int maxSweeps, double temperatureTolerance) {

	public SurfaceCoupling {
		if (maxSweeps < 1) {
			throw new IllegalArgumentException("maxSweeps must be at least 1, got " + maxSweeps);
		}
		if (!(temperatureTolerance > 0.0)) {
			throw new IllegalArgumentException("temperatureTolerance must be positive, got " + temperatureTolerance);
		}
	}

	/** One heat solve with the flux linearised at the start of the step. */
	public static SurfaceCoupling semiImplicit() {
		return new SurfaceCoupling(1, Double.POSITIVE_INFINITY);
	}

	/** Sweeps until the surface temperature moves by less than 10⁻⁶ K, at most 20. */
	public static SurfaceCoupling implicit() {
		return new SurfaceCoupling(20, 1e-6);
	}
}
