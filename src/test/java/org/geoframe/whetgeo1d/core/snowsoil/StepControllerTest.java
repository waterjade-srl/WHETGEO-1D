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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.geoframe.whetgeo1d.core.snowsoil.StepControlSettings.FailurePolicy;
import org.geoframe.whetgeo1d.core.snowsoil.StepController.ControlledStep;
import org.geoframe.whetgeo1d.core.snowsoil.StepRejection.Reason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;
import org.junit.Test;

/**
 * The step controller (SOL-5) and the restart (ARCH-5) on the synthetic season
 * of document 009: without rejections the controlled run is the plain run to
 * the bit; a run stopped, written, read back and resumed is the continuous run
 * to the bit; a rejected step is rolled back completely.
 */
public class StepControllerTest {

	private static final SyntheticSeason SEASON = SyntheticSeason.standard();
	private static final double DT = SEASON.timeDelta();
	/** Three hours into the rain on snow of day 60. */
	private static final int RAIN_STEP = (int) (SyntheticSeason.RAIN_DAY * SyntheticSeason.DAY / DT) + 3;

	/**
	 * Plain run, controlled run and a run resumed from a restart state written
	 * and read back in the middle of the rain on snow, in lockstep: every
	 * double of every step result and of the state is the same to the bit, and
	 * so are the diagnostics of the resumed and the continuous run. Frozen soil
	 * in fidelity (the most state: ψ<sub>w0</sub> with storage, ice, two
	 * potentials), soil water in the recommended configuration.
	 */
	@Test
	public void resumedRunIsTheContinuousRunBitwise() throws IOException {
		assertResumedIsContinuous(() -> SEASON.solverWithFrozenSoil(Configuration.FIDELITY), 65);
		assertResumedIsContinuous(() -> SEASON.solverWithSoilWater(Configuration.RECOMMENDED), 65);
	}

	private static void assertResumedIsContinuous(Supplier<SnowSoilSolver> factory, double days) throws IOException {
		int steps = (int) Math.round(days * SyntheticSeason.DAY / DT);
		SnowSoilSolver plain = factory.get();
		StepController controlled = new StepController(factory.get(), StepControlSettings.defaults(), 0.0, "season");
		StepController resumed = null;
		for (int k = 0; k < steps; k++) {
			if (k == RAIN_STEP) {
				byte[] bytes = controlled.checkpoint().toBytes();
				resumed = StepController.resume(factory.get(), StepControlSettings.defaults(),
						SnowSoilRestart.fromBytes(bytes), "season");
				assertEquals(controlled.time(), resumed.time(), 0.0);
				assertTrue(controlled.solver().snapshot().bitwiseEquals(resumed.solver().snapshot()));
				assertFalse("the restart has snow", resumed.solver().snow().isEmpty());
			}
			double time = k * DT;
			SnowSoilStepResult a = plain.step(forcing(plain, time));
			ControlledStep b = controlled.advance(forcing(controlled.solver(), time));
			assertEquals(1, b.substeps());
			assertArrayEquals("controlled, step " + k, bits(a), bits(b.result()));
			assertTrue(plain.snapshot().bitwiseEquals(controlled.solver().snapshot()));
			if (resumed != null) {
				ControlledStep c = resumed.advance(forcing(resumed.solver(), time));
				assertArrayEquals("resumed, step " + k, bits(b.result()), bits(c.result()));
				assertArrayEquals("resumed balance, step " + k, bits(b.balance().toArray()),
						bits(c.balance().toArray()));
				assertTrue(controlled.solver().snapshot().bitwiseEquals(resumed.solver().snapshot()));
			}
		}
		assertNotNull(resumed);
		assertTrue(controlled.diagnostics().bitwiseEquals(resumed.diagnostics()));
		assertEquals(steps, resumed.diagnostics().intervals());
		assertEquals(0, resumed.diagnostics().rejections());
		assertEquals(controlled.time(), resumed.time(), 0.0);
	}

