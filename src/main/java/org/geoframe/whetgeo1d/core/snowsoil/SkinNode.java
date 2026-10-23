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

/**
 * The skin node of the surface balance (SURF-1, SURF-2): a surface
 * temperature T<sub>s</sub> with no heat capacity, above the centre of the top
 * cell (snow, or soil without snow), joined to it by the conductance of the
 * upper half of the cell, K = λ<sub>top</sub> / (Δz<sub>top</sub>/2).
 *
 * <p>
 * GEOtop has the skin by default ({@code HighestNodeCorrespondsToLayer = 0},
 * keyword 68, {@code energy.balance.cc:1398}, commit 1f1fc5af) as an extra row
 * of zero capacity in its Newton system. WHETGEO's solver cannot take such a
 * row, so the skin is eliminated (SURF-2): with the balance linearised at
 * T<sub>k</sub>, EB(T<sub>s</sub>) ≈ E<sub>0</sub> + s (T<sub>s</sub> - T<sub>k</sub>), s ≤ 0, the skin
 * equation EB(T<sub>s</sub>) = K (T<sub>s</sub> - T<sub>top</sub>) gives
 *
 * <pre>
 * T_s = (E_0 - s T_k + K T_top) / (K - s)
 * flux into the top cell = K (T_s - T_top) = K/(K - s) · (E_0 + s (T_top - T_k))
 * </pre>
 *
 * a flux linear in T<sub>top</sub> that the heat driver takes as it is
 * ({@link TopBoundary.SurfaceFlux}). The surface sweeps relinearise at the
 * skin temperature of the last solve ({@link SurfaceCoupling}); at convergence
 * the skin equation holds with the nonlinear balance. The resistances stay
 * frozen at the start of the step at the temperature of the top cell, where
 * GEOtop restarts its skin at every step ({@code energy.balance.cc:732}).
 *
 * <p>
 * The options differ only over snow, when the skin would rise above 0 °C:
 */
public enum SkinNode {

	/**
	 * No skin: the surface is the top cell, the balance enters at its
	 * temperature. For sensitivity runs (GEOtop's
	 * {@code HighestNodeCorrespondsToLayer = 1}).
	 */
	NONE,

	/**
	 * GEOtop 3.0 (fidelity): a skin above 0 °C over snow is not admitted, and
	 * the step is solved again without the skin, the surface balance at the top
	 * cell ({@code energy.balance.cc:2100} returns -1, then
	 * {@code surfacemelting = 1}, {@code sur = 1}, line 1399).
	 */
	GEOTOP,

	/**
	 * SURF-2 (recommended): over snow the skin is held at 0 °C when the
	 * balance would raise it above: the top cell receives EB(0 °C), and what
	 * conduction from a skin at 0 °C cannot carry melts the top cell. No step
	 * is solved twice.
	 */
	CAPPED;

	/** Whether there is a skin at all. */
	public boolean active() {
		return this != NONE;
	}
}
