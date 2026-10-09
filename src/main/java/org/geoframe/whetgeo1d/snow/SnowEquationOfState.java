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

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_WATER;

/**
 * Equation of state of snow (specification §3.2): unknown T, conserved
 * quantity U, with the liquid/ice partition as a closure.
 *
 * <pre>
 * U(T; w) = [ c_i (1 - θ_w) + c_w θ_w ] · w · (T - T_m) + L_f θ_w w
 * θ_w(T)  = 1 / (1 + (α (T - T_m))²)   for T &lt; T_m,   1 otherwise
 * </pre>
 *
 * U is per unit area [J m-2] and w is the total (ice + liquid) mass of the
 * cell [kg m-2], so the class needs no geometry and compaction does not change
 * U. θ_w is the liquid fraction of the total mass. Every quantity is linear in
 * w, so the shape of U(T) — in particular the temperature of its inflection —
 * depends only on α.
 *
 * <p>
 * Above the melting temperature the cell is all liquid and U grows with the
 * heat capacity of water. How an over-melted cell is handled during the solve
 * (A20) is still open in the specification and is not decided here.
 */
public final class SnowEquationOfState {

	private static final double COLDEST_TEMPERATURE = 1.0;
	private static final int MAX_ITERATIONS = 200;

	private final double alpha;
	private final double inflectionTemperature;

	/**
	 * @param alpha regularisation of the freezing curve [K-1]; GEOtop uses 1e5
	 *              (A3)
	 */
	public SnowEquationOfState(double alpha) {
		if (!(alpha > 0.0) || Double.isInfinite(alpha)) {
			throw new IllegalArgumentException("alpha must be positive and finite, got " + alpha);
		}
		this.alpha = alpha;
		this.inflectionTemperature = findInflection();
	}

	public double alpha() {
		return alpha;
	}

	/** Liquid fraction of the total mass, θ_w(T) [-]. */
	public double liquidFraction(double temperature) {
		double tc = temperature - MELTING_TEMPERATURE;
		if (tc >= 0.0) {
			return 1.0;
		}
		double at = alpha * tc;
		return 1.0 / (1.0 + at * at);
	}

	/** dθ_w/dT [K-1]. */
	public double dLiquidFraction(double temperature) {
		double tc = temperature - MELTING_TEMPERATURE;
		if (tc >= 0.0) {
			return 0.0;
		}
		double at = alpha * tc;
		double denominator = 1.0 + at * at;
		return -2.0 * alpha * alpha * tc / (denominator * denominator);
	}

	/** d²θ_w/dT² [K-2]. */
	public double ddLiquidFraction(double temperature) {
		double tc = temperature - MELTING_TEMPERATURE;
		if (tc >= 0.0) {
			return 0.0;
		}
		double at = alpha * tc;
		double denominator = 1.0 + at * at;
		return 2.0 * alpha * alpha * (3.0 * at * at - 1.0) / (denominator * denominator * denominator);
	}

	/** Internal energy U(T; w) [J m-2]. */
	public double internalEnergy(double temperature, double totalMass) {
		return totalMass * specificEnergy(temperature);
	}

	/** dU/dT [J m-2 K-1]. */
	public double dInternalEnergy(double temperature, double totalMass) {
		return totalMass * dSpecificEnergy(temperature);
	}

	/** d²U/dT² [J m-2 K-2]. */
	public double ddInternalEnergy(double temperature, double totalMass) {
		return totalMass * ddSpecificEnergy(temperature);
	}

	/**
	 * Temperature at which dU/dT is largest [K]: the inflection of U, the x* of
	 * the nested Newton (Casulli–Zanolli) decomposition. Close to
	 * T_m - 1/(√3 α), shifted slightly by the difference of the heat capacities.
	 */
	public double inflectionTemperature() {
		return inflectionTemperature;
	}

