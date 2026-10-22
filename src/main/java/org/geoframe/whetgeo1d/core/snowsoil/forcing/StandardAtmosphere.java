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
package org.geoframe.whetgeo1d.core.snowsoil.forcing;

/**
 * Atmospheric pressure from the elevation of the column where the forcing has
 * none (LINK-1): GEOtop's isothermal standard atmosphere,
 * P = P<sub>0</sub> e<sup>-z/8500</sup>, with P<sub>0</sub> = 1013.25 hPa
 * ({@code pressure}, {@code meteo.cc:116-120}; {@code Pa0},
 * {@code constants.h:68}; GEOtop 3.0, commit 1f1fc5af). In Pa here (ARCH-5).
 *
 * <p>
 * GEOframe's {@code ShortWaveRadiationBalance} uses e<sup>-0.0001184 z</sup>
 * (scale height 8446 m); this class is GEOtop's, so that one function serves
 * every process that needs pressure (wet bulb, turbulence, air density).
 */
public final class StandardAtmosphere {

	/** Mean sea-level pressure [Pa]. */
	public static final double SEA_LEVEL_PRESSURE = 101325.0;
	/** Scale height [m]. */
	public static final double SCALE_HEIGHT = 8500.0;

	private StandardAtmosphere() {
	}

	/**
	 * @param elevation above sea level [m]
	 * @return pressure [Pa]
	 */
	public static double pressure(double elevation) {
		if (!Double.isFinite(elevation)) {
			throw new IllegalArgumentException("elevation must be finite, got " + elevation);
		}
		return SEA_LEVEL_PRESSURE * Math.exp(-elevation / SCALE_HEIGHT);
	}
}
