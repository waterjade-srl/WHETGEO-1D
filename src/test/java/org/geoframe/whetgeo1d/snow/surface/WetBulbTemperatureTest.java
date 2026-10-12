package org.geoframe.whetgeo1d.snow.surface;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wet-bulb temperature from the psychrometric balance. */
public class WetBulbTemperatureTest {

	private static final double P = 101325.0;

	@Test
	public void saturatedAirIsAtItsWetBulb() {
		double ta = MELTING_TEMPERATURE + 3.0;
		assertEquals(ta, WetBulbTemperature.of(ta, 1.0, P), 1e-9);
	}

	/** Stull (2011): 20 °C and 50 % at sea level give 13.7 °C. */
	@Test
	public void matchesStullAtTwentyDegrees() {
		assertEquals(MELTING_TEMPERATURE + 13.7, WetBulbTemperature.of(MELTING_TEMPERATURE + 20.0, 0.5, P), 0.3);
	}

	@Test
	public void solvesThePsychrometricBalance() {
		double ta = MELTING_TEMPERATURE + 1.5;
		double rh = 0.6;
		double p = 85000.0;
		double tw = WetBulbTemperature.of(ta, rh, p);
		double qa = AirProperties.specificHumidityFromRelative(ta, rh, p);
		double qs = AirProperties.specificHumidity(SaturationCurve.WATER.pressure(tw, p), p);
		double sensible = AirProperties.specificHeat(ta) * (ta - tw);
		double latent = AirProperties.latentHeatOfVaporisation(tw) * (qs - qa);
		assertEquals(sensible, latent, 1e-9 * sensible);
		// dry air above 0 °C can bring the precipitation below 0 °C
		assertTrue(tw < MELTING_TEMPERATURE);
	}

	@Test
	public void decreasesWithDrierAir() {
		double ta = MELTING_TEMPERATURE + 2.0;
		double previous = Double.POSITIVE_INFINITY;
		for (double rh = 1.0; rh >= 0.0; rh -= 0.1) {
			double tw = WetBulbTemperature.of(ta, Math.max(rh, 0.0), P);
			assertTrue(tw < previous);
			assertTrue(tw <= ta);
			previous = tw;
		}
	}
}
