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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import org.geoframe.whetgeo1d.core.snowsoil.StepControlSettings.FailurePolicy;
import org.geoframe.whetgeo1d.core.snowsoil.StepRejection.Reason;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;

/**
 * Advances a {@link SnowSoilSolver} over the intervals of the forcing, never
 * accepting a step that has not converged (SOL-5), and keeps the diagnostics
 * of the run (VAL-7) and its restart states (ARCH-5). The controller owns the
 * solver: once wrapped, the solver is stepped only through
 * {@link #advance}.
 *
 * <p>
 * <b>Not converged.</b> A step (the whole interval or a sub-step) is rejected
 * when any of these holds, checked in this order:
 * <ol>
 * <li><b>heat</b> ({@link Reason#HEAT_NOT_CONVERGED}): the last heat solve
 * fails the per-cell residual criterion of the specification §3.6,
 * |F<sub>i</sub>| ≤ τ + κ ε (S<sub>i</sub> + |∂F<sub>i</sub>/∂x<sub>i</sub>|
 * |x<sub>i</sub>|), after the driver's polish
 * ({@link NewtonReport#converged()}; SOL-3 change 2 worked around, DN-07).
 * Hitting the iteration cap of NestedNewtonThomas is not by itself a failure:
 * the per-cell check decides;</li>
 * <li><b>surface</b> ({@link Reason#SURFACE_NOT_CONVERGED}): the surface
 * sweeps of {@link SurfaceCoupling} ended with the surface temperature still
 * moving by more than the tolerance
 * ({@link SnowSoilStepResult#surfaceConverged()}). The semi-implicit coupling
 * (one sweep) is converged by definition;</li>
 * <li><b>Richards</b> ({@link Reason#RICHARDS_NOT_CONVERGED}): the water
 * balance error of the Richards step, the sum of WHETGEO's
 * {@code errorVolume}, exceeds
 * {@link StepControlSettings#richardsBalanceTolerance()} in magnitude, or is
 * not finite. The Picard iterations of Richards have no convergence test of
 * their own (fixed number); a Newton that stalls shows up in this error;</li>
 * <li><b>invalid state</b> ({@link Reason#INVALID_STATE}): a solver throws an
 * {@link ArithmeticException} (the heat driver on a non-finite temperature),
 * an {@link IllegalArgumentException} or an {@link IllegalStateException} (an
 * inadmissible cell: negative mass, too many cells), or the end state has a
 * non-finite value (soil temperatures and suctions, snow cells, surface age,
 * deferred energy, the closures of the step);</li>
 * <li>any extra {@link Check} of the caller ({@link Reason#EXTRA_CHECK}).</li>
 * </ol>
 *
 * <p>
 * <b>Rejection</b> (SOL-5 items 2–5). The state of {@link SnowSoilState} is
 * restored to the start of the step, i.e. the snow cells, the surface age, the
 * deferred surface energy and the soil; the diagnostics of the attempt are
 * dropped. The step is then covered by two halves, each one accepted or split
 * again in the same way, down to Δt / 2<sup>N</sup>,
 * N = {@link StepControlSettings#maxHalvings()}: only the half that fails is
 * refined. A sub-step has the same intensities as the interval — radiation,
 * meteorology, the bare-soil flux and the bottom condition are means over the
 * interval (LINK-4) — and the amounts of the interval times its fraction
 * 2<sup>-k</sup>: snowfall, rainfall and evapotranspiration. The totals over
 * the interval are those of the forcing (to the rounding of the sum).
 * Every rejection is logged ({@link StepRejection}, {@link #rejectionLog()}) and
 * counted ({@link RunDiagnostics#rejections()}). If the finest sub-step still
 * fails, the solver is put back at the start of the interval, the last
 * accepted state, and a {@link StepNotConvergedException} names the solver,
 * the time, the cell and the residual; with
 * {@link StepControlSettings.FailurePolicy#FLAG} the sub-step is instead
 * accepted and the interval is marked as degraded.
 *
 * <p>
 * An interval accepted at the first attempt returns the result of
 * {@link SnowSoilSolver#step} unchanged, and the solver does exactly what it
 * does without the controller: with no rejection a run is the uncontrolled run
 * to the bit. With sub-steps the result of the interval is their aggregate
 * ({@link #aggregate}).
 *
 * <p>
 * <b>Restart</b> (ARCH-5). {@link #checkpoint()} returns the state, the time
 * and the diagnostics; with {@link StepControlSettings#restartInterval()} the
 * controller hands one to the sink of {@link #onRestart} each time the time
 * crosses a multiple of the interval. {@link #resume} rebuilds the controller
 * on a fresh solver of the same configuration, and the resumed run is the
 * continuous one to the bit (tested by {@code StepControllerTest}).
 *
 * <p>
 * Relation to STA-5: an over-melted cell that makes the heat solve stagnate is
 * caught by criterion 1 and sub-stepped, never accepted (condition 1 of
 * STA-5).
 */
