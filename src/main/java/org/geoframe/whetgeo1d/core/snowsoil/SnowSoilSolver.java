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

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_WATER;

import java.util.List;
import java.util.Objects;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.equationstate.EquationStateFactory.StateEquationModel;
import org.geoframe.whetgeo1d.core.utils.EnumUtils;
import org.geoframe.whetgeo1d.snow.FreshSnow;
import org.geoframe.whetgeo1d.snow.LiquidWaterPercolation;
import org.geoframe.whetgeo1d.snow.MeltShrinkage;
import org.geoframe.whetgeo1d.snow.Percolated;
import org.geoframe.whetgeo1d.snow.Restratified;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SurfaceSnowAge;
import org.geoframe.whetgeo1d.snow.surface.SnowAlbedo;
import org.geoframe.whetgeo1d.snow.surface.SnowSublimation;
import org.geoframe.whetgeo1d.snow.surface.Sublimated;
import org.geoframe.whetgeo1d.snow.surface.SurfaceEnergyBalance;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;

/**
 * One time step of the snowpack on the soil (specification §2.2): the heat
 * step of the snow+soil column followed by the snowpack operators, in GEOtop's
 * order ({@code EnergyBalance}, {@code energy.balance.cc:455-845}, commit
 * 1f1fc5af):
 *
 * <ol>
 * <li>surface age, with the snowfall of the step and the top-cell temperature
 * at the start ({@link SurfaceSnowAge}); albedo ({@link SnowAlbedo});</li>
 * <li>surface energy balance with the resistances frozen at the top-cell
 * temperature T<sup>n</sup> ({@link SurfaceEnergyBalance});</li>
 * <li>heat step of snow and soil as one system, the surface balance entering
 * as a flux linearised at T<sup>n</sup>, EB(T<sup>n</sup>) + EB'(T<sup>n</sup>)
 * (T - T<sup>n</sup>) ({@link SnowSoilHeatDriver});</li>
 * <li>vapour exchange E(T<sup>n+1</sup>) Δt at the top
 * ({@link SnowSublimation});</li>
 * <li>compaction with the temperatures at the end of the step, then melt
 * shrinkage against the ice at the start of the step;</li>
 * <li>percolation, with the rain entering the top cell
 * ({@link LiquidWaterPercolation});</li>
 * <li>restratification;</li>
 * <li>fresh snow on the top cell ({@link SnowColumn#depositFreshSnow}).</li>
 * </ol>
 *
 * Steps 4–6 are GEOtop's {@code WBsnow}, 7 is {@code snow_layer_combination},
 * 8 is {@code new_snow}. The specification lists compaction and fresh snow
 * under the remapping, after Richards (§2.2 steps 5–6); the order here is
 * GEOtop's, which the fidelity configuration must follow, and Richards, which
 * reads only what leaves the base of the pack, can come after all of them
 * without changing anything.
 *
 * <p>
 * <b>Surface temperature.</b> Phase 1 has no skin node: the surface
 * temperature is the temperature of the top snow cell, as in GEOtop without
 * skin, and the surface balance is the linearised flux of document 007
 * (semi-implicit). The skin by elimination (A17) needs B2.
 *
 * <p>
 * <b>What leaves the pack</b> — drainage, the remains of a pack too thin to
 * keep, rain falling on bare ground, the energy left by a cell consumed by
 * sublimation — is returned as the water entering the top of the soil, with
 * its energy ({@link SnowSoilStepResult#soilInputMass()},
 * {@link SnowSoilStepResult#soilInputEnergy()}); handing it to Richards and to
 * the soil heat is the next stage (§2.2 step 5, §4.2).
 *
 * <p>
 * <b>Phase mismatch of the vapour</b> ({@link Sublimated#phaseMismatchEnergy()},
 * document 008): when the declared phase is missing in the top cell, the
 * energy its phase change would need is carried to the next step and added to
 * the surface flux, so that over the run the vapour carries exactly λ m. With
 * an imposed temperature on bare soil it waits for the next flux boundary.
 *
 * <p>
 * Not here: Richards and the soil water (next stage), frozen soil (A9), the
 * skin (A17), the penetration of shortwave into the pack, canopy.
 */
