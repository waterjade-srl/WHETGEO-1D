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

import java.util.List;

import org.geoframe.whetgeo1d.snow.SnowCell;

/**
 * Shortwave radiation absorbed below the surface of the pack (SNOW-7).
 * <p>
 * With more than one snow cell the net shortwave is absorbed inside the cells
 * with the extinction coefficient k = ρ/3 + 50 m<sup>-1</sup> (ρ in kg m-3) of
 * GEOtop's {@code rad_snow_absorption} ({@code radiation.cc:582-613}); what
 * crosses the whole pack is absorbed by the top soil cell, and none of it
 * reaches the surface (GEOtop's {@code SWlayer(0) = 0}). With one snow cell,
 * or none, the whole net shortwave stays in the surface balance. The
 * partition uses the cells at the start of the step, as GEOtop does.
 */
public enum ShortwavePenetration {

	/** All the net shortwave in the surface balance. For sensitivity runs. */
	NONE,

	/**
	 * GEOtop 3.0 (fidelity): the extinction coefficient of each cell applied to
	 * the cumulative depth of its bottom, R e<sup>-k<sub>m</sub> z<sub>m</sub></sup>,
	 * so a cell can absorb a negative amount when the density varies.
	 */
	GEOTOP,

	/**
	 * Recommended: the transmission compounded cell by cell,
	 * Π<sub>j</sub> e<sup>-k<sub>j</sub> D<sub>j</sub></sup>; every cell absorbs a
	 * non-negative amount.
	 */
	COMPOUNDED;

	/**
	 * Net shortwave absorbed in each cell [W m-2].
	 *
	 * @param snow         cells at the start of the step, index 0 at the bottom
	 * @param netShortwave net shortwave absorbed by the ground [W m-2]
	 * @return per snow cell (index 0 at the bottom), then the part transmitted to
	 *         the top soil cell at index {@code snow.size()}; null when the
	 *         whole net shortwave stays at the surface
	 */
	public double[] absorbed(List<SnowCell> snow, double netShortwave) {
		if (this == NONE || snow.size() < 2 || netShortwave == 0.0) {
			return null;
		}
		int ns = snow.size();
		double[] absorbed = new double[ns + 1];
		double above = 1.0;
		double depth = 0.0;
		for (int j = ns - 1; j >= 0; j--) {
			SnowCell cell = snow.get(j);
			double k = cell.totalMass() / cell.thickness() / 3.0 + 50.0;
			depth += cell.thickness();
			double below = this == GEOTOP ? Math.exp(-k * depth) : above * Math.exp(-k * cell.thickness());
			absorbed[j] = netShortwave * (above - below);
			above = below;
		}
		absorbed[ns] = netShortwave * above;
		return absorbed;
	}
}
