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
import java.util.Optional;

/**
 * GEOtop's restratification (specification §4.1, A4): a criterion on the ice
 * mass (snow water equivalent) of each cell, with fixed target cells at the
 * bottom and at the top and elastic cells in the middle. Port of
 * {@code snow_layer_combination} of GEOtop 3.0 ({@code snow.cc}, commit
 * 1f1fc5af), 0-based and on {@link SnowCell}.
 *
 * <p>
 * With m the target ice mass per cell ({@code max_weq_snow}) and W the total
 * mass of the pack:
 * <ol>
 * <li>W &lt; 10⁻⁶ m: the pack vanishes;</li>
 * <li>W &lt; 0.1 m: all cells are merged into one;</li>
 * <li>otherwise: cells with less than 0.1 m of ice are merged with a neighbour;
 * a top cell with more than 1.1 m of ice is split, leaving m below and the rest
 * on top (when the column is full, the lightest pair of elastic cells is merged
 * first); elastic cells with more than 2 m of ice are split, m going on top,
 * while there is room.</li>
 * </ol>
 *
 * <p>
 * Departures from GEOtop, all required by the specification (§6.2) because
 * each breaks conservation:
 * <ul>
 * <li>no cut at a maximum total SWE ({@code SWEmax_tot}), which removed mass and
 * energy without accounting for them;</li>
 * <li>a vanishing pack is released to the soil with its mass and energy
 * instead of being zeroed;</li>
 * <li>merges never fail above 0 °C: the merged cell is simply brought to
 * equilibrium;</li>
 * <li>no temperature is imposed on a new pack; fresh snow brings its own energy
 * ({@link SnowColumn#depositFreshSnow}).</li>
 * </ul>
 * Merges re-partition ice and liquid at equilibrium, as GEOtop's
 * {@code from_internal_energy} does; splits are uniform and keep the phase
 * partition of the parent.
 */
public final class MassCriterionRestratification implements RestratificationPolicy {

	/** GEOtop {@code simpl_snow}: fraction of m below which a cell is merged. */
	public static final double SMALL_CELL_FRACTION = 0.1;

	/** GEOtop {@code no_snow}: fraction of m below which the pack vanishes. */
	public static final double VANISHING_FRACTION = 1e-6;

	/**
	 * Parameters of the criterion.
	 *
	 * @param targetCellIce ice mass per cell m [kg m-2] ({@code max_weq_snow})
	 * @param bottomIce     ice mass held by the fixed bottom cells [kg m-2]
	 *                      ({@code SWE_bottom})
	 * @param topIce        ice mass held by the fixed top cells [kg m-2]
	 *                      ({@code SWE_top})
	 * @param elasticCells  number of elastic cells in the middle (GEOtop keyword
	 *                      119, at least 1)
	 */
	public record Parameters(double targetCellIce, double bottomIce, double topIce, int elasticCells) {

		public Parameters {
			if (!(targetCellIce > 0.0)) {
				throw new IllegalArgumentException("targetCellIce must be positive, got " + targetCellIce);
			}
			if (!(bottomIce >= 0.0) || !(topIce >= 0.0)) {
				throw new IllegalArgumentException("bottomIce and topIce must be non-negative");
			}
			if (elasticCells < 1) {
				throw new IllegalArgumentException("elasticCells must be at least 1, got " + elasticCells);
			}
		}

		/** GEOtop 3.0 defaults: m = 5 kg m-2, 20 + 20 kg m-2, 2 elastic cells. */
		public static Parameters geotopDefaults() {
			return new Parameters(5.0, 20.0, 20.0, 2);
		}

		/** Number of fixed cells at the bottom. */
		public int bottomCells() {
			return (int) Math.floor(bottomIce / targetCellIce);
		}

		/** Maximum number of cells. */
		public int maxCells() {
			return bottomCells() + (int) Math.floor(topIce / targetCellIce) + elasticCells;
		}
	}

	private final Parameters parameters;
	private final SnowEquationOfState eos;

	public MassCriterionRestratification(Parameters parameters, SnowEquationOfState eos) {
		this.parameters = parameters;
		this.eos = eos;
	}

	public Parameters parameters() {
		return parameters;
	}

