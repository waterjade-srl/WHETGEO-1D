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

/**
 * Choice of the latent heat of the vapour flux and of the phase the vapour
 * mass is taken from (specification A6).
 */
public enum LatentHeat {

	/**
	 * Fidelity, GEOtop 3.0: λ(T_s) = L_e(T_s) + L_f if T_s &lt; 0 °C, L_e(T_s)
	 * otherwise ({@code latent}, {@code turbulence.cc:531-543}), re-evaluated at
	 * every iterate of T_s with dλ/dT_s left out of the derivative
	 * ({@code energy.balance.cc:1497-1499}); the vapour mass is always taken
	 * from, or added to, the ice ({@code WBsnow}, {@code snow.cc:1072-1088}). At
	 * T_s ≥ 0 °C evaporation is paid with L_e but removes ice: L_f per kg is
	 * not accounted for.
	 */
	GEOTOP,

	/**
	 * Recommended: the phase is chosen once, from T_s at the start of the step
	 * ({@link VapourPhase#at}); λ is that phase's latent heat at that
	 * temperature, frozen over the step like the resistances, and the vapour mass
	 * is taken from (or added to) that phase, with its own energy. The energy of
	 * the vapour is then the same whatever the phase, and EB(T_s) has no jump at
	 * 0 °C inside the iterations.
	 */
	PHASE_CONSISTENT
}
