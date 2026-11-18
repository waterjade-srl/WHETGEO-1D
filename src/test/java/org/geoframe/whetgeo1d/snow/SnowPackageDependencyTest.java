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
package org.geoframe.whetgeo1d.snow;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * The snow package is the future standalone snowpack library (specification
 * §6.1; its repository, B9, is still open): it must not depend on the rest of
 * WHETGEO-1D nor on OMS3, so that it can be moved out unchanged.
 */
public class SnowPackageDependencyTest {

	private static final Path SOURCES = Paths.get("src/main/java/org/geoframe/whetgeo1d/snow");

	@Test
	public void noImportsFromWhetgeoOrOms() throws IOException {
		List<String> offending = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				for (String line : Files.readAllLines(file)) {
					String trimmed = line.trim();
					boolean whetgeo = trimmed.matches("import (static )?org\\.geoframe\\.whetgeo1d\\..*")
							&& !trimmed.matches("import (static )?org\\.geoframe\\.whetgeo1d\\.snow\\..*");
					boolean oms = trimmed.matches("import (static )?oms3\\..*");
					if (whetgeo || oms) {
						offending.add(file.getFileName() + ": " + trimmed);
					}
				}
			}
		}
		assertTrue("snow package must stay standalone: " + offending, offending.isEmpty());
	}
}
