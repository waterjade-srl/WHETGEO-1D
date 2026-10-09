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
 * Outcome of a {@link LiquidWaterPercolation}.
 *
 * @param cells         the new column, index 0 at the bottom
 * @param drainedMass   liquid water leaving the base of the pack [kg m-2], to
 *                      be handed to the soil (specification §2.2, step 5); with
 *                      no snow it is the rain itself
 * @param drainedEnergy internal energy carried by that water [J m-2], same
 *                      reference as {@link SnowCell#internalEnergy()}
 * @param refrozenMass  net ice gained by the pack [kg m-2]: positive when
 *                      percolating water or rain refreezes, negative when warm
 *                      rain melts ice
 */
public record Percolated(List<SnowCell> cells, double drainedMass, double drainedEnergy, double refrozenMass) {

	public Percolated {
		cells = List.copyOf(cells);
	}

	/**
	 * Temperature of the drained water [K], from its energy as liquid water;
	 * NaN when nothing drains.
	 */
	public double drainedTemperature() {
		if (!(drainedMass > 0.0)) {
			return Double.NaN;
		}
		return SnowConstants.MELTING_TEMPERATURE
				+ (drainedEnergy / drainedMass - SnowConstants.LATENT_HEAT_FUSION) / SnowConstants.SPECIFIC_HEAT_WATER;
	}
}
