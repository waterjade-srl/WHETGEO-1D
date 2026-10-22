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
 * Terms of the last step of a {@link SnowSoilSolver} that the whole-column
 * balance (VAL-7, {@link ColumnBalance}) and the acceptance of the step (SOL-5,
 * {@link StepController}) need and that {@link SnowSoilStepResult} does not
 * carry. They are values the step computes anyway; recording them changes
 * nothing in the step. Energies in J m-2, masses in kg m-2, over the step.
 *
 * @param pendingApplied       deferred surface energy that entered the top in
 *                             this step (added to the surface flux)
 * @param phaseMismatchEnergy  phase-mismatch energy of the vapour exchange
 *                             created in this step ({@code Sublimated},
 *                             document 008), deferred to the next flux step
 *                             with its sign changed; zero without snow
 * @param surfaceChange        |T<sub>s</sub> - T<sub>k</sub>| of the last
 *                             surface sweep [K], the quantity tested against
 *                             {@link SurfaceCoupling#temperatureTolerance()};
 *                             NaN without snow at the start of the step
 * @param heatWorstCell        active cell (0 at the bottom, soil first) with the
 *                             largest residual ratio in the per-cell check of
 *                             the last heat solve
 * @param rainOnBareSoilMass   rain falling on bare ground, straight to the soil
 * @param rainOnBareSoilEnergy its internal energy (reference of the snow)
 * @param infiltrationEnergy   energy carried into the soil by the water that
 *                             infiltrates (reference of the soil closures); NaN
 *                             without soil water
 * @param soilSinkEnergy       energy leaving the soil with evapotranspiration;
 *                             NaN without soil water
 */
public record ColumnStepTerms(double pendingApplied, double phaseMismatchEnergy, double surfaceChange,
		int heatWorstCell, double rainOnBareSoilMass, double rainOnBareSoilEnergy, double infiltrationEnergy,
		double soilSinkEnergy) {
}
