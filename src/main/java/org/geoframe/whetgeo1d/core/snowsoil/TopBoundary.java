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
 * Condition at the top of the snow+soil column for one heat step. Phase 1 has
 * the two linear options only; the nonlinear skin boundary (specification
 * §3.5, A17) needs a change to NestedNewtonThomas and is not here.
 */
public sealed interface TopBoundary {

	/**
	 * Imposed surface temperature (Dirichlet) at the top face of the column.
	 *
	 * @param temperature [K]
	 */
	record SurfaceTemperature(double temperature) implements TopBoundary {
		public SurfaceTemperature {
			if (!(temperature > 0.0) || Double.isInfinite(temperature)) {
				throw new IllegalArgumentException("temperature must be positive and finite [K], got " + temperature);
			}
		}
	}

	/**
	 * Imposed flux into the column, linearised around the temperature of the top
	 * cell at the start of the step and frozen over the step:
	 * G(T) = flux + fluxDerivative · (T - T<sup>n</sup>).
	 *
	 * @param flux           G at T<sup>n</sup>, positive into the column [W m-2]
	 * @param fluxDerivative dG/dT [W m-2 K-1], not positive (a surface that warms
	 *                       loses more heat), so that the top row stays
	 *                       diagonally dominant and the solution unique
	 */
	record SurfaceFlux(double flux, double fluxDerivative) implements TopBoundary {
		public SurfaceFlux {
			if (!Double.isFinite(flux)) {
				throw new IllegalArgumentException("flux must be finite, got " + flux);
			}
			if (!(fluxDerivative <= 0.0) || Double.isInfinite(fluxDerivative)) {
				throw new IllegalArgumentException("fluxDerivative must be finite and <= 0, got " + fluxDerivative);
			}
		}

		/** Flux with no dependence on the surface temperature. */
		public static SurfaceFlux constant(double flux) {
			return new SurfaceFlux(flux, 0.0);
		}
	}
}