public final class SnowSoilSolver {

	private final SnowSoilHeatDriver heat;
	private final SoilWaterDriver water;
	private final boolean[] freezing;
	private final IceAwareRetention retention;
	private final double[] controlVolume;
	/** ψ_w0 of the freezing cells, storage above saturation included (the heat sees min(ψ_w0, 0)). */
	private final double[] totalSuctions;
	private final SnowProcesses processes;
	private final SnowEquationOfState eos;

	private List<SnowCell> snow;
	private double surfaceAge;
	private double pendingSurfaceEnergy;
	private ColumnStepTerms lastTerms;

	/**
	 * @param soil        soil part of the column, with its initial temperatures
	 * @param heat        snow side of the heat system; its equation of state
	 *                    must be the one of {@code processes}
	 * @param bottomType  bottom condition of the heat equation
	 * @param settings    Newton settings of the heat step
	 * @param processes   snowpack operators
	 * @param initialSnow initial pack, index 0 at the bottom; may be empty
	 * @param initialAge  non-dimensional surface age
	 */
	public SnowSoilSolver(SoilHeatSetup soil, SnowHeatProperties heat, DiffusionBoundaryConditionType bottomType,
			NewtonSettings settings, SnowProcesses processes, List<SnowCell> initialSnow, double initialAge) {
		this(soil, null, heat, bottomType, settings, processes, initialSnow, initialAge);
	}

	/**
	 * Column with soil water: the water leaving the pack, and the rain on bare
	 * soil, enter Richards and carry their energy into the soil (document 011).
	 *
	 * @param soilWater hydraulic side of the soil; null for a soil with fixed
	 *                  suction, whose input water is only returned
	 */
	public SnowSoilSolver(SoilHeatSetup soil, SoilWaterProperties soilWater, SnowHeatProperties heat,
			DiffusionBoundaryConditionType bottomType, NewtonSettings settings, SnowProcesses processes,
			List<SnowCell> initialSnow, double initialAge) {
		this.processes = Objects.requireNonNull(processes, "processes");
		if (heat.equationOfState() != processes.equationOfState()) {
			throw new IllegalArgumentException("the heat step and the snowpack must share the equation of state");
		}
		if (!(initialAge >= 0.0) || Double.isInfinite(initialAge)) {
			throw new IllegalArgumentException("initialAge must be non-negative and finite, got " + initialAge);
		}
		this.eos = processes.equationOfState();
		this.heat = new SnowSoilHeatDriver(soil, heat, bottomType, settings);
		if (soilWater != null && soil == null) {
			throw new IllegalArgumentException("soil water needs a soil");
		}
		this.water = soilWater == null ? null : new SoilWaterDriver(soil, soilWater);
		this.freezing = soil == null ? new boolean[0] : freezingIds(soil);
		this.retention = soil == null ? null : new IceAwareRetention(soil.parameters());
		this.controlVolume = soil == null ? new double[0] : soil.controlVolume().clone();
		this.totalSuctions = soil == null ? new double[0] : soil.waterSuction().clone();
		this.snow = List.copyOf(initialSnow);
		this.surfaceAge = initialAge;
	}

	/** The pack, index 0 at the bottom. */
	public List<SnowCell> snow() {
		return snow;
	}

	/** Non-dimensional age of the snow surface. */
	public double surfaceAge() {
		return surfaceAge;
	}

	/** Current soil temperatures [K], bottom first. */
	public double[] soilTemperatures() {
		return heat.soilTemperatures();
	}

	/** Which equation-state ids of the soil are WHETGEO's freezing soil. */
	private static boolean[] freezingIds(SoilHeatSetup soil) {
		boolean[] result = new boolean[soil.typeEquationState().length];
		for (int k = 0; k < result.length; k++) {
			result[k] = EnumUtils.fromString(StateEquationModel.class,
					soil.typeEquationState()[k]) == StateEquationModel.FREEZING_SOIL_INTERNAL_ENERGY;
		}
		return result;
	}

