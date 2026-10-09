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

/**
 * Physical constants of the snowpack library, SI units, values of GEOtop 3.0
 * ({@code constants.h}, commit 1f1fc5af).
 *
 * <p>
 * One density convention only (specification §4.1, A14): volumetric ice
 * contents are always computed with {@link #ICE_DENSITY}.
 */
public final class SnowConstants {

	/** Melting temperature and reference of the internal energy [K]. */
	public static final double MELTING_TEMPERATURE = 273.15;

	/** Specific heat of ice [J kg-1 K-1]. */
	public static final double SPECIFIC_HEAT_ICE = 2117.0;

	/** Specific heat of liquid water [J kg-1 K-1]. */
	public static final double SPECIFIC_HEAT_WATER = 4188.0;

	/** Latent heat of fusion [J kg-1]. */
	public static final double LATENT_HEAT_FUSION = 333700.0;

	/** Density of liquid water [kg m-3]. */
	public static final double WATER_DENSITY = 1000.0;

	/** Density of ice [kg m-3]. */
	public static final double ICE_DENSITY = 917.0;

	/** Thermal conductivity of liquid water [W m-1 K-1]. */
	public static final double CONDUCTIVITY_WATER = 0.567;

	/** Thermal conductivity of ice [W m-1 K-1]. */
	public static final double CONDUCTIVITY_ICE = 2.290;

	/** Thermal conductivity of air [W m-1 K-1]. */
	public static final double CONDUCTIVITY_AIR = 0.023;

	private SnowConstants() {
	}
}
