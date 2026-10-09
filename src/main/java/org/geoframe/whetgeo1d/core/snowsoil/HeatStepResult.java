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
 * Outcome of one heat step of the snow+soil column. Arrays cover the active
 * cells only, index 0 at the bottom.
 *
 * @param snow             snow cells with the new internal energy and the phases
 *                         re-partitioned at equilibrium; mass and thickness
 *                         unchanged
 * @param soilTemperatures soil temperatures [K]
 * @param temperatures     temperatures of all active cells [K]
 * @param conductiveFluxes heat flux at the faces, positive upwards [W m-2]:
 *                         face i is the bottom face of cell i, face n the top
 *                         of the column
 * @param snowToSoilFlux   heat flux from the snow into the soil at their
 *                         interface, positive downwards [W m-2]; NaN without
 *                         snow or without soil
 * @param energy           energy balance of the step
 * @param newton           iterations and convergence
 */
public record HeatStepResult(List<SnowCell> snow, double[] soilTemperatures, double[] temperatures,
		double[] conductiveFluxes, double snowToSoilFlux, EnergyBudget energy, NewtonReport newton) {

	public HeatStepResult {
		snow = List.copyOf(snow);
	}

	/** Heat flux entering the column at the top, positive downwards [W m-2]. */
	public double surfaceFlux() {
		return -conductiveFluxes[conductiveFluxes.length - 1];
	}
}
