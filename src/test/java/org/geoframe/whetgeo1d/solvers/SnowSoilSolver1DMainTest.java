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
package org.geoframe.whetgeo1d.solvers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.StandardAtmosphere;
import org.geoframe.whetgeo1d.io.snowsoil.RecordingWriter;
import org.geoframe.whetgeo1d.io.snowsoil.SnowSoilOutputWriter;
import org.hortonmachine.gears.io.timedependent.OmsTimeSeriesIteratorReader;
import org.geoframe.whetgeo1d.core.snowsoil.StepNotConvergedException;
import org.junit.Test;

/**
 * The OMS3 component end to end on the synthetic season of
 * {@code cases/snowsoil/synthetic_season}, wired in Java exactly as the .sim
 * wires it (OMS readers on the case's CSV files, the soil grid from the case's
 * WHETGEO-1D CSV inputs), and on constant forcings for the boundary
 * conditions of COL-6.
 */
public class SnowSoilSolver1DMainTest {

	static final String CASE = "cases/snowsoil/synthetic_season";
	static final String START = "2025-11-15 01:00";
	static final String END = "2026-03-15 00:00";

	private static SnowSoilSolver1DMain caseComponent(String configuration, String soilWater, RecordingWriter w) {
		SnowSoilSolver1DMain c = new SnowSoilSolver1DMain();
		c.configuration = configuration;
		c.soilWaterMode = soilWater;
		c.stationID = 1;
		c.latitude = 46.5;
		c.longitude = 11.35;
		c.standardTime = 1.0;
		c.elevation = 1500.0;
		c.tTimeStep = 3600.0;
		c.soilGridFile = CASE + "/grid";
		c.bareSoilTopType = "Bulk Transfer";
		c.bottomBCType = "Bottom No Gradient";
		c.soilWaterBottomType = "Bottom Free Drainage";
		c.inputFiles = CASE + "/data/airTemperature.csv," + CASE + "/data/precipitation.csv";
		c.writers = w == null ? null : List.<SnowSoilOutputWriter>of(w);
		return c;
	}

	private static OmsTimeSeriesIteratorReader reader(String name, String start, String end) throws Exception {
		OmsTimeSeriesIteratorReader r = new OmsTimeSeriesIteratorReader();
		r.file = new File(CASE + "/data/" + name + ".csv").getAbsolutePath();
		r.idfield = "ID";
		r.tStart = start;
		r.tEnd = end;
		r.tTimestep = 60;
		r.fileNovalue = "-9999.0";
		r.initProcess();
		return r;
	}

	/** The loop of the .sim: readers, then the component, until the air temperature reader stops. */
	private static int runCase(SnowSoilSolver1DMain c, String end) throws Exception {
		return runCase(c, START, end);
	}

	private static int runCase(SnowSoilSolver1DMain c, String start, String end) throws Exception {
		OmsTimeSeriesIteratorReader ta = reader("airTemperature", start, end);
		OmsTimeSeriesIteratorReader rh = reader("relativeHumidity", start, end);
		OmsTimeSeriesIteratorReader wind = reader("windSpeed", start, end);
		OmsTimeSeriesIteratorReader sw = reader("shortwave", start, end);
		OmsTimeSeriesIteratorReader prec = reader("precipitation", start, end);
		int steps = 0;
		while (ta.doProcess) {
			ta.nextRecord();
			rh.nextRecord();
			wind.nextRecord();
			sw.nextRecord();
			prec.nextRecord();
			if (!ta.doProcess) {
				break;
			}
			c.inAirTemperature = ta.outData;
			c.inRelativeHumidity = rh.outData;
			c.inWindSpeed = wind.outData;
			c.inShortwaveGlobal = sw.outData;
			c.inPrecipitation = prec.outData;
			c.inCurrentDate = ta.tCurrent;
			c.solve();
			steps++;
		}
		for (OmsTimeSeriesIteratorReader r : List.of(ta, rh, wind, sw, prec)) {
			r.close();
		}
		c.close();
		return steps;
	}

