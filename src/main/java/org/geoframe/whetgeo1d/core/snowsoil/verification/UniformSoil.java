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
package org.geoframe.whetgeo1d.core.snowsoil.verification;

import java.util.Arrays;

import org.geoframe.closureequation.closureequation.Parameters;
import org.geoframe.whetgeo1d.core.snowsoil.SoilHeatSetup;

/**
 * A homogeneous, unfrozen soil column for the verification cases: uniform
 * cells, Van Genuchten water content at a uniform suction, WHETGEO's
 * {@code SoilInternalEnergy} and Cosenza conductivity. Parameter id 0.
 *
 * @param cells         number of cells
 * @param depth         [m]
 * @param suction       water suction [m]
 * @param temperature   uniform initial temperature [K]
 * @param interfaceModel name of WHETGEO's interface-conductivity model for the
 *                      soil faces
 */
public record UniformSoil(int cells, double depth, double suction, double temperature, String interfaceModel) {

	public static final double THETA_S = 0.38;
	public static final double THETA_R = 0.068;
	public static final double VG_N = 1.09;
	public static final double VG_ALPHA = 0.8;
	public static final double PARTICLE_DENSITY = 2500.0;
	public static final double PARTICLE_CONDUCTIVITY = 2.5;
	public static final double PARTICLE_HEAT_CAPACITY = 1000.0;

	/** The WHETGEO closure parameters, as {@code HeatDiffusionSolver1D} builds them. */
	public Parameters parameters() {
		double[] one = { 0.0 };
		return new Parameters(1000.0, 920.0, 4188.0, 2117.0, 0.6, 2.29, 333700, 273.15, 278.15, -766.45,
				new double[] { THETA_S }, new double[] { THETA_R }, new double[] { PARTICLE_DENSITY },
				new double[] { PARTICLE_HEAT_CAPACITY }, new double[] { PARTICLE_CONDUCTIVITY },
				new double[] { 273.15 }, new double[] { VG_N }, new double[] { VG_ALPHA }, one.clone(), one.clone(),
				one.clone(), new double[] { 1e-5 }, one.clone(), one.clone());
	}

	public double cellSize() {
		return depth / cells;
	}

	/** Cell centres from the bottom [m]. */
	public double[] z() {
		double[] z = new double[cells];
		for (int i = 0; i < cells; i++) {
			z[i] = (i + 0.5) * cellSize();
		}
		return z;
	}

	/** Node distances, half cells at the two faces. */
	public double[] spaceDeltaZ() {
		double[] d = new double[cells + 1];
		Arrays.fill(d, cellSize());
		d[0] = 0.5 * cellSize();
		d[cells] = 0.5 * cellSize();
		return d;
	}

	public double[] controlVolume() {
		double[] v = new double[cells];
		Arrays.fill(v, cellSize());
		return v;
	}

	public double[] suctions() {
		double[] psi = new double[cells];
		Arrays.fill(psi, suction);
		return psi;
	}

	public double[] temperatures() {
		double[] t = new double[cells];
		Arrays.fill(t, temperature);
		return t;
	}

	public int[] zeros() {
		return new int[cells];
	}

	/** The setup for the snow+soil driver. */
	public SoilHeatSetup setup() {
		return new SoilHeatSetup(z(), spaceDeltaZ(), controlVolume(), suctions(), temperatures(), zeros(), zeros(),
				new String[] { "Van Genuchten" }, new String[] { "SoilInternalEnergy" }, new String[] { "Cosenza" },
				interfaceModel, parameters());
	}
}
