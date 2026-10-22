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
package org.geoframe.whetgeo1d.io.snowsoil;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;
import org.geoframe.whetgeo1d.core.snowsoil.SoilHeatSetup;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.junit.BeforeClass;
import org.junit.Test;

import ucar.ma2.Array;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFiles;

/**
 * Writes a short synthetic season (the case of document 009, frozen soil with
 * Richards, fidelity configuration) with both writers, reads the files back
 * and checks them against what the solver returned at each step. The run is
 * also left in {@code target/snowsoil-output-sample}, the input of the
 * notebook {@code Jupyter_Notebook/5_SnowSoil_Outputs.ipynb}.
 */
public class SnowSoilOutputWritersTest {

	private static final int MAX_CELLS = 12;
	private static final int DAYS = 20;
	private static final long START = Instant.parse("2025-11-01T00:00:00Z").getEpochSecond();
	private static final Path SAMPLE = Path.of("target", "snowsoil-output-sample");

	/** What the solver gave at each step. */
	private record Step(long time, double hs, double swe, double melt, double vapour, double snowRunoff,
			List<SnowCell> snow, double[] soilT, double[] soilIce, double soilStorage) {
	}

	private static final List<Step> STEPS = new ArrayList<>();
	private static SnowEquationOfState eos;
	private static RunHeader header;
	private static double[] controlVolume;

	@BeforeClass
	public static void run() throws IOException {
		SyntheticSeason season = new SyntheticSeason(DAYS, 3600.0);
		SnowSoilSolver solver = season.solverWithFrozenSoil(Configuration.FIDELITY);
		UniformSoil soil = season.wetSoil();
		SoilHeatSetup setup = soil.freezingSetup();
		eos = new SnowEquationOfState(SyntheticSeason.ALPHA);
		controlVolume = setup.controlVolume();

		double[] depth = new double[setup.cells()];
		for (int i = 0; i < depth.length; i++) {
			depth[i] = soil.depth() - setup.z()[i];
		}
		Map<String, String> attributes = new LinkedHashMap<>();
		attributes.put("case", "SyntheticSeason, document 009, " + DAYS + " days, hourly");
		attributes.put("snow.conductivity", "Cosenza");
		attributes.put("sha256.forcing", "0".repeat(64));
		header = new RunHeader("test-" + SnowSoilOutputWritersTest.class.getSimpleName(), "fidelity", attributes,
				depth, setup.controlVolume());

		OutputOptions options = OutputOptions.of(eos, MAX_CELLS).withSoilWaterContent(SoilWaterContent.vanGenuchten(setup));
		Files.createDirectories(SAMPLE);
		List<SnowSoilOutputWriter> writers = List.of(new SnowSoilNetcdfWriter(SAMPLE.resolve("snowsoil.nc"), options),
				new SnowSoilNetcdfWriter(SAMPLE.resolve("snowsoil_6h.nc"), options.everySeconds(6 * 3600)),
				new SnowSoilCsvWriter(SAMPLE.resolve("csv"), options),
				new SnowSoilCsvWriter(SAMPLE.resolve("csv_3h"), options.everySeconds(3 * 3600)));
		for (SnowSoilOutputWriter w : writers) {
			w.open(header);
		}
		for (int k = 0; k < season.steps(); k++) {
			double[] t = solver.soilTemperatures();
			SnowSoilStepResult r = solver.step(season.forcing(k * season.timeDelta(), t[t.length - 1]));
			long time = START + (k + 1) * (long) season.timeDelta();
			for (SnowSoilOutputWriter w : writers) {
				w.write(time, solver, r);
			}
			STEPS.add(new Step(time, r.snowDepth(), r.snowWaterEquivalent(), r.melt(), r.budget().vapour(),
					r.budget().toSoilMass(), r.snow(), solver.soilTemperatures(), solver.soilIce(),
					solver.soilWaterStorage()));
		}
		for (SnowSoilOutputWriter w : writers) {
			w.close();
		}
	}

