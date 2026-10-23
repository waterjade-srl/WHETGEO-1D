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
package org.geoframe.whetgeo1d.core.snowsoil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.geoframe.whetgeo1d.snow.SnowCell;
import org.junit.Test;

/** SNOW-7: partition of the net shortwave among the snow cells and the top soil cell. */
public class ShortwavePenetrationTest {

	private static SnowCell cell(double density, double thickness) {
		return new SnowCell(density * thickness, 0.0, thickness, -1e5);
	}

	/** Transcription of GEOtop's rad_snow_absorption (radiation.cc:582-613), top layer first, Dzl in mm. */
	private static double[] geotop(double[] dzMm, double[] rho, double r) {
		int lnum = dzMm.length;
		double[] frac = new double[lnum + 2];
		double res = r;
		double z = 0.0;
		for (int m = 1; m <= lnum; m++) {
			z += 0.001 * dzMm[m - 1];
			double k = rho[m - 1] / 3.0 + 50.0;
			frac[m] = res - r * Math.exp(-k * z);
			res = r * Math.exp(-k * z);
		}
		frac[lnum + 1] = res;
		return frac;
	}

	@Test
	public void geotopMatchesTheTranscription() {
		// bottom first: dense bottom, light top
		List<SnowCell> snow = List.of(cell(320.0, 0.08), cell(250.0, 0.05), cell(150.0, 0.02));
		double[] ours = ShortwavePenetration.GEOTOP.absorbed(snow, 100.0);
		double[] theirs = geotop(new double[] { 20.0, 50.0, 80.0 }, new double[] { 150.0, 250.0, 320.0 }, 100.0);
		for (int j = 0; j < 3; j++) {
			assertEquals("cell " + j, theirs[3 - j], ours[j], 1e-12);
		}
		assertEquals(theirs[4], ours[3], 1e-12);
	}

	@Test
	public void bothCloseOnTheNetShortwave() {
		List<SnowCell> snow = List.of(cell(320.0, 0.08), cell(250.0, 0.05), cell(150.0, 0.02));
		for (ShortwavePenetration p : List.of(ShortwavePenetration.GEOTOP, ShortwavePenetration.COMPOUNDED)) {
			double sum = 0.0;
			for (double a : p.absorbed(snow, 80.0)) {
				sum += a;
			}
			assertEquals(p.name(), 80.0, sum, 1e-12);
		}
	}

	/** Light snow under a dense top: GEOtop's transmission can rise downwards, the compounded one cannot. */
	@Test
	public void compoundedNeverNegativeWhereGeotopIs() {
		List<SnowCell> snow = List.of(cell(50.0, 0.01), cell(400.0, 0.01));
		double[] geotop = ShortwavePenetration.GEOTOP.absorbed(snow, 100.0);
		double[] compounded = ShortwavePenetration.COMPOUNDED.absorbed(snow, 100.0);
		assertTrue("GEOtop's lower cell " + geotop[0], geotop[0] < 0.0);
		for (double a : compounded) {
			assertTrue(a >= 0.0);
		}
	}

	@Test
	public void oneCellOrNoneKeepsItAtTheSurface() {
		assertNull(ShortwavePenetration.GEOTOP.absorbed(List.of(cell(200.0, 0.05)), 100.0));
		assertNull(ShortwavePenetration.COMPOUNDED.absorbed(List.of(), 100.0));
		assertNull(ShortwavePenetration.NONE.absorbed(List.of(cell(200.0, 0.05), cell(200.0, 0.05)), 100.0));
	}
}