	/**
	 * Ice in the soil [-], volume of water equivalent per volume of soil, from
	 * the heat state, as {@code FreezingSoilInternalEnergy} counts it (see
	 * {@link IceAwareRetention#freezingIce}); zero for a soil that does not
	 * freeze; empty without soil.
	 */
	public double[] soilIce() {
		double[] t = heat.soilTemperatures();
		double[] psi = heat.soilSuctions();
		int[] eos = heat.soilEquationStateIds();
		int[] ids = heat.soilParameterIds();
		double[] ice = new double[t.length];
		for (int i = 0; i < t.length; i++) {
			if (freezing[eos[i]]) {
				ice[i] = retention.freezingIce(psi[i], t[i], ids[i]);
			}
		}
		return ice;
	}

	/** Current soil suctions [m], bottom first: ψ_w0 for a freezing soil (the potential of the total water). */
	public double[] soilSuctions() {
		return heat.soilSuctions();
	}

	/**
	 * Internal energy of the soil [J m-2], reference of the soil closures: the
	 * closures' U plus, in a freezing soil, the liquid held by the specific
	 * storage above saturation, which the freezing closures do not see.
	 */
	public double soilInternalEnergy() {
		double[] excess = excessWater();
		double[] t = heat.soilTemperatures();
		double sum = heat.soilInternalEnergy();
		for (int i = 0; i < excess.length; i++) {
			sum += excess[i] * heat.liquidEnergy(t[i]);
		}
		return sum;
	}

	/**
	 * Water held by the specific storage above saturation in the freezing cells
	 * [kg m-2]: ρ_w V (θ_tot(ψ_w0) - θ_s) where ψ_w0 &gt; 0. WHETGEO's freezing curve
	 * is flat at θ_s above saturation, so this water is not in the closures.
	 */
	private double[] excessWater() {
		double[] excess = new double[totalSuctions.length];
		if (water == null || !anyFreezing()) {
			return excess;
		}
		int[] eos = heat.soilEquationStateIds();
		int[] ids = heat.soilParameterIds();
		for (int i = 0; i < excess.length; i++) {
			if (freezing[eos[i]] && totalSuctions[i] > 0.0) {
				excess[i] = (retention.totalWater(totalSuctions[i], ids[i]) - retention.totalWater(0.0, ids[i]))
						* controlVolume[i] * 1000.0;
			}
		}
		return excess;
	}

	/**
	 * Water stored in the soil, liquid and ice [kg m-2]; NaN without soil water.
	 * In a freezing soil it is θ_tot(ψ_w0) of each cell, otherwise WHETGEO's
	 * water volume of Richards.
	 */
	public double soilWaterStorage() {
		if (water == null) {
			return Double.NaN;
		}
		if (!anyFreezing()) {
			return water.storage();
		}
		double[] psi = heat.soilSuctions();
		int[] eos = heat.soilEquationStateIds();
		int[] ids = heat.soilParameterIds();
		double[] liquidSuctions = water.suctions();
		double sum = 0.0;
		for (int i = 0; i < psi.length; i++) {
			double theta = freezing[eos[i]] ? retention.totalWater(totalSuctions[i], ids[i])
					: retention.liquid(liquidSuctions[i], 0.0, ids[i]);
			sum += theta * controlVolume[i];
		}
		return sum * 1000.0;
	}

	/** Energy waiting to enter the surface at the next step [J m-2]. */
	public double pendingSurfaceEnergy() {
		return pendingSurfaceEnergy;
	}

	/** Whether the soil has Richards (soil water), not a fixed suction. */
	public boolean hasSoilWater() {
		return water != null;
	}

	/**
	 * The complete state the next step reads (ARCH-5, SOL-5), copied: see
	 * {@link SnowSoilState} for what it holds and why that is all.
	 */
	public SnowSoilState snapshot() {
		return new SnowSoilState(snow, surfaceAge, pendingSurfaceEnergy, heat.soilTemperatures(),
				heat.soilSuctions(), water == null ? null : water.suctions(), totalSuctions);
	}