public final class StepController {

	/**
	 * An extra criterion of acceptance, e.g. a stricter test of a validation
	 * run, or a fault injected by a test.
	 */
	@FunctionalInterface
	public interface Check {
		/**
		 * @param time    start of the step [s]
		 * @param forcing forcing of the step (of the sub-step, if one)
		 * @param result  the step
		 * @param terms   its extra terms
		 * @return null to accept, otherwise why not
		 */
		String reject(double time, StepForcing forcing, SnowSoilStepResult result, ColumnStepTerms terms);
	}

	/**
	 * Outcome of one interval.
	 *
	 * @param result     the step over the whole interval: the result of the
	 *                   solver if accepted at once, otherwise the aggregate of
	 *                   the sub-steps
	 * @param balance    whole-column balance of the interval, summed over the
	 *                   sub-steps
	 * @param substeps   accepted steps the interval took (1 = none rejected)
	 * @param rejections attempts rejected in the interval
	 * @param degraded   a sub-step failed at the finest level and was accepted
	 *                   ({@link FailurePolicy#FLAG}): not to be used as an
	 *                   accepted result
	 */
	public record ControlledStep(SnowSoilStepResult result, ColumnBalance balance, int substeps,
			List<StepRejection> rejections, boolean degraded) {
		public ControlledStep {
			rejections = List.copyOf(rejections);
		}
	}

	private record Accepted(StepForcing forcing, SnowSoilStepResult result, ColumnBalance balance) {
	}

	private final SnowSoilSolver solver;
	private final StepControlSettings settings;
	private final String configuration;
	private final int soilCells;
	private final List<Check> checks = new ArrayList<>();
	private final List<StepRejection> log = new ArrayList<>();
	private Consumer<SnowSoilRestart> restartSink;
	private double time;
	private RunDiagnostics diagnostics;
	private ColumnBalance.Storage storage;
	private boolean degradedInterval;

	/** A controller with the default settings, starting at time zero. */
	public StepController(SnowSoilSolver solver) {
		this(solver, StepControlSettings.defaults(), 0.0, "");
	}

	/**
	 * @param solver        the column, in its initial state
	 * @param settings      rejection, sub-stepping and restart
	 * @param startTime     time of the initial state [s]; the time of the
	 *                      rejections and of the restart states
	 * @param configuration free label written into the restart states and
	 *                      checked on resume (e.g. a hash of the configuration);
	 *                      empty for none
	 */
	public StepController(SnowSoilSolver solver, StepControlSettings settings, double startTime,
			String configuration) {
		this(solver, settings, startTime, configuration, RunDiagnostics.EMPTY);
	}

