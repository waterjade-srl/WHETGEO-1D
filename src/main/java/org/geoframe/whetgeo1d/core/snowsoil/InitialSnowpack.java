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

import static org.geoframe.whetgeo1d.snow.SnowConstants.ICE_DENSITY;
import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_WATER;
import static org.geoframe.whetgeo1d.snow.SnowConstants.WATER_DENSITY;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.whetgeo1d.snow.RestratificationPolicy;
import org.geoframe.whetgeo1d.snow.Restratified;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SurfaceSnowAge;

/**
 * The snowpack at the start of a run, described as an observer describes it
 * (a pit, a survey, GEOtop's initial keywords), not by the cells of the model:
 * how many cells there are and how thick is decided by the restratification of
 * the configuration, as at every step.
 *
 * <p>
 * Each declared layer becomes a cell with its ice, its liquid water and the
 * internal energy U = (c<sub>i</sub> I + c<sub>w</sub> W)(T - T<sub>m</sub>) +
 * L W; the equation of state then re-partitions the phases at equilibrium,
 * conserving U (a layer declared at -2 °C with liquid water refreezes). Layers
 * beyond the capacity of the pack are merged, the thinnest with its thinner
 * neighbour, and the restratification policy is applied until it changes
 * nothing. Mass and energy are conserved throughout.
 */
public final class InitialSnowpack {

	/** Restratifications at most, to reach a column the policy leaves as it is. */
	private static final int MAX_PASSES = 50;

	/**
	 * A layer of the initial pack.
	 *
	 * @param thickness   [m]
	 * @param density     of ice and liquid together [kg m-3]
	 * @param temperature [K], at most the melting temperature
	 * @param liquid      liquid water [kg m-2]
	 */
	public record Layer(double thickness, double density, double temperature, double liquid) {

		public Layer {
			if (!(thickness > 0.0) || Double.isInfinite(thickness)) {
				throw new IllegalArgumentException("thickness must be positive and finite, got " + thickness);
			}
			if (!(density > 0.0) || Double.isInfinite(density)) {
				throw new IllegalArgumentException("density must be positive and finite, got " + density);
			}
			if (!(temperature > 0.0 && temperature <= MELTING_TEMPERATURE)) {
				throw new IllegalArgumentException(
						"temperature must be positive and at most " + MELTING_TEMPERATURE + " K, got " + temperature);
			}
			if (!(liquid >= 0.0 && liquid <= density * thickness)) {
				throw new IllegalArgumentException("liquid must be in [0, density x thickness], got " + liquid);
			}
			double filled = (density * thickness - liquid) / ICE_DENSITY + liquid / WATER_DENSITY;
			if (filled > thickness) {
				throw new IllegalArgumentException("ice and liquid do not fit in the layer: density " + density
						+ " kg m-3 with " + liquid + " kg m-2 of liquid in " + thickness + " m");
			}
		}

		/** Ice plus liquid [kg m-2]. */
		public double mass() {
			return density * thickness;
		}
	}

	/**
	 * The initial pack: its layers, top first, and the age of its surface.
	 *
	 * @param layers  top first; empty: no snow
	 * @param ageDays age of the surface [d], GEOtop's {@code InitSnowAge}
	 */
	public record Description(List<Layer> layers, double ageDays) {

		public Description {
			layers = List.copyOf(layers);
			if (!(ageDays >= 0.0) || Double.isInfinite(ageDays)) {
				throw new IllegalArgumentException("ageDays must be non-negative and finite, got " + ageDays);
			}
		}

		/**
		 * One layer from a snow water equivalent, a density and a temperature,
		 * as GEOtop's {@code InitSWE}, {@code InitSnowDensity},
		 * {@code InitSnowTemp}, {@code InitSnowAge}; no snow for a zero SWE.
		 *
		 * @param swe         [kg m-2]
		 * @param density     [kg m-3]
		 * @param temperature [K]
		 * @param ageDays     [d]
		 */
		public static Description bulk(double swe, double density, double temperature, double ageDays) {
			if (!(swe >= 0.0)) {
				throw new IllegalArgumentException("swe must be non-negative, got " + swe);
			}
			return new Description(swe == 0.0 ? List.of() : List.of(new Layer(swe / density, density, temperature, 0.0)),
					ageDays);
		}

		/** Ice plus liquid of the pack [kg m-2]. */
		public double mass() {
			return layers.stream().mapToDouble(Layer::mass).sum();
		}

		/** Internal energy of the pack as declared [J m-2]. */
		public double internalEnergy() {
			return layers.stream().mapToDouble(InitialSnowpack::energy).sum();
		}
	}

	/** The cells of the model and the non-dimensional age of the surface. */
	public record Snowpack(List<SnowCell> cells, double surfaceAge) {

		public Snowpack {
			cells = List.copyOf(cells);
		}
	}

	private InitialSnowpack() {
	}

	/**
	 * The cells, index 0 at the bottom, of a declared pack.
	 *
	 * @param description the declared pack
	 * @param eos         equation of state of the configuration
	 * @param policy      restratification of the configuration
	 * @param maxCells    capacity of the pack in cells
	 * @return the cells and the surface age (non-dimensional, converted as
	 *         GEOtop with the temperature of the top layer); no cells for a pack
	 *         the policy would release to the soil
	 */
	public static Snowpack cells(Description description, SnowEquationOfState eos, RestratificationPolicy policy,
			int maxCells) {
		if (maxCells < 1) {
			throw new IllegalArgumentException("maxCells must be at least 1, got " + maxCells);
		}
		List<Layer> layers = description.layers();
		if (layers.isEmpty()) {
			return new Snowpack(List.of(), 0.0);
		}
		List<SnowCell> cells = new ArrayList<>();
		for (int i = layers.size() - 1; i >= 0; i--) {
			Layer l = layers.get(i);
			cells.add(new SnowCell(l.mass() - l.liquid(), l.liquid(), l.thickness(), energy(l)));
		}
		while (cells.size() > maxCells) {
			int thinnest = 0;
			for (int i = 1; i < cells.size(); i++) {
				if (cells.get(i).thickness() < cells.get(thinnest).thickness()) {
					thinnest = i;
				}
			}
			int other = thinnest == 0 ? 1
					: thinnest == cells.size() - 1 ? thinnest - 1
							: cells.get(thinnest - 1).thickness() <= cells.get(thinnest + 1).thickness() ? thinnest - 1
									: thinnest + 1;
			int lower = Math.min(thinnest, other);
			cells.set(lower, cells.get(lower).merge(cells.get(lower + 1)));
			cells.remove(lower + 1);
		}
		cells.replaceAll(eos::equilibrate);
		for (int pass = 0; pass < MAX_PASSES; pass++) {
			Restratified r = policy.apply(cells);
			if (r.releasedToSoil().isPresent()) {
				return new Snowpack(List.of(), 0.0);
			}
			cells = new ArrayList<>(r.cells());
			cells.replaceAll(eos::equilibrate);
			if (r.merges() == 0 && r.splits() == 0) {
				break;
			}
		}
		double age = SurfaceSnowAge.fromSeconds(description.ageDays() * 86400.0, layers.get(0).temperature());
		return new Snowpack(cells, age);
	}

	/** U of a layer [J m-2], referred to the melting temperature. */
	static double energy(Layer l) {
		double ice = l.mass() - l.liquid();
		return (SPECIFIC_HEAT_ICE * ice + SPECIFIC_HEAT_WATER * l.liquid()) * (l.temperature() - MELTING_TEMPERATURE)
				+ LATENT_HEAT_FUSION * l.liquid();
	}
}
