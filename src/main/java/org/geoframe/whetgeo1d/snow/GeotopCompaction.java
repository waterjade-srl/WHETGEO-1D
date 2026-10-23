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
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.WATER_DENSITY;

import java.util.ArrayList;
import java.util.List;

/**
 * GEOtop's compaction: destructive metamorphism and overburden, with the
 * coefficients of Anderson (1976) as GEOtop 3.0 uses them. Port of
 * {@code snow_compactation} ({@code snow.cc:88-134}, commit 1f1fc5af) together
 * with the porosity limit applied after it in {@code WBsnow}
 * ({@code snow.cc:1093-1110}).
 *
 * <p>
 * For a cell with volumetric ice content θ_i = w_i / (ρ_i D), volumetric liquid
 * content θ_w = w_l / (ρ_w D), partial ice density ρ = ρ_i θ_i and temperature
 * T [°C], while θ_i &lt; θ_max:
 *
 * <pre>
 * c1  = k_dry                               ρ ≤ ρ_cut
 *     = k_dry exp(-0.046 (ρ - ρ_cut))       ρ &gt; ρ_cut
 * c1 *= k_wet                               if θ_w &gt; 0.001
 * CR1 = -c1 · 2.777·10⁻⁶ · exp(-0.04 (0 - T))                   destructive metamorphism [s-1]
 * η   = η0 exp(0.08 (0 - T) + 0.023 ρ)
 * CR2 = -|cos slope| · Σ_{k ≥ l} (w_i + w_l)_k / η             overburden [s-1]
 * D  ← D exp((CR1 + CR2) Δt)
 * </pre>
 *
 * The load includes the full mass of the cell itself, as in GEOtop. The
 * temperature comes from the internal energy through
 * {@link SnowEquationOfState}; GEOtop uses the temperature of the energy
 * solve at the end of the step, which is the same thing if the caller compacts
 * after the solve.
 *
 * <p>
 * <b>Porosity cap</b> (SNOW-6). GEOtop stops compacting at
 * θ_i = w_i / (ρ_i D) = θ_max (partial ice density 917 · 0.7 = 641.9 kg m-3)
 * but then, after the melt shrinkage, caps the cell at w_i / (ρ_w D) = θ_max
 * (700 kg m-3), resetting a denser cell (after refreezing, say) to the cap and
 * so adding volume ({@code snow.cc:1104-1110}). With
 * {@link Parameters#geotopCap()} (fidelity) this is reproduced: the floor of
 * the compaction is ρ_w θ_max and {@link #capDensity} applies GEOtop's cap
 * after the shrinkage, to the cells above the minimum ice. Without it
 * (recommended), one density, ρ_i: a compacted cell never goes beyond
 * ρ_i θ_max and no cell is ever thickened.
 *
 * <p>
 * Departures from GEOtop:
 * <ul>
 * <li>a cell is never compacted below the volume of its ice plus its liquid,
 * w_i/ρ_i + w_l/ρ_w (GEOtop has no such guard; with θ_max &lt; 1 it matters only
 * for a cell already over-filled with water);</li>
 * <li>slope in radians, temperature in K.</li>
 * </ul>
 * As in GEOtop, only cells holding more than
 * {@link Parameters#minimumIceMass()} of ice are compacted.
 */
public final class GeotopCompaction implements CompactionModel {

	/** Destructive metamorphism, c2 [s-1]. */
	static final double METAMORPHISM_RATE = 2.777e-6;
	/** Destructive metamorphism, temperature coefficient c3 [K-1]. */
	static final double METAMORPHISM_TEMPERATURE_COEFFICIENT = 0.04;
	/** Decay of the dry rate above the density cutoff [m3 kg-1]. */
	static final double METAMORPHISM_DENSITY_COEFFICIENT = 0.046;
	/** Overburden viscosity, temperature coefficient c4 [K-1]. */
	static final double VISCOSITY_TEMPERATURE_COEFFICIENT = 0.08;
	/** Overburden viscosity, density coefficient c5 [m3 kg-1]. */
	static final double VISCOSITY_DENSITY_COEFFICIENT = 0.023;
	/** Volumetric liquid content above which the snow counts as wet [-]. */
	static final double WET_THRESHOLD = 0.001;

