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
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_WATER;
import static org.geoframe.whetgeo1d.snow.SnowConstants.WATER_DENSITY;

import java.util.ArrayList;
import java.util.List;

/**
 * Percolation of liquid water through the pack, bucket scheme (specification
 * §2.2 step 4, §4.1 "Liquid water percolation", A18). Port of the percolation
 * part of GEOtop 3.0 {@code WBsnow} ({@code snow.cc:1044-1154}, commit
 * 1f1fc5af), made energy conserving.
 *
 * <p>
 * From the top cell down, each cell:
 * <ol>
 * <li>receives the water coming from above (the rain for the top cell) with
 * its mass and internal energy, thickness unchanged, and is brought to
 * equilibrium with {@link SnowEquationOfState#equilibrate}: in a cold cell the
 * water refreezes and its latent heat warms the cell (§3.3, A15);</li>
 * <li>releases the liquid above its retention capacity
 * <pre>
 * W_r = S_r (1 - θ_i) h ρ_w,    θ_i = w_i / (ρ_i h)
 * </pre>
 * (S_r irreducible saturation, θ_i volumetric ice content with ρ_i, A14),
 * optionally slowed by GEOtop's rate limiter, and is brought to equilibrium
 * again.</li>
 * </ol>
 * What leaves the bottom cell is the drainage, handed to the soil with its
 * energy. On an empty column the rain is drained as it is.
 *
 * <p>
 * The operator is meant to run after compaction and melt shrinkage, which can
 * leave a cell over-saturated, θ_i + θ_w &gt; 1, or even with θ_i &gt; 1. Such
 * cells are accepted: the pore volume is taken as max(1 - θ_i, 0), so a cell
 * with no pores retains nothing, and Se is clamped to [0, 1] as in GEOtop, so
 * the limiter never sees a negative or excessive saturation.
 *
 * <p>
 * <b>Energy of the departing water.</b> The water leaves as liquid at the
 * temperature T of the cell, with {@link #liquidWaterEnergy} =
 * L_f + c_w (T - T_m) per kg: the partial specific energy of the liquid in
 * the equation of state, whose U is the sum of c_i (T - T_m) per kg of ice
 * and L_f + c_w (T - T_m) per kg of liquid. Removing liquid at that energy
 * leaves the ice and the remaining liquid where they were; the cell is then
 * re-equilibrated, which with the regularised freezing curve shifts T and the
 * partition only by O(c (T - T_m) / L_f), about 10⁻⁷ relative. Removing the
 * mean specific energy U/w instead would take away part of the ice's (lower)
 * energy with the water, warming the cell and melting ice that did not move.
 * In a cell above T_m, all liquid, the removal leaves T unchanged exactly.
 *
 * <p>
 * Mass and internal energy are conserved to round-off: mass before + rain =
 * mass after + drained, U before + rain energy = U after + drained energy.
 * Thickness is not touched (compaction and the shrinking of melting cells are
 * another operator), except that a cell left with no mass at all, which can
 * only be an ice-free cell, is removed.
 *
 * <p>
 * Departures from GEOtop:
 * <ul>
 * <li>the water reaching a cell is brought to equilibrium at once (§3.3, A15):
 * GEOtop adds it to the liquid after the energy solve and keeps the solved
 * temperature, so the refreezing, and its latent heat, are delayed to the
 * next time step;</li>
 * <li>the departing water carries its energy out of the cell and into the
 * next one; GEOtop moves only mass, since temperature, not energy, is its
 * state;</li>
 * <li>rain enters with the energy chosen by the caller (A18). GEOtop's
 * behaviour is rain at the top-cell temperature,
 * {@code liquidWaterEnergy(rain, T_top)}, i.e. no sensible heat;</li>
 * <li>the rate limiter min(5 Se³ Δt, excess) is optional (on in
 * {@link Parameters#geotop()}, off in {@link Parameters#recommended()}): at
 * hourly steps it binds only for Se &lt; √((1 - θ_i)(1 - S_r) / (5 Δt)) &lt;
 * 0.0075, where it holds back at most (2/3)(1 - θ_i)(1 - S_r) Se* of water
 * volume, with Se* = √((1 - θ_i)(1 - S_r) / (15 Δt)), released at the next
 * steps;</li>
 * <li>GEOtop drains all the liquid of a cell with ice below
 * {@code simpl_snow · max_weq_snow} (0.5 kg m-2 by default); here this is the
 * parameter {@link Parameters#thinCellIce()}, zero in the recommended
 * configuration, where only ice-free cells drain completely;</li>
 * <li>sublimation, which {@code WBsnow} also handles, and compaction, which it
 * calls, are not part of this operator.</li>
 * </ul>
 */
