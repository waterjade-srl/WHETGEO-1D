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
 * Expected values: GEOtop's assembly ({@code energy.balance.cc:540, 602, 1497-1499},
 * {@code turbulent_fluxes}, {@code latent}) rewritten in python in GEOtop units,
 * with the albedo of {@code snow_albedo}, flat point (sky = 1), z_u = 5 m,
 * z_t = 2 m, P = 850 hPa.
 */
public class SurfaceEnergyBalanceTest {

	private static final double P = 85000.0;
	private static final double TM = 273.15;
	private static final SnowAlbedo ALBEDO = new SnowAlbedo(SnowAlbedo.Parameters.geotopDefaults());
	private static final MoninObukhov GEOTOP_TURBULENCE = new MoninObukhov(MoninObukhov.Parameters.geotop(5.0, 2.0));

	private static SurfaceEnergyBalance balance(SurfaceEnergyBalance.Parameters parameters, MoninObukhov turbulence,
			double taC, double rh, double wind, double longwave, double direct, double diffuse, double cos,
			double depth, double age, double tsStartC) {
		SurfaceForcing forcing = new SurfaceForcing(direct, diffuse, cos, longwave, taC + TM, rh, wind, P);
		SnowAlbedo.Albedos albedos = ALBEDO.albedos(depth, age, cos, 0.2, 0.3);
		return SurfaceEnergyBalance.atStartOfStep(parameters, turbulence, forcing, albedos, tsStartC + TM);
	}

	private static SurfaceEnergyBalance clearDay(SurfaceEnergyBalance.Parameters parameters) {
		return balance(parameters, GEOTOP_TURBULENCE, -5, 0.7, 2, 250, 300, 100, 0.4, 0.5, 1.5, -8);
	}

	@Test
	public void reproducesGeotopOnAClearColdDay() {
		SurfaceEnergyBalance eb = clearDay(SurfaceEnergyBalance.Parameters.geotop());
		assertEquals(1135.9729246449283, eb.resistances().heat(), 1e-6);
		assertEquals(1114.2499535885638, eb.resistances().vapour(), 1e-6);
		SurfaceFluxes f = eb.evaluate(-7 + TM);
		assertEquals(147.1246153846154, f.netShortwave(), 1e-10);
		assertEquals(-33.81428219090914, f.netLongwave(), 1e-10);
		assertEquals(-1.9589097686151868, f.sensibleHeat(), 1e-10);
		assertEquals(1.3853626458307484, f.latentHeatFlux(), 1e-10);
		assertEquals(4.887157885599e-07, f.vapourFlux(), 1e-16);
		assertEquals(2834700.0, f.latentHeat(), 0.0);
		assertEquals(113.88388031649069, f.energyBalance(), 1e-9);
		assertEquals(-5.748600854801561, f.derivative(), 1e-11);
		assertEquals(f.netShortwave() + f.netLongwave() - f.sensibleHeat() - f.latentHeatFlux(), f.energyBalance(),
				1e-12);
	}

	@Test
	public void reproducesGeotopAboveMelting() {
		// iterate at +0.5 degC (from -0.5): lambda = Levap(0.5), condensation (E < 0)
		SurfaceEnergyBalance eb = balance(SurfaceEnergyBalance.Parameters.geotop(), GEOTOP_TURBULENCE, 3, 0.9, 3, 300,
				0, 150, 0.4, 0.5, 0.5, -0.5);
		SurfaceFluxes f = eb.evaluate(0.5 + TM);
		assertEquals(2499812.5, f.latentHeat(), 1e-6);
		assertEquals(-9.292287919802885e-07, f.vapourFlux(), 1e-16);
		assertEquals(-6.394353699232131, f.sensibleHeat(), 1e-10);
		assertEquals(37.496918299107364, f.energyBalance(), 1e-9);
		assertEquals(-9.311355107020546, f.derivative(), 1e-11);
	}

	@Test
	public void reproducesGeotopOnThinSnow() {
		SurfaceFluxes f = balance(SurfaceEnergyBalance.Parameters.geotop(), GEOTOP_TURBULENCE, -2, 0.7, 2, 250, 300,
				100, 0.3, 0.005, 0.5, -3).evaluate(-3 + TM);
		assertEquals(188.21216380309392, f.netShortwave(), 1e-10);
		assertEquals(133.98821646970526, f.energyBalance(), 1e-9);
		assertEquals(-8.079188075663959, f.derivative(), 1e-11);
	}

	@Test
	public void recommendedDerivativeIsExact() {
		SurfaceEnergyBalance.Parameters recommended = SurfaceEnergyBalance.Parameters.recommended();
		MoninObukhov turbulence = new MoninObukhov(MoninObukhov.Parameters.recommended(5.0, 2.0));
		SurfaceEnergyBalance[] cases = { clearDay(recommended),
				balance(recommended, turbulence, 3, 0.9, 3, 300, 0, 150, 0.4, 0.5, 0.5, -0.5),
				balance(recommended, turbulence, 5, 0.6, 1, 280, 0, 0, 0.0, 1.0, 3.0, -10),
				balance(recommended, turbulence, -15, 0.5, 6, 200, 500, 80, 0.7, 1.0, 0.2, -12) };
		double h = 1e-4;
		for (SurfaceEnergyBalance eb : cases) {
			for (double ts = 240.0; ts < 280.0; ts += 0.7) {
				if (Math.abs(ts - TM) < 2 * h) {
					continue;
				}
				double fd = (eb.evaluate(ts + h).energyBalance() - eb.evaluate(ts - h).energyBalance()) / (2 * h);
				assertEquals("Ts=" + ts, fd, eb.evaluate(ts).derivative(), 1e-6 * Math.abs(fd));
			}
		}
	}

