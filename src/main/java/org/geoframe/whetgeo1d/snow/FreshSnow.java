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
package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;

/**
 * Fresh snow: its density and the cell it makes before it is deposited with
 * {@link SnowColumn#depositFreshSnow}.
 *
 * <p>
 * Density: port of {@code rho_newlyfallensnow} of GEOtop 3.0
 * ({@code snow.cc:55-80}, commit 1f1fc5af), after Pomeroy and Gray (1995) as
 * written in GEOtop, with T the air temperature in °C and u the wind speed:
 *
 * <pre>
 * T &gt; -13 °C:  ρ = 500 (1 - 0.951 exp(-1.4 (5 - min(T, 2.5))^-1.15 - 0.008 u^1.7))
 * otherwise:   ρ = 500 (1 - 0.904 exp(-0.008 u^1.7))
 * </pre>
 *
 * GEOtop deposits the snowfall P with a thickness P ρ_w / ρ in mm, that is P / ρ
 * in metres with P in kg m-2.
 *
 * <p>
 * The fresh snow is all ice. Its temperature is the caller's choice
 * (specification A18): {@link #geotopTemperature} reproduces GEOtop's
 * {@code new_snow}, min(T_a, -0.1 °C); the recommended configuration uses
 * min(T_wet-bulb, 0 °C).
 */
public final class FreshSnow {

	/** Upper bound GEOtop puts on the fresh-snow temperature [K] (-0.1 °C). */
	public static final double GEOTOP_WARMEST_TEMPERATURE = MELTING_TEMPERATURE - 0.1;

	private FreshSnow() {
	}

	/**
	 * Density of newly fallen snow, GEOtop {@code rho_newlyfallensnow} [kg m-3].
	 *
	 * @param airTemperature [K]
	 * @param windSpeed      [m s-1]
	 */
	public static double geotopDensity(double airTemperature, double windSpeed) {
		if (!(airTemperature > 0.0) || Double.isInfinite(airTemperature)) {
			throw new IllegalArgumentException("airTemperature must be positive (K) and finite, got " + airTemperature);
		}
		if (!(windSpeed >= 0.0) || Double.isInfinite(windSpeed)) {
			throw new IllegalArgumentException("windSpeed must be non-negative and finite, got " + windSpeed);
		}
		double celsius = airTemperature - MELTING_TEMPERATURE;
		double wind = 0.008 * Math.pow(windSpeed, 1.7);
		if (celsius > 260.15 - MELTING_TEMPERATURE) {
			double t = Math.min(celsius, 275.65 - MELTING_TEMPERATURE);
			return 500.0 * (1.0 - 0.951 * Math.exp(-1.4 * Math.pow(278.15 - MELTING_TEMPERATURE - t, -1.15) - wind));
		}
		return 500.0 * (1.0 - 0.904 * Math.exp(-wind));
	}

	/** GEOtop's fresh-snow temperature, min(T_a, -0.1 °C) [K]. */
	public static double geotopTemperature(double airTemperature) {
		return Math.min(airTemperature, GEOTOP_WARMEST_TEMPERATURE);
	}

	/**
	 * A cell of fresh snow: all ice, at the given temperature.
	 *
	 * @param mass        [kg m-2], positive
	 * @param density     [kg m-3], positive
	 * @param temperature [K], not above the melting temperature
	 */
	public static SnowCell cell(double mass, double density, double temperature) {
		if (!(mass > 0.0) || Double.isInfinite(mass)) {
			throw new IllegalArgumentException("mass must be positive and finite, got " + mass);
		}
		if (!(density > 0.0) || Double.isInfinite(density)) {
			throw new IllegalArgumentException("density must be positive and finite, got " + density);
		}
		if (!(temperature > 0.0 && temperature <= MELTING_TEMPERATURE)) {
			throw new IllegalArgumentException("fresh snow must be at or below the melting temperature, got "
					+ temperature);
		}
		return new SnowCell(mass, 0.0, mass / density, mass * SPECIFIC_HEAT_ICE * (temperature - MELTING_TEMPERATURE));
	}
}
