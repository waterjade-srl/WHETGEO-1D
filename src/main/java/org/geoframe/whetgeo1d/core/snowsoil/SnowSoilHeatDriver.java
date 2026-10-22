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

import org.geoframe.closureequation.closureequation.Parameters;
import org.geoframe.closureequation.equationstate.EquationState;
import org.geoframe.closureequation.interfaceconductivity.InterfaceConductivity;
import org.geoframe.closureequation.interfaceconductivity.InterfaceConductivityWeightedHarmonicMean;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.derivedquantities.ComputeQuantitiesInternalEnergy;
import org.geoframe.whetgeo1d.core.equationstate.models.SnowInternalEnergy;
import org.geoframe.whetgeo1d.core.finitevolume.Diffusion1DKernel;
import org.geoframe.whetgeo1d.core.state.WGGeometry;
import org.geoframe.whetgeo1d.core.state.WGProblemQuantities;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;

/**
 * Heat step of the snow+soil column, solved as one system (specification
 * §2.1): snow cells are extra cells on top of WHETGEO's soil cells, in the same
 * heat equation, solved by the same {@code Diffusion1DKernel} and
 * NestedNewtonThomas in one pass.
 *
 * <ul>
 * <li>Soil cells go through WHETGEO's closures unchanged, built and evaluated
 * by {@link ComputeQuantitiesInternalEnergy} on the first {@code soilCells}
 * elements. The soil temperatures are the driver's state, as in
 * {@code HeatDiffusionSolver1D}.</li>
 * <li>Snow cells come in at every step as a {@code List<SnowCell>} (index 0 at
 * the bottom) and go out with the new U, phases re-partitioned by
 * {@link SnowEquationOfState#equilibrate}. Unknown T, conserved U through
 * {@link SnowInternalEnergy}; conductivity from the chosen law at the current
 * temperature.</li>
 * <li>Fixed capacity and active window ({@link SnowSoilColumn}): nothing is
 * reallocated when the snow cells change in number.</li>
 * <li>Snow–snow and snow–soil faces: thickness-weighted harmonic mean (§4.1),
 * the series conductance of the two half cells. Soil–soil faces keep the
 * model configured for the soil.</li>
 * <li>Top face over snow: the conductivity of the top snow cell at its own
 * temperature. WHETGEO evaluates the soil at the boundary temperature; for
 * snow that would turn the half cell into water whenever the surface is
 * above 0 °C.</li>
 * <li>One active cell: {@link SingleCellHeatSolver}, because the library crashes
 * on KMAX = 1.</li>
 * <li>Convergence (§3.6). NestedNewtonThomas tests the Euclidean norm of the
 * residual against an absolute tolerance, and returns silently at its
 * iteration cap. Near 0 °C an absolute tolerance on a snow cell can lie below
 * round-off, and the solver then runs to the cap. The driver, without changing
 * the library: (1) passes the larger of τ and the round-off floor of the snow
 * cells ({@link #toleranceFor}), always reachable; (2) checks every cell after
 * the solve with the per-cell criterion of the specification; (3) if a cell
 * fails, typically a cold cell left with a residual that the floor of a wet
 * cell let through, adds rounds of {@value #POLISH_ITERATIONS} ×
 * {@value #POLISH_ITERATIONS} nested Newton iterations with zero tolerance,
 * warm-started from the solution ({@link InstrumentedEquationState}), checking
 * again after each, up to {@value #MAX_POLISH_ROUNDS} rounds. One round is
 * almost always enough; the first ice of a saturated soil cell exactly at its
 * melting temperature needs more: there the liquid content falls as
 * |T - T*|^n of Van Genuchten, its second derivative is unbounded, and Newton
 * converges linearly (NTGS1 JRA-3Q, 5 October 2021: 743 → 9.4 → 0.005 times
 * the bound in the first rounds, at any step length). The outcome is in
 * {@link NewtonReport}.</li>
 * </ul>
 *
 * With no snow the step is the substep of {@code HeatDiffusionSolver1D} call
 * by call, with the same tolerance, so it reproduces that solver bitwise: a
 * residual norm below τ puts every cell within its bound, so the polish does
 * not start unless the library stopped at its cap.
 */