	/**
	 * A step rejected once is covered by two halves from the restored state:
	 * the result is the plain solver doing the two half steps, to the bit, and
	 * the run goes on identical. A half rejected in turn is split again: three
	 * hours later the interval and its first half are rejected, and the step is
	 * a quarter, a quarter and a half. Frozen soil, during the rain on snow.
	 */
	@Test
	public void rejectedStepIsRolledBackAndHalved() {
		double once = RAIN_STEP * DT;
		double twice = (RAIN_STEP + 3) * DT;
		SnowSoilSolver plain = SEASON.solverWithFrozenSoil(Configuration.RECOMMENDED);
		StepController controller = new StepController(SEASON.solverWithFrozenSoil(Configuration.RECOMMENDED));
		controller.addCheck((time, f, r, t) -> (time == once && f.timeDelta() == DT)
				|| (time == twice && f.timeDelta() >= 0.5 * DT) ? "injected" : null);
		int last = RAIN_STEP + 6;
		for (int k = 0; k <= last; k++) {
			double time = k * DT;
			ControlledStep b = controller.advance(forcing(controller.solver(), time));
			StepForcing f = forcing(plain, time);
			double[] fractions = time == once ? new double[] { 0.5, 0.5 }
					: time == twice ? new double[] { 0.25, 0.25, 0.5 } : null;
			if (fractions != null) {
				assertEquals(fractions.length, b.substeps());
				assertEquals(fractions.length - 1, b.rejections().size());
				assertEquals(Reason.EXTRA_CHECK, b.rejections().get(0).reason());
				List<SnowSoilStepResult> parts = new ArrayList<>();
				List<Double> lengths = new ArrayList<>();
				for (double fraction : fractions) {
					StepForcing part = StepController.fraction(f, fraction);
					parts.add(plain.step(part));
					lengths.add(part.timeDelta());
				}
				assertArrayEquals(bits(StepController.aggregate(parts, lengths)), bits(b.result()));
				SnowSoilStepResult whole = b.result();
				assertEquals(f.rainfall(), whole.budget().rain(), 1e-12);
				assertEquals(0.0, whole.budget().massClosure(), 1e-9);
				assertEquals(DT, sum(lengths), 0.0);
				assertFalse(b.degraded());
			} else {
				assertEquals(1, b.substeps());
				assertArrayEquals("step " + k, bits(plain.step(f)), bits(b.result()));
			}
			assertTrue("step " + k, plain.snapshot().bitwiseEquals(controller.solver().snapshot()));
		}
		assertEquals(3, controller.diagnostics().rejections());
		assertEquals(last + 1, controller.diagnostics().intervals());
		assertEquals(last + 1 + 1 + 2, controller.diagnostics().substeps());
		assertEquals(3, controller.rejectionLog().size());
	}

	/**
	 * A step that fails down to Δt / 16 stops the run with an error naming the
	 * solver and the time, after 5 rejections (the interval and the first half
	 * at each level); the solver is left at the start of the interval.
	 */
	@Test
	public void stepFailingAtTheFinestLevelStopsTheRun() {
		StepController controller = new StepController(SEASON.solverWithSoilWater(Configuration.FIDELITY));
		double target = RAIN_STEP * DT;
		controller.addCheck((time, f, r, t) -> time >= target ? "injected" : null);
		for (int k = 0; k < RAIN_STEP; k++) {
			controller.advance(forcing(controller.solver(), k * DT));
		}
		SnowSoilState before = controller.solver().snapshot();
		RunDiagnostics diagnostics = controller.diagnostics();
		try {
			controller.advance(forcing(controller.solver(), target));
			fail("expected a StepNotConvergedException");
		} catch (StepNotConvergedException e) {
			assertEquals(4, e.failure().halvings());
			assertEquals(DT / 16, e.failure().timeDelta(), 0.0);
			assertEquals(target, e.failure().time(), 0.0);
			assertEquals(5, e.rejections().size());
			assertTrue(e.getMessage(), e.getMessage().contains("t = " + String.format("%.1f", target)));
			assertTrue(e.getMessage(), e.getMessage().contains("check"));
		}
		assertTrue(before.bitwiseEquals(controller.solver().snapshot()));
		assertEquals(target, controller.time(), 0.0);
		assertTrue(diagnostics.total() == controller.diagnostics().total());
		assertEquals(5, controller.diagnostics().rejections());
		// the column carries on from the last accepted state once the cause is gone
		StepController fresh = StepController.resume(SEASON.solverWithSoilWater(Configuration.FIDELITY),
				StepControlSettings.defaults(), controller.checkpoint(), "");
		assertEquals(1, fresh.advance(forcing(fresh.solver(), target)).substeps());
	}

