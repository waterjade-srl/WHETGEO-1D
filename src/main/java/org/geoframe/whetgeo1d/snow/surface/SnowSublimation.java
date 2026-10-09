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
package org.geoframe.whetgeo1d.snow.surface;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;

/**
 * Exchange of vapour between the snow column and the air: sublimation and
 * evaporation remove mass from the top, deposition and condensation add it to
 * the top cell (specification A6). Pure operation on a list of
 * {@link SnowCell}, index 0 at the bottom.
 *
 * <p>
 * The mass m = E Δt comes from the surface energy balance, together with the
 * phase it is taken from ({@link SurfaceEnergyBalance#vapourPhase()}). It
 * leaves (or arrives) <b>with the partial specific energy of that phase</b> at
 * the temperature T of the cell ({@link VapourPhase#specificEnergy}):
 * c_i (T - T_m) per kg of ice, L_f + c_w (T - T_m) per kg of liquid. The latent
 * heat λ m of the phase change is not part of the column's energy: it is the
 * λ E of the energy balance, already paid at the surface.
 *
 * <p>
 * <b>Removal</b>, from the top cell down: each cell gives up to its whole mass,
 * first from the declared phase, then from the other one, each phase with its
 * own partial energy, so the temperature of the cell does not change (to the
 * re-equilibration on the regularised curve). Mass taken from the other phase
 * has been paid in the energy balance with the wrong latent heat: the energy
 * its phase change would need, m (e_declared - e_other), is reported as
 * {@link Sublimated#phaseMismatchEnergy()} (about +L_f per kg of ice taken for
 * evaporation, -L_f per kg of water taken for sublimation) instead of being
 * charged to a cell that may not hold it; it is zero whenever the declared
 * phase is there. A cell given up completely is removed; the internal energy it
 * is left with (zero for a cell at equilibrium) goes to the cell below, or, if
 * there is none, is returned as {@link Sublimated#residualEnergy()}. A request
 * larger than the column returns the remainder as
 * {@link Sublimated#unmetMass()}.
 *
 * <b>Addition</b>: the mass is added to the top cell in the declared phase, at
 * the cell's temperature, and the cell brought to equilibrium. On an empty
 * column nothing is added (the whole request is unmet: frost on bare ground
 * belongs to the soil).
 *
 * <p>
 * Thickness, see {@link ThicknessRule}.
 *
 * <p>
 * Departures from GEOtop ({@code WBsnow}, {@code snow.cc:1072-1088}, commit
 * 1f1fc5af):
 * <ul>
 * <li>GEOtop keeps the solved temperature of the cell; here the cell keeps
 * U minus the energy of the mass removed, and is re-equilibrated: the same
 * temperature, to O(regularisation), when the removed phase is the declared
 * one;</li>
 * <li>with {@link LatentHeat#GEOTOP} the phase is always ice, as GEOtop: at
 * T_s ≥ 0 °C the flux is paid with L_e but ice is removed. Here that ice
 * leaves with c_i (T - T_m), so the column conserves its own energy, but the
 * vapour leaves with L_f per kg less than it should;</li>
 * <li>GEOtop takes only ice: a cell whose ice is exhausted is dropped and its
 * liquid passes to the cell below. Here the liquid of that cell is taken too,
 * with its own energy and a phase mismatch; the mass removed from the column is
 * the same;</li>
 * <li>a request larger than the column is reported as {@link Sublimated#unmetMass()}
 * (GEOtop drops it silently).</li>
 * </ul>
 */
public final class SnowSublimation {

	/**
	 * What happens to the thickness of the cell that exchanges vapour.
	 */
	public enum ThicknessRule {

		/**
		 * GEOtop: the thickness does not change (the density does), except that a
		 * cell given up completely disappears.
		 */
		UNCHANGED,

		/**
		 * The thickness changes in proportion to the mass, at constant bulk
		 * density: sublimation lowers the surface, deposition raises it.
		 */
		CONSTANT_DENSITY
	}

	private SnowSublimation() {
	}

