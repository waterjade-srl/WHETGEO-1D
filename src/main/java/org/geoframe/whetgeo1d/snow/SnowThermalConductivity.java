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
package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.CONDUCTIVITY_AIR;
import static org.geoframe.whetgeo1d.snow.SnowConstants.CONDUCTIVITY_ICE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.CONDUCTIVITY_WATER;
import static org.geoframe.whetgeo1d.snow.SnowConstants.ICE_DENSITY;
import static org.geoframe.whetgeo1d.snow.SnowConstants.WATER_DENSITY;

/**
 * Effective thermal conductivity of snow [W m-1 K-1] (specification §4.1, A16).
 *
 * <p>
 * {@link #COSENZA}, {@link #STURM_1997} and {@link #JORDAN_1991} are GEOtop 3.0's
 * {@code snow_conductivity} options 1, 2 and 3 ({@code k_thermal} in
 * {@code energy.balance.cc}); option 1 is GEOtop's default. {@link #CALONNE_2011}
 * is the third candidate of the specification for the recommended
 * configuration. The density-based laws use the bulk density of ice plus
 * liquid, as GEOtop does.
 */
public enum SnowThermalConductivity {

	/** Quadratic parallel mixing of ice, liquid and air (Cosenza et al., 2003). */
	COSENZA {
		@Override
		public double conductivity(double liquidVolumeFraction, double iceVolumeFraction) {
			double air = 1.0 - liquidVolumeFraction - iceVolumeFraction;
			double root = liquidVolumeFraction * Math.sqrt(CONDUCTIVITY_WATER)
					+ iceVolumeFraction * Math.sqrt(CONDUCTIVITY_ICE) + air * Math.sqrt(CONDUCTIVITY_AIR);
			return root * root;
		}
	},

	/** Sturm et al. (1997), piecewise in the density. */
	STURM_1997 {
		@Override
		public double conductivity(double liquidVolumeFraction, double iceVolumeFraction) {
			double rho = bulkDensity(liquidVolumeFraction, iceVolumeFraction) * 1e-3;
			return rho < 0.156 ? 0.023 + 0.234 * rho : 0.138 - 1.01 * rho + 3.233 * rho * rho;
		}
	},

	/** Jordan (1991), SNTHERM. */
	JORDAN_1991 {
		@Override
		public double conductivity(double liquidVolumeFraction, double iceVolumeFraction) {
			double rho = bulkDensity(liquidVolumeFraction, iceVolumeFraction);
			return CONDUCTIVITY_AIR + (7.75e-5 * rho + 1.105e-6 * rho * rho) * (CONDUCTIVITY_ICE - CONDUCTIVITY_AIR);
		}
	},

	/** Calonne et al. (2011), from 3D tomography of snow microstructure. */
	CALONNE_2011 {
		@Override
		public double conductivity(double liquidVolumeFraction, double iceVolumeFraction) {
			double rho = bulkDensity(liquidVolumeFraction, iceVolumeFraction);
			return 2.5e-6 * rho * rho - 1.23e-4 * rho + 0.024;
		}
	};

	/**
	 * @param liquidVolumeFraction volumetric liquid content [-]
	 * @param iceVolumeFraction    volumetric ice content [-]
	 */
	public abstract double conductivity(double liquidVolumeFraction, double iceVolumeFraction);

	/** Conductivity of a cell with its current phase partition. */
	public double conductivity(SnowCell cell) {
		return conductivity(cell.liquidVolumeFraction(), cell.iceVolumeFraction());
	}

	private static double bulkDensity(double liquidVolumeFraction, double iceVolumeFraction) {
		return liquidVolumeFraction * WATER_DENSITY + iceVolumeFraction * ICE_DENSITY;
	}
}
