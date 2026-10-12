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

import org.geoframe.closureequation.closureequation.Parameters;

/**
 * Van Genuchten retention of the liquid water in a soil holding ice, as GEOtop
 * writes it ({@code teta_psi}, {@code psi_teta}, {@code pedo.funct.h}, commit
 * 1f1fc5af), with WHETGEO's Van Genuchten for the ice-free curve
 * ({@code SWRCVanGenuchten}: θ_r + (θ_s - θ_r)(1 + |α ψ|ⁿ)^-m, m = 1 - 1/n,
 * and θ_s + S_s ψ above zero, S_s = ρ_w g (α_SS + θ_s β_SS)).
 *
 * <p>
 * Ice θ_i (volume of water equivalent per volume of soil) takes pore space:
 * the liquid saturates at θ_s - θ_i, reached at the suction ψ_sat(θ_i) where
 * the ice-free curve gives θ_s - θ_i. Below ψ_sat the curve is the ice-free one,
 * not rescaled; above, θ_s - θ_i + S_s (ψ - ψ_sat):
 *
 * <pre>
 * θ_w(ψ; θ_i) = VG(ψ)                         ψ &lt; ψ_sat(θ_i)
 *             = θ_s - θ_i + S_s (ψ - ψ_sat)    ψ ≥ ψ_sat(θ_i)
 * </pre>
 *
 * With θ_i = 0, ψ_sat = 0 and the curve is WHETGEO's. The potential of the
 * total water as if all liquid, ψ_w0 = ψ(θ_w + θ_i; 0), is what the freezing
 * curve needs (specification §4.2, A9); the potential of the liquid,
 * ψ(θ_w; θ_i), is what Richards carries, the Clapeyron potential of a frozen
 * soil (cryosuction).
 *
 * <p>
 * Hydraulic conductivity with ice (GEOtop {@code k_hydr_soil}): the Mualem
 * conductivity evaluated at min(ψ, ψ_sat(θ_i)), times the impedance
 * 10^(-Ω θ_i / (θ_s - θ_r)), Ω = 7 in GEOtop.
 */
public final class IceAwareRetention {

	/** GEOtop's impedance exponent Ω (keyword 40). */
	public static final double GEOTOP_IMPEDANCE = 7.0;

	private static final double GRAVITY = 9.81;
	/** Smallest effective saturation the ice can leave to the liquid. */
	private static final double MINIMUM_SATURATION = 1e-9;
	private static final double WATER_DENSITY = 1000.0;

	private final Parameters parameters;

	public IceAwareRetention(Parameters parameters) {
		this.parameters = parameters;
	}

	private double m(int id) {
		return 1.0 - 1.0 / parameters.par1[id];
	}

	/** Specific storage S_s [m-1] of WHETGEO's Van Genuchten above saturation. */
	public double storage(int id) {
		return WATER_DENSITY * GRAVITY
				* (parameters.alphaSpecificStorage[id] + parameters.thetaS[id] * parameters.betaSpecificStorage[id]);
	}

	/** Ice-free Van Genuchten θ(ψ) for ψ &lt; 0 [-]. */
	private double vanGenuchten(double suction, int id) {
		return parameters.thetaR[id] + (parameters.thetaS[id] - parameters.thetaR[id])
				* Math.pow(1.0 + Math.pow(Math.abs(parameters.par2[id] * suction), parameters.par1[id]), -m(id));
	}

	private double dVanGenuchten(double suction, int id) {
		double n = parameters.par1[id];
		double alpha = parameters.par2[id];
		double a = Math.abs(alpha * suction);
		return (parameters.thetaS[id] - parameters.thetaR[id]) * m(id) * n * alpha
				* Math.pow(1.0 + Math.pow(a, n), -m(id) - 1.0) * Math.pow(a, n - 1.0);
	}

	/** Inverse of the ice-free curve below saturation: ψ with VG(ψ) = θ, θ_r &lt; θ &lt; θ_s. */
	private double inverseVanGenuchten(double theta, int id) {
		double se = (theta - parameters.thetaR[id]) / (parameters.thetaS[id] - parameters.thetaR[id]);
		return -Math.pow(Math.pow(se, -1.0 / m(id)) - 1.0, 1.0 / parameters.par1[id]) / parameters.par2[id];
	}

	/** ψ_sat(θ_i), where the liquid saturates the pores left by the ice [m]; 0 without ice. */
	public double saturationSuction(double ice, int id) {
		if (!(ice > 0.0)) {
			return 0.0;
		}
		// ice filling the pores down to θ_r: the free saturation is floored, as GEOtop floors it at ψ_min
		double floor = parameters.thetaR[id] + MINIMUM_SATURATION * (parameters.thetaS[id] - parameters.thetaR[id]);
		return inverseVanGenuchten(Math.max(parameters.thetaS[id] - ice, floor), id);
	}