public final class SnowSoilHeatDriver {

	private static final double EPSILON = Math.ulp(1.0);
	private static final double UNDEFINED = -9999.0;
	private static final int POLISH_ITERATIONS = 2;
	private static final int MAX_POLISH_ROUNDS = 10;

	private final SnowSoilColumn column;
	private final Parameters soilParameters;
	private final ComputeQuantitiesInternalEnergy soilQuantities;
	private final SnowEquationOfState equationOfState;
	private final SnowHeatProperties snowProperties;
	private final NewtonSettings settings;
	private final DiffusionBoundaryConditionType bottomType;
	private final IBoundaryCondition bottomCondition;
	private final List<EquationState> closures;
	private final List<EquationState> instrumentedClosures;
	private final InstrumentedEquationState.Instrumentation instrumentation = new InstrumentedEquationState.Instrumentation();
	private final SnowInternalEnergy snowClosure;
	private final InterfaceConductivity snowFaceConductivity = new InterfaceConductivityWeightedHarmonicMean();
	private final SingleCellHeatSolver singleCellSolver = new SingleCellHeatSolver();
	/** Active-cell index of the largest residual ratio of the last check, -1 before any step. */
	private int worstCell = -1;

	/**
	 * @param soil       soil part of the column, its temperatures are the initial
	 *                   state
	 * @param snow       snow properties and capacity
	 * @param bottomType {@code BOTTOM_DIRICHLET}, {@code BOTTOM_NEUMANN} or
	 *                   {@code BOTTOM_NO_GRADIENT}, as in WHETGEO
	 * @param settings   iteration settings
	 */
	public SnowSoilHeatDriver(SoilHeatSetup soil, SnowHeatProperties snow, DiffusionBoundaryConditionType bottomType,
			NewtonSettings settings) {
		this.snowProperties = Objects.requireNonNull(snow, "snow");
		this.settings = Objects.requireNonNull(settings, "settings");
		this.equationOfState = snow.equationOfState();
		this.bottomType = requireBottom(bottomType);
		this.bottomCondition = IBoundaryCondition.createDiffusionBoundaryCondition(bottomType);

		int snowId = soil == null ? 0 : soil.typeEquationState().length;
		this.column = new SnowSoilColumn(soil, snow.maxCells(), snowId);
		this.soilParameters = soil == null ? null : soil.parameters();

		closures = new ArrayList<>();
		if (soil != null) {
			// TOP_DIRICHLET always: the soil top face is computed as WHETGEO does only
			// when the soil is the surface under an imposed temperature; otherwise the
			// driver overwrites it or the boundary condition does not read it
			soilQuantities = new ComputeQuantitiesInternalEnergy(soil.typeClosureEquation(),
					soil.typeEquationState(), soil.typeThermalConductivity(),
					soil.interfaceThermalConductivityModel(), DiffusionBoundaryConditionType.TOP_DIRICHLET,
					bottomType, column.variables(), column.geometry(), soil.parameters());
			closures.addAll(soilQuantities.getInternalEnergyStateEquation());
		} else {
			soilQuantities = null;
		}
		snowClosure = new SnowInternalEnergy(null, column.variables(), new SnowEquationOfState[] { equationOfState },
				column.totalMass());
		closures.add(snowClosure);
		instrumentedClosures = InstrumentedEquationState.wrap(closures, instrumentation);
	}

	/** Column with no soil: the bottom condition acts on the lowest snow cell. */
	public static SnowSoilHeatDriver snowOnly(SnowHeatProperties snow, DiffusionBoundaryConditionType bottomType,
			NewtonSettings settings) {
		return new SnowSoilHeatDriver(null, snow, bottomType, settings);
	}

