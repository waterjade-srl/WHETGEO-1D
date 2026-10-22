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
import java.util.Objects;

import org.geoframe.whetgeo1d.snow.SnowCell;

/**
 * Everything a {@link SnowSoilSolver} reads from one step to the next (ARCH-5,
 * restart state; SOL-5, rollback): with the same configuration, a solver
 * restored from this state and a solver that reached it by stepping produce the
 * same next step to the bit.
 *
 * <p>
 * What the step reads, and where it lives:
 * <ul>
 * <li>the pack, as immutable {@link SnowCell}s (ice, liquid, thickness and U;
 * the temperatures follow from the equation of state);</li>
 * <li>the surface age (SNOW-8) and the surface energy deferred to the next
 * step (the phase mismatch of the vapour, SURF-4);</li>
 * <li>the soil temperatures of the heat driver, the start of its Newton
 * iterations and the state of the closures;</li>
 * <li>the soil suctions the heat closures read (ψ<sub>w0</sub> capped at 0
 * for a freezing soil, A9), which the transport of the soil water writes;</li>
 * <li>the suctions of Richards (the potential of the liquid);</li>
 * <li>ψ<sub>w0</sub> of the freezing cells, storage above saturation
 * included (DN-12).</li>
 * </ul>
 * Everything else in the drivers (conductivities, x*, volumes, the window of
 * active cells, the kernels) is recomputed from these at the start of every
 * step. The fidelity configuration has no liquid held out of equilibrium yet
 * (STA-3): when it does, it is one more field here and a new format version
 * in {@link SnowSoilRestart}.
 *
 * <p>
 * Arrays are copied in and out; the record is immutable.
 *
 * @param snow                 the pack, index 0 at the bottom
 * @param surfaceAge           non-dimensional age of the snow surface
 * @param pendingSurfaceEnergy energy waiting to enter the surface [J m-2]
 * @param soilTemperatures     [K], bottom first; empty without soil
 * @param soilHeatSuctions     ψ read by the soil heat closures [m], bottom
 *                             first; empty without soil
 * @param soilWaterSuctions    ψ of Richards [m], bottom first; null without
 *                             soil water
 * @param totalSuctions        ψ<sub>w0</sub> with storage above saturation [m],
 *                             bottom first; empty without soil
 */
public record SnowSoilState(List<SnowCell> snow, double surfaceAge, double pendingSurfaceEnergy,
		double[] soilTemperatures, double[] soilHeatSuctions, double[] soilWaterSuctions, double[] totalSuctions) {

	public SnowSoilState {
		snow = List.copyOf(snow);
		soilTemperatures = Objects.requireNonNull(soilTemperatures, "soilTemperatures").clone();
		soilHeatSuctions = Objects.requireNonNull(soilHeatSuctions, "soilHeatSuctions").clone();
		soilWaterSuctions = soilWaterSuctions == null ? null : soilWaterSuctions.clone();
		totalSuctions = Objects.requireNonNull(totalSuctions, "totalSuctions").clone();
		int n = soilTemperatures.length;
		if (soilHeatSuctions.length != n || totalSuctions.length != n
				|| (soilWaterSuctions != null && soilWaterSuctions.length != n)) {
			throw new IllegalArgumentException("soil arrays of different lengths");
		}
	}

	@Override
	public double[] soilTemperatures() {
		return soilTemperatures.clone();
	}

	@Override
	public double[] soilHeatSuctions() {
		return soilHeatSuctions.clone();
	}

	@Override
	public double[] soilWaterSuctions() {
		return soilWaterSuctions == null ? null : soilWaterSuctions.clone();
	}

	@Override
	public double[] totalSuctions() {
		return totalSuctions.clone();
	}

	/** Number of soil cells. */
	public int soilCells() {
		return soilTemperatures.length;
	}

	/**
	 * Whether two states are the same to the bit: every double compared by
	 * {@link Double#doubleToRawLongBits}, the snow cell by cell.
	 */
	public boolean bitwiseEquals(SnowSoilState other) {
		if (other == null || snow.size() != other.snow.size()) {
			return false;
		}
		for (int j = 0; j < snow.size(); j++) {
			SnowCell a = snow.get(j);
			SnowCell b = other.snow.get(j);
			if (!same(a.iceMass(), b.iceMass()) || !same(a.liquidMass(), b.liquidMass())
					|| !same(a.thickness(), b.thickness()) || !same(a.internalEnergy(), b.internalEnergy())) {
				return false;
			}
		}
		return same(surfaceAge, other.surfaceAge) && same(pendingSurfaceEnergy, other.pendingSurfaceEnergy)
				&& same(soilTemperatures, other.soilTemperatures) && same(soilHeatSuctions, other.soilHeatSuctions)
				&& same(soilWaterSuctions, other.soilWaterSuctions) && same(totalSuctions, other.totalSuctions);
	}

	private static boolean same(double a, double b) {
		return Double.doubleToRawLongBits(a) == Double.doubleToRawLongBits(b);
	}

	private static boolean same(double[] a, double[] b) {
		if (a == null || b == null) {
			return a == b;
		}
		if (a.length != b.length) {
			return false;
		}
		for (int i = 0; i < a.length; i++) {
			if (!same(a[i], b[i])) {
				return false;
			}
		}
		return true;
	}
}