	/**
	 * Inverse of the equation of state: the temperature at which a cell of total
	 * mass w holds the internal energy U [K]. U is strictly increasing in T, so
	 * the inverse is unique.
	 */
	public double temperature(double internalEnergy, double totalMass) {
		if (!(totalMass > 0.0)) {
			throw new IllegalArgumentException("totalMass must be positive, got " + totalMass);
		}
		if (!Double.isFinite(internalEnergy)) {
			throw new IllegalArgumentException("internalEnergy must be finite, got " + internalEnergy);
		}
		double target = internalEnergy / totalMass;
		if (target >= LATENT_HEAT_FUSION) {
			return MELTING_TEMPERATURE + (target - LATENT_HEAT_FUSION) / SPECIFIC_HEAT_WATER;
		}

		// bracket [low, high] with u(low) <= target < u(high) = L_f
		double high = MELTING_TEMPERATURE;
		double low = MELTING_TEMPERATURE - 1.0;
		while (specificEnergy(low) > target) {
			high = low;
			low = MELTING_TEMPERATURE - 2.0 * (MELTING_TEMPERATURE - low);
			if (low < COLDEST_TEMPERATURE) {
				throw new IllegalArgumentException(
						"internal energy " + internalEnergy + " J/m2 is below any physical temperature");
			}
		}

		// Newton safeguarded by bisection: u is monotone, the bracket always shrinks
		double t = 0.5 * (low + high);
		for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
			double residual = specificEnergy(t) - target;
			if (residual == 0.0) {
				return t;
			}
			if (residual > 0.0) {
				high = t;
			} else {
				low = t;
			}
			double next = t - residual / dSpecificEnergy(t);
			if (!(next > low && next < high)) {
				next = 0.5 * (low + high);
			}
			if (Math.abs(next - t) <= 1e-15 * MELTING_TEMPERATURE || next == low || next == high) {
				return next;
			}
			t = next;
		}
		return t;
	}

	/** Temperature of a cell, from its internal energy and total mass [K]. */
	public double temperature(SnowCell cell) {
		return temperature(cell.internalEnergy(), cell.totalMass());
	}

	/**
	 * Re-partitions ice and liquid of a cell at thermodynamic equilibrium with its
	 * internal energy (specification §3.1, §3.3). Total mass, thickness and U are
	 * unchanged.
	 */
	public SnowCell equilibrate(SnowCell cell) {
		double totalMass = cell.totalMass();
		double liquid = totalMass * liquidFraction(temperature(cell));
		return new SnowCell(totalMass - liquid, liquid, cell.thickness(), cell.internalEnergy());
	}

	private double specificEnergy(double temperature) {
		double theta = liquidFraction(temperature);
		return (SPECIFIC_HEAT_ICE + (SPECIFIC_HEAT_WATER - SPECIFIC_HEAT_ICE) * theta)
				* (temperature - MELTING_TEMPERATURE) + LATENT_HEAT_FUSION * theta;
	}

	private double dSpecificEnergy(double temperature) {
		double theta = liquidFraction(temperature);
		double dTheta = dLiquidFraction(temperature);
		return SPECIFIC_HEAT_ICE + (SPECIFIC_HEAT_WATER - SPECIFIC_HEAT_ICE) * theta
				+ ((SPECIFIC_HEAT_WATER - SPECIFIC_HEAT_ICE) * (temperature - MELTING_TEMPERATURE)
						+ LATENT_HEAT_FUSION) * dTheta;
	}

	private double ddSpecificEnergy(double temperature) {
		double dTheta = dLiquidFraction(temperature);
		double ddTheta = ddLiquidFraction(temperature);
		return 2.0 * (SPECIFIC_HEAT_WATER - SPECIFIC_HEAT_ICE) * dTheta
				+ ((SPECIFIC_HEAT_WATER - SPECIFIC_HEAT_ICE) * (temperature - MELTING_TEMPERATURE)
						+ LATENT_HEAT_FUSION) * ddTheta;
	}

	/**
	 * Root of d²u/dT² below the melting temperature, by bisection: d²u/dT² is
	 * negative just below T_m and positive well below T_m - 1/(√3 α).
	 */
	private double findInflection() {
		double high = MELTING_TEMPERATURE - 1e-3 / alpha;
		double low = MELTING_TEMPERATURE - 10.0 / alpha;
		for (int iteration = 0; iteration < MAX_ITERATIONS && high - low > 0.0; iteration++) {
			double middle = 0.5 * (low + high);
			if (middle == low || middle == high) {
				break;
			}
			if (ddSpecificEnergy(middle) > 0.0) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return 0.5 * (low + high);
	}
}
