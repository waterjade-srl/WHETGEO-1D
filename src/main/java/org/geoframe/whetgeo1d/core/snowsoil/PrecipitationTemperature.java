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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

import org.geoframe.whetgeo1d.snow.FreshSnow;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.geoframe.whetgeo1d.snow.surface.WetBulbTemperature;

/**
 * Temperature, and so internal energy, of the precipitation reaching the
 * column (specification A18).
 */
public enum PrecipitationTemperature {

	/**
	 * GEOtop 3.0: fresh snow at min(T_a, -0.1 °C) ({@code new_snow}); rain at the
	 * temperature of the top snow cell, i.e. with no sensible heat
	 * ({@code WBsnow}).
	 */
	GEOTOP,

	/** Recommended: fresh snow at min(T_wb, 0 °C), rain at max(T_wb, 0 °C). */
	WET_BULB;

	/** Temperature of the fresh snow [K]. */
	public double snow(SurfaceForcing forcing) {
		if (this == GEOTOP) {
			return FreshSnow.geotopTemperature(forcing.airTemperature());
		}
		return Math.min(wetBulb(forcing), MELTING_TEMPERATURE);
	}

	/**
	 * Temperature of the rain [K].
	 *
	 * @param topTemperature temperature of the top snow cell when the rain enters
	 *                       it, NaN on bare ground
	 */
	public double rain(SurfaceForcing forcing, double topTemperature) {
		if (this == GEOTOP && !Double.isNaN(topTemperature)) {
			return topTemperature;
		}
		return Math.max(wetBulb(forcing), MELTING_TEMPERATURE);
	}

	private static double wetBulb(SurfaceForcing forcing) {
		return WetBulbTemperature.of(forcing.airTemperature(), forcing.relativeHumidity(), forcing.pressure());
	}
}
