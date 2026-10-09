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
package org.geoframe.whetgeo1d.core.equationstate.models;

import org.geoframe.closureequation.closureequation.ClosureEquation;
import org.geoframe.closureequation.equationstate.EquationState;
import org.geoframe.whetgeo1d.core.state.WGProblemQuantities;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;

/**
 * Snow cells in WHETGEO's heat equation: adapts {@link SnowEquationOfState} to
 * the {@link EquationState} contract of the finite-volume kernels, so that snow
 * and soil cells are solved in the same system by NestedNewtonThomas
 * (specification §2.1, §3.2).
 *
 * <p>
 * The unknown x is the temperature [K]; y (suction) is ignored. U is per unit
 * area [J m-2] like the soil closures once multiplied by the control volume.
 * The total mass of each cell is read from {@code totalMassByElement}, an array
 * the caller owns and keeps current. The nested Newton decomposition U' = p - q
 * splits at the inflection of U, which depends only on α and therefore on the
 * parameter id.
 */
public class SnowInternalEnergy extends EquationState {

	private final WGProblemQuantities variables;
	private final SnowEquationOfState[] eosById;
	private final double[] totalMassByElement;

	/**
	 * @param eosById            equation of state per parameter id, null for ids
	 *                           that are not snow
	 * @param totalMassByElement total mass of each cell [kg m-2], owned by the
	 *                           caller
	 */
	public SnowInternalEnergy(ClosureEquation closureEquation, WGProblemQuantities variables,
			SnowEquationOfState[] eosById, double[] totalMassByElement) {
		super(closureEquation);
		this.variables = variables;
		this.eosById = eosById;
		this.totalMassByElement = totalMassByElement;
	}

	@Override
	public double equationState(double x, double y, int id, int element) {
		return eosById[id].internalEnergy(x, totalMassByElement[element]);
	}

	@Override
	public double dEquationState(double x, double y, int id, int element) {
		return eosById[id].dInternalEnergy(x, totalMassByElement[element]);
	}

	@Override
	public double ddEquationState(double x, double y, int id, int element) {
		return eosById[id].ddInternalEnergy(x, totalMassByElement[element]);
	}

	@Override
	public double p(double x, double y, int id, int element) {
		double xStar = variables.temperatureStar1[element];
		return dEquationState(Math.min(x, xStar), y, id, element);
	}

	@Override
	public double pIntegral(double x, double y, int id, int element) {
		double xStar = variables.temperatureStar1[element];
		if (x <= xStar) {
			return equationState(x, y, id, element);
		}
		return equationState(xStar, y, id, element) + dEquationState(xStar, y, id, element) * (x - xStar);
	}

	@Override
	public void computeXStar(double y, int id, int element) {
		variables.temperatureStar1[element] = eosById[id].inflectionTemperature();
		variables.temperatureStar2[element] = -9999.0;
		variables.temperatureStar3[element] = -9999.0;
	}

	@Override
	public double initialGuess(double x, int id, int element) {
		return Math.min(variables.temperatures[element], variables.temperatureStar1[element]);
	}
}
