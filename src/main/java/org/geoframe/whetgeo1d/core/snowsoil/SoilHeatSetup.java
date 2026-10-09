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

import java.util.Objects;

import org.geoframe.closureequation.closureequation.Parameters;

/**
 * The soil part of the column, in the same form as the inputs of
 * {@code HeatDiffusionSolver1D}: grid (index 0 at the bottom, cell-centred,
 * {@code spaceDeltaZ} of length n + 1 with the half cells at the two faces),
 * initial state, ids and the names of the WHETGEO closures. The driver builds
 * the soil closures from these with {@code ComputeQuantitiesInternalEnergy},
 * unchanged.
 */
public record SoilHeatSetup(double[] z, double[] spaceDeltaZ, double[] controlVolume, double[] waterSuction,
		double[] temperature, int[] equationStateID, int[] parameterID, String[] typeClosureEquation,
		String[] typeEquationState, String[] typeThermalConductivity, String interfaceThermalConductivityModel,
		Parameters parameters) {

	public SoilHeatSetup {
		Objects.requireNonNull(parameters, "parameters");
		Objects.requireNonNull(interfaceThermalConductivityModel, "interfaceThermalConductivityModel");
		int n = controlVolume.length;
		if (n < 1) {
			throw new IllegalArgumentException("the soil needs at least one cell");
		}
		if (z.length != n || waterSuction.length != n || temperature.length != n || equationStateID.length != n
				|| parameterID.length != n || spaceDeltaZ.length != n + 1) {
			throw new IllegalArgumentException("soil arrays must have length n (spaceDeltaZ n + 1), n = " + n);
		}
		if (typeClosureEquation.length != typeEquationState.length
				|| typeClosureEquation.length != typeThermalConductivity.length) {
			throw new IllegalArgumentException("closure, equation-state and conductivity names must match in number");
		}
	}

	/** Number of soil cells. */
	public int cells() {
		return controlVolume.length;
	}
}
