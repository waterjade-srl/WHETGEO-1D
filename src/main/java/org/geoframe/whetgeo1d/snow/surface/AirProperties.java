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
 * Thermodynamic properties of moist air used by the surface energy balance,
 * SI units (K, Pa). Port of {@code meteo.cc:181-262}, {@code turbulence.cc:512-524}
 * and {@code radiation.cc:554-574} of GEOtop 3.0 (commit 1f1fc5af), where
 * temperatures are in °C and pressures in mbar.
 *
 * <p>
 * Saturation vapour pressure, see {@link SaturationCurve}. Specific humidity
 * and its derivative with respect to the vapour pressure:
 *
 * <pre>
 * q     = 0.622 e / (P - 0.378 e)
 * dq/de = 0.622 / (P - 0.378 e) + 0.622 · 0.378 e / (P - 0.378 e)²
 * </pre>
 *
 * Air density and specific heat, as GEOtop writes them:
 *
 * <pre>
 * ρ_a = P / (287.04 T) · (1 - 0.378 q / (0.622 + 0.368 q))
 * c_p = 1005 + (T - 250)² / 3364                              (Garratt 1992)
 * </pre>
 *
 * The 0.368 in the density is GEOtop's (the exact virtual-temperature
 * correction has 0.378 in both places); it is kept, the difference is below
 * 10⁻⁵ relative for any atmospheric q.
 */
public final class AirProperties {

	/** Stefan–Boltzmann constant as in GEOtop [W m-2 K-4]. */
	public static final double STEFAN_BOLTZMANN = 5.67e-8;

	/** Gas constant of dry air as in GEOtop [J kg-1 K-1]. */
	public static final double DRY_AIR_GAS_CONSTANT = 287.04;

	/** Ratio of the molar masses of water and dry air, ε [-]. */
	public static final double MOLAR_MASS_RATIO = 0.622;

	/** Latent heat of vaporisation at and below the melting temperature [J kg-1]. */
	public static final double LATENT_HEAT_VAPORISATION = 2.501e6;

	/** Latent heat of vaporisation at 40 °C, end point of GEOtop's linear law [J kg-1]. */
	private static final double LATENT_HEAT_VAPORISATION_40C = 2.406e6;

	private AirProperties() {
	}

	/** Specific humidity from vapour pressure e and pressure P [kg kg-1]. */
	public static double specificHumidity(double vapourPressure, double pressure) {
		return MOLAR_MASS_RATIO * vapourPressure / (pressure - (1.0 - MOLAR_MASS_RATIO) * vapourPressure);
	}

	/** dq/de [Pa-1]. */
	public static double dSpecificHumidity(double vapourPressure, double pressure) {
		double denominator = pressure - (1.0 - MOLAR_MASS_RATIO) * vapourPressure;
		return MOLAR_MASS_RATIO / denominator
				+ MOLAR_MASS_RATIO * (1.0 - MOLAR_MASS_RATIO) * vapourPressure / (denominator * denominator);
	}

	/** Vapour pressure from specific humidity [Pa], inverse of {@link #specificHumidity}. */
	public static double vapourPressure(double specificHumidity, double pressure) {
		return specificHumidity * pressure / ((1.0 - MOLAR_MASS_RATIO) * specificHumidity + MOLAR_MASS_RATIO);
	}

	/**
	 * Density of moist air [kg m-3], GEOtop {@code air_density}.
	 *
	 * @param temperature      air temperature [K]
	 * @param specificHumidity [kg kg-1]
	 * @param pressure         [Pa]
	 */
	public static double density(double temperature, double specificHumidity, double pressure) {
		return pressure / (DRY_AIR_GAS_CONSTANT * temperature)
				* (1.0 - (1.0 - MOLAR_MASS_RATIO) * specificHumidity / (MOLAR_MASS_RATIO + 0.368 * specificHumidity));
	}

	/** Specific heat of air at constant pressure [J kg-1 K-1], GEOtop {@code air_cp}. */
	public static double specificHeat(double temperature) {
		double shifted = temperature - MELTING_TEMPERATURE + 23.15;
		return 1005.0 + shifted * shifted / 3364.0;
	}

	/**
	 * Latent heat of vaporisation [J kg-1], GEOtop {@code Levap}: constant at and
	 * below the melting temperature, decreasing linearly above it.
	 */
	public static double latentHeatOfVaporisation(double temperature) {
		double celsius = temperature - MELTING_TEMPERATURE;
		if (celsius > 0.0) {
			return LATENT_HEAT_VAPORISATION + (LATENT_HEAT_VAPORISATION_40C - LATENT_HEAT_VAPORISATION) / 40.0 * celsius;
		}
		return LATENT_HEAT_VAPORISATION;
	}

	/** Black-body emissive power σ T⁴ [W m-2]. */
	public static double emissivePower(double temperature) {
		double t2 = temperature * temperature;
		return STEFAN_BOLTZMANN * t2 * t2;
	}

	/** d(σ T⁴)/dT [W m-2 K-1]. */
	public static double dEmissivePower(double temperature) {
		return 4.0 * STEFAN_BOLTZMANN * temperature * temperature * temperature;
	}

	/**
	 * Specific humidity of air at temperature T and relative humidity RH, with the
	 * vapour pressure over water, as GEOtop converts its forcing
	 * ({@code energy.balance.cc:384-385}).
	 */
	public static double specificHumidityFromRelative(double temperature, double relativeHumidity, double pressure) {
		return specificHumidity(relativeHumidity * SaturationCurve.WATER.pressure(temperature, pressure), pressure);
	}
}