	/** Current soil temperatures [K], bottom first. */
	public double[] soilTemperatures() {
		return Arrays.copyOf(column.variables().temperatures, column.soilCells());
	}

	/**
	 * Restores the soil temperatures, for instance to repeat a step with
	 * another surface condition.
	 *
	 * @param temperatures [K], bottom first, one per soil cell
	 */
	public void setSoilTemperatures(double[] temperatures) {
		if (temperatures.length != column.soilCells()) {
			throw new IllegalArgumentException(
					"expected " + column.soilCells() + " soil temperatures, got " + temperatures.length);
		}
		System.arraycopy(temperatures, 0, column.variables().temperatures, 0, temperatures.length);
	}

	/**
	 * Restores the soil suctions the heat closures read, for instance to roll a
	 * step back ({@link SnowSoilSolver#restore}).
	 *
	 * @param suctions [m], bottom first, one per soil cell
	 */
	public void setSoilSuctions(double[] suctions) {
		if (suctions.length != column.soilCells()) {
			throw new IllegalArgumentException("expected " + column.soilCells() + " soil suctions, got " + suctions.length);
		}
		System.arraycopy(suctions, 0, column.variables().waterSuctions, 0, suctions.length);
	}

	/**
	 * Index among the active cells (0 at the bottom, soil first) of the cell
	 * with the largest residual ratio in the last per-cell check, the one named
	 * when a step is rejected (SOL-5); -1 before the first step.
	 */
	public int worstCell() {
		return worstCell;
	}

	/** Equation-state ids of the soil cells, bottom first. */
	public int[] soilEquationStateIds() {
		return Arrays.copyOf(column.variables().equationStateID, column.soilCells());
	}

	/** Parameter ids of the soil cells, bottom first. */
	public int[] soilParameterIds() {
		return Arrays.copyOf(column.variables().parameterID, column.soilCells());
	}

	/** Current soil suctions [m], bottom first, as the soil heat closures read them. */
	public double[] soilSuctions() {
		return Arrays.copyOf(column.variables().waterSuctions, column.soilCells());
	}

	/**
	 * Internal energy of the soil [J m-2], Σ U(T_i, ψ_i) with WHETGEO's soil
	 * closures, the reference of their {@code Parameters}.
	 */
	public double soilInternalEnergy() {
		WGProblemQuantities variables = column.variables();
		double sum = 0.0;
		for (int i = 0; i < column.soilCells(); i++) {
			sum += soilEnergy(i, variables.temperatures[i], variables.waterSuctions[i]);
		}
		return sum;
	}

	/**
	 * Moves the energy of the water that Richards moved in the soil, and sets the
	 * new suctions (specification §2.2 step 5, A8: water after energy, in
	 * sequence). The water crossing a face carries the internal energy of liquid
	 * water at the temperature of the cell it leaves (upwind),
	 * L_f + c_w (T - T_ref) per kg, the partial energy of the liquid in the soil
	 * closures; at the top it carries the given energy. Each cell then holds
	 * U* = U(T, ψ<sup>old</sup>) + in - out, and its temperature is the one at
	 * which U(T, ψ<sup>new</sup>) = U*. Energy is conserved exactly: the soil
	 * changes by the top energy minus the energy leaving at the bottom.
	 *
	 * @param newSuctions ψ after Richards [m], bottom first
	 * @param faceWater   water through each face in the step [kg m-2], positive
	 *                    upwards, face 0 at the bottom, face n at the surface
	 * @param topEnergy   internal energy of the water entering at the top [J m-2]
	 *                    (same reference as the soil closures)
	 * @return the energy that left at the bottom [J m-2], positive out
	 */
	public double transportSoilWater(double[] newSuctions, double[] faceWater, double topEnergy) {
		int n = column.soilCells();
		return transportSoilWater(newSuctions, faceWater, topEnergy, new double[n], new double[n]);
	}

