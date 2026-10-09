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
 * Decides how the snow cells are merged and split between two time steps
 * (specification §2.2 step 6, §4.1 A4). Implementations must conserve total
 * mass, internal energy and thickness, counting what they release
 * to the soil; cells may be re-partitioned at equilibrium.
 */
public interface RestratificationPolicy {

	/**
	 * @param cells the column, index 0 at the bottom
	 */
	Restratified apply(List<SnowCell> cells);
}
