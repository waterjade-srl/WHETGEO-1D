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

import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;

/**
 * Snow side of the heat system.
 *
 * @param equationOfState U(T; w) of every snow cell (α, specification §3.2, A3)
 * @param conductivity    conductivity law; a parameter because the default of
 *                        A16 is still open
 * @param maxCells        capacity reserved for snow cells on top of the soil
 *                        (§2.1): with the GEOtop defaults 10, 11 with the
 *                        top-cell cap
 * @param faceConductance conductance of the snow–snow and snow–soil faces
 *                        (SNOW-3)
 */
public record SnowHeatProperties(SnowEquationOfState equationOfState, SnowThermalConductivity conductivity,
		int maxCells, FaceConductance faceConductance) {

	/**
	 * Conductivity of a face between two cells of thickness D<sub>1</sub>,
	 * D<sub>2</sub> (SNOW-3).
	 */
	public enum FaceConductance {
		/**
		 * Recommended: the thickness-weighted harmonic mean of the two
		 * conductivities, the exact series conductance of the two half cells.
		 */
		HARMONIC_MEAN,
		/**
		 * Fidelity: GEOtop 3.0's rule ({@code energy.balance.cc:1548-1578}, commit
		 * 1f1fc5af). The arguments of the conductivity (θ<sub>w</sub>,
		 * θ<sub>i</sub>, θ<sub>sat</sub>, k<sub>solid</sub>; for snow
		 * θ<sub>sat</sub> = 1, k<sub>solid</sub> = 0) are averaged with
		 * {@code Arithmetic_Mean}, (D<sub>1</sub> a<sub>2</sub> + D<sub>2</sub>
		 * a<sub>1</sub>)/(D<sub>1</sub> + D<sub>2</sub>), weighting each cell by
		 * the other's thickness ({@code pedo.funct.h:203-206}), then the
		 * conductivity is evaluated on the means: with the snow law between two
		 * snow cells, with Cosenza's soil formula ({@code k_thermal}, snow = 0,
		 * {@code energy.balance.cc:2743-2765}) when the lower cell is soil.
		 */
		GEOTOP_MEAN_OF_ARGUMENTS
	}

	/** Snow side with the harmonic mean at the faces. */
	public SnowHeatProperties(SnowEquationOfState equationOfState, SnowThermalConductivity conductivity,
			int maxCells) {
		this(equationOfState, conductivity, maxCells, FaceConductance.HARMONIC_MEAN);
	}

	public SnowHeatProperties {
		Objects.requireNonNull(equationOfState, "equationOfState");
		Objects.requireNonNull(conductivity, "conductivity");
		Objects.requireNonNull(faceConductance, "faceConductance");
		if (maxCells < 0) {
			throw new IllegalArgumentException("maxCells must be >= 0, got " + maxCells);
		}
	}
}