	/**
	 * Same, with liquid water the closures do not see, held at the temperature
	 * of its cell before and after the step [kg m-2] (the specific storage above
	 * saturation of a freezing soil): it enters U* with its energy, and the new
	 * temperature solves U(T, ψ<sup>new</sup>) + m<sup>new</sup> e(T) = U*.
	 */
	public double transportSoilWater(double[] newSuctions, double[] faceWater, double topEnergy, double[] oldHidden,
			double[] newHidden) {
		return transportSoilWater(newSuctions, faceWater, topEnergy, oldHidden, newHidden, new double[column.soilCells()]);
	}

	/**
	 * Same, with water taken from the cells by evapotranspiration [kg m-2],
	 * leaving with the energy of liquid water at the temperature of its cell
	 * (the latent heat of vaporisation belongs to the surface balance). The
	 * energy removed is {@link #sinkEnergy}.
	 */
	public double transportSoilWater(double[] newSuctions, double[] faceWater, double topEnergy, double[] oldHidden,
			double[] newHidden, double[] sinks) {
		int n = column.soilCells();
		if (newSuctions.length != n || faceWater.length != n + 1) {
			throw new IllegalArgumentException("expected " + n + " suctions and " + (n + 1) + " face fluxes");
		}
		WGProblemQuantities variables = column.variables();
		double[] t = variables.temperatures;
		// energy through each face, positive upwards
		double[] faceEnergy = new double[n + 1];
		for (int k = 0; k < n; k++) {
			double water = faceWater[k];
			int upwind = water > 0.0 ? k - 1 : k;
			faceEnergy[k] = upwind < 0 ? water * liquidEnergy(t[0]) : water * liquidEnergy(t[upwind]);
		}
		faceEnergy[n] = -topEnergy;
		double[] target = new double[n];
		for (int i = 0; i < n; i++) {
			target[i] = soilEnergy(i, t[i], variables.waterSuctions[i]) + oldHidden[i] * liquidEnergy(t[i])
					+ faceEnergy[i] - faceEnergy[i + 1] - sinks[i] * liquidEnergy(t[i]);
		}
		for (int i = 0; i < n; i++) {
			variables.waterSuctions[i] = newSuctions[i];
			t[i] = invertSoilEnergy(i, target[i], newSuctions[i], newHidden[i], t[i]);
		}
		return -faceEnergy[0];
	}

	/** Energy that the given sinks take from the soil at its current temperatures [J m-2]. */
	public double sinkEnergy(double[] sinks) {
		double[] t = column.variables().temperatures;
		double sum = 0.0;
		for (int i = 0; i < sinks.length; i++) {
			sum += sinks[i] * liquidEnergy(t[i]);
		}
		return sum;
	}

	/** Internal energy of liquid water per kg at T in the soil closures' reference [J kg-1]. */
	public double liquidEnergy(double temperature) {
		Parameters p = soilParameters;
		return p.latentHeatFusion + p.specificThermalCapacityWater * (temperature - p.referenceTemperatureInternalEnergy);
	}

	private double soilEnergy(int i, double temperature, double suction) {
		WGProblemQuantities variables = column.variables();
		return closures.get(variables.equationStateID[i]).equationState(temperature, suction,
				variables.parameterID[i], i);
	}