	private StepController(SnowSoilSolver solver, StepControlSettings settings, double startTime,
			String configuration, RunDiagnostics diagnostics) {
		this.solver = Objects.requireNonNull(solver, "solver");
		this.settings = Objects.requireNonNull(settings, "settings");
		if (!Double.isFinite(startTime)) {
			throw new IllegalArgumentException("startTime must be finite, got " + startTime);
		}
		this.time = startTime;
		this.configuration = configuration == null ? "" : configuration;
		this.diagnostics = diagnostics;
		this.soilCells = solver.soilTemperatures().length;
		this.storage = ColumnBalance.Storage.of(solver);
	}

	/**
	 * A controller that carries on a run from a restart state: the solver, built
	 * with the configuration of the run that wrote it, is restored, and time
	 * and diagnostics go on from the state.
	 *
	 * @param configuration the label of this run, which must be the one of the
	 *                      restart state
	 * @throws IllegalArgumentException if the labels differ or the state does
	 *                                  not fit the solver
	 */
	public static StepController resume(SnowSoilSolver solver, StepControlSettings settings, SnowSoilRestart restart,
			String configuration) {
		String label = configuration == null ? "" : configuration;
		if (!label.equals(restart.configuration())) {
			throw new IllegalArgumentException("restart state of configuration '" + restart.configuration()
					+ "', this run is '" + label + "'");
		}
		solver.restore(restart.state());
		return new StepController(solver, settings, restart.time(), label, restart.diagnostics());
	}

	/** Adds a criterion of acceptance, checked after the built-in ones. */
	public void addCheck(Check check) {
		checks.add(Objects.requireNonNull(check, "check"));
	}

	/** Where the periodic restart states go (the component writes them); null for nowhere. */
	public void onRestart(Consumer<SnowSoilRestart> sink) {
		this.restartSink = sink;
	}

	/** The solver, for reading its state. */
	public SnowSoilSolver solver() {
		return solver;
	}

	public StepControlSettings settings() {
		return settings;
	}

	/** Time of the current state [s]. */
	public double time() {
		return time;
	}

	/** Diagnostics of the run so far. */
	public RunDiagnostics diagnostics() {
		return diagnostics;
	}

	/**
	 * The rejections of the run, in order, at most
	 * {@link StepControlSettings#maxLoggedRejections()} of them (the count is in
	 * {@link #diagnostics()}).
	 */
	public List<StepRejection> rejectionLog() {
		return Collections.unmodifiableList(log);
	}

	/** Restart state of now. */
	public SnowSoilRestart checkpoint() {
		return new SnowSoilRestart(time, configuration, solver.snapshot(), diagnostics);
	}

	/**
	 * Advances the column over one interval of the forcing, sub-stepping where
	 * a step is rejected.
	 *
	 * @throws StepNotConvergedException if a step fails at the finest sub-step
	 *                                   (with {@link FailurePolicy#STOP}, or for
	 *                                   an invalid state); the solver is then at
	 *                                   the start of the interval
	 */
	public ControlledStep advance(StepForcing forcing) {
		Objects.requireNonNull(forcing, "forcing");
		SnowSoilState start = solver.snapshot();
		RunDiagnostics startDiagnostics = diagnostics;
		ColumnBalance.Storage startStorage = storage;
		List<Accepted> accepted = new ArrayList<>(1);
		List<StepRejection> rejected = new ArrayList<>(0);
		degradedInterval = false;
		try {
			cover(forcing, 0, time, start, accepted, rejected);
		} catch (StepNotConvergedException e) {
			solver.restore(start);
			storage = startStorage;
			RunDiagnostics failed = startDiagnostics;
			for (int k = 0; k < rejected.size(); k++) {
				failed = failed.withRejection();
			}
			diagnostics = failed;
			throw e;
		}
		double previous = time;
		time = previous + forcing.timeDelta();
		diagnostics = diagnostics.withInterval();
		ControlledStep outcome;
		if (accepted.size() == 1) {
			Accepted only = accepted.get(0);
			outcome = new ControlledStep(only.result(), only.balance(), 1, rejected, degradedInterval);
		} else {
			List<SnowSoilStepResult> results = new ArrayList<>(accepted.size());
			List<Double> lengths = new ArrayList<>(accepted.size());
			ColumnBalance sum = ColumnBalance.ZERO;
			for (Accepted a : accepted) {
				results.add(a.result());
				lengths.add(a.forcing().timeDelta());
				sum = sum.plus(a.balance());
			}
			outcome = new ControlledStep(aggregate(results, lengths), sum, accepted.size(), rejected,
					degradedInterval);
		}
		double interval = settings.restartInterval();
		if (interval > 0.0 && restartSink != null && Math.floor(time / interval) > Math.floor(previous / interval)) {
			restartSink.accept(checkpoint());
		}
		return outcome;
	}

