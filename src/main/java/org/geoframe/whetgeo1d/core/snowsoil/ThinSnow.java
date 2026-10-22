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

import org.geoframe.whetgeo1d.snow.surface.MoninObukhov;
import org.geoframe.whetgeo1d.snow.surface.SurfaceEnergyBalance;

/**
 * GEOtop's regime for a thin snowpack (DN-14 open point; fidelity): below a
 * depth the surface balance over snow takes the soil's properties. GEOtop 3.0
 * ({@code EnergyBalance}, commit 1f1fc5af), with the snow depth D at the start
 * of the step:
 *
 * <ul>
 * <li><b>roughness</b>: z<sub>0</sub> of the soil ({@code SoilRoughness},
 * default 10 mm) unless D &gt; {@code ThresSnowSoilRough} (default
 * {@code SoilRoughness}) ({@code update_roughness_soil},
 * {@code energy.balance.cc:609-610, 2836-2853}; defaults
 * {@code parameters.cc:1246-1248}). The displacement height is 0 and the
 * scalar roughness is the rigid-surface one in both regimes, as over snow
 * here;</li>
 * <li><b>emissivity</b>: the soil's ({@code SoilEmissiv}, default 0.96) unless
 * D &gt; 10 mm ({@code energy.balance.cc:591-599}; default
 * {@code parameters.cc:1273}).</li>
 * </ul>
 *
 * The blending of the albedo with the ground for thin snow (SURF-5) is part of
 * {@code SnowAlbedo} in both configurations, and the absorption of shortwave
 * inside the pack (SNOW-7) is not ported. The recommended configuration has no
 * thin-snow regime ({@link #none()}): the design does not adopt it there.
 *
 * @param roughnessThreshold  depth at or below which the soil roughness is used
 *                            [m]; 0: never
 * @param soilRoughness       z<sub>0</sub> of the soil [m]
 * @param emissivityThreshold depth at or below which the soil emissivity is used
 *                            [m]; 0: never
 * @param soilEmissivity      ε of the soil [-]
 */
public record ThinSnow(double roughnessThreshold, double soilRoughness, double emissivityThreshold,
		double soilEmissivity) {

	/** GEOtop's fixed depth below which the soil emissivity is used, 10 mm [m]. */
	public static final double GEOTOP_EMISSIVITY_THRESHOLD = 0.010;
	/** GEOtop's default {@code SoilRoughness}, 10 mm [m]. */
	public static final double GEOTOP_SOIL_ROUGHNESS = 0.010;
	/** GEOtop's default {@code SoilEmissiv}. */
	public static final double GEOTOP_SOIL_EMISSIVITY = 0.96;

	public ThinSnow {
		if (!(roughnessThreshold >= 0.0) || !(emissivityThreshold >= 0.0) || !(soilRoughness > 0.0)
				|| !(soilEmissivity > 0.0 && soilEmissivity <= 1.0)) {
			throw new IllegalArgumentException("invalid thin-snow parameters");
		}
	}

	/** No thin-snow regime: the snow's properties at any depth. */
	public static ThinSnow none() {
		return new ThinSnow(0.0, GEOTOP_SOIL_ROUGHNESS, 0.0, GEOTOP_SOIL_EMISSIVITY);
	}

	/** GEOtop 3.0's defaults: soil roughness 10 mm below 10 mm of snow, emissivity 0.96 below 10 mm. */
	public static ThinSnow geotop() {
		return new ThinSnow(GEOTOP_SOIL_ROUGHNESS, GEOTOP_SOIL_ROUGHNESS, GEOTOP_EMISSIVITY_THRESHOLD,
				GEOTOP_SOIL_EMISSIVITY);
	}

	/** Same with GEOtop's keywords: {@code SoilRoughness}, {@code ThresSnowSoilRough} [m], {@code SoilEmissiv}. */
	public ThinSnow with(double roughness, double threshold, double emissivity) {
		return new ThinSnow(threshold, roughness, emissivityThreshold, emissivity);
	}

	/** Whether the soil roughness applies at snow depth D [m]: GEOtop's {@code snowD > thres} is false. */
	public boolean soilRoughnessAt(double depth) {
		return !(depth > roughnessThreshold);
	}

	/** Whether the soil emissivity applies at snow depth D [m]. */
	public boolean soilEmissivityAt(double depth) {
		return !(depth > emissivityThreshold);
	}

	/** The surface-balance options at snow depth D: the soil emissivity in place of the snow's when thin. */
	public SurfaceEnergyBalance.Parameters surfaceBalance(SurfaceEnergyBalance.Parameters snow, double depth) {
		if (!soilEmissivityAt(depth)) {
			return snow;
		}
		return new SurfaceEnergyBalance.Parameters(soilEmissivity, snow.latentHeat(), snow.saturation(),
				snow.freezeAirProperties());
	}

	/** The turbulence at snow depth D: the soil roughness in place of the snow's when thin. */
	public MoninObukhov turbulence(MoninObukhov snow, double depth) {
		if (!soilRoughnessAt(depth)) {
			return snow;
		}
		MoninObukhov.Parameters t = snow.parameters();
		return new MoninObukhov(new MoninObukhov.Parameters(t.windHeight(), t.temperatureHeight(), soilRoughness,
				t.displacementHeight(), t.stability(), t.scalarRoughness(), t.roughnessRatio(), t.maxIterations(),
				t.minimumWindSpeed(), t.maximumResistance(), t.capVapourResistance()));
	}
}
