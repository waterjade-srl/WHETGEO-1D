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
 * Restratification on thicknesses graded from the surface (proposal for the
 * recommended configuration, document 003 §7, amendment draft to A4). The
 * resolution goes where the gradients are: the diurnal wave is damped within a
 * few centimetres of the surface, so the cells are thin at the top and grow
 * towards the ground.
 *
 * <p>
 * The k-th cell counted from the surface (k = 0 the surface cell) has the
 * target thickness
 *
 * <pre>
 * d_k = min(d₀ r^k, d_max)
 * </pre>
 *
 * and is left alone while its thickness stays inside the band
 * [lowerBand · d_k, upperBand · d_k]. At each call:
 * <ol>
 * <li>a pack lighter than {@link Parameters#vanishingMass()} is released to the
 * soil with its mass and energy;</li>
 * <li><b>merges</b>, from the surface down: a cell thinner than lowerBand · d_k
 * is merged into the cell below it (the bottom cell into the cell above), and
 * the scan restarts until no cell is too thin;</li>
 * <li><b>splits</b>, one pass from the surface down: a cell thicker than
 * upperBand · d_k keeps a top piece exactly d_k thick at its place and passes
 * the rest down, as a new cell below it if the column has room, otherwise
 * merged into the cell below. The rest is then the next cell of the pass, so
 * an excess travels down in the same call. The bottom cell of a full column
 * keeps whatever it receives.</li>
 * </ol>
 * Every operation is a uniform split or a merge followed by equilibrium: mass,
 * internal energy and thickness are conserved.
 *
 * <p>
 * Unlike GEOtop's criterion the grid is anchored to the surface: snowfall,
 * compaction and melt move it relative to the snow, and every merge mixes the
 * energy of two cells (the numerical diffusion of the remapping). The bands
 * keep remapping to the steps where a cell leaves its band.
 */
public final class GradedThicknessRestratification implements RestratificationPolicy {

	/**
	 * Parameters of the criterion.
	 *
	 * @param topThickness  d₀, target of the surface cell [m]
	 * @param growth        r, ratio between the targets of two neighbouring
	 *                      cells, at least 1
	 * @param maxThickness  d_max, largest target [m]
	 * @param maxCells      most cells in the column
	 * @param lowerBand     a cell thinner than lowerBand · d_k is merged, in
	 *                      (0, 1)
	 * @param upperBand     a cell thicker than upperBand · d_k is split, above
	 *                      1; for the split pieces to stay inside their bands it
	 *                      must exceed 1 + lowerBand · r
	 * @param vanishingMass a lighter pack is released to the soil [kg m-2]
	 */
	public record Parameters(double topThickness, double growth, double maxThickness, int maxCells, double lowerBand,
			double upperBand, double vanishingMass) {

		public Parameters {
			if (!(topThickness > 0.0) || Double.isInfinite(topThickness)) {
				throw new IllegalArgumentException("topThickness must be positive and finite, got " + topThickness);
			}
			if (!(growth >= 1.0) || Double.isInfinite(growth)) {
				throw new IllegalArgumentException("growth must be at least 1, got " + growth);
			}
			if (!(maxThickness >= topThickness) || Double.isInfinite(maxThickness)) {
				throw new IllegalArgumentException("maxThickness must be at least topThickness, got " + maxThickness);
			}
			if (maxCells < 1) {
				throw new IllegalArgumentException("maxCells must be at least 1, got " + maxCells);
			}
			if (!(lowerBand > 0.0 && lowerBand < 1.0)) {
				throw new IllegalArgumentException("lowerBand must be in (0, 1), got " + lowerBand);
			}
			if (!(upperBand > 1.0 + lowerBand * growth) || Double.isInfinite(upperBand)) {
				throw new IllegalArgumentException(
						"upperBand must exceed 1 + lowerBand * growth = " + (1.0 + lowerBand * growth) + ", got "
								+ upperBand);
			}
			if (!(vanishingMass >= 0.0) || Double.isInfinite(vanishingMass)) {
				throw new IllegalArgumentException("vanishingMass must be non-negative and finite, got " + vanishingMass);
			}
		}

		/**
		 * Starting values of document 003 §7: d₀ = 2 cm, r = 1.5, d_max = 0.5 m, 10
		 * cells (1.985 m of snow at target, more in the bottom cell), bands 0.5 and 2.0 (the 1.5 of the
		 * draft is too narrow: a piece split off at d_k, below r · lowerBand · d_k
		 * = 0.75 d_k of the next target, would be merged back), and GEOtop's
		 * vanishing mass 10⁻⁶ · 5 kg m-2.
		 */
		public static Parameters proposed() {
			return new Parameters(0.02, 1.5, 0.5, 10, 0.5, 2.0, 5e-6);
		}

		/** Target thickness of the k-th cell from the surface [m]. */
		public double target(int fromSurface) {
			return Math.min(topThickness * Math.pow(growth, fromSurface), maxThickness);
		}
	}

	private final Parameters parameters;
	private final SnowEquationOfState eos;

	public GradedThicknessRestratification(Parameters parameters, SnowEquationOfState eos) {
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
		List<SnowCell> cells = new ArrayList<>(input);
		if (cells.isEmpty()) {
			return new Restratified(cells, Optional.empty(), 0, 0);
		}
		int merges = 0;
		int splits = 0;
		if (SnowColumn.totalMass(cells) < parameters.vanishingMass()) {
			SnowCell released = cells.get(0);
			for (int i = 1; i < cells.size(); i++) {
				released = released.merge(cells.get(i));
				merges++;
			}
			return new Restratified(List.of(), Optional.of(released), merges, splits);
		}

		// merges, from the surface down, until no cell is too thin
		boolean merged = true;
		while (merged && cells.size() > 1) {
			merged = false;
			for (int k = 0; k < cells.size(); k++) {
				int i = cells.size() - 1 - k;
				if (cells.get(i).thickness() < parameters.lowerBand() * parameters.target(k)) {
					int lower = i > 0 ? i - 1 : 0;
					SnowCell joined = cells.get(lower).merge(cells.get(lower + 1));
					cells.set(lower, eos.equilibrate(joined));
					cells.remove(lower + 1);
					merges++;
					merged = true;
					break;
				}
			}
		}

		// one split pass from the surface down; the excess travels down
		for (int k = 0; k < cells.size(); k++) {
			int i = cells.size() - 1 - k;
			SnowCell cell = cells.get(i);
			double target = parameters.target(k);
			if (!(cell.thickness() > parameters.upperBand() * target)) {
				continue;
			}
			if (cells.size() < parameters.maxCells()) {
				SnowCell[] pieces = cell.split(1.0 - target / cell.thickness(), target / cell.thickness());
				cells.set(i, pieces[1]);
				cells.add(i, pieces[0]);
				splits++;
			} else if (i > 0) {
				SnowCell[] pieces = cell.split(1.0 - target / cell.thickness(), target / cell.thickness());
				cells.set(i, pieces[1]);
				cells.set(i - 1, eos.equilibrate(cells.get(i - 1).merge(pieces[0])));
				splits++;
				merges++;
			}
		}
		return new Restratified(cells, Optional.empty(), merges, splits);
	}
}