	/** Covers the part of the interval starting at {@code start} with 2<sup>-level</sup> of its length. */
	private void cover(StepForcing interval, int level, double start, SnowSoilState before, List<Accepted> accepted,
			List<StepRejection> rejected) {
		StepForcing forcing = level == 0 ? interval : fraction(interval, Math.scalb(1.0, -level));
		ColumnBalance.Storage storageBefore = storage;
		SnowSoilStepResult result = null;
		ColumnStepTerms terms = null;
		StepRejection rejection;
		RuntimeException cause = null;
		try {
			result = solver.step(forcing);
			terms = solver.lastStepTerms();
			rejection = judge(start, forcing, level, result, terms);
		} catch (ArithmeticException | IllegalArgumentException | IllegalStateException e) {
			result = null;
			cause = e;
			rejection = new StepRejection(start, forcing.timeDelta(), level, Reason.INVALID_STATE, -1, soilCells,
					Double.NaN, e.toString());
		}
		if (rejection == null) {
			accept(storageBefore, forcing, result, terms, accepted, false);
			return;
		}
		rejected.add(rejection);
		if (log.size() < settings.maxLoggedRejections()) {
			log.add(rejection);
		}
		diagnostics = diagnostics.withRejection();
		if (level < settings.maxHalvings()) {
			solver.restore(before);
			storage = storageBefore;
			double half = 0.5 * forcing.timeDelta();
			cover(interval, level + 1, start, before, accepted, rejected);
			cover(interval, level + 1, start + half, solver.snapshot(), accepted, rejected);
			return;
		}
		if (settings.onFailure() == FailurePolicy.FLAG && result != null
				&& rejection.reason() != Reason.INVALID_STATE) {
			degradedInterval = true;
			accept(storageBefore, forcing, result, terms, accepted, true);
			return;
		}
		// advance() puts back the start of the interval and its diagnostics
		throw new StepNotConvergedException(rejection, rejected, cause);
	}

	private void accept(ColumnBalance.Storage before, StepForcing forcing, SnowSoilStepResult result,
			ColumnStepTerms terms, List<Accepted> accepted, boolean degraded) {
		ColumnBalance.Storage after = ColumnBalance.Storage.of(solver);
		ColumnBalance balance = ColumnBalance.of(before, after, forcing, result, terms);
		storage = after;
		diagnostics = diagnostics.withStep(balance, degraded);
		accepted.add(new Accepted(forcing, result, balance));
	}

