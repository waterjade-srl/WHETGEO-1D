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

import org.geoframe.whetgeo1d.core.state.WGGeometry;
import org.geoframe.whetgeo1d.core.state.WGProblemQuantities;
import org.geoframe.whetgeo1d.snow.SnowCell;

/**
 * One heat column with fixed capacity and an active window (specification
 * §2.1, proposed): soil cells at indices [0, soilCells), snow cells stacked on
 * top, at most {@code maxSnowCells}. All arrays are allocated once, at
 * capacity; the solver works on [0, activeCells). Snow cells are switched on
 * and off from the top, and the geometry at the moving boundary is rebuilt at
 * every {@link #activateSnow}.
 *
 * <p>
 * The arrays are the {@link WGGeometry} and {@link WGProblemQuantities} that
 * WHETGEO's closures and kernels read, so the soil cells keep the indices they
 * have in a soil-only run and the soil closures see the same data.
 */
final class SnowSoilColumn {

	private final int soilCells;
	private final int capacity;
	private final double soilTopHalfCell;
	private final double soilTopElevation;
	private final int snowEquationStateId;

	private final WGGeometry geometry;
	private final WGProblemQuantities variables;
	private final double[] totalMass;

	private int snowCells;

	/**
	 * @param soil                soil part, or null for a snow-only column
	 * @param maxSnowCells        snow capacity
	 * @param snowEquationStateId equation-state id given to snow cells
	 */
	SnowSoilColumn(SoilHeatSetup soil, int maxSnowCells, int snowEquationStateId) {
		this.soilCells = soil == null ? 0 : soil.cells();
		this.capacity = soilCells + maxSnowCells;
		if (capacity < 1) {
			throw new IllegalArgumentException("the column has no cells: no soil and no snow capacity");
		}
		this.snowEquationStateId = snowEquationStateId;

		double[] z = new double[capacity];
		double[] spaceDeltaZ = new double[capacity + 1];
		double[] controlVolume = new double[capacity];
		double[] suction = new double[capacity];
		double[] temperature = new double[capacity];
		int[] equationStateID = new int[capacity];
		int[] parameterID = new int[capacity];
		if (soil != null) {
			System.arraycopy(soil.z(), 0, z, 0, soilCells);
			System.arraycopy(soil.spaceDeltaZ(), 0, spaceDeltaZ, 0, soilCells + 1);
			System.arraycopy(soil.controlVolume(), 0, controlVolume, 0, soilCells);
			System.arraycopy(soil.waterSuction(), 0, suction, 0, soilCells);
			System.arraycopy(soil.temperature(), 0, temperature, 0, soilCells);
			System.arraycopy(soil.equationStateID(), 0, equationStateID, 0, soilCells);
			System.arraycopy(soil.parameterID(), 0, parameterID, 0, soilCells);
			this.soilTopHalfCell = soil.spaceDeltaZ()[soilCells];
			this.soilTopElevation = soil.z()[soilCells - 1] + soilTopHalfCell;
		} else {
			this.soilTopHalfCell = 0.0;
			this.soilTopElevation = 0.0;
		}
		for (int i = soilCells; i < capacity; i++) {
			equationStateID[i] = snowEquationStateId;
			parameterID[i] = 0;
		}
		this.geometry = new WGGeometry(z, spaceDeltaZ, controlVolume);
		this.variables = new WGProblemQuantities(suction, temperature, equationStateID, parameterID);
		this.totalMass = new double[capacity];
		this.snowCells = 0;
	}

	/**
	 * Switches on one cell per snow layer, from the top of the soil upwards, and
	 * rebuilds the geometry of the snow window and of the soil–snow boundary.
	 * Temperatures are not touched: the caller sets them.
	 */
	void activateSnow(List<SnowCell> snow) {
		int n = snow.size();
		if (n > capacity - soilCells) {
			throw new IllegalArgumentException(
					"snow cells " + n + " exceed the capacity of the column " + (capacity - soilCells));
		}
		snowCells = n;
		double[] z = geometry.z;
		double[] spaceDeltaZ = geometry.spaceDeltaZ;
		double[] controlVolume = geometry.controlVolume;

		double base = soilTopElevation;
		double belowHalf = soilTopHalfCell;
		for (int j = 0; j < n; j++) {
			SnowCell cell = snow.get(j);
			int i = soilCells + j;
			double half = 0.5 * cell.thickness();
			controlVolume[i] = cell.thickness();
			z[i] = base + half;
			spaceDeltaZ[i] = belowHalf + half;
			totalMass[i] = cell.totalMass();
			variables.equationStateID[i] = snowEquationStateId;
			variables.parameterID[i] = 0;
			base += cell.thickness();
			belowHalf = half;
		}
		spaceDeltaZ[soilCells + n] = belowHalf;
	}

	int soilCells() {
		return soilCells;
	}

	int snowCells() {
		return snowCells;
	}

	int activeCells() {
		return soilCells + snowCells;
	}

	int capacity() {
		return capacity;
	}

	boolean isSnow(int element) {
		return element >= soilCells;
	}

	WGGeometry geometry() {
		return geometry;
	}

	WGProblemQuantities variables() {
		return variables;
	}

	/** Total mass of each cell [kg m-2], read by {@code SnowInternalEnergy}. */
	double[] totalMass() {
		return totalMass;
	}
}
