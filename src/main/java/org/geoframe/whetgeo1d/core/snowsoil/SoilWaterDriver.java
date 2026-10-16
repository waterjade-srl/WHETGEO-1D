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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.geoframe.closureequation.equationstate.EquationState;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.RichardsBoundaryConditionType;
import org.geoframe.whetgeo1d.core.derivedquantities.ComputeQuantitiesRichards;
import org.geoframe.whetgeo1d.core.finitevolume.Richards1DKernel;
import org.geoframe.whetgeo1d.core.state.SubStepping;
import org.geoframe.whetgeo1d.core.state.WGGeometry;
import org.geoframe.whetgeo1d.core.state.WGProblemQuantities;

/**
 * Richards in the soil under the snow (specification §2.2 step 5): WHETGEO's
 * {@link Richards1DKernel} and {@link ComputeQuantitiesRichards}, unchanged, on
 * the soil cells of the column.
 *
 * <p>
 * <b>Top.</b> The water leaving the base of the pack (or the rain on bare soil)
 * enters as a Neumann flux. If the soil cannot take it — the top cell would go
 * past saturation, typically a soil whose pores are filled by ice — the step is
 * repeated with the surface held saturated (Dirichlet, ψ = ψ_sat of the top
 * cell): the soil takes what it can and the rest runs off, as GEOtop switches
 * its top condition. Without ice and without saturation the call sequence of a
 * substep is the one of {@code RichardsSolver1D}, so with the same inputs the
 * suctions are the same to the bit.
 *
 * <p>
 * <b>Ice</b> (specification §4.2, A9). With ice in the soil each cell keeps its
 * ice fixed during the step, as in GEOtop: the liquid follows
 * {@link IceAwareRetention}, and the conductivity of each cell is WHETGEO's at
 * min(ψ, ψ_sat(θ_i)) times the impedance of the ice. The water balance is that
 * of the liquid.
 *
 * <p>
 * <b>Evapotranspiration</b> is an input, as in WHETGEO's
 * {@code RichardsSolverWithRootWaterUptake1D}, where GEOET provides it: water
 * taken from each cell in the step, passed to the kernel as its sink
 * {@code ETs}, spread evenly over the substeps and capped in each substep at
 * the liquid above the wilting point of the cell, θ(ψ = {@value #WILTING_SUCTION}
 * m) (ice does not evaporate). The cap plays the role of WHETGEO's
 * {@code thetaWP}: a cell drained to θ_r would have ψ → -∞, and the freezing
 * curve of the heat step, whose melting point follows ψ, would lose meaning.
 * The applied amounts are returned and enter the balance as WHETGEO's
 * {@code sumETs}. With snow on the ground the caller passes none (§6.5, B4).
 * WHETGEO's own cap, {@code ComputeQuantitiesRichardsRoot}, is not used: it
 * skips the top cell, which is where the soil evaporates.
 */
public final class SoilWaterDriver {

	private static final double WATER_DENSITY = 1000.0;
	/** Water balance error of a step above which the Neumann top is considered failed [kg m-2]. */
	private static final double FAILED_BALANCE = 1e-6;
	/** Suction of the wilting point, -1.5 MPa: evapotranspiration does not dry a cell below it [m]. */
	public static final double WILTING_SUCTION = -150.0;

	private final int cells;
	private final SoilWaterProperties properties;
	private final WGProblemQuantities variables;
	private final WGGeometry geometry;
	private final ComputeQuantitiesRichards neumann;
	private final ComputeQuantitiesRichards dirichlet;
	private final Richards1DKernel neumannKernel;
	private final Richards1DKernel dirichletKernel;
	private final Richards1DKernel neumannIceKernel;
	private final Richards1DKernel dirichletIceKernel;
	private final IceAwareRetention retention;
	private final double[] wiltingPoint;
	private final double[] ice;
	private final List<EquationState> iceStates;