	/** The criteria of the class comment; null if the step is accepted. */
	private StepRejection judge(double start, StepForcing forcing, int level, SnowSoilStepResult result,
			ColumnStepTerms terms) {
		double dt = forcing.timeDelta();
		NewtonReport newton = result.heat().newton();
		if (!newton.converged()) {
			return new StepRejection(start, dt, level, Reason.HEAT_NOT_CONVERGED, terms.heatWorstCell(), soilCells,
					newton.worstResidualRatio(), "per-cell residual above its bound after "
							+ newton.outerIterations() + " outer iterations" + (newton.polished() ? ", polished" : ""));
		}
		if (!result.surfaceConverged()) {
			return new StepRejection(start, dt, level, Reason.SURFACE_NOT_CONVERGED, -1, soilCells,
					terms.surfaceChange(), "surface temperature still moving after " + result.surfaceSweeps()
							+ " sweeps");
		}
		SoilWaterStepResult water = result.soilWater();
		if (water != null && !(Math.abs(water.balanceError()) <= settings.richardsBalanceTolerance())) {
			return new StepRejection(start, dt, level, Reason.RICHARDS_NOT_CONVERGED, -1, soilCells,
					water.balanceError(), "water balance error above " + settings.richardsBalanceTolerance());
		}
		String invalid = invalidState(result);
		if (invalid != null) {
			return new StepRejection(start, dt, level, Reason.INVALID_STATE, -1, soilCells, Double.NaN, invalid);
		}
		for (Check check : checks) {
			String why = check.reject(start, forcing, result, terms);
			if (why != null) {
				return new StepRejection(start, dt, level, Reason.EXTRA_CHECK, -1, soilCells, Double.NaN, why);
			}
		}
		return null;
	}

	/** The first non-finite value of the end state, or null. */
	private String invalidState(SnowSoilStepResult result) {
		double[] t = solver.soilTemperatures();
		for (int i = 0; i < t.length; i++) {
			if (!Double.isFinite(t[i])) {
				return "temperature of soil cell " + i + " is " + t[i];
			}
		}
		double[] psi = solver.soilSuctions();
		for (int i = 0; i < psi.length; i++) {
			if (!Double.isFinite(psi[i])) {
				return "suction of soil cell " + i + " is " + psi[i];
			}
		}
		if (result.soilWater() != null) {
			double[] water = result.soilWater().suctions();
			for (int i = 0; i < water.length; i++) {
				if (!Double.isFinite(water[i])) {
					return "Richards suction of soil cell " + i + " is " + water[i];
				}
			}
		}
		List<SnowCell> snow = solver.snow();
		for (int j = 0; j < snow.size(); j++) {
			SnowCell c = snow.get(j);
			if (!Double.isFinite(c.iceMass()) || !Double.isFinite(c.liquidMass()) || !Double.isFinite(c.thickness())
					|| !Double.isFinite(c.internalEnergy())) {
				return "snow cell " + j + " is not finite: " + c;
			}
		}
		if (!Double.isFinite(solver.surfaceAge()) || !Double.isFinite(solver.pendingSurfaceEnergy())) {
			return "surface age or deferred energy not finite";
		}
		if (!Double.isFinite(result.budget().massClosure()) || !Double.isFinite(result.budget().energyClosure())
				|| !Double.isFinite(result.heat().energy().closureError())) {
			return "closures of the step not finite";
		}
		return null;
	}

	/** The forcing of a sub-step: same intensities, amounts times the fraction (a power of two, exact). */
	static StepForcing fraction(StepForcing f, double fraction) {
		double[] sinks = f.soilWaterSinks();
		double[] subSinks = null;
		if (sinks != null) {
			subSinks = new double[sinks.length];
			for (int i = 0; i < sinks.length; i++) {
				subSinks[i] = sinks[i] * fraction;
			}
		}
		return new StepForcing(f.surface(), f.snowfall() * fraction, f.rainfall() * fraction,
				f.groundAlbedoVisible(), f.groundAlbedoNearInfrared(), f.bareSoilTop(), f.bottomValue(),
				f.timeDelta() * fraction, subSinks);
	}

