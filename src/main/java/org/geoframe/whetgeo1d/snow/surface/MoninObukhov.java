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
 * Aerodynamic resistances from Monin–Obukhov similarity, port of
 * {@code Businger} and {@code Star} of GEOtop 3.0 ({@code turbulence.cc:339-504},
 * commit 1f1fc5af), the path taken with {@code state_turb = 1}, the only one
 * GEOtop 3.0 allows ({@code parameters.cc:1438}).
 *
 * <p>
 * Specification A7: the resistances are computed <b>once per step, from the
 * state at the start of the step</b>, and frozen while the energy equation is
 * solved, because H(T_s) can be non-monotone in stable conditions. GEOtop does
 * the same: {@code EnergyFluxes} computes them before the Newton loop
 * ({@code energy.balance.cc:1484}), {@code EnergyFluxes_no_rec_turbulence}
 * reuses them in every iteration ({@code energy.balance.cc:1885}; it recomputes
 * them, without stable correction, only if the surface falls below -50 °C after
 * 10 iterations, an emergency path not ported).
 *
 * <p>
 * Fixed-point iteration on the Obukhov length L, starting from L = ±10⁵ m
 * (stable guess if T_s &lt; T_a):
 *
 * <pre>
 * u* = k v / c_m(L)              T* = k (T_s - T_a) / c_h(L)        q* = k (q_s - q_a) / c_q(L)
 * L  = -u*² T̄ / (k g (T* - 0.61 q* T̄)),   T̄ = (T_s + T_a)/2
 * r_m = c_m² / (k² v),   r_h = c_h c_m / (k² v),   r_v = c_q c_m / (k² v)
 * </pre>
 *
 * with the profiles c of {@link StabilityCorrection} and the scalar roughness of
 * {@link ScalarRoughness}, k = 0.41, g = 9.81 m s⁻². The loop and its stopping
 * rule are GEOtop's, literally: it stops when
 * |100 T* + 100 u* + 1000 q* - (10 T* + 100 u* + 1000 q*)_previous| ≤ 0.01 or
 * after {@code maxIterations + 1} passes. Because the reference uses 10 T*
 * where the test uses 100 T*, the rule is met only when T* ≈ 0, and in practice
 * the loop always makes {@code maxIterations + 1} passes (6 by default). In
 * unstable and weakly stable conditions L has converged by then; in moderately
 * stable ones r_h is still a few 10⁻³ from the fixed point (2034 instead of
 * 2044 s m⁻¹ at T_s - T_a = -7 K, 2 m s⁻¹); in strongly stable conditions the
 * iteration runs away to L → 0 and r → ∞ (decoupling), which is what the cap
 * on r_h is for.
 *
 * <p>
 * Two more GEOtop details, kept: the buoyancy term has -0.61 q* (the
 * virtual-temperature correction would have +), and the wind speed is floored
 * at {@link Parameters#minimumWindSpeed()}, GEOtop's {@code Vmin} applied to the
 * meteorological input (keyword 65, 0.5 m s⁻¹, {@code parameters.cc:1316}).
 *
 * <p>
 * The flux resistances ({@link Resistances#heatFlux()},
 * {@link Resistances#vapourFlux()}) apply the cap
 * r ≤ {@link Parameters#maximumResistance()} = 2600 s m⁻¹, the windless
 * exchange of Jordan et al. (1999), C = ρ c_p / r ≈ 0.5 W m⁻² K⁻¹
 * ({@code turbulence.cc:101-104}). GEOtop caps only the heat resistance; capping
 * the vapour resistance too is an option ({@link Parameters#capVapourResistance()}).
 */
public final class MoninObukhov {

	/** von Kármán constant [-]. */
	public static final double VON_KARMAN = 0.41;

	/** Gravity [m s-2]. */
	public static final double GRAVITY = 9.81;

	/** Windless cap on the resistance, GEOtop [s m-1]. */
	public static final double DEFAULT_MAXIMUM_RESISTANCE = 2.6e3;

	/** Roughness length of snow, GEOtop keyword 103, 0.1 mm [m]. */
	public static final double GEOTOP_SNOW_ROUGHNESS = 1.0e-4;

	/**
	 * Parameters.
	 *
	 * @param windHeight            height of the wind measurement z_u [m]
	 * @param temperatureHeight     height of the temperature and humidity
	 *                              measurement z_t [m]
	 * @param roughnessLength       momentum roughness length z₀ [m]; over snow
	 *                              GEOtop uses {@link #GEOTOP_SNOW_ROUGHNESS}
	 * @param displacementHeight    zero-plane displacement d [m]; GEOtop sets 0
	 *                              over snow
	 * @param stability             stability corrections
	 * @param scalarRoughness       roughness lengths for heat and vapour
	 * @param roughnessRatio        z₀/z₀ₜ, used only with
	 *                              {@link ScalarRoughness#FIXED_RATIO} [-]
	 * @param maxIterations         GEOtop {@code maxiter_Businger}, keyword 76,
	 *                              default 5
	 * @param minimumWindSpeed      floor of the wind speed [m s-1]
	 * @param maximumResistance     cap on the flux resistances [s m-1]
	 * @param capVapourResistance   whether the cap applies to r_v too (GEOtop:
	 *                              no)
	 */
	public record Parameters(double windHeight, double temperatureHeight, double roughnessLength,
			double displacementHeight, StabilityCorrection stability, ScalarRoughness scalarRoughness,
			double roughnessRatio, int maxIterations, double minimumWindSpeed, double maximumResistance,
			boolean capVapourResistance) {

		public Parameters {
			if (stability == null || scalarRoughness == null) {
				throw new IllegalArgumentException("stability and scalarRoughness must be given");
			}
			requirePositive(roughnessLength, "roughnessLength");
			if (!(displacementHeight >= 0.0) || Double.isInfinite(displacementHeight)) {
				throw new IllegalArgumentException(
						"displacementHeight must be non-negative and finite, got " + displacementHeight);
			}
			if (!(windHeight - displacementHeight > roughnessLength) || Double.isInfinite(windHeight)) {
				throw new IllegalArgumentException("windHeight must be above displacement plus roughness, got "
						+ windHeight);
			}
			if (!(temperatureHeight - displacementHeight > roughnessLength) || Double.isInfinite(temperatureHeight)) {
				throw new IllegalArgumentException(
						"temperatureHeight must be above displacement plus roughness, got " + temperatureHeight);
			}
			if (scalarRoughness == ScalarRoughness.FIXED_RATIO) {
				requirePositive(roughnessRatio, "roughnessRatio");
			}
			if (maxIterations < 0) {
				throw new IllegalArgumentException("maxIterations must be non-negative, got " + maxIterations);
			}
			requirePositive(minimumWindSpeed, "minimumWindSpeed");
			if (!(maximumResistance > 0.0)) {
				throw new IllegalArgumentException("maximumResistance must be positive, got " + maximumResistance);
			}
		}

		/**
		 * GEOtop 3.0 over snow: z₀ = 0.1 mm, d = 0, stable and unstable
		 * corrections ({@code MoninObukhov} = 1, {@code parameters.cc:1440}),
		 * Andreas scalar roughness, 5 iterations, wind ≥ 0.5 m s⁻¹, r_h ≤ 2600 s
		 * m⁻¹, r_v not capped.
		 */
		public static Parameters geotop(double windHeight, double temperatureHeight) {
			return new Parameters(windHeight, temperatureHeight, GEOTOP_SNOW_ROUGHNESS, 0.0, StabilityCorrection.BOTH,
					ScalarRoughness.ANDREAS, 0.0, 5, 0.5, DEFAULT_MAXIMUM_RESISTANCE, false);
		}

		/**
		 * Recommended (A7): as {@link #geotop}, stable correction on, Andreas
		 * roughness, cap kept, and the cap applied to r_v as well, so that the
		 * windless exchange holds for vapour as it does for heat.
		 */
		public static Parameters recommended(double windHeight, double temperatureHeight) {
			return new Parameters(windHeight, temperatureHeight, GEOTOP_SNOW_ROUGHNESS, 0.0, StabilityCorrection.BOTH,
					ScalarRoughness.ANDREAS, 0.0, 5, 0.5, DEFAULT_MAXIMUM_RESISTANCE, true);
		}

		private static void requirePositive(double value, String name) {
			if (!(value > 0.0) || Double.isInfinite(value)) {
				throw new IllegalArgumentException(name + " must be positive and finite, got " + value);
			}
		}
	}

	/**
	 * Resistances of a step.
	 *
	 * @param momentum      r_m [s m-1]
	 * @param heat          r_h, uncapped [s m-1]
	 * @param vapour        r_v, uncapped [s m-1]
	 * @param obukhovLength L [m], negative unstable, positive stable
	 * @param frictionVelocity u* [m s-1]
	 * @param passes        passes of the fixed-point loop
	 * @param heatFlux      r_h used for the sensible heat, capped [s m-1]
	 * @param vapourFlux    r_v used for the vapour flux, capped if so chosen [s
	 *                      m-1]
	 */
	public record Resistances(double momentum, double heat, double vapour, double obukhovLength,
			double frictionVelocity, int passes, double heatFlux, double vapourFlux) {
	}

	private final Parameters parameters;

	public MoninObukhov(Parameters parameters) {
		if (parameters == null) {
			throw new IllegalArgumentException("parameters must be given");
		}
		this.parameters = parameters;
	}

	public Parameters parameters() {
		return parameters;
	}

	/**
	 * Resistances at the state of the start of the step.
	 *
	 * @param airTemperature     T_a [K]
	 * @param surfaceTemperature T_s [K]
	 * @param airHumidity        q_a [kg kg-1]
	 * @param surfaceHumidity    q_s, saturated at T_s [kg kg-1]
	 * @param windSpeed          wind speed at z_u [m s-1], floored at the minimum
	 */
	public Resistances resistances(double airTemperature, double surfaceTemperature, double airHumidity,
			double surfaceHumidity, double windSpeed) {
		Parameters p = parameters;
		double v = Math.max(windSpeed, p.minimumWindSpeed());
		double meanTemperature = 0.5 * surfaceTemperature + 0.5 * airTemperature;
		double temperatureDifference = surfaceTemperature - airTemperature;
		double humidityDifference = surfaceHumidity - airHumidity;
		double z0 = p.roughnessLength();
		double d = p.displacementHeight();

		double obukhov = temperatureDifference < 0.0 ? 1.0e5 : -1.0e5;
		double uStar = 0.0;
		double tStar = 0.0;
		double qStar = 0.0;
		double cm;
		double ch;
		double cq;
		double tolerance = 1.0e99;
		int passes = 0;
		do {
			if (passes > 0) {
				tolerance = 10.0 * tStar + 100.0 * uStar + 1000.0 * qStar;
			}
			cm = p.stability().momentumProfile(p.windHeight(), z0, d, obukhov);
			uStar = v * VON_KARMAN / cm;
			double z0t;
			double z0q;
			if (p.scalarRoughness() == ScalarRoughness.ANDREAS) {
				double reynolds = uStar * z0 / ScalarRoughness.KINEMATIC_VISCOSITY;
				z0t = z0 * ScalarRoughness.andreasHeatRatio(reynolds);
				z0q = z0 * ScalarRoughness.andreasVapourRatio(reynolds);
			} else {
				z0t = z0 / p.roughnessRatio();
				z0q = z0t;
			}
			if (!(p.temperatureHeight() > z0t && p.temperatureHeight() > z0q)) {
				throw new IllegalArgumentException("temperature height " + p.temperatureHeight()
						+ " m is below the scalar roughness length " + Math.max(z0t, z0q) + " m");
			}
			ch = p.stability().scalarProfile(p.temperatureHeight(), z0t, d, obukhov);
			cq = p.stability().scalarProfile(p.temperatureHeight(), z0q, d, obukhov);
			tStar = temperatureDifference * VON_KARMAN / ch;
			qStar = humidityDifference * VON_KARMAN / cq;
			obukhov = -uStar * uStar * meanTemperature
					/ (VON_KARMAN * GRAVITY * (tStar - 0.61 * qStar * meanTemperature));
			passes++;
		} while (Math.abs(100.0 * tStar + 100.0 * uStar + 1000.0 * qStar - tolerance) > 0.01
				&& passes <= p.maxIterations());

		double scale = VON_KARMAN * VON_KARMAN * v;
		double rm = cm * cm / scale;
		double rh = ch * cm / scale;
		double rv = cq * cm / scale;
		double cap = p.maximumResistance();
		return new Resistances(rm, rh, rv, obukhov, uStar, passes, Math.min(rh, cap),
				p.capVapourResistance() ? Math.min(rv, cap) : rv);
	}
}