	/**
	 * @param soil  grid, initial suction and temperature, ids and parameters
	 * @param water hydraulic closures and settings
	 */
	public SoilWaterDriver(SoilHeatSetup soil, SoilWaterProperties water) {
		Objects.requireNonNull(soil, "soil");
		this.properties = Objects.requireNonNull(water, "water");
		this.cells = soil.cells();
		this.geometry = new WGGeometry(soil.z().clone(), soil.spaceDeltaZ().clone(), soil.controlVolume().clone());
		this.variables = new WGProblemQuantities(soil.waterSuction().clone(), soil.temperature().clone(),
				soil.equationStateID().clone(), soil.parameterID().clone());
		this.neumann = quantities(water, RichardsBoundaryConditionType.TOP_NEUMANN, soil);
		this.dirichlet = quantities(water, RichardsBoundaryConditionType.TOP_DIRICHLET, soil);
		this.retention = new IceAwareRetention(soil.parameters());
		this.wiltingPoint = new double[soil.parameters().thetaR.length];
		for (int id = 0; id < wiltingPoint.length; id++) {
			wiltingPoint[id] = retention.liquid(WILTING_SUCTION, 0.0, id);
		}
		this.ice = new double[cells];
		this.iceStates = new ArrayList<>();
		for (int k = 0; k < neumann.getRichardsStateEquation().size(); k++) {
			iceStates.add(new IceReducedWaterVolume(retention, geometry, ice));
		}
		this.neumannKernel = kernel(RichardsBoundaryConditionType.TOP_NEUMANN, neumann.getRichardsStateEquation());
		this.dirichletKernel = kernel(RichardsBoundaryConditionType.TOP_DIRICHLET, dirichlet.getRichardsStateEquation());
		this.neumannIceKernel = kernel(RichardsBoundaryConditionType.TOP_NEUMANN, iceStates);
		this.dirichletIceKernel = kernel(RichardsBoundaryConditionType.TOP_DIRICHLET, iceStates);
	}

	private ComputeQuantitiesRichards quantities(SoilWaterProperties water, RichardsBoundaryConditionType top,
			SoilHeatSetup soil) {
		return new ComputeQuantitiesRichards(water.typeClosureEquation(), water.typeEquationState(),
				water.typeUHCModel(), water.typeUHCTemperatureModel(), water.interfaceHydraulicConductivityModel(), top,
				water.bottomType(), variables, geometry, soil.parameters());
	}

	private Richards1DKernel kernel(RichardsBoundaryConditionType top, List<EquationState> states) {
		return new Richards1DKernel(IBoundaryCondition.createRichardsSimpleBoundaryCondition(top),
				IBoundaryCondition.createRichardsSimpleBoundaryCondition(properties.bottomType()), cells, 1,
				properties.newtonTolerance(), 0.0, properties.maxIterations(), states);
	}

	/** Liquid retention with ice of this soil. */
	public IceAwareRetention retention() {
		return retention;
	}

	/** Sets the suctions [m], bottom first, e.g. the potential of the liquid recomputed after freezing. */
	public void setSuctions(double[] suctions) {
		if (suctions.length != cells) {
			throw new IllegalArgumentException("expected " + cells + " suctions, got " + suctions.length);
		}
		System.arraycopy(suctions, 0, variables.waterSuctions, 0, cells);
	}

	/** Current suctions [m], bottom first. */
	public double[] suctions() {
		return Arrays.copyOf(variables.waterSuctions, cells);
	}

	/** Water stored in the soil, ice-free [kg m-2]. */
	public double storage() {
		neumann.computeWaterVolume(cells);
		return variables.waterVolume * WATER_DENSITY;
	}

	/**
	 * Liquid water stored in the soil holding the given ice [kg m-2].
	 *
	 * @param iceContents θ_i of each cell, water equivalent
	 */
	public double liquidStorage(double[] iceContents) {
		double sum = 0.0;
		for (int i = 0; i < cells; i++) {
			sum += retention.liquid(variables.waterSuctions[i], iceContents[i], variables.parameterID[i])
					* geometry.controlVolume[i];
		}
		return sum * WATER_DENSITY;
	}