	/**
	 * Puts the solver back in a state taken by {@link #snapshot()}, from this
	 * solver (rollback of a rejected step, SOL-5) or from another one built
	 * with the same configuration (restart, ARCH-5). The next step is then the
	 * same, to the bit, as the step that followed the snapshot.
	 *
	 * @throws IllegalArgumentException if the state does not fit the column:
	 *                                  another number of soil cells, soil water
	 *                                  where there is none or the reverse, or
	 *                                  more snow cells than the capacity
	 */
	public void restore(SnowSoilState state) {
		Objects.requireNonNull(state, "state");
		if (state.soilCells() != totalSuctions.length) {
			throw new IllegalArgumentException(
					"state has " + state.soilCells() + " soil cells, the column " + totalSuctions.length);
		}
		if ((state.soilWaterSuctions() == null) != (water == null)) {
			throw new IllegalArgumentException(water == null ? "state has soil water, the column has none"
					: "state has no soil water, the column has");
		}
		if (state.snow().size() > heat.snowCapacity()) {
			throw new IllegalArgumentException(
					"state has " + state.snow().size() + " snow cells, capacity " + heat.snowCapacity());
		}
		if (!(state.surfaceAge() >= 0.0) || Double.isInfinite(state.surfaceAge())) {
			throw new IllegalArgumentException("surface age must be non-negative and finite, got " + state.surfaceAge());
		}
		snow = state.snow();
		surfaceAge = state.surfaceAge();
		pendingSurfaceEnergy = state.pendingSurfaceEnergy();
		heat.setSoilTemperatures(state.soilTemperatures());
		heat.setSoilSuctions(state.soilHeatSuctions());
		if (water != null) {
			water.setSuctions(state.soilWaterSuctions());
		}
		System.arraycopy(state.totalSuctions(), 0, totalSuctions, 0, totalSuctions.length);
		lastTerms = null;
	}

	/**
	 * Terms of the last step that {@link SnowSoilStepResult} does not carry,
	 * for the whole-column balance and the acceptance of the step; null before
	 * the first step and after {@link #restore}.
	 */
	public ColumnStepTerms lastStepTerms() {
		return lastTerms;
	}

