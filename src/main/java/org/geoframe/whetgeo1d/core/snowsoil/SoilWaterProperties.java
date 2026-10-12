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

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.RichardsBoundaryConditionType;

/**
 * Hydraulic side of the soil, in the form of the inputs of
 * {@code RichardsSolver1D}: the names of WHETGEO's closures, the bottom
 * condition and the Newton settings. Grid, initial suction, ids and
 * {@code Parameters} are those of the {@link SoilHeatSetup}, so heat and water
 * see the same soil.
 *
 * @param typeClosureEquation                 SWRC per equation-state id, e.g.
 *                                            "Van Genuchten"
 * @param typeEquationState                   water-volume equation state per id
 * @param typeUHCModel                        unsaturated conductivity per id, e.g.
 *                                            "Mualem Van Genuchten"
 * @param typeUHCTemperatureModel             temperature dependence of the
 *                                            conductivity, e.g. "notemperature"
 * @param interfaceHydraulicConductivityModel e.g. "max"
 * @param bottomType                          bottom condition of Richards
 * @param newtonTolerance                     tolerance of NestedNewtonThomas [m]
 * @param maxIterations                       Newton iteration cap
 * @param picardIterations                    conductivity updates per substep
 * @param maxSubstep                          longest substep [s]
 * @param iceImpedance                        Ω of the impedance of ice on the
 *                                            conductivity, 10^(-Ω θ_i/(θ_s - θ_r))
 *                                            (GEOtop: 7)
 */
public record SoilWaterProperties(String[] typeClosureEquation, String[] typeEquationState, String[] typeUHCModel,
		String typeUHCTemperatureModel, String interfaceHydraulicConductivityModel,
		RichardsBoundaryConditionType bottomType, double newtonTolerance, int maxIterations, int picardIterations,
		double maxSubstep, double iceImpedance) {

	public SoilWaterProperties {
		Objects.requireNonNull(typeClosureEquation, "typeClosureEquation");
		Objects.requireNonNull(typeEquationState, "typeEquationState");
		Objects.requireNonNull(typeUHCModel, "typeUHCModel");
		Objects.requireNonNull(typeUHCTemperatureModel, "typeUHCTemperatureModel");
		Objects.requireNonNull(interfaceHydraulicConductivityModel, "interfaceHydraulicConductivityModel");
		Objects.requireNonNull(bottomType, "bottomType");
		if (typeClosureEquation.length != typeEquationState.length || typeClosureEquation.length != typeUHCModel.length) {
			throw new IllegalArgumentException("closure, equation-state and conductivity names must match in number");
		}
		if (!(newtonTolerance > 0.0) || maxIterations < 1 || picardIterations < 1) {
			throw new IllegalArgumentException("Newton settings must be positive");
		}
		if (!(maxSubstep > 0.0)) {
			throw new IllegalArgumentException("maxSubstep must be positive, got " + maxSubstep);
		}
		if (!(iceImpedance >= 0.0) || Double.isInfinite(iceImpedance)) {
			throw new IllegalArgumentException("iceImpedance must be non-negative and finite, got " + iceImpedance);
		}
	}

	/**
	 * Van Genuchten soil with Mualem conductivity and no temperature effect,
	 * free drainage, the settings of WHETGEO's Richards tests (tolerance
	 * 10⁻¹¹ m, 1 Picard, substeps of 30 minutes), GEOtop's impedance of ice
	 * (Ω = 7).
	 */
	public static SoilWaterProperties vanGenuchtenFreeDrainage() {
		return new SoilWaterProperties(new String[] { "Van Genuchten" }, new String[] { "Van Genuchten" },
				new String[] { "Mualem Van Genuchten" }, "notemperature", "max",
				RichardsBoundaryConditionType.BOTTOM_FREE_DRAINAGE, 1e-11, 50, 1, 1800.0,
				IceAwareRetention.GEOTOP_IMPEDANCE);
	}
}
