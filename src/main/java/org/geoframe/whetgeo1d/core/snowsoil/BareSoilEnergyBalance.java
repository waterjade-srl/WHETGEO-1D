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

import java.util.Objects;

import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.geoframe.whetgeo1d.snow.surface.MoninObukhov;
import org.geoframe.whetgeo1d.snow.surface.SaturationCurve;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.geoframe.whetgeo1d.snow.surface.VapourPhase;

/**
 * Energy balance of the bare soil surface, the top of the column when there
 * is no snow, as GEOtop 3.0 computes it without canopy ({@code EnergyFluxes},
 * {@code energy.balance.cc:2449-2504}, commit 1f1fc5af):
 *
 * <pre>
 * G(T) = SW_net + ε (LW_in - σ T⁴) - H(T) - λ(T) E
 * SW_net = SW_b (1 - (α_vis + α_nir)/2) + SW_d (1 - (α_vis + α_nir)/2)
 * H(T) = ρ c_p (T - T_a) / min(r_h, 2600)
 * </pre>
 *
 * <b>Skin.</b> GEOtop balances the energy at a surface node of zero
 * thickness above the top soil layer ({@code nsurface = 0}, keyword 68,
 * {@code energy.balance.cc:1398}), coupled to the layer by conduction over
 * half its thickness. The column couples this balance like the one over snow,
 * through the skin node of {@link SkinNode} eliminated in the solver (SURF-2):
 * the component passes {@link Step#evaluate} as a
 * {@link TopBoundary.SurfaceBalance}, and the {@link SnowSoilSolver} returns
 * the skin temperature and the balance at it
 * ({@link SnowSoilSolver#lastSurface()}). The two {@code topBoundary} methods
 * below give the same linear flux for one linearisation, for checks.
 *
 * <p>
 * The resistances are the Monin–Obukhov ones of the snow surface
 * ({@link MoninObukhov}) with the roughness of the soil, GEOtop's
 * {@code SoilRoughness} (keyword 15, in mm, default 10 mm), zero displacement
 * and the same scalar roughness, as {@code update_roughness_soil} gives them,
 * evaluated at the start of the step at the temperature of the top cell, where
 * GEOtop restarts its skin ({@code energy.balance.cc:732}), and frozen over it. The albedo follows
 * the water content of the top cell between its dry and saturated values
 * ({@code find_albedo}, {@code radiation.cc:1050-1057}).
 *
 * <p>
 * <b>Evaporation is an input.</b> GEOtop computes the soil evaporation from
 * the soil (Ye and Pielke 1993, {@code find_actual_evaporation_parameters});
 * in GEOframe evaporation and transpiration come from GEOET (LINK-2), so here
 * E is given (for instance GEOtop's {@code Evap_surface} in a fidelity run)
 * and only its latent heat, with GEOtop's λ (L_e, plus L_f below 0 °C),
 * enters the balance, with no dependence on T. The same E is the sink of the
 * top soil cell in Richards.
 *
 * <p>
 * The flux enters the heat step linearised over the step
 * ({@link TopBoundary.SurfaceFlux}), with G' = -4εσT³ - ρ c_p / r_h ≤ 0;
 * GEOtop iterates the nonlinear balance within the step.
 *
 * @param turbulence         Monin–Obukhov resistances with the soil roughness
 * @param emissivity         ε of the soil ({@code SoilEmissiv})
 * @param dryVisible         α_vis of the dry soil ({@code SoilAlbVisDry})
 * @param wetVisible         α_vis of the saturated soil ({@code SoilAlbVisWet})
 * @param dryNearInfrared    α_nir of the dry soil ({@code SoilAlbNIRDry})
 * @param wetNearInfrared    α_nir of the saturated soil ({@code SoilAlbNIRWet})
 */
