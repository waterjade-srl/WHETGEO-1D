package org.geoframe.whetgeo1d.snow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Reference values computed by hand from the published formulas; for the three
 * GEOtop options they coincide with {@code k_thermal} of GEOtop 3.0.
 */
public class SnowThermalConductivityTest {

	// 300 kg/m3 of dry snow: theta_i = 300/917
	private static final double ICE = 300.0 / 917.0;

	@Test
	public void sturm1997() {
		assertEquals(0.138 - 0.303 + 3.233 * 0.09, SnowThermalConductivity.STURM_1997.conductivity(0.0, ICE), 1e-12);
		// light snow, first branch: 100 kg/m3
		assertEquals(0.023 + 0.0234, SnowThermalConductivity.STURM_1997.conductivity(0.0, 100.0 / 917.0), 1e-12);
	}

	@Test
	public void jordan1991() {
		double expected = 0.023 + (7.75e-5 * 300 + 1.105e-6 * 90000) * (2.290 - 0.023);
		assertEquals(expected, SnowThermalConductivity.JORDAN_1991.conductivity(0.0, ICE), 1e-12);
	}

	@Test
	public void calonne2011() {
		assertEquals(0.225 - 0.0369 + 0.024, SnowThermalConductivity.CALONNE_2011.conductivity(0.0, ICE), 1e-12);
	}

	@Test
	public void cosenza() {
		double liquid = 0.05;
		double root = liquid * Math.sqrt(0.567) + ICE * Math.sqrt(2.290) + (1 - liquid - ICE) * Math.sqrt(0.023);
		assertEquals(root * root, SnowThermalConductivity.COSENZA.conductivity(liquid, ICE), 1e-12);
		assertEquals(2.290, SnowThermalConductivity.COSENZA.conductivity(0.0, 1.0), 1e-12);
	}

	@Test
	public void liquidCountsInTheBulkDensity() {
		// 270 kg/m3 of ice + 30 kg/m3 of liquid = 300 kg/m3
		double fromMix = SnowThermalConductivity.JORDAN_1991.conductivity(0.03, 270.0 / 917.0);
		assertEquals(SnowThermalConductivity.JORDAN_1991.conductivity(0.0, ICE), fromMix, 1e-12);
	}

	@Test
	public void cellOverload() {
		SnowCell cell = new SnowCell(150.0, 0.0, 0.5, 0.0, 0.0);
		assertEquals(SnowThermalConductivity.STURM_1997.conductivity(0.0, 300.0 / 917.0),
				SnowThermalConductivity.STURM_1997.conductivity(cell), 1e-12);
	}
}
