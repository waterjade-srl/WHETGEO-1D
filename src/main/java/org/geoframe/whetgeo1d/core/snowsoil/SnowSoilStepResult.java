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
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;

/**
 * Outcome of one step of the {@link SnowSoilSolver} (specification §2.2 step
 * 7, diagnostics).
 *
 * @param snow               the pack at the end of the step, index 0 at the
 *                           bottom
 * @param surfaceAge         non-dimensional age of the snow surface
 * @param heat               the heat step of the snow+soil column, the last
 *                           sweep
 * @param surfaceSweeps      heat solves done in the step ({@link SurfaceCoupling})
 * @param surfaceConverged   the last sweep moved the surface temperature by
 *                           less than the tolerance (always true without snow
 *                           or with one sweep)
 * @param surface            surface energy balance at the surface temperature
 *                           at the end of the heat step; null without snow at
 *                           the start of the step
 * @param surfaceTemperature temperature of the top snow cell at the end of the
 *                           heat step [K], NaN without snow
 * @param albedo             broadband albedo of the step, NaN without snow
 * @param melt               ice turned into liquid by the heat step [kg m-2],
 *                           negative for net refreezing
 * @param refrozen           ice gained by refreezing percolating water and
 *                           rain [kg m-2]
 * @param merges             merges done by the restratification
 * @param splits             splits done by the restratification
 * @param unmetVapour        vapour mass the pack could not supply or take
 *                           [kg m-2]
 * @param linearisationEnergy Δt (G_lin(T_s) - EB(T_s)) [J m-2]: the energy the
 *                           linearised surface flux of the last sweep puts in
 *                           the column beyond the nonlinear balance at the
 *                           final temperature; zero without snow, and zero to
 *                           the tolerance when the sweeps converge
 * @param pendingSurfaceEnergy energy carried to the next step as a surface
 *                           input [J m-2]: the phase mismatch of the vapour
 *                           exchange (document 008), sign changed
 * @param soilInputMass      water handed to the top of the soil [kg m-2]:
 *                           drainage, the remains of a vanished pack, rain on
 *                           bare ground
 * @param soilInputEnergy    internal energy of that water [J m-2], same
 *                           reference as the snow
 * @param soilWater          the Richards step of the soil; null without soil
 *                           water
 * @param soilDrainageEnergy energy leaving the bottom of the soil with the
 *                           drainage [J m-2], reference of the soil closures;
 *                           NaN without soil water
 * @param soilEnergyClosure  ΔU of the soil - Δt (G_bottom - G_top of soil) -
 *                           energy of the infiltration + energy of the drainage
 *                           - soilStorageHeat [J m-2]: the Newton residuals of
 *                           the soil cells in the heat step; NaN without soil
 *                           water
 * @param soilStorageHeat    change of the energy of the water held above
 *                           saturation by the specific storage of a freezing
 *                           soil, which follows the temperature of its cell in
 *                           the heat step without being part of its heat
 *                           capacity [J m-2]: energy the step creates, zero
 *                           without such water (document 012); NaN without soil
 *                           water
 * @param budget             mass and energy balance of the pack
 */
public record SnowSoilStepResult(List<SnowCell> snow, double surfaceAge, HeatStepResult heat, int surfaceSweeps,
		boolean surfaceConverged, SurfaceFluxes surface,
		double surfaceTemperature, double albedo, double melt, double refrozen, int merges, int splits, double unmetVapour,
		double linearisationEnergy, double pendingSurfaceEnergy, double soilInputMass, double soilInputEnergy,
		SoilWaterStepResult soilWater, double soilDrainageEnergy, double soilEnergyClosure, double soilStorageHeat,
		SnowBudget budget) {

	public SnowSoilStepResult {
		snow = List.copyOf(snow);
	}

	/** Snow water equivalent [kg m-2]. */
	public double snowWaterEquivalent() {
		return SnowColumn.totalMass(snow);
	}

	/** Snow depth [m]. */
	public double snowDepth() {
		return SnowColumn.depth(snow);
	}
}
