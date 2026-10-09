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
 * Surface energy balance of a step: the nonlinear boundary term of the heat
 * equation, as the "TopFlux" contract of NivoGEO-1D ({@code fluxFromDiag}),
 * specification §6.2 and A7. Built once at the start of the step, with the
 * resistances frozen, then evaluated at every iterate of the surface
 * temperature T_s:
 *
 * <pre>
 * EB(T_s)    = SW_net + ε (LW_in - σ T_s⁴) - H - λ E
 * H          = ρ_a c_p (T_s - T_a) / r_h
 * E          = ρ_a (q_sat(T_s) - q_a) / r_v
 * dEB/dT_s   = -4 ε σ T_s³ - ρ_a c_p / r_h - λ ρ_a (dq/de) (de_sat/dT) / r_v
 * </pre>
 *
 * Port of {@code PointEnergyBalance} and {@code SolvePointEnergyBalance} of GEOtop
 * 3.0 ({@code energy.balance.cc:1484-1499, 1885-1900}, {@code turbulent_fluxes},
 * {@code turbulence.cc:87-112}, commit 1f1fc5af), snow surface only: no
 * canopy, the surface saturated (GEOtop's α = β = 1 when there is snow,
 * {@code turbulence.cc:561-566}).
 *
 * <p>
 * <b>What is frozen and what is not.</b> r_h, r_v (and so the stability) are
 * computed by {@link MoninObukhov} at T_s0, the surface temperature at the start
 * of the step, with q_sat(T_s0), and never change in the step. The derivative is
 * consistent through σ T_s⁴, e_sat(T_s) and the explicit T_s of H:
 * <ul>
 * <li>with {@link Parameters#freezeAirProperties()} and
 * {@link LatentHeat#PHASE_CONSISTENT} (recommended) ρ_a, c_p (at (T_a + T_s0)/2)
 * and λ (at T_s0) are frozen too, so EB is smooth in T_s and dEB/dT_s is its
 * exact derivative;</li>
 * <li>with the GEOtop options ρ_a and c_p are evaluated at (T_a + T_s)/2 and λ
 * at T_s, as GEOtop does in every iteration, but their T_s-dependence is left
 * out of the derivative, and EB jumps by L_f E at 0 °C.</li>
 * </ul>
 *
 * <p>
 * The surface longwave emission is ε σ T_s⁴ with no sky-view factor: GEOtop's
 * {@code sky} factor (1 on a flat point) belongs to the radiation component that
 * provides LW_in.
 */
public final class SurfaceEnergyBalance {

	/** Emissivity of snow, GEOtop keyword 102 ({@code parameters.cc:1354}). */
	public static final double SNOW_EMISSIVITY = 0.98;

	/**
	 * Parameters.
	 *
	 * @param emissivity          ε of the surface [-]
	 * @param latentHeat          choice of λ and of the phase the vapour mass is
	 *                            taken from (A6)
	 * @param saturation          saturation curve of the surface humidity
	 * @param freezeAirProperties whether ρ_a and c_p are evaluated once at T_s0
	 */
	public record Parameters(double emissivity, LatentHeat latentHeat, SaturationCurve saturation,
			boolean freezeAirProperties) {

		public Parameters {
			if (!(emissivity > 0.0 && emissivity <= 1.0)) {
				throw new IllegalArgumentException("emissivity must be in (0, 1], got " + emissivity);
			}
			if (latentHeat == null || saturation == null) {
				throw new IllegalArgumentException("latentHeat and saturation must be given");
			}
		}

		/** GEOtop 3.0: ε = 0.98, GEOtop's λ(T_s), e_sat over water, ρ_a and c_p at the iterate. */
		public static Parameters geotop() {
			return new Parameters(SNOW_EMISSIVITY, LatentHeat.GEOTOP, SaturationCurve.WATER, false);
		}

		/**
		 * Recommended: ε = 0.98, phase-consistent λ (A6), e_sat over ice below
		 * 0 °C, ρ_a and c_p frozen with the resistances (A7).
		 */
		public static Parameters recommended() {
			return new Parameters(SNOW_EMISSIVITY, LatentHeat.PHASE_CONSISTENT, SaturationCurve.ICE_BELOW_MELTING,
					true);
		}
	}

	private final Parameters parameters;
	private final SurfaceForcing forcing;
	private final double startTemperature;
	private final double netShortwave;
	private final double airHumidity;
	private final MoninObukhov.Resistances resistances;
	private final VapourPhase startPhase;
	private final double frozenDensity;
	private final double frozenSpecificHeat;
	private final double frozenLatentHeat;

	private SurfaceEnergyBalance(Parameters parameters, SurfaceForcing forcing, double startTemperature,
			double netShortwave, double airHumidity, MoninObukhov.Resistances resistances) {
		this.parameters = parameters;
		this.forcing = forcing;
		this.startTemperature = startTemperature;
		this.netShortwave = netShortwave;
		this.airHumidity = airHumidity;
		this.resistances = resistances;
		this.startPhase = VapourPhase.at(startTemperature);
		double mean = 0.5 * (forcing.airTemperature() + startTemperature);
		this.frozenDensity = AirProperties.density(mean, airHumidity, forcing.pressure());
		this.frozenSpecificHeat = AirProperties.specificHeat(mean);
		this.frozenLatentHeat = startPhase.latentHeat(startTemperature);
	}

	/**
	 * The balance of a step: albedo applied, resistances computed at T_s0 and
	 * frozen.
	 *
	 * @param parameters       options of the balance
	 * @param turbulence       Monin–Obukhov model
	 * @param forcing          forcing of the step
	 * @param albedos          albedos of the step ({@link SnowAlbedo})
	 * @param startTemperature T_s0, surface temperature at the start of the step [K]
	 */
	public static SurfaceEnergyBalance atStartOfStep(Parameters parameters, MoninObukhov turbulence,
			SurfaceForcing forcing, SnowAlbedo.Albedos albedos, double startTemperature) {
		if (parameters == null || turbulence == null || forcing == null || albedos == null) {
			throw new IllegalArgumentException("parameters, turbulence, forcing and albedos must be given");
		}
		requireTemperature(startTemperature);
		double airHumidity = forcing.airHumidity();
		double surfaceHumidity = AirProperties.specificHumidity(
				parameters.saturation().pressure(startTemperature, forcing.pressure()), forcing.pressure());
		MoninObukhov.Resistances resistances = turbulence.resistances(forcing.airTemperature(), startTemperature,
				airHumidity, surfaceHumidity, forcing.windSpeed());
		double netShortwave = albedos.absorbed(forcing.shortwaveDirect(), forcing.shortwaveDiffuse());
		return new SurfaceEnergyBalance(parameters, forcing, startTemperature, netShortwave, airHumidity,
				resistances);
	}

	/** EB(T_s), dEB/dT_s and the components. */
	public SurfaceFluxes evaluate(double surfaceTemperature) {
		requireTemperature(surfaceTemperature);
		double airTemperature = forcing.airTemperature();
		double pressure = forcing.pressure();
		SaturationCurve curve = parameters.saturation();

		double density;
		double specificHeat;
		if (parameters.freezeAirProperties()) {
			density = frozenDensity;
			specificHeat = frozenSpecificHeat;
		} else {
			double mean = 0.5 * (airTemperature + surfaceTemperature);
			density = AirProperties.density(mean, airHumidity, pressure);
			specificHeat = AirProperties.specificHeat(mean);
		}
		double latentHeat = parameters.latentHeat() == LatentHeat.GEOTOP
				? VapourPhase.at(surfaceTemperature).latentHeat(surfaceTemperature)
				: frozenLatentHeat;

		double emissivity = parameters.emissivity();
		double netLongwave = emissivity * (forcing.longwaveIn() - AirProperties.emissivePower(surfaceTemperature));
		double sensibleConductance = density * specificHeat / resistances.heatFlux();
		double sensibleHeat = sensibleConductance * (surfaceTemperature - airTemperature);
		double saturation = curve.pressure(surfaceTemperature, pressure);
		double surfaceHumidity = AirProperties.specificHumidity(saturation, pressure);
		double dSurfaceHumidity = AirProperties.dSpecificHumidity(saturation, pressure)
				* curve.derivative(surfaceTemperature, pressure);
		double vapourFlux = density * (surfaceHumidity - airHumidity) / resistances.vapourFlux();
		double dVapourFlux = density * dSurfaceHumidity / resistances.vapourFlux();
		double latentHeatFlux = latentHeat * vapourFlux;

		double balance = netShortwave + netLongwave - sensibleHeat - latentHeatFlux;
		double derivative = -emissivity * AirProperties.dEmissivePower(surfaceTemperature) - sensibleConductance
				- latentHeat * dVapourFlux;
		return new SurfaceFluxes(netShortwave, netLongwave, sensibleHeat, latentHeatFlux, vapourFlux, latentHeat,
				balance, derivative);
	}

	/**
	 * The phase the vapour mass of the step is taken from or added to, for
	 * {@link SnowSublimation}: always ice with {@link LatentHeat#GEOTOP}, the
	 * phase at T_s0 with {@link LatentHeat#PHASE_CONSISTENT}.
	 */
	public VapourPhase vapourPhase() {
		return parameters.latentHeat() == LatentHeat.GEOTOP ? VapourPhase.ICE : startPhase;
	}

	public Parameters parameters() {
		return parameters;
	}

	public SurfaceForcing forcing() {
		return forcing;
	}

	/** T_s0 [K]. */
	public double startTemperature() {
		return startTemperature;
	}

	/** Absorbed shortwave [W m-2]. */
	public double netShortwave() {
		return netShortwave;
	}

	/** q_a [kg kg-1]. */
	public double airHumidity() {
		return airHumidity;
	}

	/** The frozen resistances. */
	public MoninObukhov.Resistances resistances() {
		return resistances;
	}

	private static void requireTemperature(double temperature) {
		if (!(temperature > 0.0) || Double.isInfinite(temperature)) {
			throw new IllegalArgumentException("temperature must be positive (K) and finite, got " + temperature);
		}
	}
}
