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

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_WATER;

/**
 * The condensed phase that exchanges vapour with the air: ice (sublimation and
 * deposition) or liquid water (evaporation and condensation).
 *
 * <p>
 * Each phase has a partial specific energy in the equation of state of
 * {@link org.geoframe.whetgeo1d.snow.SnowEquationOfState}, whose U is the sum
 * of c_i (T - T_m) per kg of ice and L_f + c_w (T - T_m) per kg of liquid, and a
 * latent heat of vaporisation. Their sum is the energy of the vapour, so a
 * mass removed with the partial energy of a phase and the latent heat of the
 * same phase leaves with the same energy whichever the phase:
 *
 * <pre>
 * ice:    c_i (T - T_m)       + (L_e(T) + L_f)
 * liquid: L_f + c_w (T - T_m) +  L_e(T)
 * </pre>
 *
 * (equal at T_m; they differ by (c_w - c_i)(T - T_m) elsewhere, the Kirchhoff
 * term GEOtop's constant latent heats leave out).
 */
public enum VapourPhase {

	/** Sublimation and deposition, latent heat L_e + L_f. */
	ICE,

	/** Evaporation and condensation, latent heat L_e. */
	LIQUID;

	/** The phase at temperature T: ice below the melting temperature, liquid at or above. */
	public static VapourPhase at(double temperature) {
		return temperature < MELTING_TEMPERATURE ? ICE : LIQUID;
	}

	/** Partial specific internal energy of the phase at temperature T [J kg-1]. */
	public double specificEnergy(double temperature) {
		double dt = temperature - MELTING_TEMPERATURE;
		return this == ICE ? SPECIFIC_HEAT_ICE * dt : LATENT_HEAT_FUSION + SPECIFIC_HEAT_WATER * dt;
	}

	/**
	 * Latent heat of the phase change to vapour at temperature T [J kg-1], with
	 * GEOtop's {@link AirProperties#latentHeatOfVaporisation}.
	 */
	public double latentHeat(double temperature) {
		double evaporation = AirProperties.latentHeatOfVaporisation(temperature);
		return this == ICE ? evaporation + LATENT_HEAT_FUSION : evaporation;
	}
}