	/**
	 * Advances the soil water by one step, without ice.
	 *
	 * @param input        water reaching the top in the step [kg m-2], i.e. mm
	 * @param bottomValue  value of the bottom condition, as in
	 *                     {@code RichardsSolver1D} (unused with free drainage)
	 * @param temperatures soil temperatures for the closures [K], bottom first
	 * @param timeDelta    step [s]
	 */
	public SoilWaterStepResult step(double input, double bottomValue, double[] temperatures, double timeDelta) {
		return step(input, bottomValue, temperatures, null, timeDelta);
	}

	/**
	 * Advances the soil water by one step.
	 *
	 * @param iceContents θ_i of each cell (water equivalent), null for none
	 */
	public SoilWaterStepResult step(double input, double bottomValue, double[] temperatures, double[] iceContents,
			double timeDelta) {
		return step(input, bottomValue, temperatures, iceContents, null, timeDelta);
	}

	/**
	 * Advances the soil water by one step, with evapotranspiration.
	 *
	 * @param iceContents θ_i of each cell (water equivalent), null for none
	 * @param sinks       water to take from each cell in the step [kg m-2],
	 *                    bottom first, not negative; null for none
	 */
	public SoilWaterStepResult step(double input, double bottomValue, double[] temperatures, double[] iceContents,
			double[] sinks, double timeDelta) {
		if (!(input >= 0.0) || Double.isInfinite(input)) {
			throw new IllegalArgumentException("input must be non-negative and finite, got " + input);
		}
		if (temperatures.length != cells) {
			throw new IllegalArgumentException("expected " + cells + " temperatures, got " + temperatures.length);
		}
		boolean frozen = false;
		Arrays.fill(ice, 0.0);
		if (iceContents != null) {
			if (iceContents.length != cells) {
				throw new IllegalArgumentException("expected " + cells + " ice contents, got " + iceContents.length);
			}
			for (int i = 0; i < cells; i++) {
				ice[i] = Math.max(iceContents[i], 0.0);
				frozen |= ice[i] > 0.0;
			}
		}
		double[] requested = new double[cells];
		if (sinks != null) {
			if (sinks.length != cells) {
				throw new IllegalArgumentException("expected " + cells + " sinks, got " + sinks.length);
			}
			for (int i = 0; i < cells; i++) {
				if (!(sinks[i] >= 0.0) || Double.isInfinite(sinks[i])) {
					throw new IllegalArgumentException("sinks must be non-negative and finite, got " + sinks[i]);
				}
				requested[i] = sinks[i];
			}
		}
		System.arraycopy(temperatures, 0, variables.temperatures, 0, cells);
		double[] start = suctions();

		SoilWaterStepResult result = solve(false, (input / 1000) / timeDelta, input, bottomValue, frozen, requested,
				timeDelta);
		int top = cells - 1;
		double saturation = retention.saturationSuction(ice[top], variables.parameterID[top]);
		boolean failed = !(Math.abs(result.balanceError()) <= FAILED_BALANCE)
				|| variables.waterSuctions[top] > saturation + 1e-9;
		if (!failed || input == 0.0) {
			return result;
		}
		// the soil cannot take the input: surface held saturated, the rest runs off
		setSuctions(start);
		SoilWaterStepResult saturated = solve(true, saturation, input, bottomValue, frozen, requested, timeDelta);
		if (!(saturated.infiltration() < input)) {
			setSuctions(start);
			return solve(false, (input / 1000) / timeDelta, input, bottomValue, frozen, requested, timeDelta);
		}
		return saturated;
	}

