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

import java.nio.file.Path;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.Horizon;
import org.junit.Test;

/**
 * {@link CaseGeoPackage} on the Friuli case ({@code cases/snowsoil/friuli/data/friuli.gpkg},
 * written by its {@code make_case.py}).
 */
public class CaseGeoPackageTest {

	private static final CaseGeoPackage FRIULI = new CaseGeoPackage(
			Path.of("cases/snowsoil/friuli/data/friuli.gpkg"));

	@Test
	public void columnPointAndHorizon() {
		CaseGeoPackage.Column c = FRIULI.column(3);
		assertEquals(3, c.pointId());
		CaseGeoPackage.Point p = FRIULI.point(c.pointId());
		assertEquals("Sauris di sotto", p.name());
		assertEquals(1200.0, p.elevation(), 0.0);
		assertEquals(11.0, p.slope(), 0.0);
		assertEquals(201.0, p.aspect(), 0.0);
		assertEquals(5.0, p.windHeight(), 0.0);
		Horizon h = FRIULI.horizon(3);
		assertEquals(16, h.azimuth().length);
		assertEquals(22.5, h.azimuth()[1], 0.0);
	}

	/** The soil of a column is the same as WHETGEO-1D's CSV inputs it was written from. */
	@Test
	public void soilGridAsTheCsvInputs() throws Exception {
		Path folder = FRIULI.soilGridFolder(FRIULI.column(1));
		SoilGridInputs fromCase;
		try {
			fromCase = SoilGridInputs.read(folder.toString());
		} finally {
			CaseGeoPackage.delete(folder);
		}
		SoilGridInputs fromCsv = SoilGridInputs.read("cases/snowsoil/friuli/grid");
		assertArrayEquals(fromCsv.eta, fromCase.eta, 0.0);
		assertArrayEquals(fromCsv.controlVolume, fromCase.controlVolume, 0.0);
		assertArrayEquals(fromCsv.psi, fromCase.psi, 0.0);
		assertArrayEquals(fromCsv.temperature, fromCase.temperature, 0.0);
		assertArrayEquals(fromCsv.thetaS, fromCase.thetaS, 0.0);
		assertArrayEquals(fromCsv.ks, fromCase.ks, 0.0);
		assertArrayEquals(fromCsv.par1SWRC, fromCase.par1SWRC, 0.0);
		assertArrayEquals(fromCsv.par2SWRC, fromCase.par2SWRC, 0.0);
		assertArrayEquals(fromCsv.parameterID, fromCase.parameterID);
	}

	/** The svf stored by make_case.py (Python) and the one computed here from the same horizon agree. */
	@Test
	public void skyViewFactorFromTheHorizon() {
		for (int id = 1; id <= 4; id++) {
			CaseGeoPackage.Point p = FRIULI.point(id);
			assertEquals("point " + id, p.svf(), FRIULI.horizon(id).skyViewFactor(p.slope(), p.aspect()), 1e-4);
		}
	}

	@Test
	public void skyViewFactorAnalytic() {
		// flat ground under a flat horizon sees the whole sky; a bare slope (1 + cos b)/2
		assertEquals(1.0, Horizon.flat().skyViewFactor(0.0, 0.0), 1e-12);
		assertEquals(0.5 * (1.0 + Math.cos(Math.toRadians(30.0))), Horizon.flat().skyViewFactor(30.0, 120.0), 1e-6);
		// a uniform horizon h on flat ground: cos^2 h
		Horizon ring = new Horizon(new double[] { 0, 90, 180, 270 }, new double[] { 20, 20, 20, 20 });
		assertEquals(Math.pow(Math.cos(Math.toRadians(20.0)), 2), ring.skyViewFactor(0.0, 0.0), 1e-12);
	}
}