	/** With the FLAG policy the finest sub-steps are accepted, counted and the interval marked as degraded. */
	@Test
	public void flagPolicyAcceptsAndMarksTheFailedSubsteps() {
		StepController controller = new StepController(SEASON.solverWithSoilWater(Configuration.RECOMMENDED),
				StepControlSettings.defaults().withMaxHalvings(2).withOnFailure(FailurePolicy.FLAG), 0.0, "");
		double target = RAIN_STEP * DT;
		controller.addCheck((time, f, r, t) -> time >= target && time < target + DT ? "injected" : null);
		for (int k = 0; k <= RAIN_STEP + 1; k++) {
			ControlledStep s = controller.advance(forcing(controller.solver(), k * DT));
			if (k == RAIN_STEP) {
				assertTrue(s.degraded());
				assertEquals(4, s.substeps());
				// the interval, its two halves, the four quarters
				assertEquals(7, s.rejections().size());
				assertEquals(SyntheticSeason.RAIN_RATE, s.balance().rainfall(), 1e-12);
			} else {
				assertFalse(s.degraded());
				assertEquals(1, s.substeps());
			}
		}
		assertEquals(4, controller.diagnostics().degradedSteps());
		assertEquals(7, controller.diagnostics().rejections());
	}

	/** An exception of a solver is an invalid state: rolled back and sub-stepped like a failed criterion. */
	@Test
	public void solverExceptionIsAnInvalidStateAndIsSubstepped() {
		StepController controller = new StepController(SEASON.solverWithSoilWater(Configuration.RECOMMENDED));
		double target = RAIN_STEP * DT;
		controller.addCheck((time, f, r, t) -> {
			if (time >= target && time < target + DT && f.timeDelta() > DT / 4) {
				throw new ArithmeticException("injected non-finite temperature");
			}
			return null;
		});
		for (int k = 0; k <= RAIN_STEP; k++) {
			ControlledStep s = controller.advance(forcing(controller.solver(), k * DT));
			if (k == RAIN_STEP) {
				assertEquals(4, s.substeps());
				assertEquals(3, s.rejections().size());
				for (StepRejection r : s.rejections()) {
					assertEquals(Reason.INVALID_STATE, r.reason());
					assertTrue(r.detail(), r.detail().contains("injected"));
				}
			}
		}
	}

	/**
	 * Real non-convergence: surface sweeps capped at 2 with a tolerance they
	 * cannot meet in an hour on the melting pack. The controller sub-steps,
	 * every interval is accepted or stops loudly, and every accepted step met
	 * the criteria. Without shortwave penetration: with it, in sunny hours the
	 * top cell is warmed to 0 °C by the shortwave it absorbs while the skin stays
	 * about 11 K colder, the first linearisation (at the start temperature of
	 * the top cell) is 0.45 K from the solution at any step length, and
	 * halving cannot help a sweep count capped at 3; four sweeps meet the
	 * tolerance without sub-steps (the default is 20).
	 */
	@Test
	public void surfaceNonConvergenceIsSubstepped() {
		SnowSoilSolver solver = SEASON.solver(Configuration.RECOMMENDED,
				p -> p.withSurfaceCoupling(new SurfaceCoupling(3, 1e-3)).withShortwavePenetration(ShortwavePenetration.NONE));
		StepController controller = new StepController(solver,
				StepControlSettings.defaults().withMaxHalvings(6), 0.0, "");
		int steps = (int) (110 * SyntheticSeason.DAY / DT);
		int substepped = 0;
		for (int k = 0; k < steps; k++) {
			ControlledStep s = controller.advance(forcing(solver, k * DT));
			assertTrue(s.result().surfaceConverged());
			assertTrue(s.result().heat().newton().converged());
			if (s.substeps() > 1) {
				substepped++;
				for (StepRejection r : s.rejections()) {
					assertEquals(Reason.SURFACE_NOT_CONVERGED, r.reason());
					assertTrue(r.value() > 1e-3);
				}
			}
		}
		assertTrue("intervals sub-stepped " + substepped, substepped > 0);
		assertEquals(controller.diagnostics().rejections(), controller.rejectionLog().size());
		assertEquals(0.0, controller.diagnostics().total().snowMassClosure(), 1e-9);
	}

