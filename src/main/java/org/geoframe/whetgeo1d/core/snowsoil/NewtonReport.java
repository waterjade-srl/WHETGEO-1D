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
 * How the nonlinear solve of one heat step went. NestedNewtonThomas does not
 * report any of this (numerical 1.0.4): the counts come from
 * {@link IterationCountingEquationState} and the convergence flag from the
 * per-cell check the driver does after the solve.
 *
 * @param outerIterations    outer residual evaluations, summed over the Picard
 *                           solves and the polish, the final checks included (1
 *                           per solve = the initial guess was already converged)
 * @param innerIterations    inner iterations, summed likewise
 * @param hitIterationCap    the outer loop ran up to its cap: the library gave
 *                           up silently
 * @param toleranceUsed      absolute tolerance passed to NestedNewtonThomas
 *                           [J m-2], raised above the round-off floor of the
 *                           snow cells
 * @param worstResidualRatio max over the cells of |F<sub>i</sub>| divided by the
 *                           bound τ + κ ε (S<sub>i</sub> + |∂F<sub>i</sub>/∂x<sub>i</sub>| |x<sub>i</sub>|)
 * @param converged          every cell within its bound (worstResidualRatio ≤ 1)
 * @param polished           the per-cell check failed after the solve and a few
 *                           warm-started iterations with zero tolerance were
 *                           added (see {@link SnowSoilHeatDriver})
 * @param singleCell         the column had one active cell and was solved by
 *                           {@link SingleCellHeatSolver}
 */
public record NewtonReport(int outerIterations, int innerIterations, boolean hitIterationCap, double toleranceUsed,
		double worstResidualRatio, boolean converged, boolean polished, boolean singleCell) {
}
