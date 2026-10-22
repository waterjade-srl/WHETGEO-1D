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
package org.geoframe.whetgeo1d.io.snowsoil;

import java.time.ZoneOffset;
import java.util.Objects;

import org.geoframe.whetgeo1d.snow.SnowEquationOfState;

/**
 * Options shared by the snow+soil writers.
 *
 * @param equationOfState  the equation of state of the run, to turn the
 *                         internal energy of a snow cell into its temperature
 * @param maxSnowCells     capacity of the snow profiles: the size of the
 *                         snow-cell dimension of the NetCDF file and the
 *                         number of layer columns of the CSV snow profiles. A
 *                         pack with more cells is an error, never truncated
 * @param intervalSeconds  output interval [s]: a record is written at the end
 *                         of every step that reaches a multiple of it since
 *                         1970-01-01 UTC (whole hours for 3600), with the
 *                         steps in between aggregated; 0 writes every step
 * @param zone             offset of the dates written in the CSV files (GEOtop
 *                         writes local standard time); the NetCDF time is UTC
 * @param soilWaterContent θ of the soil cells; null leaves θ out
 */
public record OutputOptions(SnowEquationOfState equationOfState, int maxSnowCells, long intervalSeconds,
		ZoneOffset zone, SoilWaterContent soilWaterContent) {

	public OutputOptions {
		Objects.requireNonNull(equationOfState, "equationOfState");
		Objects.requireNonNull(zone, "zone");
		if (maxSnowCells < 1) {
			throw new IllegalArgumentException("maxSnowCells must be at least 1, got " + maxSnowCells);
		}
		if (intervalSeconds < 0) {
			throw new IllegalArgumentException("intervalSeconds must be non-negative, got " + intervalSeconds);
		}
	}

	/** Every step, dates in UTC, no θ. */
	public static OutputOptions of(SnowEquationOfState equationOfState, int maxSnowCells) {
		return new OutputOptions(equationOfState, maxSnowCells, 0, ZoneOffset.UTC, null);
	}

	/** Same options with another output interval [s]. */
	public OutputOptions everySeconds(long seconds) {
		return new OutputOptions(equationOfState, maxSnowCells, seconds, zone, soilWaterContent);
	}

	/** Same options with the dates of the CSV files at another offset. */
	public OutputOptions inZone(ZoneOffset offset) {
		return new OutputOptions(equationOfState, maxSnowCells, intervalSeconds, offset, soilWaterContent);
	}

	/** Same options with θ of the soil written. */
	public OutputOptions withSoilWaterContent(SoilWaterContent content) {
		return new OutputOptions(equationOfState, maxSnowCells, intervalSeconds, zone, content);
	}
}
