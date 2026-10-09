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

import java.util.Objects;

import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;

/**
 * Snow side of the heat system.
 *
 * @param equationOfState U(T; w) of every snow cell (α, specification §3.2, A3)
 * @param conductivity    conductivity law; a parameter because the default of
 *                        A16 is still open
 * @param maxCells        capacity reserved for snow cells on top of the soil
 *                        (§2.1): with the GEOtop defaults 10, 11 with the
 *                        top-cell cap
 */
public record SnowHeatProperties(SnowEquationOfState equationOfState, SnowThermalConductivity conductivity,
		int maxCells) {

	public SnowHeatProperties {
		Objects.requireNonNull(equationOfState, "equationOfState");
		Objects.requireNonNull(conductivity, "conductivity");
		if (maxCells < 0) {
			throw new IllegalArgumentException("maxCells must be >= 0, got " + maxCells);
		}
	}
}
