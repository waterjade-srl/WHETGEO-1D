package org.geoframe.whetgeo1d.core.snowsoil.verification;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.geoframe.closureequation.closureequation.Parameters;
import org.junit.Test;

/** Readers of GEOtop's soil file and the solar geometry of the GEOtop comparisons. */
public class GeotopInputTest {

	/** GEOtop units become SI, equal layers share an id, cell 0 is the deepest. */
	@Test
	public void soilFileIsReadInSiUnitsBottomFirst() throws Exception {
		Path file = Files.createTempFile("soil", ".txt");
		Files.writeString(file, """
				Dz,z.tot,Kh,Kv,vwc_r,vwc_s,VG_alpha,VG_n,SS,thermcond,thermcap
				10,10,0.17,0.17,0.04,0.84,0.0127,1.2,1e-07,0.25,2600000
				10,20,0.17,0.17,0.04,0.84,0.0127,1.2,1e-07,0.25,2600000
				30,50,0.012,0.012,0.065,0.41,0.0075,1.89,1e-07,1.62,2e+06
				""");
		LayeredSoil soil = LayeredSoil.readGeotop(file, 0.0, 268.35);
		Files.delete(file);
		assertEquals(3, soil.cells());
		assertEquals(2, soil.types().size());
		assertArrayEquals(new int[] { 1, 0, 0 }, soil.parameterIds());
		assertArrayEquals(new double[] { 0.03, 0.01, 0.01 }, soil.controlVolume(), 1e-15);
		assertArrayEquals(new double[] { 0.015, 0.035, 0.045 }, soil.z(), 1e-15);
		assertArrayEquals(new double[] { 0.015, 0.02, 0.01, 0.005 }, soil.spaceDeltaZ(), 1e-15);
		assertEquals(0.035, soil.centreDepth(0), 1e-15);
		assertEquals(0.005, soil.centreDepth(2), 1e-15);
		Parameters p = soil.parameters();
		assertEquals(12.7, p.par2[0], 1e-12);
		assertEquals(1.7e-4, p.kappaSaturation[0], 1e-18);
		assertEquals(1e-4 / 9810.0, p.alphaSpecificStorage[0], 1e-20);
		assertEquals(2.6e6, p.soilParticlesDensity[0] * p.specificThermalCapacitySoilParticles[0], 1e-6);
		assertEquals(1.62, p.thermalConductivitySoilParticles[1], 0.0);
	}

	/** Evaporation from the top cell, transpiration over the root zone by thickness, as GEOtop's root. */
	@Test
	public void evapotranspirationIsSpreadLikeGeotop() {
		double[] none = { 0.0 };
		GeotopPointForcing f = new GeotopPointForcing(new LocalDateTime[1], none, none, none, none, none, none, none,
				none, none, none, none, none, new double[] { 0.4 }, new double[] { 1.0 });
		// cells from the bottom: 0.3, 0.1, 0.05, 0.05 m; roots to 0.15 m
		LayeredSoil soil = new LayeredSoil(new double[] { 0.05, 0.05, 0.1, 0.3 }, new int[4],
				java.util.List.of(new LayeredSoil.Layer(1e-5, 0.05, 0.4, 5.0, 1.5, 1e-4, 2.0, 2e6)), 0.0, 270.0);
		assertArrayEquals(new double[] { 0.0, 1.0 / 3.0, 1.0 / 3.0, 0.4 + 1.0 / 3.0 }, f.sinks(0, soil, 0.15), 1e-15);
	}

	/** At the equinox, at local solar noon, the zenith angle is the latitude (within the declination of the day). */
	@Test
	public void solarNoonAtTheEquinox() {
		// 150° W in a zone at -10 h: clock noon is solar noon up to the equation of time (about 7 min on 21/3)
		double cos = GeotopPointForcing.cosZenith(LocalDateTime.of(2001, 3, 21, 12, 7), 68.93, -150.0, -10.0);
		assertEquals(Math.cos(Math.toRadians(68.93)), cos, 0.01);
		double night = GeotopPointForcing.cosZenith(LocalDateTime.of(2001, 12, 21, 0, 0), 68.93, -150.0, -10.0);
		assertEquals(-Math.cos(Math.toRadians(68.93 - 23.44)) * 1.0, night, 0.02);
	}
}
