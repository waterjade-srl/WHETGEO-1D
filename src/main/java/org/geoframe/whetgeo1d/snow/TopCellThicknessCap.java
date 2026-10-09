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
 * Maximum thickness of the top snow cell (specification §3.5, A17), on top of
 * another policy.
 *
 * <p>
 * The top cell is a surface cell kept out of the wrapped policy, which manages
 * the rest of the pack as if the surface cell were not there. At each call:
 * <ol>
 * <li>if the surface cell is thicker than the cap, the excess is split off
 * uniformly and merged into the cell below (it becomes that cell when there is
 * none), leaving a surface cell exactly as thick as the cap;</li>
 * <li>if the surface cell has thinned below {@link #TOLERANCE} × cap, it is
 * merged into the cell below;</li>
 * <li>the wrapped policy restratifies the rest of the pack;</li>
 * <li>if step 2 removed the surface cell, a new one is split off the new top
 * cell when it is thick enough.</li>
 * </ol>
 * A single-cell pack goes through the wrapped policy first, so that it can
 * vanish or grow, and the cap is applied to the result.
 *
 * <p>
 * The cell below the surface keeps receiving the fresh snow passed down by the
 * surface cell, so the wrapped policy sees the same growth from the top that it
 * sees without the cap. The column holds at most one cell more than the wrapped
 * policy allows. Every operation is a uniform split or a merge followed by
 * equilibrium: mass, energy and thickness are conserved.
 *
 * <p>
 * The cap is off in the fidelity configuration (GEOtop has none): there the
 * wrapped policy is used alone.
 */
public final class TopCellThicknessCap implements RestratificationPolicy {

	/** Specification default [m]. */
	public static final double DEFAULT_MAX_THICKNESS = 0.02;

	/**
	 * Relative band around the cap: the surface cell is split only above
	 * (1 + TOLERANCE) × cap and merged away only below TOLERANCE × cap.
	 */
	public static final double TOLERANCE = 0.1;

	private final RestratificationPolicy inner;
	private final double maxThickness;
	private final SnowEquationOfState eos;

	/**
	 * @param inner        policy for the pack below the surface cell
	 * @param maxThickness cap on the surface cell [m]
	 */
	public TopCellThicknessCap(RestratificationPolicy inner, double maxThickness, SnowEquationOfState eos) {
		if (!(maxThickness > 0.0)) {
			throw new IllegalArgumentException("maxThickness must be positive, got " + maxThickness);
		}
		this.inner = inner;
		this.maxThickness = maxThickness;
		this.eos = eos;
	}

	@Override
	public Restratified apply(List<SnowCell> input) {
		if (input.size() <= 1) {
			Restratified first = inner.apply(input);
			List<SnowCell> cells = new ArrayList<>(first.cells());
			int splits = first.splits() + capTop(cells);
			return new Restratified(cells, first.releasedToSoil(), first.merges(), splits);
		}

		List<SnowCell> rest = new ArrayList<>(input.subList(0, input.size() - 1));
		SnowCell surface = input.get(input.size() - 1);
		int merges = 0;
		int splits = 0;

		if (surface.thickness() > maxThickness * (1.0 + TOLERANCE)) {
			double lowerFraction = 1.0 - maxThickness / surface.thickness();
			SnowCell[] pieces = surface.split(lowerFraction, 1.0 - lowerFraction);
			mergeIntoTop(rest, pieces[0]);
			surface = pieces[1];
			merges++;
			splits++;
		} else if (surface.thickness() < TOLERANCE * maxThickness) {
			mergeIntoTop(rest, surface);
			surface = null;
			merges++;
		}

		Restratified below = inner.apply(rest);
		List<SnowCell> cells = new ArrayList<>(below.cells());
		merges += below.merges();
		splits += below.splits();
		Optional<SnowCell> released = below.releasedToSoil();

		if (surface != null) {
			cells.add(surface);
			if (released.isPresent()) {
				// the pack under the surface cell vanished: its remains join the surface cell
				cells.set(cells.size() - 1, eos.equilibrate(released.get().merge(surface)));
				released = Optional.empty();
				merges++;
			}
		} else {
			splits += capTop(cells);
		}
		return new Restratified(cells, released, merges, splits);
	}

	/** Splits a surface cell off the top cell when it is thick enough; returns the splits done. */
	private int capTop(List<SnowCell> cells) {
		if (cells.isEmpty()) {
			return 0;
		}
		int top = cells.size() - 1;
		SnowCell cell = cells.get(top);
		if (!(cell.thickness() > maxThickness * (1.0 + TOLERANCE))) {
			return 0;
		}
		double lowerFraction = 1.0 - maxThickness / cell.thickness();
		SnowCell[] pieces = cell.split(lowerFraction, 1.0 - lowerFraction);
		cells.set(top, pieces[0]);
		cells.add(pieces[1]);
		return 1;
	}

	private void mergeIntoTop(List<SnowCell> rest, SnowCell cell) {
		int top = rest.size() - 1;
		rest.set(top, eos.equilibrate(rest.get(top).merge(cell)));
	}
}
