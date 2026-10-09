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

/**
 * Surface energy balance at one surface temperature, and its components.
 * Sign convention (GEOtop's): {@link #energyBalance} is positive into the
 * surface; {@link #sensibleHeat}, {@link #latentHeatFlux} and
 * {@link #vapourFlux} are positive from the surface to the air (upward), so
 * that EB = SW_net + LW_net - H - λE.
 *
 * @param netShortwave   absorbed shortwave, SW_net [W m-2]
 * @param netLongwave    ε (LW_in - σ T_s⁴) [W m-2]
 * @param sensibleHeat   H [W m-2], upward positive
 * @param latentHeatFlux λE [W m-2], upward positive
 * @param vapourFlux     E [kg m-2 s-1]: positive sublimation or evaporation,
 *                       negative deposition or condensation
 * @param latentHeat     λ used [J kg-1]
 * @param energyBalance  EB(T_s) [W m-2]
 * @param derivative     dEB/dT_s [W m-2 K-1]
 */
public record SurfaceFluxes(double netShortwave, double netLongwave, double sensibleHeat, double latentHeatFlux,
		double vapourFlux, double latentHeat, double energyBalance, double derivative) {
}
