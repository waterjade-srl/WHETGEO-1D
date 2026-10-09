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

import static org.geoframe.whetgeo1d.snow.SnowConstants.ICE_DENSITY;
import static org.geoframe.whetgeo1d.snow.SnowConstants.WATER_DENSITY;

/**
 * State of one snow cell (specification §3.1): the extensive quantities only,
 * per unit area. Temperature, density, porosity and volumetric contents are
 * derived, never stored; the temperature comes from the internal energy through
 * {@link SnowEquationOfState}.
 *
 * <p>
 * The operations are pure and return new instances. They transfer total mass,
 * thickness and internal energy exactly; ice and liquid are summed as they
 * stand, and are re-partitioned at equilibrium by
 * {@link SnowEquationOfState#equilibrate(SnowCell)} when the caller needs it.
 *
 * <p>
 * The cell holds no microstructure: the snow age that drives the albedo is a
 * property of the surface, kept by {@link SurfaceSnowAge}, not of the cells.
 *
 * @param iceMass        [kg m-2]
 * @param liquidMass     [kg m-2]
 * @param thickness      [m]
 * @param internalEnergy U [J m-2], referred to {@link SnowConstants#MELTING_TEMPERATURE}
 */
public record SnowCell(double iceMass, double liquidMass, double thickness, double internalEnergy) {

	public SnowCell {
		requireNonNegative(iceMass, "iceMass");
		requireNonNegative(liquidMass, "liquidMass");
		if (!(iceMass + liquidMass > 0.0)) {
			throw new IllegalArgumentException("a snow cell must hold some mass");
		}
		if (!(thickness > 0.0) || Double.isInfinite(thickness)) {
			throw new IllegalArgumentException("thickness must be positive and finite, got " + thickness);
		}
		if (!Double.isFinite(internalEnergy)) {
			throw new IllegalArgumentException("internalEnergy must be finite, got " + internalEnergy);
		}
	}

	/** Cell at temperature T, with its phases at equilibrium. */
	public static SnowCell atTemperature(SnowEquationOfState eos, double totalMass, double thickness,
			double temperature) {
		double liquid = totalMass * eos.liquidFraction(temperature);
		return new SnowCell(totalMass - liquid, liquid, thickness, eos.internalEnergy(temperature, totalMass));
	}

	/** Ice plus liquid [kg m-2]. */
	public double totalMass() {
		return iceMass + liquidMass;
	}

	/** Bulk density, ice plus liquid [kg m-3]. */
	public double density() {
		return totalMass() / thickness;
	}

	/** Volumetric ice content [-]. */
	public double iceVolumeFraction() {
		return iceMass / (ICE_DENSITY * thickness);
	}

	/** Volumetric liquid content [-]. */
	public double liquidVolumeFraction() {
		return liquidMass / (WATER_DENSITY * thickness);
	}

	/** Porosity, the volume not occupied by ice [-]. */
	public double porosity() {
		return 1.0 - iceVolumeFraction();
	}

	/** This cell and the other one as a single cell. */
	public SnowCell merge(SnowCell other) {
		return new SnowCell(iceMass + other.iceMass, liquidMass + other.liquidMass, thickness + other.thickness,
				internalEnergy + other.internalEnergy);
	}

	/**
	 * Splits the cell into uniform pieces, bottom to top, each holding the given
	 * fraction of every extensive quantity. The last piece takes the remainder,
	 * so the pieces add up to this cell to round-off.
	 *
	 * @param fractions positive, summing to one
	 */
	public SnowCell[] split(double... fractions) {
		if (fractions.length < 2) {
			throw new IllegalArgumentException("a split needs at least two pieces");
		}
		double sum = 0.0;
		for (double fraction : fractions) {
			if (!(fraction > 0.0)) {
				throw new IllegalArgumentException("split fractions must be positive, got " + fraction);
			}
			sum += fraction;
		}
		if (Math.abs(sum - 1.0) > 1e-12) {
			throw new IllegalArgumentException("split fractions must sum to one, got " + sum);
		}
		SnowCell[] pieces = new SnowCell[fractions.length];
		double ice = iceMass;
		double liquid = liquidMass;
		double depth = thickness;
		double energy = internalEnergy;
		for (int i = 0; i < fractions.length - 1; i++) {
			double f = fractions[i];
			pieces[i] = new SnowCell(f * iceMass, f * liquidMass, f * thickness, f * internalEnergy);
			ice -= pieces[i].iceMass;
			liquid -= pieces[i].liquidMass;
			depth -= pieces[i].thickness;
			energy -= pieces[i].internalEnergy;
		}
		pieces[fractions.length - 1] = new SnowCell(Math.max(ice, 0.0), Math.max(liquid, 0.0), depth, energy);
		return pieces;
	}

	/**
	 * Adds mass with its energy and volume, for instance fresh snow or rain.
	 */
	public SnowCell addMass(double addedIce, double addedLiquid, double addedEnergy, double addedThickness) {
		requireNonNegative(addedIce, "addedIce");
		requireNonNegative(addedLiquid, "addedLiquid");
		requireNonNegative(addedThickness, "addedThickness");
		return new SnowCell(iceMass + addedIce, liquidMass + addedLiquid, thickness + addedThickness,
				internalEnergy + addedEnergy);
	}

	/**
	 * Removes mass with its energy and volume, for instance drained water or
	 * sublimated ice. Fails if the cell would be left with negative mass or
	 * thickness.
	 */
	public SnowCell removeMass(double removedIce, double removedLiquid, double removedEnergy,
			double removedThickness) {
		requireNonNegative(removedIce, "removedIce");
		requireNonNegative(removedLiquid, "removedLiquid");
		requireNonNegative(removedThickness, "removedThickness");
		return new SnowCell(iceMass - removedIce, liquidMass - removedLiquid, thickness - removedThickness,
				internalEnergy - removedEnergy);
	}

	/** Same cell, with the given internal energy. */
	public SnowCell withInternalEnergy(double newInternalEnergy) {
		return new SnowCell(iceMass, liquidMass, thickness, newInternalEnergy);
	}

	/** Same cell, with the given thickness (compaction). */
	public SnowCell withThickness(double newThickness) {
		return new SnowCell(iceMass, liquidMass, newThickness, internalEnergy);
	}

	private static void requireNonNegative(double value, String name) {
		if (!(value >= 0.0) || Double.isInfinite(value)) {
			throw new IllegalArgumentException(name + " must be non-negative and finite, got " + value);
		}
	}
}
