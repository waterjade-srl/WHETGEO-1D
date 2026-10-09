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

/**
 * Energy balance of one heat step of the whole active column [J m-2].
 *
 * @param storageChange Σ U<sup>n+1</sup> - Σ U<sup>n</sup> over the active cells
 * @param bottomInput   energy entered through the bottom face, Δt · G_bottom
 * @param topInput      energy entered through the top face, Δt · G_top
 * @param closureError  storageChange - bottomInput - topInput: the sum of the
 *                      Newton residuals of the cells, zero to the solver
 *                      tolerance
 */
public record EnergyBudget(double storageChange, double bottomInput, double topInput, double closureError) {

	static EnergyBudget of(double storageChange, double bottomInput, double topInput) {
		return new EnergyBudget(storageChange, bottomInput, topInput, storageChange - bottomInput - topInput);
	}
}
