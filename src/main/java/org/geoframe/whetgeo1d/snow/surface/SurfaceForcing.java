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
 * Meteorological forcing of a step at the surface, SI units. Shortwave and
 * longwave radiation are inputs (specification §6.2: radiation is computed
 * outside the library, by GEOframe's radiation components or by the fidelity
 * path).
 *
 * @param shortwaveDirect  direct-beam shortwave on the surface [W m-2]
 * @param shortwaveDiffuse diffuse shortwave on the surface [W m-2]
 * @param cosIncidence     cosine of the incidence angle of the beam on the
 *                         surface, in [0, 1] (any value when there is no beam)
 * @param longwaveIn       incoming longwave [W m-2]
 * @param airTemperature   T_a [K]
 * @param relativeHumidity RH, over water, in [0, 1]
 * @param windSpeed        wind speed at the measurement height [m s-1]
 * @param pressure         P [Pa]
 * @param skyViewFactor    V, the fraction of the sky the surface sees, in
 *                         (0, 1]: the surface emits ε V σ T⁴ to the sky, the
 *                         rest being exchanged with terrain at its own
 *                         temperature, as GEOtop ({@code energy.balance.cc:2466},
 *                         {@code sky}); 1 on flat ground
 */
public record SurfaceForcing(double shortwaveDirect, double shortwaveDiffuse, double cosIncidence,
		double longwaveIn, double airTemperature, double relativeHumidity, double windSpeed, double pressure,
		double skyViewFactor) {

	/** On flat ground, V = 1. */
	public SurfaceForcing(double shortwaveDirect, double shortwaveDiffuse, double cosIncidence, double longwaveIn,
			double airTemperature, double relativeHumidity, double windSpeed, double pressure) {
		this(shortwaveDirect, shortwaveDiffuse, cosIncidence, longwaveIn, airTemperature, relativeHumidity,
				windSpeed, pressure, 1.0);
	}

	public SurfaceForcing {
		requireNonNegative(shortwaveDirect, "shortwaveDirect");
		requireNonNegative(shortwaveDiffuse, "shortwaveDiffuse");
		requireNonNegative(longwaveIn, "longwaveIn");
		requireNonNegative(windSpeed, "windSpeed");
		if (!(cosIncidence >= 0.0 && cosIncidence <= 1.0)) {
			throw new IllegalArgumentException("cosIncidence must be in [0, 1], got " + cosIncidence);
		}
		if (!(airTemperature > 0.0) || Double.isInfinite(airTemperature)) {
			throw new IllegalArgumentException("airTemperature must be positive (K) and finite, got " + airTemperature);
		}
		if (!(relativeHumidity >= 0.0 && relativeHumidity <= 1.0)) {
			throw new IllegalArgumentException("relativeHumidity must be in [0, 1], got " + relativeHumidity);
		}
		if (!(pressure > 0.0) || Double.isInfinite(pressure)) {
			throw new IllegalArgumentException("pressure must be positive (Pa) and finite, got " + pressure);
		}
		if (!(skyViewFactor > 0.0 && skyViewFactor <= 1.0)) {
			throw new IllegalArgumentException("skyViewFactor must be in (0, 1], got " + skyViewFactor);
		}
	}

	/** Diffuse-only forcing with no beam, e.g. at night. */
	public static SurfaceForcing withoutBeam(double shortwaveDiffuse, double longwaveIn, double airTemperature,
			double relativeHumidity, double windSpeed, double pressure) {
		return new SurfaceForcing(0.0, shortwaveDiffuse, 0.0, longwaveIn, airTemperature, relativeHumidity, windSpeed,
				pressure);
	}

	/** Specific humidity of the air, with GEOtop's conversion [kg kg-1]. */
	public double airHumidity() {
		return AirProperties.specificHumidityFromRelative(airTemperature, relativeHumidity, pressure);
	}

	private static void requireNonNegative(double value, String name) {
		if (!(value >= 0.0) || Double.isInfinite(value)) {
			throw new IllegalArgumentException(name + " must be non-negative and finite, got " + value);
		}
	}
}