	private static double[] values(NetcdfFile nc, String name) throws IOException {
		return (double[]) nc.findVariable(name).read().get1DJavaArray(double.class);
	}

	@Test
	public void theRunHasSnow() {
		assertTrue(STEPS.stream().anyMatch(s -> s.snow().size() > 1));
		assertTrue(STEPS.stream().anyMatch(s -> s.snow().isEmpty()));
	}

	@Test
	public void netcdfHeaderAndGrid() throws IOException {
		try (NetcdfFile nc = NetcdfFiles.open(SAMPLE.resolve("snowsoil.nc").toString())) {
			assertEquals(header.codeVersion(), nc.findGlobalAttribute("code_version").getStringValue());
			assertEquals("fidelity", nc.findGlobalAttribute("configuration").getStringValue());
			assertEquals("0".repeat(64), nc.findGlobalAttribute("sha256.forcing").getStringValue());
			assertEquals("Cosenza", nc.findGlobalAttribute("snow.conductivity").getStringValue());
			assertEquals(STEPS.size(), nc.findDimension("time").getLength());
			assertEquals(MAX_CELLS, nc.findDimension("snowCell").getLength());
			assertEquals(header.soilDepth().length, nc.findDimension("depth").getLength());
			double[] eta = values(nc, "depth");
			double[] faces = values(nc, "dualDepth");
			for (int i = 0; i < eta.length; i++) {
				assertEquals(-header.soilDepth()[i], eta[i], 0.0);
				assertTrue(faces[i] < eta[i] && eta[i] < faces[i + 1]);
			}
			assertEquals(0.0, faces[faces.length - 1], 0.0);
			assertEquals(-2.0, faces[0], 1e-12);
		}
	}

	@Test
	public void netcdfEveryStepMatchesTheSolver() throws IOException {
		try (NetcdfFile nc = NetcdfFiles.open(SAMPLE.resolve("snowsoil.nc").toString())) {
			double[] time = values(nc, "time");
			double[] hs = values(nc, "snowDepth");
			double[] swe = values(nc, "snowWaterEquivalent");
			double[] cells = values(nc, "snowCells");
			double[] storage = values(nc, "soilWaterStorage");
			Array thickness = nc.findVariable("snowCellThickness").read();
			Array top = nc.findVariable("snowCellEtaTop").read();
			Array temperature = nc.findVariable("snowCellTemperature").read();
			Array ice = nc.findVariable("snowCellIce").read();
			Array soilT = nc.findVariable("T").read();
			Array theta = nc.findVariable("theta").read();
			Array thetaIce = nc.findVariable("thetaIce").read();
			int nSoil = header.soilDepth().length;
			for (int k = 0; k < STEPS.size(); k++) {
				Step s = STEPS.get(k);
				assertEquals(s.time(), time[k], 0.0);
				assertEquals(s.hs(), hs[k], 0.0);
				assertEquals(s.swe(), swe[k], 0.0);
				assertEquals(s.snow().size(), cells[k], 0.0);
				assertEquals(s.soilStorage(), storage[k], 0.0);
				for (int c = 0; c < MAX_CELLS; c++) {
					int idx = k * MAX_CELLS + c;
					if (c < s.snow().size()) {
						SnowCell cell = s.snow().get(c);
						assertEquals(cell.thickness(), thickness.getDouble(idx), 0.0);
						assertEquals(cell.iceMass(), ice.getDouble(idx), 0.0);
						assertEquals(eos.temperature(cell), temperature.getDouble(idx), 0.0);
					} else {
						assertTrue(Double.isNaN(thickness.getDouble(idx)));
						assertTrue(Double.isNaN(temperature.getDouble(idx)));
					}
				}
				if (!s.snow().isEmpty()) {
					assertEquals(s.hs(), top.getDouble(k * MAX_CELLS + s.snow().size() - 1), 1e-12);
				}
				double water = 0.0;
				for (int i = 0; i < nSoil; i++) {
					assertEquals(s.soilT()[i], soilT.getDouble(k * nSoil + i), 0.0);
					assertEquals(s.soilIce()[i], thetaIce.getDouble(k * nSoil + i), 0.0);
					water += (theta.getDouble(k * nSoil + i) + thetaIce.getDouble(k * nSoil + i)) * controlVolume[i]
							* 1000.0;
				}
				// θ_w + θ_i over the column is the water the solver stores (no water above saturation here)
				assertEquals(s.soilStorage(), water, 1e-9 * s.soilStorage());
			}
		}
	}

