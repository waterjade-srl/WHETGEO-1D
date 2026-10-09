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

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition;

/**
 * Top flux with its derivative, frozen over the step: the top row of
 * {@code DiffusionTopBoundaryConditionNeumann} plus the implicit part
 * -Δt · dG/dT on the main diagonal. The kernel passes the boundary value bC
 * to {@link #rightHandSide}; the caller sets it to G<sup>n</sup> - dG/dT ·
 * T<sup>n</sup>, so that the row reads
 *
 * <pre>
 * U(T) + kM Δt/Δz (T - T_below) = U^n + Δt [G^n + dG/dT (T - T^n)]
 * </pre>
 *
 * With dG/dT = 0 it is the Neumann condition of WHETGEO.
 */
final class LinearizedFluxTopBoundaryCondition implements IBoundaryCondition {

	private final double fluxDerivative;

	/** @param fluxDerivative dG/dT [W m-2 K-1], not positive */
	LinearizedFluxTopBoundaryCondition(double fluxDerivative) {
		this.fluxDerivative = fluxDerivative;
	}

	@Override
	public double upperDiagonal(double bC, double kP, double kM, double spaceDeltaP, double spaceDeltaM,
			double timeDelta) {
		return 0.0;
	}

	@Override
	public double mainDiagonal(double bC, double kP, double kM, double spaceDeltaP, double spaceDeltaM,
			double timeDelta) {
		return kM * timeDelta / spaceDeltaM - timeDelta * fluxDerivative;
	}

	@Override
	public double lowerDiagonal(double bC, double kP, double kM, double spaceDeltaP, double spaceDeltaM,
			double timeDelta) {
		return -kM * timeDelta / spaceDeltaM;
	}

	@Override
	public double rightHandSide(double bC, double kP, double kM, double spaceDeltaP, double spaceDeltaM,
			double timeDelta) {
		return timeDelta * bC;
	}
}
