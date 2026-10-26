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
 * {@link CaseGeoPackage} on the GeoPackages of two cases of the repository,
 * written by {@code tools/geotop2case} from GEOtop 3.0's official tests
 * ({@code make_case.sh} of each case): Matsch_P2_Ref_007 (a slope, no point
 * horizon) and ARF_1D (50 soil layers).
 */
public class CaseGeoPackageTest {

	private static final String MATSCH = "cases/snowsoil/matsch_p2_ref_007/";
	private static final String ARF = "cases/snowsoil/arf_1d/";

	private static CaseGeoPackage file(String folder, String name) {
		return new CaseGeoPackage(Path.of(folder + "data/" + name + ".gpkg"));
	}

	@Test
	public void columnPointAndHorizon() {
		CaseGeoPackage matsch = file(MATSCH, "matsch_p2_ref_007");
		CaseGeoPackage.Column c = matsch.column(1);
		assertEquals(1, c.pointId());
		CaseGeoPackage.Point p = matsch.point(c.pointId());
		assertEquals("Matsch P2", p.name());
		assertEquals(1549.0, p.elevation(), 0.0);
		assertEquals(21.4, p.slope(), 0.0);
		assertEquals(221.6, p.aspect(), 0.0);
		assertEquals(2.5, p.windHeight(), 0.0);
		assertEquals(2.0, p.temperatureHeight(), 0.0);
		// GEOtop's run has no point horizon file: flat
		assertEquals(1, matsch.horizon(1).azimuth().length);
		assertEquals(0.0, matsch.horizon(1).height()[0], 0.0);
	}

	/** The soil of a column is the one of the case's hand-written CSV inputs, to the digits printed there. */
	@Test
	public void soilGridAsTheCsvInputs() throws Exception {
		assertSameSoil(file(MATSCH, "matsch_p2_ref_007"), MATSCH + "grid");
		assertSameSoil(file(ARF, "arf_1d"), ARF + "grid");
	}

	private static void assertSameSoil(CaseGeoPackage file, String csvFolder) throws Exception {
		Path folder = file.soilGridFolder(file.column(1));
		SoilGridInputs fromCase;
		try {
			fromCase = SoilGridInputs.read(folder.toString());
		} finally {
			CaseGeoPackage.delete(folder);
		}
		SoilGridInputs fromCsv = SoilGridInputs.read(csvFolder);
		assertArrayEquals(fromCsv.eta, fromCase.eta, 1e-6);
		assertArrayEquals(fromCsv.controlVolume, fromCase.controlVolume, 1e-6);
		assertArrayEquals(fromCsv.psi, fromCase.psi, 1e-8);
		assertArrayEquals(fromCsv.temperature, fromCase.temperature, 1e-9);
		// parameters by cell: the CSV inputs may repeat a set that the GeoPackage holds once
		assertArrayEquals(byCell(fromCsv, fromCsv.thetaS), byCell(fromCase, fromCase.thetaS), 0.0);
		assertArrayEquals(byCell(fromCsv, fromCsv.thetaR), byCell(fromCase, fromCase.thetaR), 0.0);
		assertArrayEquals(byCell(fromCsv, fromCsv.ks), byCell(fromCase, fromCase.ks), 1e-15);
		assertArrayEquals(byCell(fromCsv, fromCsv.par1SWRC), byCell(fromCase, fromCase.par1SWRC), 0.0);
		assertArrayEquals(byCell(fromCsv, fromCsv.par2SWRC), byCell(fromCase, fromCase.par2SWRC), 0.0);
		assertArrayEquals(byCell(fromCsv, fromCsv.soilParticlesThermalConductivity),
				byCell(fromCase, fromCase.soilParticlesThermalConductivity), 0.0);
	}

	private static double[] byCell(SoilGridInputs g, double[] bySet) {
		double[] v = new double[g.parameterID.length];
		for (int i = 0; i < v.length; i++) {
			v[i] = g.parameterID[i] > 0 ? bySet[g.parameterID[i]] : Double.NaN;
		}
		return v;
	}

	/** The svf stored by geotop2case (Python) and the one computed here from the same horizon agree. */
	@Test
	public void skyViewFactorFromTheHorizon() {
		for (CaseGeoPackage f : new CaseGeoPackage[] { file(MATSCH, "matsch_p2_ref_007"), file(ARF, "arf_1d") }) {
			CaseGeoPackage.Point p = f.point(1);
			assertEquals(f.file().toString(), p.svf(), f.horizon(1).skyViewFactor(p.slope(), p.aspect()), 1e-4);
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