	/** T with U(T, ψ) + m e(T) = target, by Newton safeguarded with bisection; both increase with T. */
	private double invertSoilEnergy(int i, double target, double suction, double hidden, double guess) {
		WGProblemQuantities variables = column.variables();
		EquationState closure = closures.get(variables.equationStateID[i]);
		int id = variables.parameterID[i];
		double low = guess - 1.0;
		double high = guess + 1.0;
		while (closure.equationState(low, suction, id, i) + hidden * liquidEnergy(low) > target) {
			low -= 2.0 * (high - low);
		}
		while (closure.equationState(high, suction, id, i) + hidden * liquidEnergy(high) < target) {
			high += 2.0 * (high - low);
		}
		double x = Math.min(Math.max(guess, low), high);
		for (int iteration = 0; iteration < 200; iteration++) {
			double residual = closure.equationState(x, suction, id, i) + hidden * liquidEnergy(x) - target;
			if (residual == 0.0) {
				return x;
			}
			if (residual > 0.0) {
				high = x;
			} else {
				low = x;
			}
			double slope = closure.dEquationState(x, suction, id, i)
					+ hidden * soilParameters.specificThermalCapacityWater;
			double next = slope > 0.0 ? x - residual / slope : 0.5 * (low + high);
			if (!(next > low && next < high)) {
				next = 0.5 * (low + high);
			}
			if (next == x || Math.abs(next - x) <= 1e-15 * Math.abs(x)) {
				return next;
			}
			x = next;
		}
		return x;
	}

	/** Largest number of snow cells the column holds. */
	public int snowCapacity() {
		return column.capacity() - column.soilCells();
	}

	/** Number of soil cells. */
	public int soilCells() {
		return column.soilCells();
	}