	/**
	 * The substeps of one step.
	 *
	 * @param saturatedTop Dirichlet top at {@code topValue} [m] instead of a
	 *                     Neumann flux {@code topValue} [m s-1]
	 * @param requested    water to take from each cell in the step [kg m-2]
	 */
	private SoilWaterStepResult solve(boolean saturatedTop, double topValue, double input, double bottomValue,
			boolean frozen, double[] requested, double timeDelta) {
		ComputeQuantitiesRichards quantities = saturatedTop ? dirichlet : neumann;
		Richards1DKernel kernel = frozen ? (saturatedTop ? dirichletIceKernel : neumannIceKernel)
				: (saturatedTop ? dirichletKernel : neumannKernel);
		variables.richardsTopBCValue = topValue;
		variables.richardsBottomBCValue = bottomValue;
		quantities.resetRunOff();

		double storageBefore = frozen ? liquidStorage(ice) : storage();
		double[] faceWater = new double[cells + 1];
		double[] held = new double[cells];
		double[] applied = new double[cells];
		double error = 0.0;
		for (double dt : SubStepping.subStepLengths(timeDelta, properties.maxSubstep())) {
			if (frozen) {
				iceVolumes(variables.volumes);
				variables.waterVolume = sum(variables.volumes);
				for (int i = 0; i < cells; i++) {
					iceStates.get(variables.equationStateID[i]).computeXStar(variables.temperatures[i],
							variables.parameterID[i], i);
				}
			} else {
				quantities.computeWaterVolume(cells);
				quantities.computeXStar(cells);
			}
			// the sink of the substep, at most the liquid above the wilting point [m]
			variables.sumETs = 0.0;
			for (int i = 0; i < cells; i++) {
				double available = variables.volumes[i]
						- wiltingPoint[variables.parameterID[i]] * geometry.controlVolume[i];
				// the order of the operations is WHETGEO's (ComputeQuantitiesRichardsRoot), for the same bits
				variables.ETs[i] = Math.min(requested[i] / 1000 / timeDelta * dt, Math.max(available, 0.0));
				variables.sumETs += variables.ETs[i];
				applied[i] += variables.ETs[i] * WATER_DENSITY;
			}
			for (int picard = 0; picard < properties.picardIterations(); picard++) {
				if (frozen) {
					// conductivity at min(ψ, ψ_sat(θ_i)), then the impedance of the ice
					double[] psi = variables.waterSuctions;
					for (int i = 0; i < cells; i++) {
						held[i] = psi[i];
						psi[i] = Math.min(psi[i], retention.saturationSuction(ice[i], variables.parameterID[i]));
					}
					quantities.computeHydraulicConductivity(cells);
					for (int i = 0; i < cells; i++) {
						psi[i] = held[i];
						variables.kappas[i] *= retention.impedance(ice[i], properties.iceImpedance(),
								variables.parameterID[i]);
					}
				} else {
					quantities.computeHydraulicConductivity(cells);
				}
				quantities.computeInterfaceHydraulicConductivity(cells);
				variables.waterSuctions = kernel.solve(dt, variables.richardsBottomBCValue,
						variables.richardsTopBCValue, cells, variables.kappasInterface, variables.volumes,
						geometry.spaceDeltaZ, variables.ETs, variables.waterSuctions, variables.temperatures,
						variables.parameterID, variables.equationStateID);
			}
			if (frozen) {
				iceVolumes(variables.volumesNew);
				variables.waterVolumeNew = sum(variables.volumesNew);
			} else {
				quantities.computeWaterVolumeNew(cells);
			}
			quantities.computeDarcyVelocities(cells);
			quantities.computeError(cells, dt);
			for (int k = 0; k <= cells; k++) {
				faceWater[k] += variables.darcyVelocities[k] * dt * WATER_DENSITY;
			}
			error += variables.errorVolume * WATER_DENSITY;
		}
		double storageChange = (frozen ? liquidStorage(ice) : storage()) - storageBefore;
		double infiltration = -faceWater[cells];
		double runoff = saturatedTop ? Math.max(input - infiltration, 0.0) : 0.0;
		return new SoilWaterStepResult(suctions(), faceWater, infiltration, -faceWater[0], storageChange, error,
				runoff, applied);
	}

	private void iceVolumes(double[] volumes) {
		for (int i = 0; i < cells; i++) {
			volumes[i] = iceStates.get(variables.equationStateID[i]).equationState(variables.waterSuctions[i],
					variables.temperatures[i], variables.parameterID[i], i);
		}
	}

	private double sum(double[] values) {
		double total = 0.0;
		for (int i = 0; i < cells; i++) {
			total += values[i];
		}
		return total;
	}
}