	/**
	 * The result of an interval covered by sub-steps, in the shape of one step:
	 *
	 * <ul>
	 * <li>states at the end of the interval from the last sub-step: snow,
	 * surface age, surface temperature, albedo, deferred energy, the
	 * temperatures of the heat step, the suctions of Richards;</li>
	 * <li>amounts summed over the sub-steps: melt, refrozen mass, merges and
	 * splits, unmet vapour, linearisation energy, water and energy handed to the
	 * soil, the Richards amounts, the soil energies and closures, the energy
	 * budget of the heat step, the snow budget (from the start of the first to
	 * the end of the last sub-step);</li>
	 * <li>fluxes [W m-2] as means over the interval: the conductive fluxes
	 * through the soil faces and through the top face (the snow faces of the
	 * last sub-step: the cells may change between sub-steps), the snow-to-soil
	 * flux (NaN if the last sub-step has no snow), the surface fluxes (mean over
	 * the sub-steps that had snow at their start, null if none; latent heat and
	 * derivative of the last one);</li>
	 * <li>the Newton report: iterations summed, worst ratio and tolerance the
	 * largest, converged only if every sub-step converged; surface sweeps
	 * summed, converged only if every sub-step converged.</li>
	 * </ul>
	 */
	static SnowSoilStepResult aggregate(List<SnowSoilStepResult> results, List<Double> lengths) {
		SnowSoilStepResult first = results.get(0);
		SnowSoilStepResult last = results.get(results.size() - 1);
		double total = 0.0;
		for (double dt : lengths) {
			total += dt;
		}

		// heat step
		HeatStepResult lastHeat = last.heat();
		double[] fluxes = lastHeat.conductiveFluxes().clone();
		int soilFaces = lastHeat.soilTemperatures().length + 1;
		int top = fluxes.length - 1;
		double[] soilFluxes = new double[soilFaces];
		double topFlux = 0.0;
		double snowToSoil = 0.0;
		double storage = 0.0;
		double bottom = 0.0;
		double topInput = 0.0;
		int outer = 0;
		int inner = 0;
		boolean cap = false;
		double tolerance = 0.0;
		double worst = 0.0;
		boolean converged = true;
		boolean polished = false;
		boolean single = false;
		int sweeps = 0;
		boolean surfaceConverged = true;
		double[] surfaceSum = new double[6];
		double surfaceTime = 0.0;
		SurfaceFluxes lastSurface = null;
		double melt = 0.0;
		double refrozen = 0.0;
		int merges = 0;
		int splits = 0;
		double unmet = 0.0;
		double linearisation = 0.0;
		double soilInputMass = 0.0;
		double soilInputEnergy = 0.0;
		double drainageEnergy = 0.0;
		double soilClosure = 0.0;
		double storageHeat = 0.0;
		double[] faceWater = null;
		double[] sinks = null;
		double infiltration = 0.0;
		double drainage = 0.0;
		double waterStorage = 0.0;
		double waterError = 0.0;
		double runoff = 0.0;
		double snowfall = 0.0;
		double rain = 0.0;
		double vapour = 0.0;
		double toSoilMass = 0.0;
		double heatEnergy = 0.0;
		double snowfallEnergy = 0.0;
		double rainEnergy = 0.0;
		double vapourEnergy = 0.0;
		double toSoilEnergy = 0.0;
		for (int k = 0; k < results.size(); k++) {
			SnowSoilStepResult r = results.get(k);
			double dt = lengths.get(k);
			HeatStepResult h = r.heat();
			double[] f = h.conductiveFluxes();
			for (int i = 0; i < soilFaces && i < f.length; i++) {
				soilFluxes[i] += f[i] * dt;
			}
			topFlux += f[f.length - 1] * dt;
			if (!Double.isNaN(h.snowToSoilFlux())) {
				snowToSoil += h.snowToSoilFlux() * dt;
			}
			storage += h.energy().storageChange();
			bottom += h.energy().bottomInput();
			topInput += h.energy().topInput();
			NewtonReport n = h.newton();
			outer += n.outerIterations();
			inner += n.innerIterations();
			cap |= n.hitIterationCap();
			tolerance = Math.max(tolerance, n.toleranceUsed());
			worst = Math.max(worst, n.worstResidualRatio());
			converged &= n.converged();
			polished |= n.polished();
			single |= n.singleCell();
			sweeps += r.surfaceSweeps();
			surfaceConverged &= r.surfaceConverged();
			if (r.surface() != null) {
				SurfaceFluxes s = r.surface();
				surfaceSum[0] += s.netShortwave() * dt;
				surfaceSum[1] += s.netLongwave() * dt;
				surfaceSum[2] += s.sensibleHeat() * dt;
				surfaceSum[3] += s.latentHeatFlux() * dt;
				surfaceSum[4] += s.vapourFlux() * dt;
				surfaceSum[5] += s.energyBalance() * dt;
				surfaceTime += dt;
				lastSurface = s;
			}
			melt += r.melt();
			refrozen += r.refrozen();
			merges += r.merges();
			splits += r.splits();
			unmet += r.unmetVapour();
			linearisation += r.linearisationEnergy();
			soilInputMass += r.soilInputMass();
			soilInputEnergy += r.soilInputEnergy();
			drainageEnergy += r.soilDrainageEnergy();
			soilClosure += r.soilEnergyClosure();
			storageHeat += r.soilStorageHeat();
			SoilWaterStepResult w = r.soilWater();
			if (w != null) {
				faceWater = add(faceWater, w.faceWater());
				sinks = add(sinks, w.sinks());
				infiltration += w.infiltration();
				drainage += w.drainage();
				waterStorage += w.storageChange();
				waterError += w.balanceError();
				runoff += w.runoff();
			}
			SnowBudget b = r.budget();
			snowfall += b.snowfall();
			rain += b.rain();
			vapour += b.vapour();
			toSoilMass += b.toSoilMass();
			heatEnergy += b.heat();
			snowfallEnergy += b.snowfallEnergy();
			rainEnergy += b.rainEnergy();
			vapourEnergy += b.vapourEnergy();
			toSoilEnergy += b.toSoilEnergy();
		}
		for (int i = 0; i < soilFaces && i < fluxes.length; i++) {
			fluxes[i] = soilFluxes[i] / total;
		}
		fluxes[top] = topFlux / total;
		double meanSnowToSoil = Double.isNaN(lastHeat.snowToSoilFlux()) ? Double.NaN : snowToSoil / total;
		EnergyBudget energy = EnergyBudget.of(storage, bottom, topInput);
		NewtonReport newton = new NewtonReport(outer, inner, cap, tolerance, worst, converged, polished, single);
		HeatStepResult heat = new HeatStepResult(lastHeat.snow(), lastHeat.soilTemperatures(),
				lastHeat.temperatures(), fluxes, meanSnowToSoil, energy, newton);
		SurfaceFluxes surface = lastSurface == null ? null
				: new SurfaceFluxes(surfaceSum[0] / surfaceTime, surfaceSum[1] / surfaceTime,
						surfaceSum[2] / surfaceTime, surfaceSum[3] / surfaceTime, surfaceSum[4] / surfaceTime,
						lastSurface.latentHeat(), surfaceSum[5] / surfaceTime, lastSurface.derivative());
		SoilWaterStepResult water = last.soilWater() == null ? null
				: new SoilWaterStepResult(last.soilWater().suctions(), faceWater, infiltration, drainage, waterStorage,
						waterError, runoff, sinks);
		SnowBudget budget = SnowBudget.of(first.budget().massBefore(), last.budget().massAfter(), snowfall, rain,
				vapour, toSoilMass, first.budget().energyBefore(), last.budget().energyAfter(), heatEnergy,
				snowfallEnergy, rainEnergy, vapourEnergy, toSoilEnergy);
		return new SnowSoilStepResult(last.snow(), last.surfaceAge(), heat, sweeps, surfaceConverged, surface,
				last.surfaceTemperature(), last.albedo(), melt, refrozen, merges, splits, unmet, linearisation,
				last.pendingSurfaceEnergy(), soilInputMass, soilInputEnergy, water, drainageEnergy, soilClosure,
				storageHeat, budget);
	}

	private static double[] add(double[] sum, double[] values) {
		if (sum == null) {
			return values.clone();
		}
		double[] result = sum.clone();
		for (int i = 0; i < result.length; i++) {
			result[i] += values[i];
		}
		return result;
	}
}