	/**
	 * Parameters, GEOtop keywords in brackets.
	 *
	 * @param maxIceVolumeFraction θ_max, volumetric ice content beyond which
	 *                             compaction stops [-] ({@code MaxSnowPorosity},
	 *                             106; despite the name it bounds the ice
	 *                             fraction, not the porosity)
	 * @param dryDeformationRate   k_dry, factor of the destructive metamorphism
	 *                             [-] ({@code DrySnowDefRate}, 107)
	 * @param densityCutoff        ρ_cut, partial ice density above which the
	 *                             destructive metamorphism slows down [kg m-3]
	 *                             ({@code SnowDensityCutoff}, 108)
	 * @param wetDeformationRate   k_wet, enhancement of the destructive
	 *                             metamorphism in wet snow [-]
	 *                             ({@code WetSnowDefRate}, 109)
	 * @param viscosity            η0, viscosity of the overburden at 0 °C and zero
	 *                             density [kg s m-2] ({@code SnowViscosity}, 110)
	 * @param minimumIceMass       cells with less ice are not compacted [kg m-2];
	 *                             GEOtop {@code simpl_snow · max_weq_snow}
	 * @param geotopCap            GEOtop's porosity cap at ρ_w θ_max, which
	 *                             may thicken a cell (fidelity); false: the cap
	 *                             at ρ_i θ_max that never thickens
	 */
	public record Parameters(double maxIceVolumeFraction, double dryDeformationRate, double densityCutoff,
			double wetDeformationRate, double viscosity, double minimumIceMass, boolean geotopCap) {

		/** Parameters with the cap at ρ_i θ_max. */
		public Parameters(double maxIceVolumeFraction, double dryDeformationRate, double densityCutoff,
				double wetDeformationRate, double viscosity, double minimumIceMass) {
			this(maxIceVolumeFraction, dryDeformationRate, densityCutoff, wetDeformationRate, viscosity,
					minimumIceMass, false);
		}

		public Parameters {
			if (!(maxIceVolumeFraction > 0.0 && maxIceVolumeFraction <= 1.0)) {
				throw new IllegalArgumentException(
						"maxIceVolumeFraction must be in (0, 1], got " + maxIceVolumeFraction);
			}
			requireNonNegative(dryDeformationRate, "dryDeformationRate");
			requireNonNegative(densityCutoff, "densityCutoff");
			requireNonNegative(wetDeformationRate, "wetDeformationRate");
			requireNonNegative(minimumIceMass, "minimumIceMass");
			if (!(viscosity > 0.0) || Double.isInfinite(viscosity)) {
				throw new IllegalArgumentException("viscosity must be positive and finite, got " + viscosity);
			}
		}

		/**
		 * GEOtop 3.0 defaults ({@code parameters.cc:1358-1362}): θ_max = 0.7,
		 * k_dry = 1, ρ_cut = 100 kg m-3, k_wet = 1.5, η0 = 10⁶ kg s m-2; minimum ice
		 * 0.1 × 5 kg m-2 ({@code snow.cc:47}, {@code parameters.cc:1372}); GEOtop's
		 * porosity cap at ρ_w θ_max.
		 */
		public static Parameters geotopDefaults() {
			return new Parameters(0.7, 1.0, 100.0, 1.5, 1.0e6, 0.5, true);
		}

		/**
		 * Recommended: GEOtop's coefficients with no minimum ice mass. The
		 * minimum is a constant per cell, so it makes the compaction depend on the
		 * grid: with cells of a few millimetres most of the pack holds less than
		 * 0.5 kg m-2 of ice per cell and stops settling (document 010).
		 */
		public static Parameters recommended() {
			return new Parameters(0.7, 1.0, 100.0, 1.5, 1.0e6, 0.0);
		}

		private static void requireNonNegative(double value, String name) {
			if (!(value >= 0.0) || Double.isInfinite(value)) {
				throw new IllegalArgumentException(name + " must be non-negative and finite, got " + value);
			}
		}
	}

	private final Parameters parameters;
	private final SnowEquationOfState eos;

	public GeotopCompaction(Parameters parameters, SnowEquationOfState eos) {
		this.parameters = parameters;
		this.eos = eos;
	}

	public Parameters parameters() {
		return parameters;
	}

