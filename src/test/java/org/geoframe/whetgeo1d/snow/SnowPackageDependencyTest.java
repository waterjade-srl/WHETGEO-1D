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
