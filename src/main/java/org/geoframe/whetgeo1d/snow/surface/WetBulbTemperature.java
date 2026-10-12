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
 * Wet-bulb temperature of the air, the temperature of precipitation in the
 * recommended configuration (specification A18: rain at max(T_wb, 0 °C), fresh
 * snow at min(T_wb, 0 °C)). Root of the psychrometric balance of a wet surface
 * in equilibrium with the air:
 *
 * <pre>
 * c_p(T_a) (T_a - T_wb) = L_e(T_wb) (q_sat(T_wb) - q_a)
 * </pre>
 *
 * with q_sat over water (the hydrometeor surface is wet), q_a from the relative
 * humidity as GEOtop converts it, c_p and L_e of {@link AirProperties}. The left
 * side decreases and the right side increases with T_wb, so the root is unique
 * and lies in [T_a - 60 K, T_a]; it is found by bisection to round-off. At
 * RH = 1 it is T_a.
 */
public final class WetBulbTemperature {

	private static final double SEARCH_RANGE = 60.0;
	private static final int MAX_ITERATIONS = 200;

	private WetBulbTemperature() {
	}

	/**
	 * @param airTemperature   T_a [K]
	 * @param relativeHumidity RH over water, in [0, 1]
	 * @param pressure         P [Pa]
	 * @return T_wb [K]
	 */
	public static double of(double airTemperature, double relativeHumidity, double pressure) {
		if (!(airTemperature > SEARCH_RANGE) || Double.isInfinite(airTemperature)) {
			throw new IllegalArgumentException("airTemperature must be a physical temperature in K, got "
					+ airTemperature);
		}
		if (!(relativeHumidity >= 0.0 && relativeHumidity <= 1.0)) {
			throw new IllegalArgumentException("relativeHumidity must be in [0, 1], got " + relativeHumidity);
		}
		if (!(pressure > 0.0) || Double.isInfinite(pressure)) {
			throw new IllegalArgumentException("pressure must be positive (Pa) and finite, got " + pressure);
		}
		double airHumidity = AirProperties.specificHumidityFromRelative(airTemperature, relativeHumidity, pressure);
		double specificHeat = AirProperties.specificHeat(airTemperature);
		double high = airTemperature;
		double low = airTemperature - SEARCH_RANGE;
		if (!(balance(high, airTemperature, airHumidity, specificHeat, pressure) < 0.0)) {
			return airTemperature;
		}
		for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
			double middle = 0.5 * (low + high);
			if (middle == low || middle == high) {
				break;
			}
			if (balance(middle, airTemperature, airHumidity, specificHeat, pressure) > 0.0) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return 0.5 * (low + high);
	}

	/** Sensible heat given minus latent heat taken, per unit mass of air; decreasing in T_wb. */
	private static double balance(double wetBulb, double airTemperature, double airHumidity, double specificHeat,
			double pressure) {
		double saturated = AirProperties.specificHumidity(SaturationCurve.WATER.pressure(wetBulb, pressure), pressure);
		return specificHeat * (airTemperature - wetBulb)
				- AirProperties.latentHeatOfVaporisation(wetBulb) * (saturated - airHumidity);
	}
}