	@Override
	public List<SnowCell> compact(List<SnowCell> cells, double timeDelta, double slopeAngle) {
		if (!(timeDelta >= 0.0) || Double.isInfinite(timeDelta)) {
			throw new IllegalArgumentException("timeDelta must be non-negative and finite, got " + timeDelta);
		}
		if (!Double.isFinite(slopeAngle)) {
			throw new IllegalArgumentException("slopeAngle must be finite, got " + slopeAngle);
		}
		double slopeFactor = Math.abs(Math.cos(slopeAngle));
		SnowCell[] result = new SnowCell[cells.size()];
		double load = 0.0;
		for (int i = cells.size() - 1; i >= 0; i--) {
			SnowCell cell = cells.get(i);
			load += cell.totalMass();
			result[i] = compactCell(cell, slopeFactor * load, timeDelta);
		}
		return List.of(result);
	}

	/**
	 * Compaction rate (CR1 + CR2) of a cell [s-1], negative or zero.
	 *
	 * @param cell        the cell
	 * @param temperature its temperature [K]
	 * @param load        mass of the cell and of everything above it, times
	 *                    |cos slope| [kg m-2]
	 */
	public double rate(SnowCell cell, double temperature, double load) {
		double partialIceDensity = cell.iceMass() / cell.thickness();
		double undercooling = MELTING_TEMPERATURE - temperature;

		double c1 = parameters.dryDeformationRate();
		if (partialIceDensity > parameters.densityCutoff()) {
			c1 *= Math.exp(-METAMORPHISM_DENSITY_COEFFICIENT * (partialIceDensity - parameters.densityCutoff()));
		}
		if (cell.liquidMass() / (WATER_DENSITY * cell.thickness()) > WET_THRESHOLD) {
			c1 *= parameters.wetDeformationRate();
		}
		double metamorphism = -c1 * METAMORPHISM_RATE * Math.exp(-METAMORPHISM_TEMPERATURE_COEFFICIENT * undercooling);

		double eta = parameters.viscosity() * Math.exp(VISCOSITY_TEMPERATURE_COEFFICIENT * undercooling
				+ VISCOSITY_DENSITY_COEFFICIENT * partialIceDensity);
		double overburden = -load / eta;
		return metamorphism + overburden;
	}

	/**
	 * Thinnest a cell may be compacted to [m]: ice at θ_max, and never less than
	 * the volume of its ice and its liquid.
	 */
	public double minimumThickness(SnowCell cell) {
		double capDensity = parameters.geotopCap() ? WATER_DENSITY : ICE_DENSITY;
		double atMaxIce = cell.iceMass() / (capDensity * parameters.maxIceVolumeFraction());
		double filled = cell.iceMass() / ICE_DENSITY + cell.liquidMass() / WATER_DENSITY;
		return Math.max(atMaxIce, filled);
	}

	/**
	 * The minimum ice of the compaction: in fidelity GEOtop's
	 * {@code simpl_snow · max_weq_snow}, below which a cell is neither compacted
	 * nor shrunk by the melt; 0 in the recommended configuration.
	 */
	@Override
	public double thinCellIce() {
		return parameters.minimumIceMass();
	}

	/**
	 * GEOtop's porosity cap after the melt shrinkage ({@code snow.cc:1104-1110}),
	 * with {@link Parameters#geotopCap()}: a cell holding more than the minimum
	 * ice with w_i / (ρ_w D) &gt; θ_max gets D = w_i / (ρ_w θ_max), thinner or
	 * thicker. Without it, nothing.
	 */
	@Override
	public List<SnowCell> capDensity(List<SnowCell> cells) {
		if (!parameters.geotopCap()) {
			return cells;
		}
		SnowCell[] result = new SnowCell[cells.size()];
		for (int i = 0; i < result.length; i++) {
			SnowCell cell = cells.get(i);
			double cap = cell.iceMass() / (WATER_DENSITY * parameters.maxIceVolumeFraction());
			result[i] = cell.iceMass() > parameters.minimumIceMass() && cell.thickness() < cap
					? cell.withThickness(cap)
					: cell;
		}
		return List.of(result);
	}

	private SnowCell compactCell(SnowCell cell, double load, double timeDelta) {
		if (!(cell.iceMass() > parameters.minimumIceMass())
				|| !(cell.iceVolumeFraction() < parameters.maxIceVolumeFraction())) {
			return cell;
		}
		double compacted = cell.thickness() * Math.exp(rate(cell, eos.temperature(cell), load) * timeDelta);
		double floor = Math.min(cell.thickness(), minimumThickness(cell));
		return cell.withThickness(Math.max(compacted, floor));
	}
}