public final class LiquidWaterPercolation {

	/** GEOtop's coefficient of the rate limiter, 5 Se³ Δt [s-1]. */
	public static final double GEOTOP_RATE_COEFFICIENT = 5.0;

	/**
	 * Parameters of the percolation.
	 *
	 * @param irreducibleSaturation S_r, the fraction of the pore volume held by
	 *                              capillarity [-] (GEOtop {@code Sr}, keyword 101,
	 *                              default 0.02)
	 * @param rateLimited           whether the outflow of a cell is limited to
	 *                              {@value #GEOTOP_RATE_COEFFICIENT} Se³ Δt of
	 *                              volumetric water, as in GEOtop
	 * @param thinCellIce           ice mass at or below which a cell holds no
	 *                              liquid at all [kg m-2] (GEOtop
	 *                              {@code simpl_snow · max_weq_snow})
	 */
	public record Parameters(double irreducibleSaturation, boolean rateLimited, double thinCellIce) {

		public Parameters {
			if (!(irreducibleSaturation >= 0.0 && irreducibleSaturation < 1.0)) {
				throw new IllegalArgumentException(
						"irreducibleSaturation must be in [0, 1), got " + irreducibleSaturation);
			}
			if (!(thinCellIce >= 0.0) || Double.isInfinite(thinCellIce)) {
				throw new IllegalArgumentException("thinCellIce must be non-negative and finite, got " + thinCellIce);
			}
		}

		/** GEOtop 3.0: S_r = 0.02, rate limiter on, thin cells below 0.1 × 5 kg m-2. */
		public static Parameters geotop() {
			return new Parameters(0.02, true, 0.5);
		}

		/** Specification, recommended: S_r = 0.02 as the only parameter. */
		public static Parameters recommended() {
			return new Parameters(0.02, false, 0.0);
		}
	}

	private final Parameters parameters;
	private final SnowEquationOfState eos;

	public LiquidWaterPercolation(Parameters parameters, SnowEquationOfState eos) {
		this.parameters = parameters;
		this.eos = eos;
	}

	public Parameters parameters() {
		return parameters;
	}

	/**
	 * Internal energy of liquid water at temperature T [J m-2]:
	 * m (L_f + c_w (T - T_m)). It is what departing water carries, and a way to
	 * give the rain its energy: at max(T_wet-bulb, T_m) in the recommended
	 * configuration, at the top-cell temperature to reproduce GEOtop.
	 *
	 * @param mass        [kg m-2]
	 * @param temperature [K]
	 */
	public static double liquidWaterEnergy(double mass, double temperature) {
		return mass * (LATENT_HEAT_FUSION + SPECIFIC_HEAT_WATER * (temperature - MELTING_TEMPERATURE));
	}

	/** Percolation without rain. */
	public Percolated apply(List<SnowCell> cells, double timeStep) {
		return apply(cells, timeStep, 0.0, 0.0);
	}

