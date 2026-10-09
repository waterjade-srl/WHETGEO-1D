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
 * Expected values: GEOtop's {@code aero_resistance}/{@code Businger}
 * ({@code turbulence.cc:36-504}) rewritten literally in python in GEOtop units
 * (°C, mbar); NivoGEO-1D's {@code Turbulence.aeroResistance} (commit 49f4899),
 * run on the same inputs, returns the same numbers to the last digit for
 * selectors 1 and 2. z_u = 5 m, z_t = 2 m, z₀ = 0.1 mm, P = 850 hPa, q_s
 * saturated over water at T_s, q_a from RH over water.
 */
public class MoninObukhovTest {

	private static final double P = 85000.0;

	/** T_a [°C], T_s [°C], wind, RH, selector, r_m, r_h, r_v, L, passes. */
	private static final double[][] GEOTOP = {
			// stable, T_s < T_a
			{ 2, -5, 2, 0.7, 1, 2820.4891700872377, 2033.5955610023595, 2003.0934601478066, 0.3753049849504383, 6 },
			{ 2, -5, 2, 0.7, 2, 348.20821571622946, 303.0614402991775, 295.01999308651193, 1.2895713832063973, 6 },
			{ 2, -5, 2, 0.7, 3, 2820.4891700872377, 2033.5955610023595, 2003.0934601478066, 0.3753049849504383, 6 },
			{ 2, -5, 2, 0.7, 4, 348.20821571622946, 303.0614402991775, 295.01999308651193, 1.2895713832063973, 6 },
			// unstable, T_s > T_a
			{ -10, -2, 2, 0.7, 1, 239.37954398436526, 196.45473478732507, 190.18322357502797, -1.312238849196818, 6 },
			{ -10, -2, 2, 0.7, 3, 348.20821571622946, 303.0614402991775, 295.01999308651193, -1.1535184739532411, 6 },
			// weakly stable
			{ 0, -0.5, 3, 0.8, 1, 270.7706811242727, 230.56120428143217, 225.36489912299643, 29.134932044090657, 6 },
			{ 0, -0.5, 3, 0.8, 2, 232.1388104774863, 206.8255594746591, 202.14313783615393, 32.923325139864836, 6 },
			// strongly stable: the iteration runs away (decoupling)
			{ 5, -10, 1, 0.6, 1, 1073033606.0120983, 429687175.52168304, 429519090.60984385, 5.591509626346987e-05, 6 },
			{ 5, -10, 1, 0.6, 2, 696.4164314324589, 581.5849011416775, 562.0220750843085, 0.14484360544016095, 6 },
			// T_s = T_a: stable only through GEOtop's -0.61 q* (evaporation counts as stabilising)
			{ -5, -5, 2, 0.9, 1, 358.3902283842327, 309.25921700284056, 301.06429816698, 164.53229482847792, 3 },
			{ -5, -5, 2, 0.9, 2, 348.20821571622946, 303.0614402991775, 295.01999308651193, 168.35230439655325, 2 } };

	private static MoninObukhov.Resistances resistances(MoninObukhov.Parameters p, double taC, double tsC,
			double wind, double rh) {
		double ta = taC + 273.15;
		double ts = tsC + 273.15;
		double qa = AirProperties.specificHumidityFromRelative(ta, rh, P);
		double qs = AirProperties.specificHumidity(SaturationCurve.WATER.pressure(ts, P), P);
		return new MoninObukhov(p).resistances(ta, ts, qa, qs, wind);
	}

	private static MoninObukhov.Parameters withStability(StabilityCorrection stability) {
		MoninObukhov.Parameters g = MoninObukhov.Parameters.geotop(5.0, 2.0);
		return new MoninObukhov.Parameters(g.windHeight(), g.temperatureHeight(), g.roughnessLength(),
				g.displacementHeight(), stability, g.scalarRoughness(), g.roughnessRatio(), g.maxIterations(),
				g.minimumWindSpeed(), g.maximumResistance(), g.capVapourResistance());
	}

	@Test
	public void reproducesGeotop() {
		for (double[] c : GEOTOP) {
			StabilityCorrection stability = StabilityCorrection.ofGeotopCode((int) c[4]);
			MoninObukhov.Resistances r = resistances(withStability(stability), c[0], c[1], c[2], c[3]);
			String label = "Ta=" + c[0] + " Ts=" + c[1] + " " + stability;
			assertEquals(label, c[5], r.momentum(), 1e-9 * c[5]);
			assertEquals(label, c[6], r.heat(), 1e-9 * c[6]);
			assertEquals(label, c[7], r.vapour(), 1e-9 * c[7]);
			assertEquals(label, c[8], r.obukhovLength(), 1e-9 * Math.abs(c[8]));
			assertEquals(label, (int) c[9], r.passes());
		}
	}

