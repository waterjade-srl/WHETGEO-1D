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

import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.HeatStepResult;
import org.geoframe.whetgeo1d.core.snowsoil.NewtonSettings;
import org.geoframe.whetgeo1d.core.snowsoil.SnowHeatProperties;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilHeatDriver;
import org.geoframe.whetgeo1d.core.snowsoil.TopBoundary;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;

/**
 * Verification case with a closed-form solution: a slab of cold dry snow,
 * uniform density, initially at the bottom temperature, with the top face
 * brought at t = 0 to another temperature (Dirichlet on both faces). Far below
 * 0 °C the liquid fraction is ~10⁻¹² and the problem is linear heat
 * conduction with constant diffusivity κ = k / (ρ c_i):
 *
 * <pre>
 * T(z,t) = T_b + ΔT z/H + Σ_n 2 ΔT (-1)^n / (nπ) · sin(nπ z/H) · exp(-κ (nπ/H)² t),   ΔT = T_t - T_b
 * </pre>
 *
 * The snow-only column of the driver (no soil) is run on N uniform cells.
 *
 * @param depth       H [m]
 * @param density     [kg m-3]
 * @param bottom      T_b, also the initial temperature [K]
 * @param top         T_t [K]
 * @param law         conductivity law
 */
public record SnowSlabConduction(double depth, double density, double bottom, double top,
		SnowThermalConductivity law) {

	private static final int SERIES_TERMS = 4000;
	private static final double ALPHA = 1e5;

	/** Conductivity of the dry snow [W m-1 K-1]. */
	public double conductivity() {
		return law.conductivity(new SnowCell(density, 0.0, 1.0, 0.0));
	}

	/** κ [m2 s-1]. */
	public double diffusivity() {
		return conductivity() / (density * SPECIFIC_HEAT_ICE);
	}

	/** Closed-form temperature at height z above the bottom face [K]. */
	public double exact(double z, double time) {
		double delta = top - bottom;
		double value = bottom + delta * z / depth;
		double kappa = diffusivity();
		for (int n = 1; n <= SERIES_TERMS; n++) {
			double k = n * Math.PI / depth;
			double decay = Math.exp(-kappa * k * k * time);
			if (decay == 0.0) {
				break;
			}
			value += 2.0 * delta * (n % 2 == 0 ? 1.0 : -1.0) / (n * Math.PI) * Math.sin(k * z) * decay;
		}
		return value;
	}

	/** Errors of one run: max and root-mean-square over the cell centres [K]. */
	public record Errors(int cells, double timeDelta, double maxError, double rmsError, double[] z, double[] numeric,
			double[] exact, int maxOuterIterations) {
	}

	/**
	 * Runs the driver on N cells up to {@code time} with steps of
	 * {@code timeDelta} and compares the cell-centre temperatures.
	 */
	public Errors run(int cells, double timeDelta, double time) {
		SnowEquationOfState eos = new SnowEquationOfState(ALPHA);
		double h = depth / cells;
		List<SnowCell> snow = new ArrayList<>();
		for (int j = 0; j < cells; j++) {
			snow.add(SnowCell.atTemperature(eos, density * h, h, bottom));
		}
		SnowSoilHeatDriver driver = SnowSoilHeatDriver.snowOnly(new SnowHeatProperties(eos, law, cells),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		int steps = (int) Math.round(time / timeDelta);
		HeatStepResult result = null;
		int maxOuter = 0;
		for (int s = 0; s < steps; s++) {
			result = driver.step(snow, new TopBoundary.SurfaceTemperature(top), bottom, timeDelta);
			snow = result.snow();
			maxOuter = Math.max(maxOuter, result.newton().outerIterations());
		}
		double[] z = new double[cells];
		double[] exact = new double[cells];
		double max = 0.0;
		double sum = 0.0;
		for (int j = 0; j < cells; j++) {
			z[j] = (j + 0.5) * h;
			exact[j] = exact(z[j], steps * timeDelta);
			double error = Math.abs(result.temperatures()[j] - exact[j]);
			max = Math.max(max, error);
			sum += error * error;
		}
		return new Errors(cells, timeDelta, max, Math.sqrt(sum / cells), z, result.temperatures(), exact, maxOuter);
	}
}
