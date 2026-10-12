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

/**
 * Mass and energy balance of the snowpack over one step of the
 * {@link SnowSoilSolver}, term by term, as each operator reports it. Masses in
 * kg m-2, energies in J m-2 with the reference of
 * {@link org.geoframe.whetgeo1d.snow.SnowCell#internalEnergy()}.
 *
 * <pre>
 * massAfter   = massBefore + snowfall + rain - vapour - toSoilMass                     + massClosure
 * energyAfter = energyBefore + heat + snowfallEnergy + rainEnergy - vapourEnergy
 *               - toSoilEnergy                                                         + energyClosure
 * </pre>
 *
 * The closures are what the operators fail to account for, zero to
 * round-off.
 *
 * @param massBefore      SWE at the start of the step
 * @param massAfter       SWE at the end of the step
 * @param snowfall        fresh snow deposited
 * @param rain            rain entering the pack (on bare ground it goes
 *                        straight to the soil and is counted there only)
 * @param vapour          mass exchanged with the air, positive removed
 * @param toSoilMass      mass handed to the soil: drainage and the remains of
 *                        a vanished pack
 * @param energyBefore    U of the pack at the start of the step
 * @param energyAfter     U of the pack at the end of the step
 * @param heat            change of U of the snow cells in the heat step:
 *                        surface input minus the flux into the soil, plus the
 *                        Newton residuals
 * @param snowfallEnergy  U of the fresh snow
 * @param rainEnergy      U of the rain entering the pack
 * @param vapourEnergy    U leaving with the vapour (the latent heat is in the
 *                        surface flux)
 * @param toSoilEnergy    U handed to the soil with {@code toSoilMass}, plus the
 *                        energy left by a cell consumed by sublimation
 * @param massClosure     unaccounted mass
 * @param energyClosure   unaccounted energy
 */
public record SnowBudget(double massBefore, double massAfter, double snowfall, double rain, double vapour,
		double toSoilMass, double energyBefore, double energyAfter, double heat, double snowfallEnergy,
		double rainEnergy, double vapourEnergy, double toSoilEnergy, double massClosure, double energyClosure) {

	static SnowBudget of(double massBefore, double massAfter, double snowfall, double rain, double vapour,
			double toSoilMass, double energyBefore, double energyAfter, double heat, double snowfallEnergy,
			double rainEnergy, double vapourEnergy, double toSoilEnergy) {
		double massClosure = massAfter - (massBefore + snowfall + rain - vapour - toSoilMass);
		double energyClosure = energyAfter
				- (energyBefore + heat + snowfallEnergy + rainEnergy - vapourEnergy - toSoilEnergy);
		return new SnowBudget(massBefore, massAfter, snowfall, rain, vapour, toSoilMass, energyBefore, energyAfter,
				heat, snowfallEnergy, rainEnergy, vapourEnergy, toSoilEnergy, massClosure, energyClosure);
	}
}
