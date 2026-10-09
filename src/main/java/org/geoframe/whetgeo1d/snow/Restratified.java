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
import java.util.Optional;

/**
 * Outcome of a restratification.
 *
 * @param cells          the new column, index 0 at the bottom
 * @param releasedToSoil mass and energy of a pack too thin to keep, to be
 *                       handed to the first soil cell (specification §4.2);
 *                       empty when the pack survives
 * @param merges         number of pairwise merges performed
 * @param splits         number of splits performed
 */
public record Restratified(List<SnowCell> cells, Optional<SnowCell> releasedToSoil, int merges, int splits) {

	public Restratified {
		cells = List.copyOf(cells);
	}
}