	@Test
	public void capsTheHeatResistanceOnly() {
		MoninObukhov.Resistances r = resistances(MoninObukhov.Parameters.geotop(5.0, 2.0), 5, -10, 1, 0.6);
		assertEquals(2600.0, r.heatFlux(), 0.0);
		assertEquals("GEOtop leaves r_v uncapped", r.vapour(), r.vapourFlux(), 0.0);
		MoninObukhov.Resistances capped = resistances(MoninObukhov.Parameters.recommended(5.0, 2.0), 5, -10, 1, 0.6);
		assertEquals(2600.0, capped.vapourFlux(), 0.0);
		MoninObukhov.Resistances unstable = resistances(MoninObukhov.Parameters.recommended(5.0, 2.0), -10, -2, 2, 0.7);
		assertEquals(unstable.heat(), unstable.heatFlux(), 0.0);
		assertEquals(unstable.vapour(), unstable.vapourFlux(), 0.0);
	}

	@Test
	public void fixedRatioOfRoughnessLengths() {
		// Businger with z0_z0t = 10 ("bending surface"): z0t = z0q = z0/10
		MoninObukhov.Parameters g = MoninObukhov.Parameters.geotop(5.0, 2.0);
		MoninObukhov.Parameters p = new MoninObukhov.Parameters(5.0, 2.0, g.roughnessLength(), 0.0,
				StabilityCorrection.BOTH, ScalarRoughness.FIXED_RATIO, 10.0, 5, 0.5, 2600.0, false);
		MoninObukhov.Resistances r = resistances(p, 2, -5, 2, 0.7);
		assertEquals(2500.7856826599286, r.momentum(), 1e-9 * 2500.0);
		assertEquals(2092.943255486681, r.heat(), 1e-9 * 2000.0);
		assertEquals(r.heat(), r.vapour(), 0.0);
		assertEquals(0.4625562725523684, r.obukhovLength(), 1e-10);
	}

	@Test
	public void windIsFlooredAtGeotopsMinimum() {
		// Vmin = 0.5 m/s: wind 0.1 gives the resistances of 0.5
		MoninObukhov.Resistances r = resistances(MoninObukhov.Parameters.geotop(5.0, 2.0), -10, -2, 0.1, 0.7);
		assertEquals(568.8809321334808, r.momentum(), 1e-9 * 568.0);
		assertEquals(352.85208530152556, r.heat(), 1e-9 * 352.0);
		assertEquals(327.0337649503573, r.vapour(), 1e-9 * 327.0);
		assertEquals(-0.080609517452755, r.obukhovLength(), 1e-12);
	}

	@Test
	public void neutralLimitIsTheLogProfile() {
		// NEUTRAL, Andreas at the converged u*: r_h = ln(z_u/z0) ln(z_t/z0t) / (k^2 v)
		MoninObukhov.Resistances r = resistances(withStability(StabilityCorrection.NEUTRAL), -10, -2, 2, 0.7);
		double cm = Math.log(5.0 / 1e-4);
		double uStar = 2.0 * 0.41 / cm;
		double z0t = 1e-4 * ScalarRoughness.andreasHeatRatio(uStar * 1e-4 / 1.4e-5);
		assertEquals(cm * cm / (0.41 * 0.41 * 2.0), r.momentum(), 1e-9);
		assertEquals(cm * Math.log(2.0 / z0t) / (0.41 * 0.41 * 2.0), r.heat(), 1e-9);
		assertEquals(uStar, r.frictionVelocity(), 1e-15);
	}

	@Test
	public void andreasCoefficients() {
		// smooth flow, R* <= 0.135: z0t/z0 = e^1.25, z0q/z0 = e^1.61
		assertEquals(Math.exp(1.25), ScalarRoughness.andreasHeatRatio(0.1), 1e-14);
		assertEquals(Math.exp(1.61), ScalarRoughness.andreasVapourRatio(0.1), 1e-14);
		// rough flow, R* = 10
		double ln = Math.log(10.0);
		assertEquals(Math.exp(0.317 - 0.565 * ln - 0.183 * ln * ln), ScalarRoughness.andreasHeatRatio(10.0), 1e-14);
	}

	@Test
	public void stableCorrectionRaisesTheResistance() {
		MoninObukhov.Resistances on = resistances(withStability(StabilityCorrection.BOTH), 2, -2, 3, 0.8);
		MoninObukhov.Resistances off = resistances(withStability(StabilityCorrection.UNSTABLE_ONLY), 2, -2, 3, 0.8);
		assertTrue(on.obukhovLength() > 0.0);
		assertTrue(on.heat() > off.heat());
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsSensorBelowRoughness() {
		new MoninObukhov.Parameters(5.0, 1e-5, 1e-4, 0.0, StabilityCorrection.BOTH, ScalarRoughness.ANDREAS, 0.0, 5,
				0.5, 2600.0, false);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonPositiveMinimumWind() {
		new MoninObukhov.Parameters(5.0, 2.0, 1e-4, 0.0, StabilityCorrection.BOTH, ScalarRoughness.ANDREAS, 0.0, 5,
				0.0, 2600.0, false);
	}
}
