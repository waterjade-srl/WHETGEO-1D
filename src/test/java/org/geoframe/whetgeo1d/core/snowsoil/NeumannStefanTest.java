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

import java.util.Arrays;
import java.util.List;

import org.apache.commons.math3.special.Erf;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.junit.Test;

/**
 * VAL-6: the two-phase Stefan problem of Neumann, freezing and thawing, against
 * the heat solver of the column with WHETGEO's freezing soil (Dall'Amico on Van
 * Genuchten, Cosenza's conductivity), heat only.
 * <p>
 * A saturated soil (ψ = 0) with a very steep retention curve (α = 100 m-1, n =
 * 6) freezes within a few millikelvin below 0 °C, so the front is sharp. The
 * surface is held at T<sub>s</sub>, the soil starts at T<sub>i</sub> on the
 * other side of the melting point. The analytic solution uses the soil's own
 * properties, taken from WHETGEO's closures: volumetric heat capacity and
 * conductivity of each phase, and the volumetric latent heat of the step. The
 * column is 3 m deep, with the bottom held at T<sub>i</sub>; over 5 days the
 * perturbation stays above 1.5 m, so the domain is semi-infinite for the test.
 */
public class NeumannStefanTest {

	private static final double MELTING = 273.15;
	private static final double DEPTH = 3.0;
	private static final double STEP = 3600.0;
	private static final int STEPS = 5 * 24;
	private static final UniformSoil.Hydraulics SHARP = new UniformSoil.Hydraulics(0.4, 0.02, 6.0, 100.0, 1e-5, 0.0);

	/** Properties of the two phases from the closures, and the latent heat between them. */
	private record Phases(double cFrozen, double cThawed, double kFrozen, double kThawed, double latent) {
	}