	/** Advances the column by one step. */
	public SnowSoilStepResult step(StepForcing forcing) {
		Objects.requireNonNull(forcing, "forcing");
		double dt = forcing.timeDelta();
		List<SnowCell> start = snow;
		double massBefore = SnowColumn.totalMass(start);
		double energyBefore = SnowColumn.internalEnergy(start);
		boolean snowAtStart = !start.isEmpty();
		double soilEnergyBefore = water == null ? Double.NaN : soilInternalEnergy();
		double[] hiddenStart = water == null ? null : excessWater();
		double[] soilStartTemperatures = water == null ? null : heat.soilTemperatures();

		// 1-2: age, albedo and surface balance at the start of the step
		SurfaceEnergyBalance balance = null;
		double lineAtStart = 0.0;
		double lineSlope = 0.0;
		double startTemperature = Double.NaN;
		double albedo = Double.NaN;
		TopBoundary top;
		double pendingApplied = 0.0;
		boolean imposedSnowTop = snowAtStart && forcing.snowTop() != null;
		if (imposedSnowTop) {
			// COL-6: an imposed condition over snow in place of the surface balance
			startTemperature = eos.temperature(start.get(start.size() - 1));
			surfaceAge = SurfaceSnowAge.advance(surfaceAge, forcing.snowfall(), startTemperature, dt,
					processes.restoringSnowfall());
			if (forcing.snowTop() instanceof TopBoundary.SurfaceFlux flux) {
				pendingApplied = pendingSurfaceEnergy;
				top = new TopBoundary.SurfaceFlux(flux.flux() + pendingApplied / dt, flux.fluxDerivative());
			} else {
				double imposed = ((TopBoundary.SurfaceTemperature) forcing.snowTop()).temperature();
				top = new TopBoundary.SurfaceTemperature(Math.min(imposed, MELTING_TEMPERATURE));
			}
		} else if (snowAtStart) {
			startTemperature = eos.temperature(start.get(start.size() - 1));
			surfaceAge = SurfaceSnowAge.advance(surfaceAge, forcing.snowfall(), startTemperature, dt,
					processes.restoringSnowfall());
			SnowAlbedo.Albedos albedos = processes.albedo().albedos(SnowColumn.depth(start), surfaceAge,
					forcing.surface().cosIncidence(), forcing.groundAlbedoVisible(),
					forcing.groundAlbedoNearInfrared());
			albedo = albedos.broadband(forcing.surface().shortwaveDirect(), forcing.surface().shortwaveDiffuse());
			// GEOtop's thin-snow regime (fidelity): soil roughness and emissivity under a thin pack
			double depth = SnowColumn.depth(start);
			balance = SurfaceEnergyBalance.atStartOfStep(
					processes.thinSnow().surfaceBalance(processes.surfaceBalance(), depth),
					processes.thinSnow().turbulence(processes.turbulence(), depth), forcing.surface(), albedos,
					startTemperature);
			SurfaceFluxes startFluxes = balance.evaluate(startTemperature);
			lineAtStart = startFluxes.energyBalance();
			lineSlope = startFluxes.derivative();
			pendingApplied = pendingSurfaceEnergy;
			top = new TopBoundary.SurfaceFlux(lineAtStart + pendingApplied / dt, lineSlope);
		} else if (forcing.bareSoilTop() instanceof TopBoundary.SurfaceFlux flux) {
			pendingApplied = pendingSurfaceEnergy;
			top = new TopBoundary.SurfaceFlux(flux.flux() + pendingApplied / dt, flux.fluxDerivative());
		} else {
			top = forcing.bareSoilTop();
		}
		pendingSurfaceEnergy -= pendingApplied;

		// 3: heat, repeated with the surface flux linearised at the last surface temperature
		double[] soilStart = heat.soilTemperatures();
		HeatStepResult heatStep = heat.step(start, top, forcing.bottomValue(), dt);
		int sweeps = 1;
		boolean surfaceConverged = !snowAtStart || imposedSnowTop || processes.surfaceCoupling().maxSweeps() == 1;
		double linearisedAt = startTemperature;
		while (!surfaceConverged && sweeps < processes.surfaceCoupling().maxSweeps()) {
			double[] temperatures = heatStep.temperatures();
			double surfaceNow = temperatures[temperatures.length - 1];
			if (Math.abs(surfaceNow - linearisedAt) <= processes.surfaceCoupling().temperatureTolerance()) {
				surfaceConverged = true;
				break;
			}
			SurfaceFluxes at = balance.evaluate(surfaceNow);
			// G(T) = EB(T_k) + EB'(T_k) (T - T_k), written around T^n as the driver expects
			lineSlope = at.derivative();
			lineAtStart = at.energyBalance() + lineSlope * (startTemperature - surfaceNow);
			top = new TopBoundary.SurfaceFlux(lineAtStart + pendingApplied / dt, lineSlope);
			linearisedAt = surfaceNow;
			heat.setSoilTemperatures(soilStart);
			heatStep = heat.step(start, top, forcing.bottomValue(), dt);
			sweeps++;
		}
		if (!surfaceConverged) {
			double[] temperatures = heatStep.temperatures();
			surfaceConverged = Math.abs(temperatures[temperatures.length - 1] - linearisedAt) <= processes
					.surfaceCoupling().temperatureTolerance();
		}
		double surfaceChange = Double.NaN;
		if (snowAtStart && !imposedSnowTop) {
			double[] temperatures = heatStep.temperatures();
			surfaceChange = Math.abs(temperatures[temperatures.length - 1] - linearisedAt);
		}
		int heatWorstCell = heat.worstCell();
		List<SnowCell> cells = heatStep.snow();
		double heatEnergy = SnowColumn.internalEnergy(cells) - energyBefore;
		double melt = ice(start) - ice(cells);

		// 4: vapour exchange at the final surface temperature
		SurfaceFluxes surface = null;
		double surfaceTemperature = Double.NaN;
		double linearisation = 0.0;
		double vapour = 0.0;
		double vapourEnergy = 0.0;
		double unmetVapour = 0.0;
		double toSoilMass = 0.0;
		double toSoilEnergy = 0.0;
		double phaseMismatch = 0.0;
		if (imposedSnowTop) {
			double[] temperatures = heatStep.temperatures();
			surfaceTemperature = temperatures[temperatures.length - 1];
		} else if (snowAtStart) {
			double[] temperatures = heatStep.temperatures();
			surfaceTemperature = temperatures[temperatures.length - 1];
			surface = balance.evaluate(surfaceTemperature);
			linearisation = dt
					* (lineAtStart + lineSlope * (surfaceTemperature - startTemperature) - surface.energyBalance());
			Sublimated sublimated = SnowSublimation.apply(cells, surface.vapourFlux() * dt, balance.vapourPhase(),
					processes.sublimationThickness(), eos);
			cells = sublimated.cells();
			vapour = sublimated.exchangedMass();
			vapourEnergy = sublimated.exchangedEnergy();
			unmetVapour = Math.abs(sublimated.unmetMass());
			toSoilEnergy += sublimated.residualEnergy();
			phaseMismatch = sublimated.phaseMismatchEnergy();
			pendingSurfaceEnergy -= phaseMismatch;
		}

		// 5: compaction and melt shrinkage, against the ice at the start of the step
		cells = processes.compaction().compact(cells, dt, processes.slopeAngle());
		cells = MeltShrinkage.apply(start.subList(0, cells.size()), cells);

		// 6: percolation, with the rain entering the top cell
		double rainEnergy = 0.0;
		double rainIntoPack = 0.0;
		double refrozen = 0.0;
		double soilInputMass;
		double soilInputEnergy;
		if (cells.isEmpty()) {
			double rainTemperature = processes.precipitation().rain(forcing.surface(), Double.NaN,
					forcing.precipitationTemperature());
			soilInputMass = forcing.rainfall();
			soilInputEnergy = LiquidWaterPercolation.liquidWaterEnergy(forcing.rainfall(), rainTemperature);
		} else {
			rainIntoPack = forcing.rainfall();
			if (rainIntoPack > 0.0) {
				double rainTemperature = processes.precipitation().rain(forcing.surface(),
						eos.temperature(cells.get(cells.size() - 1)), forcing.precipitationTemperature());
				rainEnergy = LiquidWaterPercolation.liquidWaterEnergy(rainIntoPack, rainTemperature);
			}
			Percolated percolated = processes.percolation().apply(cells, dt, rainIntoPack, rainEnergy);
			cells = percolated.cells();
			refrozen = percolated.refrozenMass();
			toSoilMass += percolated.drainedMass();
			toSoilEnergy += percolated.drainedEnergy();
			soilInputMass = 0.0;
			soilInputEnergy = 0.0;
		}

		// 7: restratification
		Restratified restratified = processes.restratification().apply(cells);
		cells = restratified.cells();
		if (restratified.releasedToSoil().isPresent()) {
			SnowCell released = restratified.releasedToSoil().get();
			toSoilMass += released.totalMass();
			toSoilEnergy += released.internalEnergy();
		}

		// 8: fresh snow
		double snowfallEnergy = 0.0;
		if (forcing.snowfall() > 0.0) {
			SnowCell fresh = FreshSnow.cell(forcing.snowfall(),
					FreshSnow.geotopDensity(forcing.surface().airTemperature(), forcing.surface().windSpeed()),
					processes.precipitation().snow(forcing.surface(), forcing.precipitationTemperature()));
			snowfallEnergy = fresh.internalEnergy();
			cells = SnowColumn.depositFreshSnow(cells, fresh, eos);
		}

		// 9: the water leaving the pack (or the rain on bare soil) enters Richards with its energy
		double totalSoilInput = soilInputMass + toSoilMass;
		double totalSoilInputEnergy = soilInputEnergy + toSoilEnergy;
		SoilWaterStepResult soilWater = null;
		double infiltrationEnergy = Double.NaN;
		double soilDrainageEnergy = Double.NaN;
		double soilEnergyClosure = Double.NaN;
		double soilStorageHeat = Double.NaN;
		double soilSinkEnergy = Double.NaN;
		if (water == null && forcing.soilWaterSinks() != null) {
			throw new IllegalArgumentException("evapotranspiration needs soil water");
		}
		if (water != null) {
			// the water stored above saturation follows its cell in the heat step, without its heat capacity
			double[] afterHeat = heat.soilTemperatures();
			soilStorageHeat = 0.0;
			for (int i = 0; i < hiddenStart.length; i++) {
				soilStorageHeat += hiddenStart[i] * (heat.liquidEnergy(afterHeat[i])
						- heat.liquidEnergy(soilStartTemperatures[i]));
			}
			// A9: Richards carries the potential of the liquid, the freezing curve that of the total water
			double[] ice = soilIce();
			double[] oldExcess = excessWater();
			int[] eos = heat.soilEquationStateIds();
			int[] ids = heat.soilParameterIds();
			double[] liquidSuctions = water.suctions();
			boolean anyIce = false;
			boolean anyFreezing = anyFreezing();
			for (int i = 0; i < ice.length && anyFreezing; i++) {
				if (!freezing[eos[i]]) {
					continue;
				}
				if (ice[i] > 0.0) {
					double liquid = retention.totalWater(totalSuctions[i], ids[i]) - ice[i];
					liquidSuctions[i] = retention.suction(liquid, ice[i], ids[i]);
					anyIce = true;
				} else {
					liquidSuctions[i] = totalSuctions[i];
				}
			}
			if (anyFreezing) {
				water.setSuctions(liquidSuctions);
			}
			soilWater = water.step(totalSoilInput, forcing.soilWaterBottomValue(), heat.soilTemperatures(), anyIce ? ice : null,
					forcing.soilWaterSinks(), dt);
			double[] newHeatSuctions = soilWater.suctions().clone();
			for (int i = 0; i < ice.length && anyFreezing; i++) {
				if (!freezing[eos[i]]) {
					continue;
				}
				totalSuctions[i] = ice[i] > 0.0
						? retention.totalWaterSuction(retention.liquid(newHeatSuctions[i], ice[i], ids[i]) + ice[i],
								ids[i])
						: newHeatSuctions[i];
				// GEOtop caps the melting temperature at 0 °C: the freezing curve sees at most saturation
				newHeatSuctions[i] = Math.min(totalSuctions[i], 0.0);
			}
			double[] newExcess = excessWater();
			// the water that enters carries the energy per kg of the input; the runoff leaves with the rest
			double topEnergy = totalSoilInput > 0.0
					? soilWater.infiltration() * heat.liquidEnergy(MELTING_TEMPERATURE
							+ (totalSoilInputEnergy / totalSoilInput - LATENT_HEAT_FUSION) / SPECIFIC_HEAT_WATER)
					: 0.0;
			soilSinkEnergy = heat.sinkEnergy(soilWater.sinks());
			infiltrationEnergy = topEnergy;
			soilDrainageEnergy = heat.transportSoilWater(newHeatSuctions, soilWater.faceWater(), topEnergy, oldExcess,
					newExcess, soilWater.sinks());
			double[] fluxes = heatStep.conductiveFluxes();
			int nSoil = heat.soilCells();
			soilEnergyClosure = soilInternalEnergy() - soilEnergyBefore
					- dt * (fluxes[0] - fluxes[nSoil]) - topEnergy + soilDrainageEnergy + soilSinkEnergy - soilStorageHeat;
		}

		snow = cells;
		lastTerms = new ColumnStepTerms(pendingApplied, phaseMismatch, surfaceChange, heatWorstCell, soilInputMass,
				soilInputEnergy, infiltrationEnergy, soilSinkEnergy);
		SnowBudget budget = SnowBudget.of(massBefore, SnowColumn.totalMass(cells), forcing.snowfall(), rainIntoPack,
				vapour, toSoilMass, energyBefore, SnowColumn.internalEnergy(cells), heatEnergy, snowfallEnergy,
				rainEnergy, vapourEnergy, toSoilEnergy);
		return new SnowSoilStepResult(cells, surfaceAge, heatStep, sweeps, surfaceConverged, surface,
				surfaceTemperature, albedo, melt, refrozen, restratified.merges(), restratified.splits(), unmetVapour,
				linearisation, pendingSurfaceEnergy, totalSoilInput, totalSoilInputEnergy, soilWater,
				soilDrainageEnergy, soilEnergyClosure, soilStorageHeat, budget);
	}

	private boolean anyFreezing() {
		for (boolean f : freezing) {
			if (f) {
				return true;
			}
		}
		return false;
	}

	private static double ice(List<SnowCell> cells) {
		double sum = 0.0;
		for (SnowCell cell : cells) {
			sum += cell.iceMass();
		}
		return sum;
	}
}
