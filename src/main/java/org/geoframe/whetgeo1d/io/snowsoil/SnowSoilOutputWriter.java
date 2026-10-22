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

import java.io.IOException;

import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;

/**
 * Where the OMS3 snow+soil component sends what a run produces: the header
 * once, then one record per output step. The component owns the solver and
 * the time loop; a writer only reads the solver and the result of the step,
 * and never changes them.
 */
public interface SnowSoilOutputWriter extends AutoCloseable {

	/** Called once, before the first {@link #write}. */
	void open(RunHeader header) throws IOException;

	/**
	 * One output step.
	 *
	 * @param epochSeconds end of the step, seconds since 1970-01-01 UTC
	 * @param solver       the column after the step
	 * @param result       what the step returned
	 */
	void write(long epochSeconds, SnowSoilSolver solver, SnowSoilStepResult result) throws IOException;

	/**
	 * The bare-soil surface fluxes of the step about to be written, when the
	 * component computes them (energy-balance top, no snow); null otherwise.
	 * Called before {@link #write}, with the temperature of the surface [K];
	 * writers that do not report them ignore it.
	 */
	default void bareSoilSurface(SurfaceFluxes fluxes, double surfaceTemperature) {
	}

	@Override
	void close() throws IOException;
}
