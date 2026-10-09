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
package org.geoframe.whetgeo1d.snow.surface;

/**
 * Two-band albedo of the snow surface, visible and near infrared, direct and
 * diffuse. Port of {@code snow_albedo} and {@code Fzen} of GEOtop 3.0
 * ({@code snow.cc:893-925}, commit 1f1fc5af), as assembled in
 * {@code PointEnergyBalance} ({@code energy.balance.cc:455-544}). Each band:
 *
 * <pre>
 * F_age = 1 - 1/(1 + τ)                                   τ: non-dimensional surface age
 * α     = α₀ (1 - C F_age)                                α₀: fresh snow, C: ageing coefficient
 * α    += 0.4 (1 - α) f(cos θ)                            direct beam only (diffuse: f = 0)
 * f     = ((b + 1)/(1 + 2 b cos θ) - 1)/b,  b = 2,  if cos θ &lt; 0.5; 0 otherwise
 * if D &lt; d_e:   w = (1 - D/d_e) exp(-k D/d_e),   α = w α_ground + (1 - w) α
 * </pre>
 *
 * τ is the age of {@link org.geoframe.whetgeo1d.snow.SurfaceSnowAge}, θ the
 * incidence angle of the beam on the surface, D the snow depth and d_e the
 * albedo extinction depth; k = 0.5 in GEOtop 3.0, 2 in the MobyGIS fork
 * ({@code snow.cc:586} there, "according to simulation tuning"), which ports
 * it to NivoGEO-1D. With D = 0 the albedo is the ground's, as GEOtop's
 * bare-ground branch. The absorbed shortwave splits each stream equally
 * between the two bands, as GEOtop does:
 *
 * <pre>
 * SW_abs = SW_diffuse (1 - (α_vis,d + α_nir,d)/2) + SW_direct (1 - (α_vis,b + α_nir,b)/2)
 * </pre>
 *
 * The ground albedos are the caller's (GEOtop interpolates them between dry
 * and saturated soil with the water content of the top soil layer).
 */
public final class SnowAlbedo {

	/** Constant b of the zenith factor. */
	private static final double ZENITH_B = 2.0;

	/** Largest increase of the albedo at grazing incidence, 0.4 (1 - α). */
	private static final double ZENITH_WEIGHT = 0.4;

	/**
	 * Parameters, GEOtop keywords in brackets.
	 *
	 * @param freshVisible     fresh-snow albedo, visible ({@code FreshSnowReflVis},
	 *                         99)
	 * @param freshNearInfrared fresh-snow albedo, near infrared
	 *                         ({@code FreshSnowReflNIR}, 100)
	 * @param ageingVisible    C, ageing coefficient, visible
	 *                         ({@code SnowAgingCoeffVis}, 366)
	 * @param ageingNearInfrared C, ageing coefficient, near infrared
	 *                         ({@code SnowAgingCoeffNIR}, 367)
	 * @param extinctionDepth  d_e, depth below which the ground shows through [m]
	 *                         ({@code AlbExtParSnow}, 98, given in mm)
	 * @param thinSnowDecay    k, decay of the ground weight with depth [-]
	 *                         (written in the code: 0.5 in GEOtop 3.0, 2 in the
	 *                         MobyGIS fork)
	 */
	public record Parameters(double freshVisible, double freshNearInfrared, double ageingVisible,
			double ageingNearInfrared, double extinctionDepth, double thinSnowDecay) {

		public Parameters {
			requireUnit(freshVisible, "freshVisible");
			requireUnit(freshNearInfrared, "freshNearInfrared");
			requireUnit(ageingVisible, "ageingVisible");
			requireUnit(ageingNearInfrared, "ageingNearInfrared");
			if (!(extinctionDepth > 0.0) || Double.isInfinite(extinctionDepth)) {
				throw new IllegalArgumentException("extinctionDepth must be positive and finite, got " + extinctionDepth);
			}
			if (!(thinSnowDecay >= 0.0) || Double.isInfinite(thinSnowDecay)) {
				throw new IllegalArgumentException("thinSnowDecay must be non-negative and finite, got " + thinSnowDecay);
			}
		}

		/**
		 * GEOtop 3.0 defaults ({@code parameters.cc:1350-1352, 2157-2158}):
		 * α₀ = 0.9 and 0.65, C = 0.2 and 0.5, d_e = 10 mm; k = 0.5
		 * ({@code snow.cc:902}).
		 */
		public static Parameters geotopDefaults() {
			return new Parameters(0.9, 0.65, 0.2, 0.5, 0.010, 0.5);
		}

		/**
		 * Defaults of NivoGEO-1D ({@code AlbedoParams}, point case): α₀ = 0.95
		 * and 0.65, C = 0.35 and 0.75, d_e = 50 mm, and the MobyGIS k = 2. The
		 * MobyGIS fork itself defaults to 0.95, 0.65, 0.2, 0.5, 10 mm
		 * ({@code inputKeywords.cc}). For cross-checks only.
		 */
		public static Parameters nivogeoDefaults() {
			return new Parameters(0.95, 0.65, 0.35, 0.75, 0.050, 2.0);
		}

		private static void requireUnit(double value, String name) {
			if (!(value >= 0.0 && value <= 1.0)) {
				throw new IllegalArgumentException(name + " must be in [0, 1], got " + value);
			}
		}
	}