	/**
	 * Percolates the liquid water of the column, with the rain entering the top
	 * cell.
	 *
	 * @param cells      the column, index 0 at the bottom; not modified
	 * @param timeStep   Δt [s], used only by the rate limiter
	 * @param rainMass   rain reaching the pack in this step [kg m-2]
	 * @param rainEnergy internal energy of that rain [J m-2], chosen by the
	 *                   caller (see {@link #liquidWaterEnergy})
	 */
	public Percolated apply(List<SnowCell> cells, double timeStep, double rainMass, double rainEnergy) {
		if (!(timeStep > 0.0) || Double.isInfinite(timeStep)) {
			throw new IllegalArgumentException("timeStep must be positive and finite, got " + timeStep);
		}
		if (!(rainMass >= 0.0) || Double.isInfinite(rainMass)) {
			throw new IllegalArgumentException("rainMass must be non-negative and finite, got " + rainMass);
		}
		if (!Double.isFinite(rainEnergy) || (rainMass == 0.0 && rainEnergy != 0.0)) {
			throw new IllegalArgumentException("rainEnergy must be finite, and zero without rain, got " + rainEnergy);
		}

		List<SnowCell> result = new ArrayList<>(cells);
		double iceBefore = 0.0;
		for (SnowCell cell : cells) {
			iceBefore += cell.iceMass();
		}
		double flowMass = rainMass;
		double flowEnergy = rainEnergy;

		for (int i = result.size() - 1; i >= 0; i--) {
			SnowCell cell = result.get(i);
			if (flowMass > 0.0) {
				cell = eos.equilibrate(cell.addMass(0.0, flowMass, flowEnergy, 0.0));
			}
			double outflow = outflow(cell, timeStep);
			if (cell.iceMass() == 0.0 && outflow >= cell.liquidMass()) {
				// an ice-free cell drains completely and disappears, with all its energy
				flowMass = cell.liquidMass();
				flowEnergy = cell.internalEnergy();
				result.remove(i);
				continue;
			}
			if (outflow > 0.0) {
				flowMass = outflow;
				flowEnergy = liquidWaterEnergy(outflow, eos.temperature(cell));
				cell = eos.equilibrate(cell.removeMass(0.0, outflow, flowEnergy, 0.0));
			} else {
				flowMass = 0.0;
				flowEnergy = 0.0;
			}
			result.set(i, cell);
		}

		double iceAfter = 0.0;
		for (SnowCell cell : result) {
			iceAfter += cell.iceMass();
		}
		return new Percolated(result, flowMass, flowEnergy, iceAfter - iceBefore);
	}

	/** Retention capacity W_r = S_r (1 - θ_i) h ρ_w of a cell [kg m-2]. */
	public double retentionCapacity(SnowCell cell) {
		if (cell.iceMass() <= parameters.thinCellIce()) {
			return 0.0;
		}
		return parameters.irreducibleSaturation() * Math.max(cell.porosity(), 0.0) * cell.thickness()
				* WATER_DENSITY;
	}

	/** Liquid leaving a cell in one step [kg m-2], never more than its liquid. */
	private double outflow(SnowCell cell, double timeStep) {
		double excess = cell.liquidMass() - retentionCapacity(cell);
		if (!(excess > 0.0)) {
			return 0.0;
		}
		if (!parameters.rateLimited() || cell.iceMass() <= parameters.thinCellIce()) {
			return excess;
		}
		// GEOtop: Se = (θ_w - S_r (1 - θ_i)) / ((1 - θ_i)(1 - S_r)), outflow min(5 Se³ Δt, θ_w - S_r (1 - θ_i))
		double volume = cell.thickness() * WATER_DENSITY;
		double pores = Math.max(cell.porosity(), 0.0);
		double se = pores > 0.0 ? excess / volume / (pores * (1.0 - parameters.irreducibleSaturation())) : 1.0;
		se = Math.min(Math.max(se, 0.0), 1.0);
		double limited = GEOTOP_RATE_COEFFICIENT * se * se * se * timeStep * volume;
		return Math.min(Math.min(limited, excess), cell.liquidMass());
	}
}
