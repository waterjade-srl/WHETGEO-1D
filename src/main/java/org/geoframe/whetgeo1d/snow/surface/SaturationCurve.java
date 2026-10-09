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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

/**
 * Saturation vapour pressure e_sat(T, P) and its derivative, Buck (1981)
 * formulas with the pressure enhancement factor:
 *
 * <pre>
 * water: e_sat = 611.21 (1.0007 + 3.46·10⁻⁸ P) exp(17.502 t / (240.97 + t))
 * ice:   e_sat = 611.15 (1.0003 + 4.18·10⁻⁸ P) exp(22.452 t / (272.55 + t))
 * </pre>
 *
 * with t = T - 273.15 [°C] and P [Pa].
 *
 * <p>
 * GEOtop 3.0 ({@code SatVapPressure}, {@code meteo.cc:181-200}) uses the water
 * formula everywhere, also over snow at any temperature: that is
 * {@link #WATER}. {@link #ICE_BELOW_MELTING} uses the ice formula below the
 * melting temperature, about 9 % lower at -10 °C, 18 % at -20 °C, and is
 * continuous to 0.01 % at 0 °C.
 */
public enum SaturationCurve {

	/** Over water at any temperature, as GEOtop. */
	WATER,

	/** Over ice below the melting temperature, over water above it. */
	ICE_BELOW_MELTING;

	/** e_sat [Pa] at temperature T [K] and pressure P [Pa]. */
	public double pressure(double temperature, double pressure) {
		double t = temperature - MELTING_TEMPERATURE;
		if (overIce(t)) {
			return iceFactor(pressure) * Math.exp(22.452 * t / (272.55 + t));
		}
		return waterFactor(pressure) * Math.exp(17.502 * t / (240.97 + t));
	}

	/** de_sat/dT [Pa K-1]. */
	public double derivative(double temperature, double pressure) {
		double t = temperature - MELTING_TEMPERATURE;
		if (overIce(t)) {
			double c = 272.55 + t;
			return pressure(temperature, pressure) * 22.452 * 272.55 / (c * c);
		}
		double c = 240.97 + t;
		return pressure(temperature, pressure) * 17.502 * 240.97 / (c * c);
	}

	private boolean overIce(double celsius) {
		return this == ICE_BELOW_MELTING && celsius < 0.0;
	}

	private static double waterFactor(double pressure) {
		return 611.21 * (1.0007 + 3.46e-8 * pressure);
	}

	private static double iceFactor(double pressure) {
		return 611.15 * (1.0003 + 4.18e-8 * pressure);
	}
}
