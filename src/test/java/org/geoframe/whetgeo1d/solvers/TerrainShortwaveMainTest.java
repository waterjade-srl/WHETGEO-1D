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
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.Horizon;
import org.junit.Test;

/**
 * {@link TerrainShortwaveMain}: the shortwave on the terrain of a station, as
 * GEOtop 3.0 for a point.
 */
public class TerrainShortwaveMainTest {

	private static final double LATITUDE = 46.47;
	private static final double LONGITUDE = 12.68;

	/** One step for one station: beam, diffuse, cosine, τ. */
	private static double[] step(double slope, double aspect, double sky, String horizon, String date, double tau,
			double global) throws Exception {
		TerrainShortwaveMain c = new TerrainShortwaveMain();
		c.latitude = LATITUDE;
		c.longitude = LONGITUDE;
		c.elevation = 1400.0;
		c.slope = slope;
		c.aspect = aspect;
		c.skyViewFactor = sky;
		c.horizonFile = horizon;
		c.inCurrentDate = date;
		c.inAirTemperature = series(-2.0);
		c.inRelativeHumidity = series(70.0);
		c.inCloudTransmissivity = Double.isNaN(tau) ? null : series(tau);
		c.inShortwaveGlobal = Double.isNaN(global) ? null : series(global);
		c.process();
		return new double[] { c.outShortwaveDirect.get(1)[0], c.outShortwaveDiffuse.get(1)[0],
				c.outCosIncidence.get(1)[0], c.outCloudTransmissivity.get(1)[0] };
	}

	private static Map<Integer, double[]> series(double v) {
		Map<Integer, double[]> m = new HashMap<>();
		m.put(1, new double[] { v });
		return m;
	}

	@Test
	public void southFacesTheWinterSunNorthTurnsAway() throws Exception {
		// noon in UTC+1 is about 11:10 UTC at 12.7 E: the step ending at 12:00 UTC
		double[] flat = step(0, 0, 1, "", "2026-01-15 12:00", 0.8, Double.NaN);
		double[] south = step(30, 180, 1, "", "2026-01-15 12:00", 0.8, Double.NaN);
		double[] north = step(30, 0, 1, "", "2026-01-15 12:00", 0.8, Double.NaN);
		assertTrue(flat[0] > 100.0);
		assertTrue(south[0] > 1.5 * flat[0]);
		assertTrue(north[0] < 0.3 * flat[0]);
		// the diffuse does not depend on the slope, only on V
		assertEquals(flat[1], south[1], 1e-9);
		assertEquals(flat[1], north[1], 1e-9);
	}

	@Test
	public void skyViewFactorScalesOnlyTheDiffuse() throws Exception {
		double[] open = step(0, 0, 1, "", "2026-01-15 12:00", 0.6, Double.NaN);
		double[] valley = step(0, 0, 0.8, "", "2026-01-15 12:00", 0.6, Double.NaN);
		assertEquals(open[0], valley[0], 1e-9);
		assertEquals(0.8 * open[1], valley[1], 1e-9);
	}

	@Test
	public void horizonHidesTheBeam() throws Exception {
		Path table = Files.createTempFile("horizon", ".csv");
		Files.writeString(table, "@T,table\nCreated,test\n@H,azimuth,value_1\nType,double,double\nFormat,,\n"
				+ ",90,40\n,180,40\n,270,40\n,360,40\n");
		double[] shaded = step(0, 0, 1, table.toString(), "2026-01-15 12:00", 0.8, Double.NaN);
		double[] open = step(0, 0, 1, "", "2026-01-15 12:00", 0.8, Double.NaN);
		assertEquals(0.0, shaded[0], 0.0);
		assertEquals(open[1], shaded[1], 1e-9);
		Files.delete(table);
	}

	@Test
	public void measuredGlobalIsKeptOnFlatGround() throws Exception {
		double[] r = step(0, 0, 1, "", "2026-01-15 12:00", Double.NaN, 300.0);
		assertEquals(300.0, r[0] + r[1], 1e-6 * 300.0);
		assertTrue(r[3] > 0.0 && r[3] <= 1.0);
		// at night nothing
		double[] night = step(0, 0, 1, "", "2026-01-15 22:00", Double.NaN, 0.0);
		assertEquals(0.0, night[0] + night[1], 0.0);
	}

	@Test
	public void readsTheHorizonOfOneStation() throws Exception {
		Path table = Files.createTempFile("horizon", ".csv");
		Files.writeString(table, "@T,table\n@H,azimuth,value_1,value_7\nType,double,double,double\n"
				+ ",180,5,30\n,90,10,20\n");
		Horizon h = Horizon.readTable(table, 7);
		assertEquals(90.0, h.azimuth()[0], 0.0);
		assertEquals(20.0, h.height()[0], 0.0);
		assertEquals(25.0, h.heightAt(135.0), 1e-12);
		Files.delete(table);
	}
}
