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

import java.util.List;

/**
 * Settling of the snow cells over a time step (specification §4.1, row
 * "Compaction"). Implementations change thicknesses only: ice mass, liquid
 * mass and internal energy of every cell are returned unchanged, and no
 * thickness ever grows.
 */
public interface CompactionModel {

	/**
	 * @param cells      the column, index 0 at the bottom
	 * @param timeDelta  step length [s]
	 * @param slopeAngle terrain slope [rad], 0 on flat ground
	 * @return the compacted column, a new list of the same size
	 */
	List<SnowCell> compact(List<SnowCell> cells, double timeDelta, double slopeAngle);
}
