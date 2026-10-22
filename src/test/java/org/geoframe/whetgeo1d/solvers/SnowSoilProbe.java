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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import oms3.annotations.Execute;
import oms3.annotations.Finalize;
import oms3.annotations.In;

/**
 * Test-only OMS3 component: connected to the snow+soil component in a .sim,
 * it counts the steps and keeps the largest SWE, and writes them to a file at
 * the end of the run, so that a test can check that the .sim really ran.
 */
public class SnowSoilProbe {

	@In
	public double inSwe;
	@In
	public String file;

	private int steps;
	private double maxSwe;

	@Execute
	public void execute() {
		steps++;
		maxSwe = Math.max(maxSwe, inSwe);
	}

	@Finalize
	public void done() throws IOException {
		Files.writeString(Path.of(file), steps + "," + maxSwe);
	}
}
