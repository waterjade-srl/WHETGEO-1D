package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** GEOtop's fresh-snow density and the fresh-snow cell. */
public class FreshSnowTest {

	/** Values of GEOtop's formula, evaluated independently in °C. */
	@Test
	public void densityReproducesGeotop() {
		assertEquals(80.41656591479251, FreshSnow.geotopDensity(MELTING_TEMPERATURE - 5.0, 2.0), 1e-9);
		assertEquals(47.999999999999986, FreshSnow.geotopDensity(MELTING_TEMPERATURE - 20.0, 0.0), 1e-9);
		assertEquals(100.47537322926608, FreshSnow.geotopDensity(MELTING_TEMPERATURE - 20.0, 5.0), 1e-9);
		assertEquals(70.83424568621238, FreshSnow.geotopDensity(MELTING_TEMPERATURE - 12.9, 3.0), 1e-9);
	}

	/** Above 2.5 °C the temperature is capped. */
	@Test
	public void densityIsCappedAboveTwoAndAHalfDegrees() {
		assertEquals(210.46386049273303, FreshSnow.geotopDensity(MELTING_TEMPERATURE + 4.0, 1.0), 1e-9);
		assertEquals(FreshSnow.geotopDensity(MELTING_TEMPERATURE + 2.5, 1.0),
				FreshSnow.geotopDensity(MELTING_TEMPERATURE + 4.0, 1.0), 0.0);
	}

	@Test
	public void geotopTemperatureIsAtMostMinusOneTenth() {
		assertEquals(MELTING_TEMPERATURE - 0.1, FreshSnow.geotopTemperature(MELTING_TEMPERATURE + 3.0), 0.0);
		assertEquals(MELTING_TEMPERATURE - 7.0, FreshSnow.geotopTemperature(MELTING_TEMPERATURE - 7.0), 0.0);
	}

	@Test
	public void cellIsAllIceWithItsSensibleHeat() {
		SnowCell cell = FreshSnow.cell(10.0, 80.0, MELTING_TEMPERATURE - 4.0);
		assertEquals(10.0, cell.iceMass(), 0.0);
		assertEquals(0.0, cell.liquidMass(), 0.0);
		assertEquals(0.125, cell.thickness(), 1e-15);
		assertEquals(-4.0 * 10.0 * SPECIFIC_HEAT_ICE, cell.internalEnergy(), 1e-9);
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		assertEquals(MELTING_TEMPERATURE - 4.0, eos.temperature(cell), 1e-6);
	}

	@Test(expected = IllegalArgumentException.class)
	public void cellAboveMeltingIsRejected() {
		FreshSnow.cell(1.0, 100.0, MELTING_TEMPERATURE + 0.5);
	}
}
