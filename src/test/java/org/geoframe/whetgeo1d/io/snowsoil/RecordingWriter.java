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

import java.util.ArrayList;
import java.util.List;

import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;

/**
 * Test-only writer: keeps the header and a few numbers of every step, so that
 * tests can check what the component sends to its writers.
 */
public final class RecordingWriter implements SnowSoilOutputWriter {

	/** One recorded step. */
	public record Step(long epochSeconds, double swe, double depth, int cells, double snowMassClosure,
			double snowEnergyClosure, boolean heatConverged, double soilTopTemperature, double soilInput) {
	}

	public RunHeader header;
	public final List<Step> steps = new ArrayList<>();
	public int opens;
	public boolean closed;

	@Override
	public void open(RunHeader header) {
		this.header = header;
		opens++;
	}

	@Override
	public void write(long epochSeconds, SnowSoilSolver solver, SnowSoilStepResult result) {
		double[] t = solver.soilTemperatures();
		steps.add(new Step(epochSeconds, result.snowWaterEquivalent(), result.snowDepth(), result.snow().size(),
				result.budget().massClosure(), result.budget().energyClosure(), result.heat().newton().converged(),
				t[t.length - 1], result.soilInputMass()));
	}

	@Override
	public void close() {
		closed = true;
	}
}
