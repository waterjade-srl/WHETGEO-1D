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
 * Which Monin–Obukhov stability corrections are applied: GEOtop's
 * {@code MoninObukhov} keyword (131), {@code CZ} in {@code turbulence.cc:299-332}
 * (commit 1f1fc5af). The integrated profile of a variable measured at height z
 * over a roughness length z₀ (displacement d) is
 *
 * <pre>
 * c = ln((z - d)/z₀) - ψ_u(ζ) + ψ_u(z₀/L)     ζ = (z - d)/L &lt; 0   (unstable)
 * c = ln((z - d)/z₀) + ψ_s(ζ) - ψ_s(z₀/L)     ζ ≥ 0             (stable)
 * </pre>
 *
 * with, unstable (Businger–Dyer as written in GEOtop, x = (1 - 15 ζ)^¼):
 *
 * <pre>
 * ψ_m = 2 ln((1 + x)/2) + ln((1 + x²)/2) - 2 atan(x) + π/2      momentum
 * ψ_h = 2 ln((1 + x²)/2)                                       heat and vapour
 * </pre>
 *
 * and, stable, the same function for momentum, heat and vapour
 * (Holtslag and De Bruin 1988):
 *
 * <pre>
 * ψ_s = 10.71 + 0.7 ζ + 0.75 (ζ - 14.28) exp(-0.35 ζ)
 * </pre>
 *
 * A switched-off side uses ψ = 0. GEOtop 3.0 defaults to {@link #BOTH}; the
 * MobyGIS fork as ported in NivoGEO-1D defaults to {@link #UNSTABLE_ONLY}.
 */
public enum StabilityCorrection {

	/** Unstable and stable corrections, GEOtop option 1 (GEOtop default). */
	BOTH(true, true),

	/** Unstable correction only, GEOtop option 2 (NivoGEO-1D default). */
	UNSTABLE_ONLY(true, false),

	/** Stable correction only, GEOtop option 3. */
	STABLE_ONLY(false, true),

	/** Neutral profiles, GEOtop option 4. */
	NEUTRAL(false, false);

	private final boolean unstable;
	private final boolean stable;

	StabilityCorrection(boolean unstable, boolean stable) {
		this.unstable = unstable;
		this.stable = stable;
	}

	/** GEOtop's integer selector, 1 to 4. */
	public int geotopCode() {
		return ordinal() + 1;
	}

	/** The option with the given GEOtop selector. */
	public static StabilityCorrection ofGeotopCode(int code) {
		if (code < 1 || code > values().length) {
			throw new IllegalArgumentException("GEOtop MoninObukhov selector must be 1 to 4, got " + code);
		}
		return values()[code - 1];
	}

	/** Integrated profile for momentum, c_m. */
	public double momentumProfile(double height, double roughnessLength, double displacement, double obukhovLength) {
		return profile(height, roughnessLength, displacement, obukhovLength, true);
	}

	/** Integrated profile for heat and vapour, c_h. */
	public double scalarProfile(double height, double roughnessLength, double displacement, double obukhovLength) {
		return profile(height, roughnessLength, displacement, obukhovLength, false);
	}

	private double profile(double height, double roughnessLength, double displacement, double obukhovLength,
			boolean momentum) {
		double zeta = (height - displacement) / obukhovLength;
		double zeta0 = roughnessLength / obukhovLength;
		double neutral = Math.log((height - displacement) / roughnessLength);
		if (zeta < 0.0) {
			if (!unstable) {
				return neutral;
			}
			return momentum ? neutral - psiMomentumUnstable(zeta) + psiMomentumUnstable(zeta0)
					: neutral - psiScalarUnstable(zeta) + psiScalarUnstable(zeta0);
		}
		if (!stable) {
			return neutral;
		}
		return neutral + psiStable(zeta) - psiStable(zeta0);
	}

	/** Businger–Dyer ψ_m, ζ &lt; 0. */
	public static double psiMomentumUnstable(double zeta) {
		double x = Math.pow(1.0 - 15.0 * zeta, 0.25);
		return 2.0 * Math.log((1.0 + x) / 2.0) + Math.log((1.0 + x * x) / 2.0) - 2.0 * Math.atan(x) + 0.5 * Math.PI;
	}

	/** Businger–Dyer ψ_h, ζ &lt; 0. */
	public static double psiScalarUnstable(double zeta) {
		double x = Math.pow(1.0 - 15.0 * zeta, 0.25);
		return 2.0 * Math.log((1.0 + x * x) / 2.0);
	}

	/** Holtslag–De Bruin ψ, ζ ≥ 0, for momentum and scalars. */
	public static double psiStable(double zeta) {
		return 10.71 + 0.7 * zeta + 0.75 * (zeta - 14.28) * Math.exp(-0.35 * zeta);
	}
}