	@Test
	public void netcdfAggregatesBetweenRecords() throws IOException {
		try (NetcdfFile nc = NetcdfFiles.open(SAMPLE.resolve("snowsoil_6h.nc").toString())) {
			double[] time = values(nc, "time");
			double[] melt = values(nc, "melt");
			double[] vapour = values(nc, "vapour");
			double[] runoff = values(nc, "snowRunoff");
			double[] steps = values(nc, "steps");
			double[] hs = values(nc, "snowDepth");
			assertEquals(DAYS * 4, time.length);
			int k = 0;
			for (int r = 0; r < time.length; r++) {
				double m = 0.0;
				double v = 0.0;
				double q = 0.0;
				int n = 0;
				while (STEPS.get(k).time() < time[r]) {
					m += STEPS.get(k).melt();
					v += STEPS.get(k).vapour();
					q += STEPS.get(k).snowRunoff();
					k++;
					n++;
				}
				Step last = STEPS.get(k);
				m += last.melt();
				v += last.vapour();
				q += last.snowRunoff();
				n++;
				assertEquals(last.time(), time[r], 0.0);
				assertEquals(6, n);
				assertEquals(n, steps[r], 0.0);
				assertEquals(m, melt[r], 1e-12);
				assertEquals(v, vapour[r], 1e-12);
				assertEquals(q, runoff[r], 1e-12);
				assertEquals(last.hs(), hs[r], 0.0);
				k++;
			}
		}
	}

	private static List<String[]> csv(Path file, List<String> comments) throws IOException {
		List<String[]> rows = new ArrayList<>();
		for (String line : Files.readAllLines(file)) {
			if (line.startsWith("#")) {
				comments.add(line);
			} else {
				rows.add(line.split(",", -1));
			}
		}
		return rows;
	}

	@Test
	public void csvPointFileMatchesTheSolver() throws IOException {
		List<String> comments = new ArrayList<>();
		List<String[]> rows = csv(SAMPLE.resolve("csv/point.csv"), comments);
		assertEquals("# code_version: " + header.codeVersion(), comments.get(0));
		assertEquals("# configuration: fidelity", comments.get(1));
		assertTrue(comments.contains("# sha256.forcing: " + "0".repeat(64)));
		List<String> h = Arrays.asList(rows.get(0));
		assertEquals("Date12[DDMMYYYYhhmm]", h.get(0));
		int hs = h.indexOf("snow_depth[mm]");
		int swe = h.indexOf("snow_water_equivalent[mm]");
		int ts = h.indexOf("snow_temperature[C]");
		int runoff = h.indexOf("snowRunoff[mm]");
		assertTrue(hs > 0 && swe > 0 && ts > 0 && runoff > 0);
		assertEquals(STEPS.size() + 1, rows.size());
		assertEquals("01/11/2025 01:00", rows.get(1)[0]);
		for (int k = 0; k < STEPS.size(); k++) {
			String[] r = rows.get(k + 1);
			Step s = STEPS.get(k);
			assertEquals(h.size(), r.length);
			assertEquals(s.time(), Long.parseLong(r[1]));
			assertEquals(1e3 * s.hs(), Double.parseDouble(r[hs]), 0.0);
			assertEquals(s.swe(), Double.parseDouble(r[swe]), 0.0);
			assertEquals(s.snowRunoff(), Double.parseDouble(r[runoff]), 0.0);
			if (s.snow().isEmpty()) {
				assertEquals(SnowSoilCsvWriter.NO_VALUE, Double.parseDouble(r[ts]), 0.0);
			}
		}
	}