	@Test
	public void syntheticSeasonRecommendedWithRichards() throws Exception {
		RecordingWriter w = new RecordingWriter();
		SnowSoilSolver1DMain c = caseComponent("recommended", "Richards", w);
		int steps = runCase(c, END);

		assertEquals(1, w.opens);
		assertTrue(w.closed);
		assertEquals(steps, w.steps.size());
		assertTrue("a season of hourly steps, got " + steps, steps >= 2800);
		assertEquals("recommended", w.header.configuration());
		assertEquals(20, w.header.soilDepth().length);
		assertEquals(0.05, w.header.soilDepth()[19], 1e-12);
		assertEquals(1.95, w.header.soilDepth()[0], 1e-12);
		assertEquals(64, w.header.attributes().get("sha256:airTemperature.csv").length());
		assertTrue(w.header.attributes().containsKey("sha256:grid_geometry.csv"));

		double maxSwe = 0.0;
		int maxCells = 0;
		double maxMass = 0.0;
		double maxEnergy = 0.0;
		int failed = 0;
		for (RecordingWriter.Step s : w.steps) {
			maxSwe = Math.max(maxSwe, s.swe());
			maxCells = Math.max(maxCells, s.cells());
			maxMass = Math.max(maxMass, Math.abs(s.snowMassClosure()));
			maxEnergy = Math.max(maxEnergy, Math.abs(s.snowEnergyClosure()));
			if (!s.heatConverged()) {
				failed++;
			}
		}
		// seven snowfalls of 25 mm on a cold pack, some lost to sublimation and to the rain-on-snow melt
		assertTrue("max SWE " + maxSwe, maxSwe > 100.0 && maxSwe < 200.0);
		assertTrue("snow cells " + maxCells, maxCells >= 2 && maxCells <= 11);
		assertTrue("snow mass closure " + maxMass, maxMass < 1e-9);
		assertTrue("snow energy closure " + maxEnergy, maxEnergy < 1e-3);
		assertTrue("heat steps failing the per-cell check: " + failed, failed <= steps / 100);
		// forcing estimates: standard atmosphere and a GEOtop-like longwave
		assertEquals(StandardAtmosphere.pressure(1500.0), c.outPressure, 0.0);
		assertTrue("longwave " + c.outLongwaveIn, c.outLongwaveIn > 150.0 && c.outLongwaveIn < 400.0);
		assertEquals(20, c.outSoilTemperature.length);
	}

	/**
	 * The default writers under outputPath, and a run carried on from a
	 * restart state written by the component, which ends bit for bit where the
	 * continuous run ends (ARCH-5).
	 */
	@Test
	public void writersAndRestartThroughTheComponent() throws Exception {
		String end = "2025-12-31 00:00";
		Path folder = Files.createTempDirectory("snowsoil-component");
		SnowSoilSolver1DMain continuous = caseComponent("recommended", "Richards", null);
		continuous.writers = null;
		continuous.outputPath = folder.resolve("output").toString();
		continuous.outputInterval = 6 * 3600;
		continuous.restartPath = folder.resolve("restart").toString();
		continuous.restartInterval = 20 * 86400.0;
		runCase(continuous, end);
		assertTrue(Files.size(folder.resolve("output/snowsoil.nc")) > 0);
		assertTrue(Files.readAllLines(folder.resolve("output/point.csv")).size() > 100);
		assertEquals(0, continuous.outRunDiagnostics.rejections());

		Path state;
		try (var files = Files.list(folder.resolve("restart"))) {
			state = files.sorted().findFirst().orElseThrow();
		}
		long time = Long.parseLong(state.getFileName().toString().replaceAll("\\D", ""));
		String start = LocalDateTime.ofEpochSecond(time + 3600, 0, ZoneOffset.UTC)
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		SnowSoilSolver1DMain resumed = caseComponent("recommended", "Richards", null);
		resumed.writers = List.of();
		resumed.resumeFrom = state.toString();
		runCase(resumed, start, end);

		assertEquals(continuous.outSnowWaterEquivalent, resumed.outSnowWaterEquivalent, 0.0);
		assertEquals(continuous.outSurfaceTemperature, resumed.outSurfaceTemperature, 0.0);
		assertArrayEquals(continuous.outSoilTemperature, resumed.outSoilTemperature, 0.0);
		assertArrayEquals(continuous.outSoilSuction, resumed.outSoilSuction, 0.0);
	}

