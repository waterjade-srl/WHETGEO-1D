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
import static org.junit.Assert.assertTrue;

import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.geoframe.whetgeo1d.snow.surface.MoninObukhov;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.junit.Test;

public class BareSoilEnergyBalanceTest {

	private static final BareSoilEnergyBalance SOIL = new BareSoilEnergyBalance(
			BareSoilEnergyBalance.soilTurbulence(MoninObukhov.Parameters.geotop(2.5, 2.0), 0.1), 0.96, 0.15, 0.15,
			0.25, 0.25);
	private static final SurfaceForcing NOON = new SurfaceForcing(400.0, 100.0, 0.6, 300.0, 283.15, 0.5, 3.0,
			84445.0);

	@Test
	public void componentsAndDerivative() {
		BareSoilEnergyBalance.Step step = SOIL.atStartOfStep(NOON, 288.15, 0.5, 1e-5);
		SurfaceFluxes f = step.evaluate(SOIL, 288.15);
		assertEquals(500.0 * 0.8, f.netShortwave(), 1e-9);
		assertEquals(0.96 * (300.0 - AirProperties.emissivePower(288.15)), f.netLongwave(), 1e-9);
		assertTrue(f.sensibleHeat() > 0.0);
		assertEquals(1e-5 * AirProperties.latentHeatOfVaporisation(288.15), f.latentHeatFlux(), 1e-9);
		assertEquals(f.netShortwave() + f.netLongwave() - f.sensibleHeat() - f.latentHeatFlux(), f.energyBalance(),
				1e-9);
		// derivative against a finite difference with the resistances frozen; as GEOtop's dH/dT,
		// the analytic one leaves out the dependence of rho and c_p on the mean temperature
		double h = 1e-4;
		double numeric = (step.evaluate(SOIL, 288.15 + h).energyBalance() - step.evaluate(SOIL, 288.15 - h)
				.energyBalance()) / (2 * h);
		assertEquals(numeric, f.derivative(), 0.01 * Math.abs(numeric));
		assertTrue(f.derivative() < 0.0);
	}

	@Test
	public void skinBalancesConductionIntoTheSoil() {
		BareSoilEnergyBalance.Step step = SOIL.atStartOfStep(NOON, 290.0, 1.0, 0.0);
		double conductance = 1.5 / 0.005;
		double topCell = 285.0;
		double skin = step.skinTemperature(SOIL, 290.0, topCell, conductance);
		// G linearised at the start skin equals the conduction to the top cell
		SurfaceFluxes f = step.evaluate(SOIL, 290.0);
		double linear = f.energyBalance() + f.derivative() * (skin - 290.0);
		assertEquals(conductance * (skin - topCell), linear, 1e-8);
		TopBoundary.SurfaceFlux top = step.topBoundary(SOIL, 290.0, topCell, conductance);
		assertEquals(conductance * (skin - topCell), top.flux(), 1e-8);
		// the flux at another top-cell temperature follows the slope
		double other = step.skinTemperature(SOIL, 290.0, topCell + 1.0, conductance);
		assertEquals(conductance * (other - topCell - 1.0) - top.flux(), top.fluxDerivative(), 1e-8);
		assertTrue(top.fluxDerivative() < 0.0);
	}

	@Test
	public void albedoBetweenDryAndSaturated() {
		BareSoilEnergyBalance wetter = new BareSoilEnergyBalance(SOIL.turbulence(), 0.96, 0.3, 0.1, 0.4, 0.2);
		assertEquals(500.0 * (1 - 0.35), wetter.atStartOfStep(NOON, 288.15, 0.0, 0.0).netShortwave(), 1e-9);
		assertEquals(500.0 * (1 - 0.15), wetter.atStartOfStep(NOON, 288.15, 1.0, 0.0).netShortwave(), 1e-9);
	}
}