public record BareSoilEnergyBalance(MoninObukhov turbulence, double emissivity, double dryVisible, double wetVisible,
		double dryNearInfrared, double wetNearInfrared) {

	public BareSoilEnergyBalance {
		Objects.requireNonNull(turbulence, "turbulence");
		if (!(emissivity > 0.0 && emissivity <= 1.0)) {
			throw new IllegalArgumentException("emissivity must be in (0, 1], got " + emissivity);
		}
		for (double a : new double[] { dryVisible, wetVisible, dryNearInfrared, wetNearInfrared }) {
			if (!(a >= 0.0 && a <= 1.0)) {
				throw new IllegalArgumentException("albedos must be in [0, 1], got " + a);
			}
		}
	}

	/** The turbulence of the snow surface with the roughness of the soil [m]. */
	public static MoninObukhov soilTurbulence(MoninObukhov.Parameters snow, double roughnessLength) {
		return new MoninObukhov(new MoninObukhov.Parameters(snow.windHeight(), snow.temperatureHeight(),
				roughnessLength, 0.0, snow.stability(), snow.scalarRoughness(), snow.roughnessRatio(),
				snow.maxIterations(), snow.minimumWindSpeed(), snow.maximumResistance(), snow.capVapourResistance()));
	}

	/** α of a band at the relative water content s = (θ - θ_r)/(θ_s - θ_r). */
	private static double albedo(double dry, double wet, double saturation) {
		return dry + (wet - dry) * saturation;
	}

	/**
	 * The balance of a step, evaluated at the start temperature and at any other.
	 *
	 * @param forcing            forcing of the step
	 * @param startTemperature   T<sup>n</sup> of the top soil cell [K]
	 * @param relativeSaturation (θ - θ_r)/(θ_s - θ_r) of the top cell, for the
	 *                           albedo
	 * @param evaporation        E [kg m-2 s-1], positive from the soil, imposed
	 */
	public Step atStartOfStep(SurfaceForcing forcing, double startTemperature, double relativeSaturation,
			double evaporation) {
		double s = Math.max(0.0, Math.min(1.0, relativeSaturation));
		double absorbed = 1.0 - 0.5 * (albedo(dryVisible, wetVisible, s) + albedo(dryNearInfrared, wetNearInfrared, s));
		double netShortwave = (forcing.shortwaveDirect() + forcing.shortwaveDiffuse()) * absorbed;
		double surfaceHumidity = AirProperties.specificHumidity(
				SaturationCurve.WATER.pressure(startTemperature, forcing.pressure()), forcing.pressure());
		MoninObukhov.Resistances r = turbulence.resistances(forcing.airTemperature(), startTemperature,
				forcing.airHumidity(), surfaceHumidity, forcing.windSpeed());
		return new Step(forcing, netShortwave, r, evaporation, 1.0 - absorbed);
	}

	/**
	 * A step's balance with the resistances frozen.
	 *
	 * @param forcing       forcing of the step
	 * @param netShortwave  SW_net [W m-2]
	 * @param resistances   frozen at T<sup>n</sup>
	 * @param evaporation   E [kg m-2 s-1]
	 * @param albedo        broadband albedo
	 */
	public record Step(SurfaceForcing forcing, double netShortwave, MoninObukhov.Resistances resistances,
			double evaporation, double albedo) {

		/** G(T), its components and G'(T). */
		public SurfaceFluxes evaluate(BareSoilEnergyBalance soil, double temperature) {
			double ta = forcing.airTemperature();
			double mean = 0.5 * (ta + temperature);
			double density = AirProperties.density(mean, forcing.airHumidity(), forcing.pressure());
			double conductance = density * AirProperties.specificHeat(mean) / resistances.heatFlux();
			double sensible = conductance * (temperature - ta);
			double netLongwave = soil.emissivity()
					* (forcing.longwaveIn() - AirProperties.emissivePower(temperature));
			double latentHeat = VapourPhase.at(temperature).latentHeat(temperature);
			double latent = latentHeat * evaporation;
			double balance = netShortwave + netLongwave - sensible - latent;
			double derivative = -soil.emissivity() * AirProperties.dEmissivePower(temperature) - conductance;
			return new SurfaceFluxes(netShortwave, netLongwave, sensible, latent, evaporation, latentHeat, balance,
					derivative);
		}

		/** The linearised top condition of the heat step, the top cell being the surface. */
		public TopBoundary.SurfaceFlux topBoundary(BareSoilEnergyBalance soil, double startTemperature) {
			SurfaceFluxes f = evaluate(soil, startTemperature);
			return new TopBoundary.SurfaceFlux(f.energyBalance(), f.derivative());
		}

		/**
		 * The top condition of the heat step through the skin node.
		 *
		 * @param skinStart   T<sub>s</sub><sup>0</sup>, where G is linearised [K]
		 * @param topCell     T<sub>1</sub><sup>n</sup> of the top cell [K]
		 * @param conductance K, conductivity of the top cell over half its
		 *                    thickness [W m-2 K-1]
		 */
		public TopBoundary.SurfaceFlux topBoundary(BareSoilEnergyBalance soil, double skinStart, double topCell,
				double conductance) {
			SurfaceFluxes f = evaluate(soil, skinStart);
			double g0 = f.energyBalance();
			double g1 = f.derivative();
			double ts = skin(g0, g1, skinStart, topCell, conductance);
			return new TopBoundary.SurfaceFlux(conductance * (ts - topCell), conductance * g1 / (conductance - g1));
		}

		/**
		 * T<sub>s</sub> of the skin at a top-cell temperature, with G linearised at
		 * {@code skinStart} [K].
		 */
		public double skinTemperature(BareSoilEnergyBalance soil, double skinStart, double topCell,
				double conductance) {
			SurfaceFluxes f = evaluate(soil, skinStart);
			return skin(f.energyBalance(), f.derivative(), skinStart, topCell, conductance);
		}

		private static double skin(double g0, double g1, double skinStart, double topCell, double conductance) {
			return (g0 - g1 * skinStart + conductance * topCell) / (conductance - g1);
		}
	}
}