	/**
	 * Stop on a step that does not converge (SOL-5, policy Stop): a check that
	 * rejects every step from a given time makes the run fail there; the
	 * outputs up to the last accepted step are those of the continuous run, the
	 * writers are closed, the state of the last accepted step is saved, and a
	 * run resumed from it ends bit for bit as the continuous run.
	 */
	@Test
	public void stopOnNonConvergenceSavesTheLastAcceptedState() throws Exception {
		String end = "2025-12-10 00:00";
		RecordingWriter all = new RecordingWriter();
		SnowSoilSolver1DMain continuous = caseComponent("recommended", "Richards", all);
		runCase(continuous, end);

		long failAt = LocalDateTime.of(2025, 12, 3, 12, 0).toEpochSecond(ZoneOffset.UTC);
		Path folder = Files.createTempDirectory("snowsoil-stop");
		RecordingWriter partial = new RecordingWriter();
		SnowSoilSolver1DMain failing = caseComponent("recommended", "Richards", partial);
		failing.outputPath = folder.toString();
		failing.maxHalvings = 1;
		failing.stepChecks = List.of((time, forcing, result, terms) -> time >= failAt ? "injected failure" : null);
		try {
			runCase(failing, end);
			fail("the run should stop");
		} catch (StepNotConvergedException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("saved in"));
			assertTrue(e.getMessage(), e.getMessage().contains("injected failure"));
			assertEquals(2, e.rejections().size());
		}
		assertTrue("writers closed", partial.closed);
		assertTrue(partial.steps.size() > 100);
		assertEquals(failAt, partial.steps.get(partial.steps.size() - 1).epochSeconds());
		for (int k = 0; k < partial.steps.size(); k++) {
			assertEquals("step " + k, all.steps.get(k), partial.steps.get(k));
		}
		Path state = folder.resolve("snowsoil_restart_" + failAt + ".bin");
		assertTrue(Files.isRegularFile(state));
		assertTrue(Files.isRegularFile(folder.resolve("snowsoil_restart_" + failAt + ".forcing")));
		assertTrue(Files.readString(folder.resolve("snowsoil_rejections_" + failAt + ".txt"))
				.contains("injected failure"));

