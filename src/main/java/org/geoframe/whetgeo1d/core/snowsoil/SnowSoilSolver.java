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
import org.geoframe.whetgeo1d.snow.RestratificationPolicy;
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
	/** Snow heat properties, for the conductance of the skin over snow. */
	private final SnowHeatProperties heatProperties;
	/** How the surface of the last step was solved; null before the first step. */
	private SurfaceSolution lastSurface;
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
		this.heatProperties = heat;
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

	/**
	 * Thermal conductivity of the top soil cell at the current state [W m-1
	 * K-1], for a surface balance through a skin node outside the solver
	 * ({@link BareSoilEnergyBalance}); NaN without soil.
	 */
	public double topSoilConductivity() {
		return heat.topSoilConductivity();
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

	/**
	 * How the surface of a step was solved (SURF-1/2, {@link SkinNode}).
	 */
	public enum SurfaceMode {
		/** No surface balance in the step: an imposed top, or a bare-soil flux given as such. */
		IMPOSED,
		/** The balance at the top cell, no skin ({@link SkinNode#NONE}). */
		TOP_CELL,
		/** Through the skin node. */
		SKIN,
		/** Over snow the skin rose above 0 °C and the step was solved without it ({@link SkinNode#GEOTOP}). */
		SKIN_ABOVE_ZERO_RESOLVED,
		/** Over snow the skin was held at 0 °C ({@link SkinNode#CAPPED}). */
		SKIN_AT_ZERO
	}

	/**
	 * The surface of the last step.
	 *
	 * @param mode               how it was solved
	 * @param surfaceTemperature T<sub>s</sub>: the skin, or the top cell without
	 *                           it [K]; NaN without a surface balance
	 * @param topCellTemperature temperature of the top cell at the end of the
	 *                           heat step [K]
	 * @param fluxes             the balance at T<sub>s</sub>; null without one
	 * @param conductance        K of the skin [W m-2 K-1], NaN without it
	 * @param overSnow           the surface was the snow
	 * @param absorbedBelow      net shortwave absorbed below the surface, in the
	 *                           cells (SNOW-7) [W m-2]: the surface balance is
	 *                           {@code fluxes.energyBalance()} minus it
	 */
	public record SurfaceSolution(SurfaceMode mode, double surfaceTemperature, double topCellTemperature,
			SurfaceFluxes fluxes, double conductance, boolean overSnow, double absorbedBelow) {
	}

	/** The surface of the last step (skin temperature, bare-soil fluxes); null before the first step. */
	public SurfaceSolution lastSurface() {
		return lastSurface;
	}

	/** Result of the surface iterations of a step. */
	private record SurfaceSolve(HeatStepResult heatStep, int sweeps, boolean converged, double surfaceTemperature,
			double lineAtStart, double lineSlope, double linearisedAt, double change, SurfaceMode mode,
			double conductance) {
	}

	/**
	 * Heat step with a nonlinear surface balance at the top, through the skin
	 * node of {@link SnowProcesses#skin()} (SURF-1/2; the elimination is in
	 * {@link SkinNode}), or at the top cell. The balance is linearised at the
	 * start temperature, then at the surface temperature of the last solve, up to
	 * {@link SurfaceCoupling#maxSweeps()} solves from the same start
	 * ({@link SurfaceCoupling}).
	 *
	 * @param cells            snow of the heat step (empty over bare soil)
	 * @param balance          EB(T) with the resistances frozen at
	 *                         {@code startTemperature}
	 * @param startTemperature T<sup>n</sup> of the top cell, where GEOtop
	 *                         restarts its skin [K]
	 * @param conductance      K of the upper half of the top cell [W m-2 K-1]
	 * @param overSnow         the 0 °C rule of the skin applies
	 * @param pendingFlux      deferred surface energy entering in the step [W m-2]
	 */
	private SurfaceSolve solveSurface(List<SnowCell> cells, TopBoundary.Balance balance, double startTemperature,
			double conductance, boolean overSnow, double pendingFlux, double bottomValue, double dt,
			double[] soilStart) {
		SkinNode node = processes.skin();
		if (node.active() && conductance > 0.0 && Double.isFinite(conductance)) {
			SurfaceSolve skin = skinSweeps(cells, balance, startTemperature, conductance, pendingFlux, bottomValue, dt,
					soilStart);
			// over snow the balance jumps at 0 °C (sublimation below, evaporation above): sweeps that alternate
			// across it without converging have no fixed point on either side, the skin is at 0 °C and is treated
			// as one that rose above it
			boolean acrossMelting = !skin.converged()
					&& (skin.linearisedAt() - MELTING_TEMPERATURE) * (skin.surfaceTemperature() - MELTING_TEMPERATURE) < 0.0;
			if (!overSnow || skin.surfaceTemperature() <= MELTING_TEMPERATURE && !acrossMelting) {
				return skin;
			}
			heat.setSoilTemperatures(soilStart);
			if (node == SkinNode.GEOTOP) {
				// GEOtop does not admit a skin above 0 °C over snow: the step again, at the top cell
				SurfaceSolve top = topCellSweeps(cells, balance, startTemperature, pendingFlux, bottomValue, dt,
						soilStart);
				return new SurfaceSolve(top.heatStep(), skin.sweeps() + top.sweeps(), top.converged(),
						top.surfaceTemperature(), top.lineAtStart(), top.lineSlope(), top.linearisedAt(), top.change(),
						SurfaceMode.SKIN_ABOVE_ZERO_RESOLVED, Double.NaN);
			}
			// SURF-2: the skin held at 0 °C, the top cell receives EB(0 °C), the surplus melts it
			double atZero = balance.evaluate(MELTING_TEMPERATURE).energyBalance();
			HeatStepResult step = heat.step(cells, new TopBoundary.SurfaceFlux(atZero + pendingFlux, 0.0), bottomValue,
					dt);
			return new SurfaceSolve(step, skin.sweeps() + 1, true, MELTING_TEMPERATURE, atZero, 0.0,
					MELTING_TEMPERATURE, 0.0, SurfaceMode.SKIN_AT_ZERO, conductance);
		}
		return topCellSweeps(cells, balance, startTemperature, pendingFlux, bottomValue, dt, soilStart);
	}

	/** The sweeps with the balance at the top cell. */
	private SurfaceSolve topCellSweeps(List<SnowCell> cells, TopBoundary.Balance balance, double startTemperature,
			double pendingFlux, double bottomValue, double dt, double[] soilStart) {
		SurfaceCoupling coupling = processes.surfaceCoupling();
		SurfaceFluxes startFluxes = balance.evaluate(startTemperature);
		double lineAtStart = startFluxes.energyBalance();
		double lineSlope = startFluxes.derivative();
		TopBoundary top = new TopBoundary.SurfaceFlux(lineAtStart + pendingFlux, lineSlope);
		HeatStepResult heatStep = heat.step(cells, top, bottomValue, dt);
		int sweeps = 1;
		boolean converged = coupling.maxSweeps() == 1;
		double linearisedAt = startTemperature;
		// a fixed point bracketed by the sweeps: below it the surface comes out warmer than linearised, above it
		// colder; bisected once the sweeps alternate without contracting (a top cell melting out within the step)
		double bracketLow = Double.NEGATIVE_INFINITY;
		double bracketHigh = Double.POSITIVE_INFINITY;
		double previousChange = Double.NaN;
		boolean bisecting = false;
		while (!converged && sweeps < coupling.maxSweeps()) {
			double surfaceNow = topOf(heatStep);
			double change = surfaceNow - linearisedAt;
			if (Math.abs(change) <= coupling.temperatureTolerance()) {
				converged = true;
				break;
			}
			if (change > 0.0) {
				bracketLow = Math.max(bracketLow, linearisedAt);
			} else {
				bracketHigh = Math.min(bracketHigh, linearisedAt);
			}
			bisecting |= change * previousChange < 0.0 && Math.abs(change) > 0.5 * Math.abs(previousChange);
			previousChange = change;
			double next = bisecting && Double.isFinite(bracketLow) && Double.isFinite(bracketHigh)
					? 0.5 * (bracketLow + bracketHigh)
					: surfaceNow;
			SurfaceFluxes at = balance.evaluate(next);
			// G(T) = EB(T_k) + EB'(T_k) (T - T_k), written around T^n as the driver expects
			lineSlope = at.derivative();
			lineAtStart = at.energyBalance() + lineSlope * (startTemperature - next);
			top = new TopBoundary.SurfaceFlux(lineAtStart + pendingFlux, lineSlope);
			linearisedAt = next;
			heat.setSoilTemperatures(soilStart);
			heatStep = heat.step(cells, top, bottomValue, dt);
			sweeps++;
		}
		if (!converged) {
			converged = Math.abs(topOf(heatStep) - linearisedAt) <= coupling.temperatureTolerance();
		}
		double surface = topOf(heatStep);
		return new SurfaceSolve(heatStep, sweeps, converged, surface, lineAtStart, lineSlope, linearisedAt,
				Math.abs(surface - linearisedAt), SurfaceMode.TOP_CELL, Double.NaN);
	}

	/** The sweeps through the skin node, eliminated as in {@link SkinNode}. */
	private SurfaceSolve skinSweeps(List<SnowCell> cells, TopBoundary.Balance balance, double startTemperature,
			double conductance, double pendingFlux, double bottomValue, double dt, double[] soilStart) {
		SurfaceCoupling coupling = processes.surfaceCoupling();
		double tk = startTemperature;
		SurfaceFluxes at = balance.evaluate(tk);
		double e0 = at.energyBalance();
		double slope = at.derivative();
		double factor = conductance / (conductance - slope);
		HeatStepResult heatStep = heat.step(cells,
				new TopBoundary.SurfaceFlux(factor * (e0 + slope * (startTemperature - tk)) + pendingFlux, factor * slope),
				bottomValue, dt);
		double skin = (e0 - slope * tk + conductance * topOf(heatStep)) / (conductance - slope);
		int sweeps = 1;
		boolean converged = coupling.maxSweeps() == 1;
		while (!converged && sweeps < coupling.maxSweeps()) {
			if (Math.abs(skin - tk) <= coupling.temperatureTolerance()) {
				converged = true;
				break;
			}
			tk = skin;
			at = balance.evaluate(tk);
			e0 = at.energyBalance();
			slope = at.derivative();
			factor = conductance / (conductance - slope);
			heat.setSoilTemperatures(soilStart);
			heatStep = heat.step(cells, new TopBoundary.SurfaceFlux(
					factor * (e0 + slope * (startTemperature - tk)) + pendingFlux, factor * slope), bottomValue, dt);
			skin = (e0 - slope * tk + conductance * topOf(heatStep)) / (conductance - slope);
			sweeps++;
		}
		if (!converged) {
			converged = Math.abs(skin - tk) <= coupling.temperatureTolerance();
		}
		return new SurfaceSolve(heatStep, sweeps, converged, skin, factor * (e0 + slope * (startTemperature - tk)),
				factor * slope, tk, Math.abs(skin - tk), SurfaceMode.SKIN, conductance);
	}

	/** The balance at the surface without the shortwave absorbed below it (SNOW-7). */
	private static SurfaceFluxes withoutPenetrated(SurfaceFluxes f, double penetrated) {
		return new SurfaceFluxes(f.netShortwave(), f.netLongwave(), f.sensibleHeat(), f.latentHeatFlux(),
				f.vapourFlux(), f.latentHeat(), f.energyBalance() - penetrated, f.derivative());
	}

	/** K of the upper half of the top snow cell, at its state at the start of the step [W m-2 K-1]. */
	private double snowSkinConductance(SnowCell top) {
		return heatProperties.conductivity().conductivity(top) / (0.5 * top.thickness());
	}

	/** K of the upper half of the top soil cell [W m-2 K-1]. */
	private double soilSkinConductance() {
		return heat.topSoilConductivity() / heat.topSoilHalfCell();
	}

	private static double topOf(HeatStepResult step) {
		double[] t = step.temperatures();
		return t[t.length - 1];
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
		double startTemperature = Double.NaN;
		double albedo = Double.NaN;
		TopBoundary top = null;
		double pendingApplied = 0.0;
		boolean imposedSnowTop = snowAtStart && forcing.snowTop() != null;
		double[] soilStart = heat.soilTemperatures();
		if (imposedSnowTop) {
			// COL-6: an imposed condition over snow in place of the surface balance
			startTemperature = eos.temperature(start.get(start.size() - 1));
			surfaceAge = SurfaceSnowAge.advance(surfaceAge, forcing.snowfall(), startTemperature, dt,
					processes.restoringSnowfall());
			if (forcing.snowTop() instanceof TopBoundary.SurfaceFlux flux) {
				pendingApplied = pendingSurfaceEnergy;
				top = new TopBoundary.SurfaceFlux(flux.flux() + pendingApplied / dt, flux.fluxDerivative());
			} else if (forcing.snowTop() instanceof TopBoundary.SurfaceTemperature imposed) {
				top = new TopBoundary.SurfaceTemperature(Math.min(imposed.temperature(), MELTING_TEMPERATURE));
			} else {
				throw new IllegalArgumentException("the top over snow is a flux or a temperature");
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
			pendingApplied = pendingSurfaceEnergy;
		} else if (forcing.bareSoilTop() instanceof TopBoundary.SurfaceFlux flux) {
			pendingApplied = pendingSurfaceEnergy;
			top = new TopBoundary.SurfaceFlux(flux.flux() + pendingApplied / dt, flux.fluxDerivative());
		} else if (forcing.bareSoilTop() instanceof TopBoundary.SurfaceBalance) {
			pendingApplied = pendingSurfaceEnergy;
			startTemperature = soilStart[soilStart.length - 1];
		} else {
			top = forcing.bareSoilTop();
		}
		pendingSurfaceEnergy -= pendingApplied;

		// 3: heat, the surface balance through the skin or at the top cell (SURF-1/2)
		SurfaceSolve solved = null;
		HeatStepResult heatStep;
		double penetrated = 0.0;
		double soilSource = 0.0;
		if (snowAtStart && !imposedSnowTop) {
			SurfaceEnergyBalance snowBalance = balance;
			// SNOW-7: the shortwave absorbed inside the pack leaves the surface balance for the cells
			double[] absorbed = processes.shortwavePenetration().absorbed(start,
					balance.evaluate(startTemperature).netShortwave());
			TopBoundary.Balance surfaceBalance = snowBalance::evaluate;
			if (absorbed != null) {
				int nSoil = heat.soilCells();
				double[] sources = new double[nSoil + start.size()];
				for (int j = 0; j < start.size(); j++) {
					sources[nSoil + j] = absorbed[j];
					penetrated += absorbed[j];
				}
				// what crosses the pack: the top soil cell, or the bottom snow cell of a snow-only column
				sources[nSoil > 0 ? nSoil - 1 : 0] += absorbed[start.size()];
				penetrated += absorbed[start.size()];
				soilSource = nSoil > 0 ? dt * absorbed[start.size()] : 0.0;
				heat.setCellSources(sources);
				double below = penetrated;
				surfaceBalance = t -> withoutPenetrated(snowBalance.evaluate(t), below);
			}
			try {
				solved = solveSurface(start, surfaceBalance, startTemperature,
						snowSkinConductance(start.get(start.size() - 1)), true, pendingApplied / dt,
						forcing.bottomValue(), dt, soilStart);
			} finally {
				heat.setCellSources(null);
			}
			heatStep = solved.heatStep();
		} else if (!snowAtStart && forcing.bareSoilTop() instanceof TopBoundary.SurfaceBalance soilBalance) {
			solved = solveSurface(start, soilBalance.balance(), startTemperature, soilSkinConductance(), false,
					pendingApplied / dt, forcing.bottomValue(), dt, soilStart);
			heatStep = solved.heatStep();
		} else {
			heatStep = heat.step(start, top, forcing.bottomValue(), dt);
		}
		int sweeps = solved == null ? 1 : solved.sweeps();
		boolean surfaceConverged = solved == null || solved.converged();
		double surfaceChange = solved == null ? Double.NaN : solved.change();
		double lineAtStart = solved == null ? 0.0 : solved.lineAtStart();
		double lineSlope = solved == null ? 0.0 : solved.lineSlope();
		// GEOtop's single layer that melts away (thin-snow regime, fidelity): merged into the soil, the step
		// solved again as bare soil (sux = -6, energy.balance.cc:702-729, 2130-2141)
		boolean merged = snowAtStart && !imposedSnowTop && start.size() == 1
				&& processes.thinSnow().mergesMeltedLayer() && ice(heatStep.snow()) < MERGED_LAYER_ICE;
		if (merged) {
			heat.setSoilTemperatures(soilStart);
			penetrated = 0.0;
			soilSource = 0.0;
			pendingSurfaceEnergy += pendingApplied;
			pendingApplied = 0.0;
			solved = null;
			if (forcing.bareSoilTop() instanceof TopBoundary.SurfaceFlux flux) {
				pendingApplied = pendingSurfaceEnergy;
				top = new TopBoundary.SurfaceFlux(flux.flux() + pendingApplied / dt, flux.fluxDerivative());
				pendingSurfaceEnergy -= pendingApplied;
				heatStep = heat.step(List.of(), top, forcing.bottomValue(), dt);
			} else if (forcing.bareSoilTop() instanceof TopBoundary.SurfaceBalance soilBalance) {
				pendingApplied = pendingSurfaceEnergy;
				pendingSurfaceEnergy -= pendingApplied;
				solved = solveSurface(List.of(), soilBalance.balance(), soilStart[soilStart.length - 1],
						soilSkinConductance(), false, pendingApplied / dt, forcing.bottomValue(), dt, soilStart);
				heatStep = solved.heatStep();
				lineAtStart = solved.lineAtStart();
				lineSlope = solved.lineSlope();
			} else {
				heatStep = heat.step(List.of(), forcing.bareSoilTop(), forcing.bottomValue(), dt);
			}
			surfaceConverged = solved == null || solved.converged();
			surfaceChange = Double.NaN;
			albedo = Double.NaN;
		}
				int heatWorstCell = heat.worstCell();
		List<SnowCell> cells = heatStep.snow();
		double heatEnergy = merged ? 0.0 : SnowColumn.internalEnergy(cells) - energyBefore;
		double melt = merged ? 0.0 : ice(start) - ice(cells);

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
		} else if (merged) {
			// the layer leaves for the soil whole, ice and liquid with its energy
			toSoilMass += massBefore;
			toSoilEnergy += energyBefore;
		} else if (snowAtStart) {
			double topCellTemperature = topOf(heatStep);
			surfaceTemperature = solved.surfaceTemperature();
			surface = balance.evaluate(surfaceTemperature);
			linearisation = dt * (lineAtStart + lineSlope * (topCellTemperature - startTemperature)
					- (surface.energyBalance() - penetrated));
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
		// the balance of the bare soil at its surface temperature, and its linearisation energy
		SurfaceFluxes bareSoil = null;
		boolean snowSurface = snowAtStart && !imposedSnowTop && !merged;
		if (solved != null && !snowSurface) {
			bareSoil = ((TopBoundary.SurfaceBalance) forcing.bareSoilTop()).balance().evaluate(solved.surfaceTemperature());
			linearisation = dt * (lineAtStart + lineSlope * (topOf(heatStep) - soilStart[soilStart.length - 1])
					- bareSoil.energyBalance());
		}
		lastSurface = solved == null
				? new SurfaceSolution(SurfaceMode.IMPOSED, Double.NaN, topOf(heatStep), null, Double.NaN, false, 0.0)
				: new SurfaceSolution(solved.mode(), solved.surfaceTemperature(), topOf(heatStep),
						snowSurface ? surface : bareSoil, solved.conductance(), snowSurface, snowSurface ? penetrated : 0.0);

		// 5: compaction and melt shrinkage, against the ice at the start of the step
		cells = processes.compaction().compact(cells, dt, processes.slopeAngle());
		// thin cells keep their thickness in fidelity, as GEOtop (snow.cc:1093-1110)
		cells = MeltShrinkage.apply(merged ? List.of() : start.subList(0, cells.size()), cells,
				processes.compaction().thinCellIce());
		// GEOtop's porosity cap after the shrinkage (fidelity; nothing in the recommended configuration)
		cells = processes.compaction().capDensity(cells);

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

		// 7-8: restratification and fresh snow, in the order of the policy (COL-5): GEOtop's restratifies
		// first; with the top-cell cap the fresh snow comes first, so that the next heat solve sees the cap
		RestratificationPolicy policy = processes.restratification();
		double snowfallEnergy = 0.0;
		SnowCell fresh = null;
		if (forcing.snowfall() > 0.0) {
			fresh = FreshSnow.cell(forcing.snowfall(),
					FreshSnow.geotopDensity(forcing.surface().airTemperature(), forcing.surface().windSpeed()),
					processes.precipitation().snow(forcing.surface(), forcing.precipitationTemperature()));
			snowfallEnergy = fresh.internalEnergy();
		}
		if (fresh != null && policy.afterFreshSnow()) {
			cells = SnowColumn.depositFreshSnow(cells, fresh, eos);
		}
		Restratified restratified = policy.apply(cells);
		cells = restratified.cells();
		double vanishedMass = 0.0;
		double vanishedEnergy = 0.0;
		if (restratified.releasedToSoil().isPresent()) {
			// SOIL-4: a pack too thin to keep goes to the soil with its mass and energy, in both configurations
			// (GEOtop sets it to zero; below 1e-6 W_c the difference is at most 1e-5 kg m-2 per event)
			SnowCell released = restratified.releasedToSoil().get();
			vanishedMass = released.totalMass();
			vanishedEnergy = released.internalEnergy();
			if (vanishedMass > MAX_VANISHED_MASS) {
				throw new IllegalStateException(String.format(java.util.Locale.ROOT,
						"a vanishing pack of %.3g kg m-2, above the admissible %.3g kg m-2", vanishedMass,
						MAX_VANISHED_MASS));
			}
			toSoilMass += vanishedMass;
			toSoilEnergy += vanishedEnergy;
		}
		if (fresh != null && !policy.afterFreshSnow()) {
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
			// no soil evaporation under the snow (LINK-2): sinks only when the soil was the surface of the step
			double[] sinks = snowAtStart && !merged ? null : forcing.soilWaterSinks();
			soilWater = water.step(totalSoilInput, forcing.soilWaterBottomValue(), heat.soilTemperatures(), anyIce ? ice : null,
					sinks, dt);
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
					- dt * (fluxes[0] - fluxes[nSoil]) - soilSource - topEnergy + soilDrainageEnergy + soilSinkEnergy
					- soilStorageHeat;
		}

		snow = cells;
		lastTerms = new ColumnStepTerms(pendingApplied, phaseMismatch, surfaceChange, heatWorstCell, soilInputMass,
				soilInputEnergy, infiltrationEnergy, soilSinkEnergy, vanishedMass, vanishedEnergy);
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

	/** Ice below which GEOtop's single snow layer counts as melted away [kg m-2] ({@code energy.balance.cc:2137}). */
	static final double MERGED_LAYER_ICE = 1e-5;
	/**
	 * Largest mass of a vanishing pack released to the soil in one step (SOIL-4)
	 * [kg m-2]: the restratification policies release a pack below 10⁻⁶ W_c, so
	 * a larger release is an error of the state, and the step is rejected.
	 */
	public static final double MAX_VANISHED_MASS = 1e-3;

	private static double ice(List<SnowCell> cells) {
		double sum = 0.0;
		for (SnowCell cell : cells) {
			sum += cell.iceMass();
		}
		return sum;
	}
}
