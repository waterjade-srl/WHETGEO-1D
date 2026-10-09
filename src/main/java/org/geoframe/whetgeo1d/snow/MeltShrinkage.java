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

import java.util.ArrayList;
import java.util.List;

/**
 * Melt shrinkage: when a cell loses ice, its thickness shrinks so that its
 * partial ice density w_i / D stays the same. Port of the line after the
 * compaction in GEOtop's {@code WBsnow} ({@code snow.cc:1100-1102}, commit
 * 1f1fc5af, "melting: snow depth decreases maintaining the same density"):
 *
 * <pre>
 * D ← D · w_i / w_i,previous     if w_i &lt; w_i,previous
 * </pre>
 *
 * GEOtop applies it after the compaction of the same step, with the ice mass
 * at the start of the step as the reference; the caller does the same. Any
 * loss of ice counts, melt or sublimation, as in GEOtop; a gain of ice
 * (refreezing) leaves the thickness alone.
 *
 * <p>
 * Mass and internal energy are untouched. The liquid stays in the smaller
 * volume, so the volumetric liquid content can exceed the pore space; draining
 * it is the job of the percolation, as in GEOtop, which drains it in the same
 * {@code WBsnow} loop.
 *
 * <p>
 * Departures from GEOtop: GEOtop applies the rule only to cells holding more
 * than {@code simpl_snow · max_weq_snow} (0.5 kg m-2) of ice, together with the
 * compaction; here it applies to every cell that still holds some ice. A cell
 * left with no ice keeps its thickness, since a cell cannot have zero
 * thickness: the restratification removes it.
 */
public final class MeltShrinkage {

	private MeltShrinkage() {
	}

	/**
	 * @param previousIceMass ice mass of the cell before the phase change
	 *                        [kg m-2]
	 * @param cell            the cell after the phase change
	 * @return the cell with its thickness reduced in proportion to the lost ice
	 */
	public static SnowCell apply(double previousIceMass, SnowCell cell) {
		if (!(previousIceMass >= 0.0) || Double.isInfinite(previousIceMass)) {
			throw new IllegalArgumentException(
					"previousIceMass must be non-negative and finite, got " + previousIceMass);
		}
		if (!(cell.iceMass() < previousIceMass) || !(cell.iceMass() > 0.0)) {
			return cell;
		}
		return cell.withThickness(cell.thickness() * (cell.iceMass() / previousIceMass));
	}

	/**
	 * Shrinkage of a whole column, cell by cell.
	 *
	 * @param before the column before the phase change
	 * @param after  the same cells after it, index 0 at the bottom
	 */
	public static List<SnowCell> apply(List<SnowCell> before, List<SnowCell> after) {
		if (before.size() != after.size()) {
			throw new IllegalArgumentException(
					"columns differ in size: " + before.size() + " and " + after.size());
		}
		List<SnowCell> result = new ArrayList<>(after.size());
		for (int i = 0; i < after.size(); i++) {
			result.add(apply(before.get(i).iceMass(), after.get(i)));
		}
		return List.copyOf(result);
	}
}
