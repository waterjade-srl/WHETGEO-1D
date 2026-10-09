package org.geoframe.whetgeo1d.core.equationstate.models;

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.geoframe.whetgeo1d.core.state.WGProblemQuantities;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.junit.Test;

public class SnowInternalEnergyTest {

	private static final double MASS = 80.0;
	private static final double ROUND_OFF = 4 * Math.ulp(1.0);

	private static SnowInternalEnergy closure(double alpha, double initialTemperature) {
		WGProblemQuantities variables = new WGProblemQuantities(new double[] { 0.0 },
				new double[] { initialTemperature }, new int[] { 0 }, new int[] { 0 });
		SnowInternalEnergy snow = new SnowInternalEnergy(null, variables,
				new SnowEquationOfState[] { new SnowEquationOfState(alpha) }, new double[] { MASS });
		snow.computeXStar(0.0, 0, 0);
		return snow;
	}

	@Test
	public void nestedNewtonDecomposition() {
		SnowInternalEnergy snow = closure(1e5, 260.0);
		double previousP = 0.0, previousQ = 0.0;
		for (double t = MELTING_TEMPERATURE - 1e-3; t < MELTING_TEMPERATURE + 1e-3; t += 1e-7) {
			double p = snow.p(t, 0, 0, 0);
			double q = snow.q(t, 0, 0, 0);
			assertEquals(snow.dEquationState(t, 0, 0, 0), p - q, 1e-9 * p);
			assertEquals(snow.equationState(t, 0, 0, 0), snow.pIntegral(t, 0, 0, 0) - snow.qIntegral(t, 0, 0, 0),
					1e-6 * MASS * LATENT_HEAT_FUSION);
			assertTrue("p must be non-decreasing", p >= previousP - 1e-9 * p);
			assertTrue("q must be non-negative and non-decreasing", q >= 0.0 && q >= previousQ - 1e-9 * p);
			previousP = p;
			previousQ = q;
		}
	}

	/**
	 * Inverts U(T) = b with the Casulli–Zanolli nested Newton, the scheme of
	 * NestedNewtonThomas reduced to one cell, from cold and warm starts and across
	 * the phase change. The tolerance is floored at round-off as proposed in the
	 * specification (§3.6): near the melting point dU/dT reaches 1e12 J m-2 K-1,
	 * so one ulp of T is worth about 0.1 J m-2 and a purely absolute tolerance
	 * would be unreachable.
	 */
	@Test
	public void scalarNestedNewtonConverges() {
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		double[] targets = { MELTING_TEMPERATURE - 15.0, MELTING_TEMPERATURE - 1e-4, MELTING_TEMPERATURE - 6e-6,
				MELTING_TEMPERATURE - 1e-6, MELTING_TEMPERATURE + 0.5 };
		double[] starts = { 250.0, MELTING_TEMPERATURE - 3e-6, 280.0 };
		for (double target : targets) {
			for (double start : starts) {
				SnowInternalEnergy snow = closure(1e5, start);
				double b = eos.internalEnergy(target, MASS);
				double tolerance = 1e-10 * MASS * LATENT_HEAT_FUSION;
				double x = snow.initialGuess(start, 0, 0);
				int outer = 0;
				while (Math.abs(snow.equationState(x, 0, 0, 0) - b) > tolerance
						+ ROUND_OFF * snow.dEquationState(x, 0, 0, 0) * Math.abs(x)) {
					assertTrue("outer iterations for target " + target + " from " + start, ++outer <= 20);
					double xk = x;
					double qk = snow.q(xk, 0, 0, 0);
					double qIk = snow.qIntegral(xk, 0, 0, 0);
					int inner = 0;
					while (true) {
						double r = snow.pIntegral(x, 0, 0, 0) - qIk - qk * (x - xk) - b;
						double jacobian = snow.p(x, 0, 0, 0) - qk;
						if (Math.abs(r) <= tolerance + ROUND_OFF * jacobian * Math.abs(x)) {
							break;
						}
						assertTrue("inner iterations for target " + target + " from " + start, ++inner <= 30);
						x -= r / jacobian;
					}
				}
				assertEquals("target " + target + " from " + start, eos.temperature(b, MASS), x, 1e-9);
			}
		}
	}
}