	/** θ_w(ψ; θ_i) [-]. */
	public double liquid(double suction, double ice, int id) {
		double saturation = saturationSuction(ice, id);
		if (suction >= saturation) {
			double free = ice > 0.0 ? vanGenuchten(saturation, id) : parameters.thetaS[id];
			return free + storage(id) * (suction - saturation);
		}
		return vanGenuchten(suction, id);
	}

	/** dθ_w/dψ [m-1]. */
	public double dLiquid(double suction, double ice, int id) {
		if (suction >= saturationSuction(ice, id)) {
			return storage(id);
		}
		return dVanGenuchten(suction, id);
	}

	/**
	 * Slope of the ice-free curve at ψ [m-1]: the left derivative of θ_w(ψ; θ_i)
	 * at ψ_sat, where the curve is cut.
	 */
	public double dIceFree(double suction, int id) {
		return suction < 0.0 ? dVanGenuchten(suction, id) : storage(id);
	}

	/** ψ(θ_w; θ_i), the potential of the liquid [m]; at or above saturation with no storage, ψ_sat. */
	public double suction(double liquid, double ice, int id) {
		double saturation = saturationSuction(ice, id);
		double free = liquid(saturation, ice, id);
		if (liquid >= free) {
			double s = storage(id);
			return s > 0.0 ? saturation + (liquid - free) / s : saturation;
		}
		double floor = parameters.thetaR[id] + MINIMUM_SATURATION * (parameters.thetaS[id] - parameters.thetaR[id]);
		return inverseVanGenuchten(Math.max(liquid, floor), id);
	}

	/** ψ_w0 = ψ(θ_tot; 0), the potential the total water would have if all liquid [m]. */
	public double totalWaterSuction(double totalWater, int id) {
		return suction(totalWater, 0.0, id);
	}

	/** θ of the ice-free curve at ψ, total water from ψ_w0 [-]. */
	public double totalWater(double suctionW0, int id) {
		return liquid(suctionW0, 0.0, id);
	}

	/**
	 * Inflection of the ice-free curve, -(1/α)((n-1)/n)^(1/n) [m], WHETGEO's x*
	 * of the Van Genuchten water volume.
	 */
	public double inflection(int id) {
		double n = parameters.par1[id];
		return -1.0 / parameters.par2[id] * Math.pow((n - 1.0) / n, 1.0 / n);
	}

	/**
	 * Melting temperature of the soil water at ψ_w0, the freezing-point
	 * depression of WHETGEO's {@code SWRCVanGenuchtenDallAmico}:
	 * T* = 273.15 + g 273.15 ψ_w0 / L_f [K].
	 */
	public double meltingTemperature(double suctionW0) {
		return 273.15 + GRAVITY * 273.15 / parameters.latentHeatFusion * suctionW0;
	}

	/**
	 * Liquid water of the freezing soil at T, WHETGEO's
	 * {@code SWRCVanGenuchtenDallAmico.f(ψ_w0, T)}: the ice-free curve at
	 * ψ_w0 above T*, at the Clapeyron potential
	 * ψ_T = ψ_w0 + L_f / (g T*) (T - T*) below [-].
	 */
	public double freezingLiquid(double suctionW0, double temperature, int id) {
		double melting = meltingTemperature(suctionW0);
		double psi = temperature > melting ? suctionW0
				: suctionW0 + parameters.latentHeatFusion / (GRAVITY * melting) * (temperature - melting);
		return psi >= 0.0 ? parameters.thetaS[id] : vanGenuchten(psi, id);
	}

	/**
	 * Ice of the freezing soil at T, as WHETGEO's
	 * {@code FreezingSoilInternalEnergy} counts it:
	 * θ(ψ_w0, 273.2 K) - θ(ψ_w0, T) with the Dall'Amico curve, which is flat at
	 * θ_s above saturation. Water held by the specific storage of a saturated
	 * cell (ψ_w0 &gt; 0) stays liquid [-].
	 */
	public double freezingIce(double suctionW0, double temperature, int id) {
		return Math.max(freezingLiquid(suctionW0, 273.2, id) - freezingLiquid(suctionW0, temperature, id), 0.0);
	}

	/** Impedance factor of the ice on the conductivity, 10^(-Ω θ_i / (θ_s - θ_r)). */
	public double impedance(double ice, double exponent, int id) {
		if (!(ice > 0.0)) {
			return 1.0;
		}
		return Math.pow(10.0, -exponent * ice / (parameters.thetaS[id] - parameters.thetaR[id]));
	}
}
