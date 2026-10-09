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
 * cell let through, adds {@value #POLISH_ITERATIONS} × {@value #POLISH_ITERATIONS}
 * nested Newton iterations with zero tolerance, warm-started from the solution
 * ({@link InstrumentedEquationState}), and checks again. The outcome is in
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

	private final SnowSoilColumn column;
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
		if (!evaluation.converged() && n > 1) {
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
			worst = Math.max(worst, Math.abs(residual) / bound);
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
