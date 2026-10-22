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
package org.geoframe.whetgeo1d.io.snowsoil;

import org.geoframe.whetgeo1d.core.snowsoil.IceAwareRetention;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SoilHeatSetup;

/**
 * Liquid water content θ_w of the soil cells [-], bottom first. The solver
 * exposes ψ and the ice but not θ, which needs the retention curves of the
 * soil; the component, which has them, passes this to the writers.
 */
@FunctionalInterface
public interface SoilWaterContent {

	/** θ_w of each soil cell [-], bottom first. */
	double[] liquid(SnowSoilSolver solver);

	/**
	 * θ_w = θ_tot(ψ) - θ_i, with θ_tot the ice-free Van Genuchten curve of
	 * {@link IceAwareRetention} at the suction the solver reports (ψ_w0 in a
	 * freezing soil, where θ_tot is the total water; the soil's own ψ
	 * elsewhere, where the ice is zero) and θ_i from {@link SnowSoilSolver#soilIce()}.
	 * Above saturation it includes the water held by the specific storage.
	 */
	static SoilWaterContent vanGenuchten(SoilHeatSetup soil) {
		IceAwareRetention retention = new IceAwareRetention(soil.parameters());
		int[] ids = soil.parameterID().clone();
		return solver -> {
			double[] psi = solver.soilSuctions();
			double[] ice = solver.soilIce();
			double[] theta = new double[psi.length];
			for (int i = 0; i < psi.length; i++) {
				theta[i] = retention.totalWater(psi[i], ids[i]) - ice[i];
			}
			return theta;
		};
	}
}
