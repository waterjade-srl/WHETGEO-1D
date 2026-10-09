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
package org.geoframe.whetgeo1d.snow.surface;

import java.util.List;

import org.geoframe.whetgeo1d.snow.SnowCell;

/**
 * Result of {@link SnowSublimation#apply}.
 *
 * <p>
 * Balances, to round-off: mass before = mass after + {@link #exchangedMass};
 * U before = U after + {@link #exchangedEnergy} + {@link #residualEnergy}.
 * The energy carried away by the vapour is {@link #exchangedEnergy} plus λ
 * times {@link #exchangedMass}, the λ E Δt already counted in the surface
 * energy balance.
 *
 * @param cells           the column after the exchange, index 0 at the bottom
 * @param exchangedMass   vapour mass actually exchanged [kg m-2]: positive
 *                        removed (sublimation, evaporation), negative added
 *                        (deposition, condensation)
 * @param exchangedEnergy internal energy that left the column with that mass
 *                        [J m-2], negative when mass was added
 * @param unmetMass       part of the requested mass that could not be
 *                        exchanged: more than the whole column for a removal,
 *                        any deposition on an empty column [kg m-2]
 * @param residualEnergy  internal energy left over by the bottom cell when it
 *                        was consumed completely, to hand to the soil [J m-2];
 *                        zero for a cell at equilibrium
 * @param phaseMismatchEnergy energy the phase change of the mass taken from the
 *                        other phase would need, Σ m (e_declared - e_other)
 *                        [J m-2]: zero when the declared phase was available.
 *                        It is not charged to the column; a caller that wants
 *                        the vapour to carry exactly λ m + exchangedEnergy
 *                        removes it from the column, e.g. through the surface
 *                        flux of the next step
 */
public record Sublimated(List<SnowCell> cells, double exchangedMass, double exchangedEnergy, double unmetMass,
		double residualEnergy, double phaseMismatchEnergy) {

	public Sublimated {
		cells = List.copyOf(cells);
	}
}
