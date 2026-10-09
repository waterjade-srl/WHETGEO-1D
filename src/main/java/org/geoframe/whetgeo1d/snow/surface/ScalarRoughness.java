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
package org.geoframe.whetgeo1d.snow.surface;

/**
 * Where the roughness lengths for heat and vapour, z₀ₜ and z₀ᵩ, come from.
 */
public enum ScalarRoughness {

	/**
	 * Andreas (1987), GEOtop's "rigid surface" ({@code z0_z0t = 0}), always used
	 * over snow ({@code update_roughness_soil}, {@code energy.balance.cc:2836-2853}):
	 * ln(z₀ₓ/z₀) = b₀ + b₁ ln R* + b₂ (ln R*)², R* = u* z₀ / ν, with ν = 1.4·10⁻⁵
	 * m² s⁻¹ and the coefficients of {@code roughT}, {@code roughQ}
	 * ({@code turbulence.cc:357-418}).
	 */
	ANDREAS,

	/** A fixed ratio z₀/z₀ₜ = z₀/z₀ᵩ, GEOtop's "bending surface" ({@code z0_z0t > 0}). */
	FIXED_RATIO;

	/** Kinematic viscosity of air in the roughness Reynolds number [m2 s-1]. */
	public static final double KINEMATIC_VISCOSITY = 1.4e-5;

	private static final double[][] HEAT = { { 1.250, 0.0, 0.0 }, { 0.149, -0.550, 0.0 }, { 0.317, -0.565, -0.183 } };
	private static final double[][] VAPOUR = { { 1.610, 0.0, 0.0 }, { 0.351, -0.628, 0.0 }, { 0.396, -0.512, -0.180 } };

	/** z₀ₜ/z₀ of Andreas (1987) at roughness Reynolds number R*. */
	public static double andreasHeatRatio(double reynolds) {
		return andreas(reynolds, HEAT);
	}

	/** z₀ᵩ/z₀ of Andreas (1987) at roughness Reynolds number R*. */
	public static double andreasVapourRatio(double reynolds) {
		return andreas(reynolds, VAPOUR);
	}

	private static double andreas(double reynolds, double[][] coefficients) {
		double[] b = reynolds <= 0.135 ? coefficients[0] : reynolds < 2.5 ? coefficients[1] : coefficients[2];
		double ln = Math.log(reynolds);
		return Math.exp(b[0] + b[1] * ln + b[2] * ln * ln);
	}
}