	/**
	 * Advances the column by one heat step.
	 *
	 * @param snow        snow cells, index 0 at the bottom; may be empty
	 * @param top         condition at the top of the column (snow surface, or
	 *                    soil surface without snow)
	 * @param bottomValue temperature [K] for a Dirichlet bottom, flux into the
	 *                    column [W m-2] for a Neumann bottom
	 * @param timeDelta   [s]
	 */
	public HeatStepResult step(List<SnowCell> snow, TopBoundary top, double bottomValue, double timeDelta) {
		Objects.requireNonNull(top, "top");
		if (!(timeDelta > 0.0) || Double.isInfinite(timeDelta)) {
			throw new IllegalArgumentException("timeDelta must be positive, got " + timeDelta);
		}
		column.activateSnow(snow);
		int nSoil = column.soilCells();
		int n = column.activeCells();
		if (n == 0) {
			throw new IllegalArgumentException("no active cell: a snow-only column needs snow");
		}
		WGProblemQuantities variables = column.variables();
		WGGeometry geometry = column.geometry();

		// state at the start of the step
		variables.internalEnergyBottomBCValue = bottomValue;
		for (int j = 0; j < snow.size(); j++) {
			variables.temperatures[nSoil + j] = equationOfState.temperature(snow.get(j));
		}
		double topStartTemperature = variables.temperatures[n - 1];
		double conductivityTopValue = top instanceof TopBoundary.SurfaceTemperature imposed ? imposed.temperature()
				: topStartTemperature;
		variables.internalEnergyTopBCValue = conductivityTopValue;
		if (nSoil > 0) {
			soilQuantities.computeInternalEnergy(nSoil);
			soilQuantities.computeXStar(nSoil);
		}
		for (int j = 0; j < snow.size(); j++) {
			int i = nSoil + j;
			variables.internalEnergys[i] = snow.get(j).internalEnergy();
			snowClosure.computeXStar(0.0, 0, i);
		}
		double[] oldEnergy = Arrays.copyOf(variables.internalEnergys, n);

		IBoundaryCondition topCondition;
		double topValue;
		if (top instanceof TopBoundary.SurfaceFlux flux) {
			topCondition = new LinearizedFluxTopBoundaryCondition(flux.fluxDerivative());
			topValue = flux.flux() - flux.fluxDerivative() * topStartTemperature;
		} else {
			topCondition = IBoundaryCondition
					.createDiffusionBoundaryCondition(DiffusionBoundaryConditionType.TOP_DIRICHLET);
			topValue = conductivityTopValue;
		}

		double tolerance = toleranceFor(nSoil, n);
		int outer = 0;
		int inner = 0;
		boolean hitCap = false;
		for (int picard = 0; picard < settings.picardIterations(); picard++) {
			variables.internalEnergyTopBCValue = conductivityTopValue;
			computeConductivities(nSoil, n);
			variables.internalEnergyTopBCValue = topValue;
			if (n == 1) {
				variables.temperatures[0] = singleCellSolver.solve(closures.get(variables.equationStateID[0]),
						variables.waterSuctions[0], variables.parameterID[0], oldEnergy[0], variables.temperatures[0],
						bottomCondition, bottomValue, variables.lambdasInterface[0], geometry.spaceDeltaZ[0],
						topCondition, topValue, variables.lambdasInterface[1], geometry.spaceDeltaZ[1], timeDelta,
						settings.absoluteTolerance(), settings.roundOffSafety());
				outer += singleCellSolver.iterations() + 1;
			} else {
				solveSystem(n, topCondition, topValue, bottomValue, timeDelta, tolerance, settings.maxIterations(),
						false);
				outer += instrumentation.outer();
				inner += instrumentation.inner();
				hitCap |= instrumentation.outer() >= settings.maxIterations();
			}
		}
		requireFinite(variables.temperatures, n);
		Evaluation evaluation = evaluate(nSoil, n, oldEnergy, top, topStartTemperature, bottomValue, timeDelta);

		boolean polished = false;
		for (int round = 0; round < MAX_POLISH_ROUNDS && !evaluation.converged() && n > 1; round++) {
			solveSystem(n, topCondition, topValue, bottomValue, timeDelta, 0.0, POLISH_ITERATIONS, true);
			outer += instrumentation.outer();
			inner += instrumentation.inner();
			polished = true;
			requireFinite(variables.temperatures, n);
			evaluation = evaluate(nSoil, n, oldEnergy, top, topStartTemperature, bottomValue, timeDelta);
		}
		double[] newEnergy = evaluation.newEnergy();
		double[] fluxes = evaluation.fluxes();
		double storage = 0.0;
		for (int i = 0; i < n; i++) {
			storage += newEnergy[i] - oldEnergy[i];
		}
		EnergyBudget budget = EnergyBudget.of(storage, timeDelta * fluxes[0], -timeDelta * fluxes[n]);
		NewtonReport report = new NewtonReport(outer, inner, hitCap, n == 1 ? settings.absoluteTolerance() : tolerance,
				evaluation.worstResidualRatio(), evaluation.converged(), polished, n == 1);

		List<SnowCell> updated = new ArrayList<>(snow.size());
		for (int j = 0; j < snow.size(); j++) {
			updated.add(equationOfState.equilibrate(snow.get(j).withInternalEnergy(newEnergy[nSoil + j])));
		}
		double snowToSoil = snow.isEmpty() || nSoil == 0 ? Double.NaN : -fluxes[nSoil];
		return new HeatStepResult(updated, soilTemperatures(), Arrays.copyOf(variables.temperatures, n), fluxes,
				snowToSoil, budget, report);
	}

	/** New energies, face fluxes and the per-cell convergence check of a solution. */
	private record Evaluation(double[] newEnergy, double[] fluxes, double worstResidualRatio) {
		boolean converged() {
			return worstResidualRatio <= 1.0;
		}
	}

	private Evaluation evaluate(int nSoil, int n, double[] oldEnergy, TopBoundary top, double topStartTemperature,
			double bottomValue, double timeDelta) {
		double[] newEnergy = newEnergies(nSoil, n);
		double[] fluxes = conductiveFluxes(n, top, topStartTemperature, bottomValue);
		return new Evaluation(newEnergy, fluxes,
				worstResidualRatio(n, oldEnergy, newEnergy, fluxes, top, timeDelta));
	}

