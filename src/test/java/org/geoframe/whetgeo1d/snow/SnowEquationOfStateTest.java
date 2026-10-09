package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_WATER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SnowEquationOfStateTest {

	private static final double[] ALPHAS = { 1e3, 1e4, 1e5 };
	private static final double MASS = 120.0;

	@Test
	public void matchesTheSpecificationFormula() {
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		double t = MELTING_TEMPERATURE - 2e-5;
		double theta = 1.0 / (1.0 + Math.pow(1e5 * -2e-5, 2));
		double expected = (SPECIFIC_HEAT_ICE * (1 - theta) + SPECIFIC_HEAT_WATER * theta) * MASS * -2e-5
				+ LATENT_HEAT_FUSION * theta * MASS;
		assertEquals(0.2, theta, 1e-15);
		assertEquals(expected, eos.internalEnergy(t, MASS), 1e-9 * Math.abs(expected));
	}

	@Test
	public void coldDrySnowAndWarmWater() {
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		// at -10 degC the liquid fraction is 1e-12: U is the sensible heat of ice
		assertEquals(SPECIFIC_HEAT_ICE * MASS * -10.0, eos.internalEnergy(MELTING_TEMPERATURE - 10.0, MASS), 1e-3);
		assertEquals(LATENT_HEAT_FUSION * MASS, eos.internalEnergy(MELTING_TEMPERATURE, MASS), 1e-9);
		assertEquals(LATENT_HEAT_FUSION * MASS + SPECIFIC_HEAT_WATER * MASS * 2.0,
				eos.internalEnergy(MELTING_TEMPERATURE + 2.0, MASS), 1e-6);
	}

	@Test
	public void derivativesMatchFiniteDifferences() {
		for (double alpha : ALPHAS) {
			SnowEquationOfState eos = new SnowEquationOfState(alpha);
			for (double scaled : new double[] { -20.0, -3.0, -1.0, -0.577, -0.3, -0.05 }) {
				double t = MELTING_TEMPERATURE + scaled / alpha;
				double h = 1e-3 / alpha;
				double fd = (eos.internalEnergy(t + h, MASS) - eos.internalEnergy(t - h, MASS)) / (2 * h);
				double fdd = (eos.dInternalEnergy(t + h, MASS) - eos.dInternalEnergy(t - h, MASS)) / (2 * h);
				double d = eos.dInternalEnergy(t, MASS);
				double dd = eos.ddInternalEnergy(t, MASS);
				assertEquals("dU at alpha=" + alpha + " x=" + scaled, d, fd, 1e-5 * Math.abs(d));
				assertEquals("ddU at alpha=" + alpha + " x=" + scaled, dd, fdd, 1e-4 * Math.abs(d) * alpha);
			}
		}
	}

	@Test
	public void energyIsStrictlyIncreasing() {
		for (double alpha : ALPHAS) {
			SnowEquationOfState eos = new SnowEquationOfState(alpha);
			for (double t = MELTING_TEMPERATURE - 40.0; t < MELTING_TEMPERATURE + 5.0; t += 1e-3) {
				assertTrue(eos.dInternalEnergy(t, MASS) > 0.0);
			}
		}
	}

	@Test
	public void inflectionIsTheMaximumOfTheDerivative() {
		for (double alpha : ALPHAS) {
			SnowEquationOfState eos = new SnowEquationOfState(alpha);
			double xStar = eos.inflectionTemperature();
			double approximate = MELTING_TEMPERATURE - 1.0 / (Math.sqrt(3.0) * alpha);
			assertEquals(approximate, xStar, 1e-3 / alpha);
			double peak = eos.dInternalEnergy(xStar, MASS);
			for (double shift : new double[] { -1e-2, -1e-3, 1e-3, 1e-2 }) {
				assertTrue(eos.dInternalEnergy(xStar + shift / alpha, MASS) < peak);
			}
		}
	}

	@Test
	public void inversionRoundTrip() {
		for (double alpha : ALPHAS) {
			SnowEquationOfState eos = new SnowEquationOfState(alpha);
			double[] temperatures = { MELTING_TEMPERATURE - 60.0, MELTING_TEMPERATURE - 5.0,
					MELTING_TEMPERATURE - 3.0 / alpha, MELTING_TEMPERATURE - 0.5 / alpha,
					MELTING_TEMPERATURE - 1e-3 / alpha, MELTING_TEMPERATURE, MELTING_TEMPERATURE + 3.0 };
			for (double t : temperatures) {
				double u = eos.internalEnergy(t, MASS);
				double back = eos.temperature(u, MASS);
				double residual = eos.internalEnergy(back, MASS) - u;
				assertEquals("residual at alpha=" + alpha + " T=" + t, 0.0, residual,
						1e-12 * MASS * LATENT_HEAT_FUSION);
				if (t <= MELTING_TEMPERATURE - 0.1 / alpha || t >= MELTING_TEMPERATURE) {
					assertEquals(t, back, 1e-9);
				}
			}
		}
	}

	@Test
	public void equilibrateKeepsExtensiveQuantitiesAndRepartitions() {
		SnowEquationOfState eos = new SnowEquationOfState(1e4);
		SnowCell cell = new SnowCell(100.0, 0.0, 0.3, 0.2 * LATENT_HEAT_FUSION * 100.0);
		SnowCell equilibrium = eos.equilibrate(cell);
		assertEquals(cell.totalMass(), equilibrium.totalMass(), 1e-12);
		assertEquals(cell.internalEnergy(), equilibrium.internalEnergy(), 0.0);
		assertEquals(cell.thickness(), equilibrium.thickness(), 0.0);
		double t = eos.temperature(equilibrium);
		assertTrue(t < MELTING_TEMPERATURE);
		assertEquals(eos.liquidFraction(t) * 100.0, equilibrium.liquidMass(), 1e-9);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonPositiveAlpha() {
		new SnowEquationOfState(0.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsEnergyBelowAbsoluteZero() {
		new SnowEquationOfState(1e5).temperature(-1e12, 1.0);
	}
}