	/**
	 * Exchanges a vapour mass with the column.
	 *
	 * @param cells      the column, index 0 at the bottom, cells at equilibrium
	 * @param vapourMass m = E Δt [kg m-2]: positive removes, negative adds
	 * @param phase      phase the mass is taken from or added to
	 * @param rule       thickness rule
	 * @param eos        equation of state
	 */
	public static Sublimated apply(List<SnowCell> cells, double vapourMass, VapourPhase phase, ThicknessRule rule,
			SnowEquationOfState eos) {
		if (cells == null || phase == null || rule == null || eos == null) {
			throw new IllegalArgumentException("cells, phase, rule and eos must be given");
		}
		if (!Double.isFinite(vapourMass)) {
			throw new IllegalArgumentException("vapourMass must be finite, got " + vapourMass);
		}
		if (vapourMass > 0.0) {
			return remove(cells, vapourMass, phase, rule, eos);
		}
		if (vapourMass < 0.0) {
			return add(cells, -vapourMass, phase, rule, eos);
		}
		return new Sublimated(cells, 0.0, 0.0, 0.0, 0.0, 0.0);
	}

	private static Sublimated remove(List<SnowCell> cells, double mass, VapourPhase phase, ThicknessRule rule,
			SnowEquationOfState eos) {
		VapourPhase other = phase == VapourPhase.ICE ? VapourPhase.LIQUID : VapourPhase.ICE;
		List<SnowCell> result = new ArrayList<>(cells);
		double remaining = mass;
		double removedEnergy = 0.0;
		double mismatch = 0.0;
		double carriedEnergy = 0.0;
		while (remaining > 0.0 && !result.isEmpty()) {
			int top = result.size() - 1;
			SnowCell cell = result.get(top);
			if (carriedEnergy != 0.0) {
				cell = eos.equilibrate(cell.withInternalEnergy(cell.internalEnergy() + carriedEnergy));
				carriedEnergy = 0.0;
			}
			double temperature = eos.temperature(cell);
			double declaredEnergy = phase.specificEnergy(temperature);
			double otherEnergy = other.specificEnergy(temperature);
			double total = cell.totalMass();
			double available = phase == VapourPhase.ICE ? cell.iceMass() : cell.liquidMass();
			double take = Math.min(remaining, total);
			double fromDeclared = Math.min(take, available);
			double fromOther = take - fromDeclared;
			double energy = fromDeclared * declaredEnergy + fromOther * otherEnergy;
			removedEnergy += energy;
			mismatch += fromOther * (declaredEnergy - otherEnergy);
			remaining -= take;
			if (take < total) {
				double ice = Math.min(phase == VapourPhase.ICE ? fromDeclared : fromOther, cell.iceMass());
				double liquid = Math.min(phase == VapourPhase.ICE ? fromOther : fromDeclared, cell.liquidMass());
				double thickness = rule == ThicknessRule.CONSTANT_DENSITY ? cell.thickness() * take / total : 0.0;
				result.set(top, eos.equilibrate(cell.removeMass(ice, liquid, energy, thickness)));
				remaining = 0.0;
			} else {
				carriedEnergy = cell.internalEnergy() - energy;
				result.remove(top);
			}
		}
		double residual = 0.0;
		if (carriedEnergy != 0.0) {
			if (result.isEmpty()) {
				residual = carriedEnergy;
			} else {
				int top = result.size() - 1;
				SnowCell cell = result.get(top);
				result.set(top, eos.equilibrate(cell.withInternalEnergy(cell.internalEnergy() + carriedEnergy)));
			}
		}
		return new Sublimated(result, mass - remaining, removedEnergy, remaining, residual, mismatch);
	}

	private static Sublimated add(List<SnowCell> cells, double mass, VapourPhase phase, ThicknessRule rule,
			SnowEquationOfState eos) {
		if (cells.isEmpty()) {
			return new Sublimated(cells, 0.0, 0.0, -mass, 0.0, 0.0);
		}
		List<SnowCell> result = new ArrayList<>(cells);
		int top = result.size() - 1;
		SnowCell cell = result.get(top);
		double energy = mass * phase.specificEnergy(eos.temperature(cell));
		double thickness = rule == ThicknessRule.CONSTANT_DENSITY ? cell.thickness() * mass / cell.totalMass() : 0.0;
		double ice = phase == VapourPhase.ICE ? mass : 0.0;
		double liquid = phase == VapourPhase.ICE ? 0.0 : mass;
		result.set(top, eos.equilibrate(cell.addMass(ice, liquid, energy, thickness)));
		return new Sublimated(result, -mass, -energy, 0.0, 0.0, 0.0);
	}
}
