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

import org.geoframe.whetgeo1d.snow.SnowCell;

/**
 * Shortwave radiation absorbed below the surface of the pack (SNOW-7).
 * <p>
 * With more than one snow cell part or all of the net shortwave is absorbed
 * inside the cells, and what crosses the whole pack is absorbed by the top soil
 * cell. With one snow cell, or none, the whole net shortwave stays in the
 * surface balance. The partition uses the cells at the start of the step, as
 * GEOtop does.
 * <p>
 * Broadband schemes ({@link #GEOTOP}, {@link #COMPOUNDED}) use the extinction
 * coefficient k = ρ/3 + 50 m<sup>-1</sup> (ρ in kg m-3) of GEOtop's
 * {@code rad_snow_absorption} ({@code radiation.cc:582-613}), and none of the
 * net shortwave reaches the surface (GEOtop's {@code SWlayer(0) = 0}).
 * <p>
 * {@link #VISIBLE} (recommended) keeps the near infrared, absorbed within
 * the first millimetres, in the surface balance, the counterpart of Crocus's
 * β<sub>3</sub> = +∞ band (Lafaysse et al., 2026, Eq. 77), and lets the
 * visible penetrate with the transmission compounded cell by cell.
 */
public enum ShortwavePenetration {

	/** All the net shortwave in the surface balance. For sensitivity runs. */
	NONE,

	/**
	 * GEOtop 3.0 (fidelity): the extinction coefficient of each cell applied to
	 * the cumulative depth of its bottom, R e<sup>-k<sub>m</sub> z<sub>m</sub></sup>,
	 * so a cell can absorb a negative amount when the density varies.
	 */
	GEOTOP,

	/**
	 * The whole net shortwave with the transmission compounded cell by cell,
	 * Π<sub>j</sub> e<sup>-k<sub>j</sub> D<sub>j</sub></sup>; every cell absorbs a
	 * non-negative amount.
	 */
	COMPOUNDED,

	/**
	 * Recommended: two bands, the near infrared absorbed at the surface, the
	 * visible compounded cell by cell with Crocus's visible coefficient
	 * β<sub>1</sub> = max(40, 0.00192 ρ/√d) m<sup>-1</sup> (Eq. 75), d =
	 * {@link #OPTICAL_DIAMETER}.
	 */
	VISIBLE;

	/**
	 * Optical diameter of the spectral coefficients [m]: rounded grains, between
	 * Crocus's fresh snow (0.1 mm) and the upper bound of its albedo (2.3 mm).
	 * The pack carries no grain size.
	 */
	public static final double OPTICAL_DIAMETER = 5e-4;

	/** True when the scheme keeps part of the net shortwave at the surface by band. */
	public boolean spectral() {
		return this == VISIBLE;
	}

	/**
	 * Net shortwave absorbed in each cell by a broadband scheme [W m-2].
	 *
	 * @see #absorbed(List, double, double)
	 */
	public double[] absorbed(List<SnowCell> snow, double netShortwave) {
		if (spectral()) {
			throw new IllegalStateException(this + " needs the visible and the near infrared");
		}
		return absorbed(snow, netShortwave, 0.0);
	}

	/**
	 * Net shortwave absorbed in each cell [W m-2]. What is not in the returned
	 * array stays in the surface balance.
	 *
	 * @param snow            cells at the start of the step, index 0 at the bottom
	 * @param netVisible      net visible shortwave absorbed by the ground [W m-2]
	 * @param netNearInfrared net near-infrared shortwave absorbed by the ground [W m-2]
	 * @return per snow cell (index 0 at the bottom), then the part transmitted to
	 *         the top soil cell at index {@code snow.size()}; null when the
	 *         whole net shortwave stays at the surface
	 */
	public double[] absorbed(List<SnowCell> snow, double netVisible, double netNearInfrared) {
		double net = netVisible + netNearInfrared;
		if (this == NONE || snow.size() < 2 || net == 0.0) {
			return null;
		}
		double[] absorbed = new double[snow.size() + 1];
		switch (this) {
		case GEOTOP -> addBand(absorbed, snow, net, Band.GEOTOP, true);
		case COMPOUNDED -> addBand(absorbed, snow, net, Band.GEOTOP, false);
		case VISIBLE -> addBand(absorbed, snow, netVisible, Band.CROCUS_VISIBLE, false);
		default -> throw new IllegalStateException(name());
		}
		return absorbed;
	}

	private enum Band {
		GEOTOP, CROCUS_VISIBLE;

		/** Extinction coefficient [m-1] at density ρ [kg m-3]. */
		double coefficient(double density) {
			return switch (this) {
			case GEOTOP -> density / 3.0 + 50.0;
			case CROCUS_VISIBLE -> Math.max(40.0, 0.00192 * density / Math.sqrt(OPTICAL_DIAMETER));
			};
		}
	}

	/** Adds the absorption of one band, from the top cell down, then the soil. */
	private static void addBand(double[] absorbed, List<SnowCell> snow, double radiation, Band band,
			boolean cumulativeDepth) {
		int ns = snow.size();
		double above = 1.0;
		double depth = 0.0;
		for (int j = ns - 1; j >= 0; j--) {
			SnowCell cell = snow.get(j);
			double k = band.coefficient(cell.totalMass() / cell.thickness());
			depth += cell.thickness();
			double below = cumulativeDepth ? Math.exp(-k * depth) : above * Math.exp(-k * cell.thickness());
			absorbed[j] += radiation * (above - below);
			above = below;
		}
		absorbed[ns] += radiation * above;
	}
}