	/**
	 * One call of WHETGEO's {@link Diffusion1DKernel}, unchanged. The kernel is
	 * built per call because the tolerance of its NestedNewtonThomas is fixed at
	 * construction; the arrays are at capacity and the window is n.
	 */
	private void solveSystem(int n, IBoundaryCondition topCondition, double topValue, double bottomValue,
			double timeDelta, double tolerance, int maxIterations, boolean warmStart) {
		WGProblemQuantities variables = column.variables();
		instrumentation.reset(warmStart);
		Diffusion1DKernel kernel = new Diffusion1DKernel(topCondition, bottomCondition, column.capacity(), 1,
				tolerance, 0.0, maxIterations, instrumentedClosures);
		variables.temperatures = kernel.solve(timeDelta, bottomValue, topValue, n, variables.lambdasInterface,
				variables.internalEnergys, column.geometry().spaceDeltaZ, variables.heatSourcesSinksTerm,
				variables.temperatures, variables.waterSuctions, variables.parameterID, variables.equationStateID);
		instrumentation.endWarmStart();
	}

	/**
	 * Absolute tolerance for NestedNewtonThomas: τ, raised to the round-off floor
	 * of the snow cells, κ ε x* dU/dT(x*) per cell summed in quadrature (the
	 * solver tests the Euclidean norm). dU/dT is largest at x*, so the floor
	 * holds whatever the temperature reached in the step. Soil cells do not
	 * raise it: without snow the tolerance is τ, as in WHETGEO.
	 */
	private double toleranceFor(int nSoil, int n) {
		double xStar = equationOfState.inflectionTemperature();
		double sum = 0.0;
		for (int i = nSoil; i < n; i++) {
			double floor = settings.roundOffSafety() * EPSILON * xStar
					* equationOfState.dInternalEnergy(xStar, column.totalMass()[i]);
			sum += floor * floor;
		}
		return Math.max(settings.absoluteTolerance(), Math.sqrt(sum));
	}

	private void computeConductivities(int nSoil, int n) {
		WGProblemQuantities variables = column.variables();
		double[] thickness = column.geometry().controlVolume;
		if (nSoil > 0) {
			soilQuantities.computeThermalConductivity(nSoil);
			soilQuantities.computeInterfaceThermalConductivity(nSoil);
		}
		if (n == nSoil) {
			return;
		}
		for (int i = nSoil; i < n; i++) {
			variables.lambdas[i] = snowConductivity(variables.temperatures[i], column.totalMass()[i], thickness[i]);
		}
		for (int i = Math.max(nSoil, 1); i < n; i++) {
			variables.lambdasInterface[i] = snowFaceConductivity.compute(variables.lambdas[i - 1], variables.lambdas[i],
					thickness[i - 1], thickness[i]);
		}
		if (nSoil == 0) {
			variables.lambdasInterface[0] = bottomType == DiffusionBoundaryConditionType.BOTTOM_DIRICHLET
					? variables.lambdas[0]
					: UNDEFINED;
		}
		variables.lambdasInterface[n] = variables.lambdas[n - 1];
	}

	/** Conductivity of a snow cell with its phases at equilibrium at T. */
	private double snowConductivity(double temperature, double totalMass, double thickness) {
		double liquid = totalMass * equationOfState.liquidFraction(temperature);
		SnowCell cell = new SnowCell(totalMass - liquid, liquid, thickness, 0.0);
		return snowProperties.conductivity().conductivity(cell);
	}

	private double[] newEnergies(int nSoil, int n) {
		WGProblemQuantities variables = column.variables();
		double[] energy = new double[n];
		if (nSoil > 0) {
			soilQuantities.computeInternalEnergyNew(nSoil);
			System.arraycopy(variables.internalEnergysNew, 0, energy, 0, nSoil);
		}
		for (int i = nSoil; i < n; i++) {
			energy[i] = equationOfState.internalEnergy(variables.temperatures[i], column.totalMass()[i]);
		}
		return energy;
	}