	@Override
	public Restratified apply(List<SnowCell> input) {
		if (input.size() > parameters.maxCells()) {
			throw new IllegalArgumentException(
					"column has " + input.size() + " cells, more than the " + parameters.maxCells() + " allowed");
		}
		Work work = new Work(input);
		if (work.cells.isEmpty()) {
			return work.result(Optional.empty());
		}
		double m = parameters.targetCellIce();
		double total = SnowColumn.totalMass(work.cells);

		if (total < VANISHING_FRACTION * m) {
			SnowCell released = work.cells.get(0);
			for (int i = 1; i < work.cells.size(); i++) {
				released = released.merge(work.cells.get(i));
				work.merges++;
			}
			work.cells.clear();
			return work.result(Optional.of(released));
		}

		if (total < SMALL_CELL_FRACTION * m) {
			while (work.cells.size() > 1) {
				work.mergeIntoLower(work.cells.size() - 1);
			}
			return work.result(Optional.empty());
		}

		removeSmallCells(work, m);
		splitTopCell(work, m);
		splitElasticCells(work, m);
		return work.result(Optional.empty());
	}

	/**
	 * GEOtop "remove layers": a scan from the bottom merges every cell with too
	 * little ice into its upper neighbour (into the lower one for the top cell),
	 * repeated until a scan finds nothing, at most maxCells + 1 times.
	 */
	private void removeSmallCells(Work work, double m) {
		int passes = 0;
		boolean merged;
		do {
			merged = false;
			for (int i = 0; i < work.cells.size() && work.cells.size() > 1; i++) {
				if (work.cells.get(i).iceMass() < SMALL_CELL_FRACTION * m) {
					if (i == work.cells.size() - 1) {
						work.mergeIntoLower(i);
					} else {
						work.mergeIntoLower(i + 1);
					}
					merged = true;
				}
			}
			passes++;
		} while (passes <= parameters.maxCells() && merged);
	}

	/** GEOtop "add new layer": one new cell per call on a heavy top cell. */
	private void splitTopCell(Work work, double m) {
		int top = work.cells.size() - 1;
		SnowCell topCell = work.cells.get(top);
		if (!(topCell.iceMass() > m * (1.0 + SMALL_CELL_FRACTION))) {
			return;
		}
		if (work.cells.size() == parameters.maxCells()) {
			mergeLightestElasticPair(work);
			top = work.cells.size() - 1;
			topCell = work.cells.get(top);
		}
		double lowerFraction = m / topCell.iceMass();
		work.split(top, lowerFraction);
	}

	/**
	 * Merges the elastic cell e and its upper neighbour e + 1 with the smallest
	 * combined ice mass; ties go to the lowest pair, as in GEOtop.
	 */
	private void mergeLightestElasticPair(Work work) {
		int first = parameters.bottomCells();
		int best = -1;
		double lightest = Double.POSITIVE_INFINITY;
		for (int e = first; e < first + parameters.elasticCells(); e++) {
			if (e + 1 < work.cells.size()) {
				double pair = work.cells.get(e).iceMass() + work.cells.get(e + 1).iceMass();
				if (pair < lightest) {
					lightest = pair;
					best = e;
				}
			}
		}
		if (best < 0) {
			throw new IllegalStateException("no elastic pair to merge in a full column");
		}
		work.mergeIntoLower(best + 1);
	}

	/**
	 * GEOtop "split layers": scanning the elastic cells from the top down, the
	 * first one with more than 2 m of ice gives m to a new cell above it; the scan
	 * restarts until no cell splits or the column is full.
	 */
	private void splitElasticCells(Work work, double m) {
		int first = parameters.bottomCells();
		boolean split = true;
		while (split && work.cells.size() < parameters.maxCells()) {
			split = false;
			for (int e = first + parameters.elasticCells() - 1; e >= first; e--) {
				if (e < work.cells.size() && work.cells.get(e).iceMass() > 2.0 * m) {
					SnowCell cell = work.cells.get(e);
					work.split(e, 1.0 - m / cell.iceMass());
					split = true;
					break;
				}
			}
		}
	}

	/** Mutable working copy of the column with the operation counters. */
	private final class Work {

		private final List<SnowCell> cells;
		private int merges;
		private int splits;

		private Work(List<SnowCell> input) {
			this.cells = new ArrayList<>(input);
		}

		/** Merges cell i into cell i - 1 and removes cell i. */
		private void mergeIntoLower(int i) {
			SnowCell merged = cells.get(i - 1).merge(cells.get(i));
			cells.set(i - 1, eos.equilibrate(merged));
			cells.remove(i);
			merges++;
		}

		/** Splits cell i, the lower piece taking the given fraction. */
		private void split(int i, double lowerFraction) {
			SnowCell[] pieces = cells.get(i).split(lowerFraction, 1.0 - lowerFraction);
			cells.set(i, pieces[0]);
			cells.add(i + 1, pieces[1]);
			splits++;
		}

		private Restratified result(Optional<SnowCell> released) {
			return new Restratified(cells, released, merges, splits);
		}
	}
}