	/** Restart states are handed over at every multiple of the interval, and each one resumes the run. */
	@Test
	public void periodicRestartStates() throws IOException {
		StepController controller = new StepController(SEASON.solver(Configuration.FIDELITY),
				StepControlSettings.defaults().withRestartInterval(SyntheticSeason.DAY), 0.0, "fidelity");
		List<SnowSoilRestart> states = new ArrayList<>();
		controller.onRestart(states::add);
		for (int k = 0; k < 3 * 24 + 5; k++) {
			controller.advance(forcing(controller.solver(), k * DT));
		}
		assertEquals(3, states.size());
		for (int d = 0; d < 3; d++) {
			assertEquals((d + 1) * SyntheticSeason.DAY, states.get(d).time(), 0.0);
			assertEquals((d + 1) * 24, states.get(d).diagnostics().intervals());
		}
		SnowSoilRestart read = SnowSoilRestart.fromBytes(states.get(2).toBytes());
		assertTrue(read.state().bitwiseEquals(states.get(2).state()));
		assertTrue(read.diagnostics().bitwiseEquals(states.get(2).diagnostics()));
		assertEquals("fidelity", read.configuration());
	}

	/** The format is checked: magic, version, configuration, shape of the column. */
	@Test
	public void restartFormatIsChecked() throws IOException {
		StepController controller = new StepController(SEASON.solverWithSoilWater(Configuration.FIDELITY),
				StepControlSettings.defaults(), 0.0, "a");
		for (int k = 0; k < 60; k++) {
			controller.advance(forcing(controller.solver(), k * DT));
		}
		byte[] bytes = controller.checkpoint().toBytes();
		byte[] wrongVersion = bytes.clone();
		wrongVersion[7] = 99;
		assertThrowsIo(wrongVersion, "version");
		byte[] wrongMagic = bytes.clone();
		wrongMagic[0] = 0;
		assertThrowsIo(wrongMagic, "not a snow+soil restart");
		SnowSoilRestart restart = SnowSoilRestart.fromBytes(bytes);
		try {
			StepController.resume(SEASON.solverWithSoilWater(Configuration.FIDELITY), StepControlSettings.defaults(),
					restart, "b");
			fail("configuration label not checked");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("'a'"));
		}
		try {
			// a column without soil water cannot take a state with Richards
			StepController.resume(SEASON.solver(Configuration.FIDELITY), StepControlSettings.defaults(), restart, "a");
			fail("soil water not checked");
		} catch (IllegalArgumentException expected) {
			assertTrue(expected.getMessage().contains("soil water"));
		}
	}

	private static void assertThrowsIo(byte[] bytes, String message) {
		try {
			SnowSoilRestart.fromBytes(bytes);
			fail("expected an IOException");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains(message));
		}
	}

	/** An interval accepted at once returns the very result of the solver. */
	@Test
	public void acceptedIntervalReturnsTheSolverResult() {
		SnowSoilSolver solver = SEASON.solver(Configuration.FIDELITY);
		StepController controller = new StepController(solver);
		ControlledStep s = controller.advance(forcing(solver, 2 * SyntheticSeason.DAY));
		assertEquals(1, s.substeps());
		assertTrue(s.rejections().isEmpty());
		assertSame(s.result().snow(), solver.snow());
	}

	private static StepForcing forcing(SnowSoilSolver solver, double time) {
		double[] soil = solver.soilTemperatures();
		return SEASON.forcing(time, soil[soil.length - 1]);
	}

	private static double sum(List<Double> values) {
		double s = 0.0;
		for (double v : values) {
			s += v;
		}
		return s;
	}

	/** Every number of a step result, as raw bits. */
	static long[] bits(SnowSoilStepResult r) {
		List<Double> v = new ArrayList<>();
		for (SnowCell c : r.snow()) {
			v.add(c.iceMass());
			v.add(c.liquidMass());
			v.add(c.thickness());
			v.add(c.internalEnergy());
		}
		v.add(r.surfaceAge());
		HeatStepResult h = r.heat();
		for (double x : h.soilTemperatures()) {
			v.add(x);
		}
		for (double x : h.temperatures()) {
			v.add(x);
		}
		for (double x : h.conductiveFluxes()) {
			v.add(x);
		}
		v.add(h.snowToSoilFlux());
		v.add(h.energy().storageChange());
		v.add(h.energy().bottomInput());
		v.add(h.energy().topInput());
		v.add(h.energy().closureError());
		NewtonReport n = h.newton();
		v.add((double) n.outerIterations());
		v.add((double) n.innerIterations());
		v.add(n.hitIterationCap() ? 1.0 : 0.0);
		v.add(n.toleranceUsed());
		v.add(n.worstResidualRatio());
		v.add(n.converged() ? 1.0 : 0.0);
		v.add(n.polished() ? 1.0 : 0.0);
		v.add((double) r.surfaceSweeps());
		v.add(r.surfaceConverged() ? 1.0 : 0.0);
		SurfaceFluxes s = r.surface();
		if (s != null) {
			v.add(s.netShortwave());
			v.add(s.netLongwave());
			v.add(s.sensibleHeat());
			v.add(s.latentHeatFlux());
			v.add(s.vapourFlux());
			v.add(s.latentHeat());
			v.add(s.energyBalance());
			v.add(s.derivative());
		}
		v.add(r.surfaceTemperature());
		v.add(r.albedo());
		v.add(r.melt());
		v.add(r.refrozen());
		v.add((double) r.merges());
		v.add((double) r.splits());
		v.add(r.unmetVapour());
		v.add(r.linearisationEnergy());
		v.add(r.pendingSurfaceEnergy());
		v.add(r.soilInputMass());
		v.add(r.soilInputEnergy());
		SoilWaterStepResult w = r.soilWater();
		if (w != null) {
			for (double x : w.suctions()) {
				v.add(x);
			}
			for (double x : w.faceWater()) {
				v.add(x);
			}
			for (double x : w.sinks()) {
				v.add(x);
			}
			v.add(w.infiltration());
			v.add(w.drainage());
			v.add(w.storageChange());
			v.add(w.balanceError());
			v.add(w.runoff());
		}
		v.add(r.soilDrainageEnergy());
		v.add(r.soilEnergyClosure());
		v.add(r.soilStorageHeat());
		SnowBudget b = r.budget();
		for (double x : new double[] { b.massBefore(), b.massAfter(), b.snowfall(), b.rain(), b.vapour(),
				b.toSoilMass(), b.energyBefore(), b.energyAfter(), b.heat(), b.snowfallEnergy(), b.rainEnergy(),
				b.vapourEnergy(), b.toSoilEnergy(), b.massClosure(), b.energyClosure() }) {
			v.add(x);
		}
		long[] out = new long[v.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = Double.doubleToRawLongBits(v.get(i));
		}
		return out;
	}

	static long[] bits(double[] values) {
		long[] out = new long[values.length];
		for (int i = 0; i < out.length; i++) {
			out[i] = Double.doubleToRawLongBits(values[i]);
		}
		return out;
	}
}