	@Test
	public void geotopDerivativeMissesOnlyTheAirPropertiesAndTheJump() {
		SurfaceEnergyBalance eb = clearDay(SurfaceEnergyBalance.Parameters.geotop());
		double h = 1e-4;
		double maxRelative = 0.0;
		for (double ts = 245.0; ts < 272.0; ts += 0.5) {
			double fd = (eb.evaluate(ts + h).energyBalance() - eb.evaluate(ts - h).energyBalance()) / (2 * h);
			maxRelative = Math.max(maxRelative, Math.abs(eb.evaluate(ts).derivative() / fd - 1.0));
		}
		assertTrue("rho_a(Ts), c_p(Ts) left out: " + maxRelative, maxRelative > 1e-6 && maxRelative < 0.02);
		// across 0 degC GEOtop's lambda jumps by L_f: EB drops by L_f E going below
		SurfaceFluxes fb = eb.evaluate(TM - 1e-9);
		SurfaceFluxes fa = eb.evaluate(TM);
		assertEquals(-333700.0 * fa.vapourFlux(), fb.energyBalance() - fa.energyBalance(), 1e-6);
	}

	@Test
	public void recommendedIsContinuousAtMelting() {
		SurfaceEnergyBalance eb = clearDay(SurfaceEnergyBalance.Parameters.recommended());
		double jump = eb.evaluate(TM - 1e-9).energyBalance() - eb.evaluate(TM).energyBalance();
		assertTrue("jump " + jump, Math.abs(jump) < 0.01);
		assertEquals(2834700.0, eb.evaluate(TM + 1.0).latentHeat(), 0.0);
	}

	@Test
	public void resistancesAreFrozenAtTheStartTemperature() {
		SurfaceEnergyBalance eb = clearDay(SurfaceEnergyBalance.Parameters.geotop());
		double ta = 268.15;
		double qa = AirProperties.specificHumidityFromRelative(ta, 0.7, P);
		double qs0 = AirProperties.specificHumidity(SaturationCurve.WATER.pressure(265.15, P), P);
		MoninObukhov.Resistances expected = GEOTOP_TURBULENCE.resistances(ta, 265.15, qa, qs0, 2.0);
		assertEquals(expected, eb.resistances());
		double conductance = eb.evaluate(260.0).sensibleHeat() / (260.0 - ta);
		double frozenConductance = eb.evaluate(275.0).sensibleHeat() / (275.0 - ta);
		assertEquals("only rho_a c_p moves with Ts in GEOtop's option", conductance, frozenConductance,
				0.05 * conductance);
		SurfaceEnergyBalance frozen = clearDay(SurfaceEnergyBalance.Parameters.recommended());
		assertEquals(frozen.evaluate(260.0).sensibleHeat() / (260.0 - ta),
				frozen.evaluate(275.0).sensibleHeat() / (275.0 - ta), 1e-12);
	}

	@Test
	public void signConvention() {
		SurfaceEnergyBalance eb = clearDay(SurfaceEnergyBalance.Parameters.geotop());
		SurfaceFluxes warm = eb.evaluate(270.0);
		assertTrue("surface warmer than air: H upward, positive", warm.sensibleHeat() > 0.0);
		assertTrue("saturated surface, RH 0.7: sublimation, E > 0", warm.vapourFlux() > 0.0);
		assertTrue("EB decreases with Ts", warm.derivative() < 0.0);
	}

	@Test
	public void vapourPhase() {
		assertEquals(VapourPhase.ICE, clearDay(SurfaceEnergyBalance.Parameters.geotop()).vapourPhase());
		assertEquals(VapourPhase.ICE, clearDay(SurfaceEnergyBalance.Parameters.recommended()).vapourPhase());
		SurfaceEnergyBalance wetGeotop = balance(SurfaceEnergyBalance.Parameters.geotop(), GEOTOP_TURBULENCE, 3, 0.9,
				3, 300, 0, 150, 0.4, 0.5, 0.5, 0.0);
		SurfaceEnergyBalance wetRecommended = balance(SurfaceEnergyBalance.Parameters.recommended(), GEOTOP_TURBULENCE,
				3, 0.9, 3, 300, 0, 150, 0.4, 0.5, 0.5, 0.0);
		assertEquals("GEOtop always takes ice", VapourPhase.ICE, wetGeotop.vapourPhase());
		assertEquals(VapourPhase.LIQUID, wetRecommended.vapourPhase());
		assertEquals(2.501e6, wetRecommended.evaluate(272.0).latentHeat(), 0.0);
	}
}