	/**
	 * The four albedos of a step.
	 */
	public record Albedos(double visibleDirect, double visibleDiffuse, double nearInfraredDirect,
			double nearInfraredDiffuse) {

		/** Shortwave absorbed by the surface [W m-2]. */
		public double absorbed(double shortwaveDirect, double shortwaveDiffuse) {
			return shortwaveDiffuse * (1.0 - 0.5 * visibleDiffuse - 0.5 * nearInfraredDiffuse)
					+ shortwaveDirect * (1.0 - 0.5 * visibleDirect - 0.5 * nearInfraredDirect);
		}

		/** Shortwave reflected by the surface [W m-2]. */
		public double reflected(double shortwaveDirect, double shortwaveDiffuse) {
			return shortwaveDiffuse * (0.5 * visibleDiffuse + 0.5 * nearInfraredDiffuse)
					+ shortwaveDirect * (0.5 * visibleDirect + 0.5 * nearInfraredDirect);
		}

		/** Broadband albedo for the given direct and diffuse shortwave [-]. */
		public double broadband(double shortwaveDirect, double shortwaveDiffuse) {
			double total = shortwaveDirect + shortwaveDiffuse;
			if (!(total > 0.0)) {
				return 0.25 * (visibleDirect + visibleDiffuse + nearInfraredDirect + nearInfraredDiffuse);
			}
			return reflected(shortwaveDirect, shortwaveDiffuse) / total;
		}
	}

	private final Parameters parameters;

	public SnowAlbedo(Parameters parameters) {
		if (parameters == null) {
			throw new IllegalArgumentException("parameters must be given");
		}
		this.parameters = parameters;
	}

	public Parameters parameters() {
		return parameters;
	}

	/**
	 * The four albedos.
	 *
	 * @param snowDepth           D [m]
	 * @param age                 non-dimensional surface age τ [-]
	 * @param cosIncidence        cosine of the incidence angle of the beam on
	 *                            the surface [-], in [0, 1]
	 * @param groundVisible       albedo of the ground, visible [-]
	 * @param groundNearInfrared  albedo of the ground, near infrared [-]
	 */
	public Albedos albedos(double snowDepth, double age, double cosIncidence, double groundVisible,
			double groundNearInfrared) {
		Parameters p = parameters;
		return new Albedos(
				bandAlbedo(p.freshVisible(), p.ageingVisible(), age, zenithFactor(cosIncidence), snowDepth,
						groundVisible),
				bandAlbedo(p.freshVisible(), p.ageingVisible(), age, 0.0, snowDepth, groundVisible),
				bandAlbedo(p.freshNearInfrared(), p.ageingNearInfrared(), age, zenithFactor(cosIncidence), snowDepth,
						groundNearInfrared),
				bandAlbedo(p.freshNearInfrared(), p.ageingNearInfrared(), age, 0.0, snowDepth, groundNearInfrared));
	}

	/**
	 * Albedo of one band, GEOtop {@code snow_albedo}.
	 *
	 * @param zenithFactor f(cos θ) for the direct beam, 0 for the diffuse
	 */
	public double bandAlbedo(double fresh, double ageing, double age, double zenithFactor, double snowDepth,
			double ground) {
		if (!(age >= 0.0) || Double.isInfinite(age)) {
			throw new IllegalArgumentException("age must be non-negative and finite, got " + age);
		}
		if (!(snowDepth >= 0.0) || Double.isInfinite(snowDepth)) {
			throw new IllegalArgumentException("snowDepth must be non-negative and finite, got " + snowDepth);
		}
		double ageFactor = 1.0 - 1.0 / (1.0 + age);
		double albedo = fresh * (1.0 - ageing * ageFactor);
		albedo += ZENITH_WEIGHT * (1.0 - albedo) * zenithFactor;
		double extinction = parameters.extinctionDepth();
		if (snowDepth < extinction) {
			double w = (1.0 - snowDepth / extinction) * Math.exp(-snowDepth * parameters.thinSnowDecay() / extinction);
			albedo = w * ground + (1.0 - w) * albedo;
		}
		return albedo;
	}

	/** Zenith factor f(cos θ) of the direct beam, GEOtop {@code Fzen}. */
	public static double zenithFactor(double cosIncidence) {
		if (!(cosIncidence >= 0.0 && cosIncidence <= 1.0)) {
			throw new IllegalArgumentException("cosIncidence must be in [0, 1], got " + cosIncidence);
		}
		if (cosIncidence < 0.5) {
			return (1.0 / ZENITH_B) * ((ZENITH_B + 1.0) / (1.0 + 2.0 * ZENITH_B * cosIncidence) - 1.0);
		}
		return 0.0;
	}
}
