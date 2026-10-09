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

import java.util.ArrayList;
import java.util.List;

/**
 * Operations on a whole snow column: a list of cells, index 0 at the bottom
 * (specification §6.3). Lists are never modified; every operation returns a new
 * one.
 */
public final class SnowColumn {

	private SnowColumn() {
	}

	/** Snow water equivalent, ice plus liquid [kg m-2]. */
	public static double totalMass(List<SnowCell> cells) {
		double sum = 0.0;
		for (SnowCell cell : cells) {
			sum += cell.totalMass();
		}
		return sum;
	}

	/** Internal energy of the column [J m-2]. */
	public static double internalEnergy(List<SnowCell> cells) {
		double sum = 0.0;
		for (SnowCell cell : cells) {
			sum += cell.internalEnergy();
		}
		return sum;
	}

	/** Snow depth [m]. */
	public static double depth(List<SnowCell> cells) {
		double sum = 0.0;
		for (SnowCell cell : cells) {
			sum += cell.thickness();
		}
		return sum;
	}

	/**
	 * Deposits fresh snow on the column. On bare ground the fresh snow becomes the
	 * only cell; otherwise it is merged into the top cell, which is then brought
	 * to equilibrium, as GEOtop's {@code new_snow} does. Mass and internal energy
	 * of the fresh snow are those of the given cell: its temperature is the
	 * caller's choice (A18), never imposed here.
	 */
	public static List<SnowCell> depositFreshSnow(List<SnowCell> cells, SnowCell freshSnow,
			SnowEquationOfState eos) {
		List<SnowCell> result = new ArrayList<>(cells);
		if (result.isEmpty()) {
			result.add(eos.equilibrate(freshSnow));
		} else {
			int top = result.size() - 1;
			result.set(top, eos.equilibrate(result.get(top).merge(freshSnow)));
		}
		return List.copyOf(result);
	}
}
