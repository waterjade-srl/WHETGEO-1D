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

import java.util.Objects;

import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;

/**
 * Condition at the top of the snow+soil column for one heat step. The heat
 * driver takes the two linear options; a nonlinear surface balance
 * ({@link SurfaceBalance}, the bare soil without snow) is coupled by the
 * {@link SnowSoilSolver}, through the skin node or at the top cell
 * ({@link SkinNode}), into a linear flux of the driver.
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

	/**
	 * A surface energy balance at the top of the bare soil, nonlinear in the
	 * surface temperature, with its resistances frozen over the step (SOL-2):
	 * the {@link SnowSoilSolver} couples it like the balance over snow, through
	 * the skin node of {@link SnowProcesses#skin()} (no 0 °C rule on soil) or at
	 * the top cell. Not taken by the heat driver directly.
	 *
	 * @param balance EB(T) and EB'(T), positive into the column
	 */
	record SurfaceBalance(Balance balance) implements TopBoundary {
		public SurfaceBalance {
			Objects.requireNonNull(balance, "balance");
		}
	}

	/** EB(T) [W m-2] and dEB/dT ≤ 0 of a surface at temperature T [K], as {@link SurfaceFluxes}. */
	@FunctionalInterface
	interface Balance {
		SurfaceFluxes evaluate(double temperature);
	}
}
