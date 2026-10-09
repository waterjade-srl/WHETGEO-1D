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
package org.geoframe.whetgeo1d.snow.surface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Expected values: GEOtop's {@code snow_albedo} and {@code Fzen}
 * ({@code snow.cc:893-925}) in python, depths in mm.
 */
public class SnowAlbedoTest {

	private static final SnowAlbedo GEOTOP = new SnowAlbedo(SnowAlbedo.Parameters.geotopDefaults());

	@Test
	public void deepSnowByHand() {
		// age 1.5: F_age = 0.6; visible diffuse 0.9 (1 - 0.2 · 0.6) = 0.792;
		// cos 0.4: f = (3/2.6 - 1)/2 = 1/13, direct 0.792 + 0.4 · 0.208 / 13 = 0.7984
		SnowAlbedo.Albedos a = GEOTOP.albedos(0.5, 1.5, 0.4, 0.2, 0.3);
		assertEquals(0.7984, a.visibleDirect(), 1e-15);
		assertEquals(0.792, a.visibleDiffuse(), 1e-15);
		assertEquals(0.4717692307692307, a.nearInfraredDirect(), 1e-15);
		assertEquals(0.455, a.nearInfraredDiffuse(), 1e-15);
	}

	@Test
	public void thinSnowBlendsWithTheGround() {
		// 5 mm of snow, d_e = 10 mm: w = 0.5 exp(-0.25)
		SnowAlbedo.Albedos a = GEOTOP.albedos(0.005, 0.5, 0.3, 0.2, 0.3);
		assertEquals(0.5978889084974623, a.visibleDirect(), 1e-14);
		assertEquals(0.5907837494171505, a.visibleDiffuse(), 1e-14);
		assertEquals(0.4679148923276818, a.nearInfraredDirect(), 1e-14);
		assertEquals(0.4475615720455385, a.nearInfraredDiffuse(), 1e-14);
	}

	@Test
	public void bareGroundAndFreshSnow() {
		SnowAlbedo.Albedos bare = GEOTOP.albedos(0.0, 3.0, 0.2, 0.2, 0.3);
		assertEquals(0.2, bare.visibleDirect(), 0.0);
		assertEquals(0.3, bare.nearInfraredDiffuse(), 0.0);
		SnowAlbedo.Albedos fresh = GEOTOP.albedos(1.0, 0.0, 0.8, 0.2, 0.3);
		assertEquals(0.9, fresh.visibleDirect(), 0.0);
		assertEquals(0.65, fresh.nearInfraredDirect(), 0.0);
		SnowAlbedo.Albedos old = GEOTOP.albedos(1.0, 1e12, 0.8, 0.2, 0.3);
		assertEquals("infinitely old: α₀ (1 - C)", 0.72, old.visibleDiffuse(), 1e-9);
		assertEquals(0.325, old.nearInfraredDiffuse(), 1e-9);
	}

	@Test
	public void zenithFactor() {
		assertEquals(1.0, SnowAlbedo.zenithFactor(0.0), 0.0);
		assertEquals(0.0, SnowAlbedo.zenithFactor(0.5), 0.0);
		assertEquals(0.0, SnowAlbedo.zenithFactor(1.0), 0.0);
		assertEquals((3.0 / 1.8 - 1.0) / 2.0, SnowAlbedo.zenithFactor(0.2), 1e-15);
	}

	@Test
	public void absorbedAndReflectedSplitTheBandsEqually() {
		SnowAlbedo.Albedos a = GEOTOP.albedos(0.5, 1.5, 0.4, 0.2, 0.3);
		double absorbed = a.absorbed(300.0, 100.0);
		// GEOtop (energy.balance.cc:540): 100 (1 - (0.792 + 0.455)/2) + 300 (1 - (0.7984 + 0.47177)/2)
		assertEquals(147.1246153846154, absorbed, 1e-12);
		assertEquals(400.0, absorbed + a.reflected(300.0, 100.0), 1e-12);
		assertEquals(a.reflected(300.0, 100.0) / 400.0, a.broadband(300.0, 100.0), 1e-15);
	}

	@Test
	public void nivogeoThinSnowWeightDiffers() {
		// NivoGEO-1D Albedo.snowAlbedo(0.2, 5 mm, 10 mm, 0.9, 0.2, 1.5, 0.3, Fzen) = 0.6954524518218473 (MobyGIS k = 2);
		// GEOtop 3.0 (k = 0.5) gives 0.5707116750152695
		SnowAlbedo mobygis = new SnowAlbedo(new SnowAlbedo.Parameters(0.9, 0.65, 0.2, 0.5, 0.010, 2.0));
		double f = SnowAlbedo.zenithFactor(0.3);
		assertEquals(0.6954524518218473, mobygis.bandAlbedo(0.9, 0.2, 1.5, f, 0.005, 0.2), 1e-15);
		assertEquals(0.5707116750152695, GEOTOP.bandAlbedo(0.9, 0.2, 1.5, f, 0.005, 0.2), 1e-15);
	}

	@Test
	public void decreasesWithAgeAndIncreasesWithDepth() {
		double previous = 1.0;
		for (double age = 0.0; age < 20.0; age += 0.5) {
			double a = GEOTOP.albedos(1.0, age, 0.6, 0.2, 0.3).visibleDiffuse();
			assertTrue(a < previous);
			previous = a;
		}
		previous = 0.0;
		for (double depth = 0.0; depth <= 0.01; depth += 0.001) {
			double a = GEOTOP.albedos(depth, 0.0, 0.6, 0.2, 0.3).visibleDiffuse();
			assertTrue(a > previous);
			previous = a;
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNegativeAge() {
		GEOTOP.albedos(1.0, -1.0, 0.5, 0.2, 0.3);
	}
}