		String start = LocalDateTime.ofEpochSecond(failAt + 3600, 0, ZoneOffset.UTC)
				.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
		RecordingWriter rest = new RecordingWriter();
		SnowSoilSolver1DMain resumed = caseComponent("recommended", "Richards", rest);
		resumed.resumeFrom = state.toString();
		runCase(resumed, start, end);
		int offset = partial.steps.size();
		assertEquals(all.steps.size(), offset + rest.steps.size());
		for (int k = 0; k < rest.steps.size(); k++) {
			assertEquals("resumed step " + k, all.steps.get(offset + k), rest.steps.get(k));
		}
		assertArrayEquals(continuous.outSoilTemperature, resumed.outSoilTemperature, 0.0);
	}

	/** The code version of the run header: given, or captured at build time. */
	@Test
	public void codeVersionOfTheRunHeader() {
		assertEquals("v1", SnowSoilSolver1DMain.version("v1"));
		String built = SnowSoilSolver1DMain.version("");
		assertTrue(built, built.startsWith("whetgeo1d "));
		if (getClass().getResource("/whetgeo1d-git-version.txt") != null) {
			assertTrue(built, !built.contains("unknown"));
		}
	}

	@Test
	public void fidelityHeatOnlyKeepsTheSuction() throws Exception {
		RecordingWriter w = new RecordingWriter();
		SnowSoilSolver1DMain c = caseComponent("fidelity", "Fixed", w);
		runCase(c, "2025-12-15 00:00");
		assertEquals("fidelity", w.header.configuration());
		assertEquals("FIXED", w.header.attributes().get("soilWaterMode"));
		assertEquals("10", w.header.attributes().get("maxSnowCells"));
		assertEquals("COSENZA", w.header.attributes().get("snowThermalConductivity"));
		double[] psi = c.outSoilSuction;
		for (double p : psi) {
			assertEquals(-1.0, p, 0.0);
		}
		double maxSwe = 0.0;
		for (RecordingWriter.Step s : w.steps) {
			maxSwe = Math.max(maxSwe, s.swe());
		}
		assertTrue("snow on the ground in fidelity, max SWE " + maxSwe, maxSwe > 25.0);
	}

	private static Map<Integer, double[]> constant(double value) {
		Map<Integer, double[]> m = new HashMap<>();
		m.put(1, new double[] { value });
		return m;
	}

	/** Snow-free column with imposed temperatures at top and bottom: the linear steady profile (COL-6). */
	@Test
	public void dirichletTopAndBottomGiveTheLinearProfile() throws Exception {
		RecordingWriter w = new RecordingWriter();
		SnowSoilSolver1DMain c = caseComponent("recommended", "Fixed", w);
		c.bareSoilTopType = "Dirichlet";
		c.bottomBCType = "Bottom Dirichlet";
		c.bottomBCValue = 2.0;
		c.inputFiles = "";
		c.inAirTemperature = constant(8.0);
		c.inRelativeHumidity = constant(70.0);
		c.inWindSpeed = constant(2.0);
		c.inShortwaveGlobal = constant(0.0);
		c.inPressure = constant(85000.0);
		c.inLongwaveIn = constant(300.0);
		c.inPrecipitation = constant(0.0);
		// the top temperature as a time series, with one missing value falling back to the constant
		c.bareSoilTopValue = 12.0;
		List<Double> tops = new ArrayList<>();
		long t0 = java.time.LocalDateTime.of(2026, 1, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC);
		java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
		for (int k = 1; k <= 24 * 200; k++) {
			c.inTopBC = constant(k == 10 ? -9999.0 : 12.0);
			c.inCurrentDate = java.time.LocalDateTime.ofEpochSecond(t0 + 3600L * k, 0, java.time.ZoneOffset.UTC)
					.format(f);
			c.solve();
			tops.add(c.outSoilTemperature[c.outSoilTemperature.length - 1]);
		}
		c.close();
		double[] t = c.outSoilTemperature;
		double[] depth = w.header.soilDepth();
		for (int i = 0; i < t.length; i++) {
			// cell centres between the top face at 12 °C and the bottom face at 2 °C, 2 m apart
			double expected = 273.15 + 12.0 - 10.0 * depth[i] / 2.0;
			assertEquals("cell " + i, expected, t[i], 0.05);
		}
		assertEquals(0, c.outSnowCells);
	}

	/** Neumann bottom: a geothermal flux warms an insulated column at the rate of its heat capacity. */
	@Test
	public void neumannBottomBringsTheImposedFlux() throws Exception {
		RecordingWriter w = new RecordingWriter();
		SnowSoilSolver1DMain c = caseComponent("recommended", "Fixed", w);
		c.bareSoilTopType = "Neumann";
		c.bareSoilTopValue = 0.0;
		c.bottomBCType = "Bottom Neumann";
		c.bottomBCValue = 5.0;
		c.inputFiles = "";
		c.inAirTemperature = constant(8.0);
		c.inRelativeHumidity = constant(70.0);
		c.inWindSpeed = constant(2.0);
		c.inShortwaveGlobal = constant(0.0);
		c.inLongwaveIn = constant(300.0);
		c.inPrecipitation = constant(0.0);
		java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
		long t0 = java.time.LocalDateTime.of(2026, 6, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC);
		double u1 = Double.NaN;
		int steps = 24 * 10;
		for (int k = 1; k <= steps; k++) {
			c.inCurrentDate = java.time.LocalDateTime.ofEpochSecond(t0 + 3600L * k, 0, java.time.ZoneOffset.UTC)
					.format(f);
			c.solve();
			if (k == 1) {
				u1 = c.solver().soilInternalEnergy();
			}
		}
		c.close();
		// an insulated top: the soil gains exactly the bottom flux, 5 W m-2 over the last steps - 1 hours
		double gained = c.solver().soilInternalEnergy() - u1;
		assertEquals(5.0 * 3600.0 * (steps - 1), gained, 1e-3 * 5.0 * 3600.0 * steps);
	}

	/**
	 * Without shortwave but with a cloud transmissivity (the Friuli stations)
	 * the component takes GEOtop's clear sky times τ<sub>c</sub>: zero at night,
	 * proportional to τ<sub>c</sub>, about 18 MJ m-2 a day under a clear sky in
	 * early March at 46.5 N.
	 */
	@Test
	public void shortwaveFromCloudTransmissivity() throws Exception {
		SnowSoilSolver1DMain clear = cloudyComponent(1.0);
		SnowSoilSolver1DMain cloudy = cloudyComponent(0.6);
		java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
		long t0 = java.time.LocalDateTime.of(2026, 3, 1, 0, 0).toEpochSecond(java.time.ZoneOffset.UTC);
		double daily = 0.0;
		for (int k = 1; k <= 24; k++) {
			String date = java.time.LocalDateTime.ofEpochSecond(t0 + 3600L * k, 0, java.time.ZoneOffset.UTC).format(f);
			clear.inCurrentDate = date;
			clear.solve();
			cloudy.inCurrentDate = date;
			cloudy.solve();
			assertEquals(0.6 * clear.outShortwaveGlobal, cloudy.outShortwaveGlobal, 1e-9);
			assertEquals(0.6, cloudy.outCloudTransmissivity, 0.0);
			if (k <= 4 || k >= 20) {
				assertEquals("hour " + k, 0.0, clear.outShortwaveGlobal, 0.0);
			}
			if (k >= 10 && k <= 13) {
				assertTrue("hour " + k + ": " + clear.outShortwaveGlobal, clear.outShortwaveGlobal > 400.0);
			}
			daily += clear.outShortwaveGlobal * 3600.0;
		}
		clear.close();
		cloudy.close();
		assertTrue("daily " + daily, daily > 14e6 && daily < 22e6);
	}

	private static SnowSoilSolver1DMain cloudyComponent(double tau) {
		SnowSoilSolver1DMain c = caseComponent("recommended", "Fixed", new RecordingWriter());
		c.inputFiles = "";
		c.inAirTemperature = constant(2.0);
		c.inRelativeHumidity = constant(70.0);
		c.inWindSpeed = constant(2.0);
		c.inPressure = constant(85000.0);
		c.inPrecipitation = constant(0.0);
		c.inCloudTransmissivity = constant(tau);
		c.clearSkyEmissivity = "Konzelmann 1994";
		return c;
	}
}
