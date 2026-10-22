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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * Runs the example .sim of {@code cases/snowsoil/synthetic_season} with OMS3's
 * own launcher, {@code oms3.CLI -r}, in a child JVM, as a user would. A test
 * probe is added to the .sim to record the steps and the largest SWE.
 *
 * <p>
 * OMS3's DSL needs {@code oms-3.5.12.jar} first on the classpath, because
 * hm-gears carries an incompatible copy of {@code oms3.dsl}; the jar is not a
 * dependency of the project, so the test looks for it in the local Maven
 * repository and is skipped where it is absent.
 */
public class SnowSoilSimFileTest {

	@Test
	public void exampleSimRunsWithOmsLauncher() throws Exception {
		Path oms = Path.of(System.getProperty("user.home"), ".m2", "repository", "edu", "colostate", "omslab", "oms",
				"3.5.12", "oms-3.5.12.jar");
		assumeTrue("oms-3.5.12.jar not in the local Maven repository", Files.isRegularFile(oms));

		Path caseDir = Path.of(SnowSoilSolver1DMainTest.CASE).toAbsolutePath();
		String sim = Files.readString(caseDir.resolve("snowsoil_synthetic_season.sim"));
		Path work = Files.createTempDirectory("snowsoil-sim");
		Path probeFile = work.resolve("probe.txt");
		String probed = sim
				.replace("\"snowsoil\"     \"org.geoframe.whetgeo1d.solvers.SnowSoilSolver1DMain\"",
						"\"snowsoil\"     \"org.geoframe.whetgeo1d.solvers.SnowSoilSolver1DMain\"\n"
								+ "            \"probe\"        \"org.geoframe.whetgeo1d.solvers.SnowSoilProbe\"")
				.replace("        parameter {", "        parameter {\n            \"probe.file\" \"" + probeFile + "\"")
				.replace("        connect {",
						"        connect {\n            \"snowsoil.outSnowWaterEquivalent\" \"probe.inSwe\"");
		assertFalse("the probe was not added", probed.equals(sim));
		Path simFile = work.resolve("probed.sim");
		Files.writeString(simFile, probed);

		String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
		String classpath = oms + File.pathSeparator + System.getProperty("java.class.path");
		Path log = work.resolve("run.log");
		Process p = new ProcessBuilder(List.of(java, "-Doms.prj=" + caseDir, "-cp", classpath, "oms3.CLI", "-r",
				simFile.toString())).redirectErrorStream(true).redirectOutput(log.toFile()).start();
		assertTrue("the .sim did not finish in 10 minutes", p.waitFor(10, TimeUnit.MINUTES));
		String output = Files.readString(log);
		assertEquals(output, 0, p.exitValue());
		assertFalse(output, output.contains(">>>> Error"));
		assertTrue("no probe output; log:\n" + output, Files.isRegularFile(probeFile));
		String[] probe = Files.readString(probeFile).trim().split(",");
		int steps = Integer.parseInt(probe[0]);
		double maxSwe = Double.parseDouble(probe[1]);
		assertTrue("steps " + steps, steps >= 2800);
		assertTrue("max SWE " + maxSwe, maxSwe > 100.0);
	}
}