	private static SnowSoilHeatDriver driver(int cells, double initial) {
		UniformSoil soil = new UniformSoil(cells, DEPTH, 0.0, initial, "Weighted Harmonic Mean", SHARP);
		return new SnowSoilHeatDriver(soil.freezingSetup(),
				new SnowHeatProperties(new SnowEquationOfState(1e5), SnowThermalConductivity.JORDAN_1991, 1),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
	}

	/** Energy per volume [J m-3] of the column at a uniform temperature. */
	private static double energy(SnowSoilHeatDriver d, int cells, double temperature) {
		double[] t = new double[cells];
		Arrays.fill(t, temperature);
		d.setSoilTemperatures(t);
		return d.soilInternalEnergy() / DEPTH;
	}

	private static double conductivity(SnowSoilHeatDriver d, int cells, double temperature) {
		double[] t = new double[cells];
		Arrays.fill(t, temperature);
		d.setSoilTemperatures(t);
		return d.topSoilConductivity();
	}

	private static Phases phases() {
		int cells = 10;
		SnowSoilHeatDriver d = driver(cells, MELTING);
		double cFrozen = (energy(d, cells, MELTING - 2.0) - energy(d, cells, MELTING - 6.0)) / 4.0;
		double cThawed = (energy(d, cells, MELTING + 6.0) - energy(d, cells, MELTING + 2.0)) / 4.0;
		double latent = energy(d, cells, MELTING + 2.0) - energy(d, cells, MELTING - 2.0) - 2.0 * (cFrozen + cThawed);
		return new Phases(cFrozen, cThawed, conductivity(d, cells, MELTING - 5.0), conductivity(d, cells, MELTING + 5.0),
				latent);
	}

	/**
	 * Neumann's solution: region 1 next to the surface (frozen when freezing),
	 * region 2 below at the initial temperature; front at X = 2 λ √(κ₁ t).
	 */
	private record Neumann(double surface, double initial, double k1, double c1, double k2, double c2,
			double latent, double lambda) {

		static Neumann of(double surface, double initial, Phases p) {
			boolean freezing = surface < MELTING;
			double k1 = freezing ? p.kFrozen() : p.kThawed();
			double c1 = freezing ? p.cFrozen() : p.cThawed();
			double k2 = freezing ? p.kThawed() : p.kFrozen();
			double c2 = freezing ? p.cThawed() : p.cFrozen();
			double kappa1 = k1 / c1;
			double kappa2 = k2 / c2;
			double ratio = Math.sqrt(kappa1 / kappa2);
			double low = 1e-6;
			double high = 5.0;
			for (int i = 0; i < 200; i++) {
				double lambda = 0.5 * (low + high);
				// flux balance at the front, times √t
				double out = k1 * Math.abs(MELTING - surface) * Math.exp(-lambda * lambda)
						/ (Erf.erf(lambda) * Math.sqrt(Math.PI * kappa1));
				double in = k2 * Math.abs(initial - MELTING) * Math.exp(-lambda * lambda * kappa1 / kappa2)
						/ (Erf.erfc(lambda * ratio) * Math.sqrt(Math.PI * kappa2));
				double released = p.latent() * lambda * Math.sqrt(kappa1);
				if (out - in > released) {
					low = lambda;
				} else {
					high = lambda;
				}
			}
			return new Neumann(surface, initial, k1, c1, k2, c2, p.latent(), 0.5 * (low + high));
		}

		double front(double time) {
			return 2.0 * lambda * Math.sqrt(k1 / c1 * time);
		}

		/** Temperature at depth z below the surface [K]. */
		double temperature(double z, double time) {
			double kappa1 = k1 / c1;
			double kappa2 = k2 / c2;
			if (z < front(time)) {
				return surface + (MELTING - surface) * Erf.erf(z / (2.0 * Math.sqrt(kappa1 * time))) / Erf.erf(lambda);
			}
			return initial - (initial - MELTING) * Erf.erfc(z / (2.0 * Math.sqrt(kappa2 * time)))
					/ Erf.erfc(lambda * Math.sqrt(kappa1 / kappa2));
		}
	}

	/** Result of a run: worst temperature error above 1.5 m and the front error [m]. */
	private record Errors(double temperature, double front, double frontDepth) {
	}

	private static Errors run(double surface, double initial, int cells) {
		Neumann exact = Neumann.of(surface, initial, phases());
		SnowSoilHeatDriver d = driver(cells, initial);
		double[] t = null;
		for (int k = 0; k < STEPS; k++) {
			HeatStepResult r = d.step(List.of(), new TopBoundary.SurfaceTemperature(surface), initial, STEP);
			assertTrue("step " + k, r.newton().converged());
			t = r.soilTemperatures();
		}
		double time = STEPS * STEP;
		double dz = DEPTH / cells;
		double worst = 0.0;
		double front = Double.NaN;
		// from the surface down: cell i from the top has its centre at (i + 0.5) dz
		for (int i = 0; i < cells; i++) {
			double z = (i + 0.5) * dz;
			double value = t[cells - 1 - i];
			if (z < 1.5) {
				worst = Math.max(worst, Math.abs(value - exact.temperature(z, time)));
			}
			if (Double.isNaN(front) && i > 0) {
				double above = t[cells - i];
				if ((above - MELTING) * (value - MELTING) <= 0.0) {
					front = z - dz + dz * (MELTING - above) / (value - above);
				}
			}
		}
		return new Errors(worst, Math.abs(front - exact.front(time)), exact.front(time));
	}

	@Test
	public void freezingFollowsNeumann() {
		Errors e = run(MELTING - 5.0, MELTING + 5.0, 300);
		assertTrue("front at " + e.frontDepth(), e.frontDepth() > 0.1);
		assertTrue("front error " + e.front(), e.front() < 0.01);
		assertTrue("temperature error " + e.temperature(), e.temperature() < 0.15);
	}

	@Test
	public void thawingFollowsNeumann() {
		Errors e = run(MELTING + 5.0, MELTING - 5.0, 300);
		assertTrue("front at " + e.frontDepth(), e.frontDepth() > 0.1);
		assertTrue("front error " + e.front(), e.front() < 0.01);
		assertTrue("temperature error " + e.temperature(), e.temperature() < 0.15);
	}

	/** Halving the cells (and keeping the step) halves the front error at least roughly: first order. */
	@Test
	public void frontErrorShrinksWithTheGrid() {
		Errors coarse = run(MELTING - 5.0, MELTING + 5.0, 75);
		Errors fine = run(MELTING - 5.0, MELTING + 5.0, 300);
		assertTrue("coarse " + coarse + " fine " + fine, fine.front() < 0.5 * coarse.front() || fine.front() < 1e-3);
		assertEquals(coarse.frontDepth(), fine.frontDepth(), 0.0);
	}
}