	/** Face fluxes, positive upwards [W m-2], with the conductivities of the solve. */
	private double[] conductiveFluxes(int n, TopBoundary top, double topStartTemperature, double bottomValue) {
		WGProblemQuantities variables = column.variables();
		double[] t = variables.temperatures;
		double[] lambda = variables.lambdasInterface;
		double[] distance = column.geometry().spaceDeltaZ;
		double[] fluxes = new double[n + 1];
		for (int k = 1; k < n; k++) {
			fluxes[k] = -lambda[k] * (t[k] - t[k - 1]) / distance[k];
		}
		fluxes[0] = switch (bottomType) {
		case BOTTOM_DIRICHLET -> -lambda[0] * (t[0] - bottomValue) / distance[0];
		case BOTTOM_NEUMANN -> bottomValue;
		default -> 0.0;
		};
		if (top instanceof TopBoundary.SurfaceTemperature imposed) {
			fluxes[n] = -lambda[n] * (imposed.temperature() - t[n - 1]) / distance[n];
		} else {
			TopBoundary.SurfaceFlux flux = (TopBoundary.SurfaceFlux) top;
			fluxes[n] = -(flux.flux() + flux.fluxDerivative() * (t[n - 1] - topStartTemperature));
		}
		return fluxes;
	}

	/**
	 * Largest ratio between the residual of a cell and its bound (specification
	 * §3.6): F_i = U_i^{n+1} - U_i^n - Δt (G_i - G_{i+1}), bound τ + κ ε (S_i +
	 * |∂F_i/∂T_i| |T_i|), S_i the sum of the magnitudes of the terms of F_i.
	 */
	private double worstResidualRatio(int n, double[] oldEnergy, double[] newEnergy, double[] fluxes, TopBoundary top,
			double timeDelta) {
		WGProblemQuantities variables = column.variables();
		double[] lambda = variables.lambdasInterface;
		double[] distance = column.geometry().spaceDeltaZ;
		double worst = 0.0;
		worstCell = 0;
		for (int i = 0; i < n; i++) {
			double residual = newEnergy[i] - oldEnergy[i] - timeDelta * (fluxes[i] - fluxes[i + 1]);
			double scale = Math.abs(newEnergy[i]) + Math.abs(oldEnergy[i])
					+ timeDelta * (Math.abs(fluxes[i]) + Math.abs(fluxes[i + 1]));
			double below = i > 0 || bottomType == DiffusionBoundaryConditionType.BOTTOM_DIRICHLET
					? lambda[i] / distance[i]
					: 0.0;
			double above = i < n - 1 || top instanceof TopBoundary.SurfaceTemperature ? lambda[i + 1] / distance[i + 1]
					: -((TopBoundary.SurfaceFlux) top).fluxDerivative();
			double slope = closures.get(variables.equationStateID[i]).dEquationState(variables.temperatures[i],
					variables.waterSuctions[i], variables.parameterID[i], i) + timeDelta * (below + above);
			double bound = settings.absoluteTolerance()
					+ settings.roundOffSafety() * EPSILON * (scale + Math.abs(slope * variables.temperatures[i]));
			double ratio = Math.abs(residual) / bound;
			if (ratio > worst) {
				worstCell = i;
			}
			worst = Math.max(worst, ratio);
		}
		return worst;
	}

	private static DiffusionBoundaryConditionType requireBottom(DiffusionBoundaryConditionType type) {
		Objects.requireNonNull(type, "bottomType");
		if (type != DiffusionBoundaryConditionType.BOTTOM_DIRICHLET
				&& type != DiffusionBoundaryConditionType.BOTTOM_NEUMANN
				&& type != DiffusionBoundaryConditionType.BOTTOM_NO_GRADIENT) {
			throw new IllegalArgumentException("not a bottom condition: " + type);
		}
		return type;
	}

	private static void requireFinite(double[] temperatures, int n) {
		for (int i = 0; i < n; i++) {
			if (!Double.isFinite(temperatures[i])) {
				throw new ArithmeticException("heat step produced a non-finite temperature at cell " + i);
			}
		}
	}
}
