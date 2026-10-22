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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What every output of a run carries at the top (VAL-7): code version,
 * configuration, hashes of the inputs, and the soil grid.
 *
 * @param codeVersion   version of the code (git describe or the Maven version)
 * @param configuration name of the configuration, "fidelity" or "recommended"
 * @param attributes    configuration entries and input hashes, written as
 *                      global attributes or header lines, in insertion order
 * @param soilDepth     depth of the soil cell centres below the surface [m],
 *                      index 0 at the bottom, as the solver
 * @param soilThickness thickness of the soil cells [m], index 0 at the bottom
 */
public record RunHeader(String codeVersion, String configuration, Map<String, String> attributes,
		double[] soilDepth, double[] soilThickness) {

	public RunHeader {
		attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
		soilDepth = soilDepth.clone();
		soilThickness = soilThickness.clone();
		if (soilDepth.length != soilThickness.length) {
			throw new IllegalArgumentException("soilDepth and soilThickness must have the same length");
		}
	}
}