	@Test
	public void csvProfiles() throws IOException {
		List<String> comments = new ArrayList<>();
		List<String[]> soil = csv(SAMPLE.resolve("csv/soilTz.csv"), comments);
		assertTrue(comments.get(comments.size() - 1).startsWith("# soil temperature [C]"));
		int n = header.soilDepth().length;
		// columns from the surface down, depth in mm
		assertEquals(1e3 * header.soilDepth()[n - 1], Double.parseDouble(soil.get(0)[2]), 1e-6);
		assertEquals(1e3 * header.soilDepth()[0], Double.parseDouble(soil.get(0)[n + 1]), 1e-6);
		List<String[]> layers = csv(SAMPLE.resolve("csv/snowDepth.csv"), new ArrayList<>());
		assertEquals("L1", layers.get(0)[2]);
		assertEquals(MAX_CELLS + 2, layers.get(0).length);
		for (int k = 0; k < STEPS.size(); k++) {
			Step s = STEPS.get(k);
			assertEquals(s.soilT()[n - 1] - 273.15, Double.parseDouble(soil.get(k + 1)[2]), 1e-9);
			String[] r = layers.get(k + 1);
			for (int c = 0; c < MAX_CELLS; c++) {
				double expected = c < s.snow().size() ? 1e3 * s.snow().get(c).thickness() : SnowSoilCsvWriter.NO_VALUE;
				assertEquals(expected, Double.parseDouble(r[c + 2]), 0.0);
			}
		}
		List<String[]> coarse = csv(SAMPLE.resolve("csv_3h/point.csv"), new ArrayList<>());
		assertEquals(DAYS * 8 + 1, coarse.size());
	}

	@Test
	public void recordBoundaries() {
		assertTrue(StepAccumulator.closesRecord(Long.MIN_VALUE, 7200, 3600));
		assertFalse(StepAccumulator.closesRecord(Long.MIN_VALUE, 900, 3600));
		assertFalse(StepAccumulator.closesRecord(900, 1800, 3600));
		assertTrue(StepAccumulator.closesRecord(1800, 3700, 3600));
		assertTrue(StepAccumulator.closesRecord(1800, 1900, 0));
	}

	@Test
	public void namesAndFaces() {
		assertEquals("sha256.forcing", SnowSoilNetcdfWriter.netcdfName("sha256.forcing"));
		assertEquals("input_file_name", SnowSoilNetcdfWriter.netcdfName("input file/name"));
		assertEquals("_1st", SnowSoilNetcdfWriter.netcdfName("1st"));
		assertArrayEquals(new double[] { -0.3, -0.1, 0.0 }, SnowSoilNetcdfWriter.faces(new double[] { 0.2, 0.1 }),
				1e-15);
	}

	@Test
	public void tooManySnowCellsIsAnError() throws IOException {
		SyntheticSeason season = new SyntheticSeason(3, 3600.0);
		SnowSoilSolver solver = season.solver(Configuration.FIDELITY);
		Path dir = Files.createTempDirectory("snowsoil-out");
		OutputOptions options = OutputOptions.of(eos, 1);
		try (SnowSoilNetcdfWriter w = new SnowSoilNetcdfWriter(dir.resolve("x.nc"), options)) {
			w.open(header);
			IOException e = assertThrows(IOException.class, () -> {
				for (int k = 0; k < season.steps(); k++) {
					double[] t = solver.soilTemperatures();
					w.write(START + 3600L * (k + 1), solver,
							solver.step(season.forcing(k * season.timeDelta(), t[t.length - 1])));
				}
			});
			assertNotNull(e.getMessage());
			assertTrue(e.getMessage().contains("maxSnowCells"));
		}
	}

	@Test
	public void hashesAsSha256sum() throws IOException {
		Path f = Files.createTempFile("hash", ".txt");
		Files.writeString(f, "abc");
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", InputHashes.sha256(f));
		Map<String, String> a = InputHashes.attributes(Map.of("forcing", f));
		assertEquals(f.toString(), a.get("input.forcing"));
	}
}
