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

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.apache.commons.math3.special.Erf;
import org.geoframe.closureequation.equationstate.EquationState;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.finitevolume.Diffusion1DKernel;
import org.junit.Test;

/**
 * VAL-6: Lunardini's three-zone solution (InterFrost TH1, the case of
 * FreeThaw1D's {@code TestLunardini}) against WHETGEO-1D's heat kernel
 * ({@link Diffusion1DKernel}, nested Newton–Thomas).
 * <p>
 * The liquid water content falls linearly from the porosity φ = 0.336 at T<sub>f</sub>
 * = 0 °C to φ S<sub>r</sub>, S<sub>r</sub> = 0.391, at T<sub>m</sub> = -0.1 °C,
 * and stays there below; the heat capacity is 690 030 J m-3 K-1 everywhere and
 * the conductivity is constant in each zone (frozen, mushy, thawed), as in
 * FreeThaw1D's {@code InternalEnergyLunardini}, {@code ThermalCondLunardini} and
 * {@code LunardiniSFCC}. The closure here is that test closure, written for the
 * kernel; it is not one of WHETGEO's soils, whose freezing curve is
 * Dall'Amico's ({@link NeumannStefanTest} covers that one).
 * <p>
 * Each zone then obeys the linear heat equation with constant properties, the
 * two fronts move as √t, and the similarity solution is exact. A 7.5 m column
 * at +4 °C is frozen from the top at -6 °C for 10 days, steps of one hour, 1 cm
 * cells, the bottom held at +4 °C.
 */
public class LunardiniTest {

	private static final double TF = 273.15;
	private static final double TM = 273.05;
	private static final double POROSITY = 0.336;
	private static final double RESIDUAL = 0.391;
	private static final double CAPACITY = 690030.0;
	private static final double LATENT = 334000.0;
	private static final double DENSITY = 1000.0;
	private static final double K_FROZEN = 3.462696;
	private static final double K_MUSHY = 2.939946;
	private static final double K_THAWED = 2.417196;
	private static final double DEPTH = 7.5;
	private static final double STEP = 3600.0;
	private static final int STEPS = 10 * 24;
	private static final double SURFACE = 267.15;
	private static final double INITIAL = 277.15;

	/** Energy per unit area of a cell [J m-2]: sensible heat plus the latent heat of the liquid. */
	private static final class LinearFreezing extends EquationState {
		private final double[] volume;

		LinearFreezing(double[] volume) {
			super(null);
			this.volume = volume;
		}

		private static double liquid(double t) {
			if (t >= TF) {
				return POROSITY;
			}
			if (t <= TM) {
				return POROSITY * RESIDUAL;
			}
			return POROSITY * (RESIDUAL + (1.0 - RESIDUAL) * (t - TM) / (TF - TM));
		}

		private static double dLiquid(double t) {
			return t > TM && t < TF ? POROSITY * (1.0 - RESIDUAL) / (TF - TM) : 0.0;
		}

		@Override
		public double equationState(double x, double y, int id, int element) {
			return (CAPACITY * (x - TF) + DENSITY * LATENT * liquid(x)) * volume[element];
		}

		@Override
		public double dEquationState(double x, double y, int id, int element) {
			return (CAPACITY + DENSITY * LATENT * dLiquid(x)) * volume[element];
		}

		@Override
		public double ddEquationState(double x, double y, int id, int element) {
			return 0.0;
		}

		/** Convex part: the slope up to T_f (the inflection), then held. */
		@Override
		public double p(double x, double y, int id, int element) {
			return x <= TF ? dEquationState(x, y, id, element) : dEquationState(TF - 1e-9, y, id, element);
		}

		@Override
		public double pIntegral(double x, double y, int id, int element) {
			if (x <= TF) {
				return equationState(x, y, id, element);
			}
			return equationState(TF, y, id, element) + p(x, y, id, element) * (x - TF);
		}

		@Override
		public void computeXStar(double y, int id, int element) {
		}

		@Override
		public double initialGuess(double x, int id, int element) {
			return TF;
		}
	}

	private static double conductivity(double t) {
		return t >= TF ? K_THAWED : t <= TM ? K_FROZEN : K_MUSHY;
	}

	/** Lunardini's solution: fronts at T_m (2 a √t) and T_f (2 b √t), η = z / (2 √t). */
	private record Lunardini(double a, double b, double c2) {

		static double k1() {
			return K_FROZEN / CAPACITY;
		}

		static double k3() {
			return K_THAWED / CAPACITY;
		}

		double k2() {
			return K_MUSHY / c2;
		}

		double mushyAmplitude(double a, double b) {
			return (TF - TM) / (Erf.erf(b / Math.sqrt(k2())) - Erf.erf(a / Math.sqrt(k2())));
		}

		static Lunardini solve() {
			double c2 = CAPACITY + DENSITY * LATENT * POROSITY * (1.0 - RESIDUAL) / (TF - TM);
			Lunardini shape = new Lunardini(0.0, 0.0, c2);
			double low = 1e-6;
			double high = 1e-2;
			double b = Double.NaN;
			for (int i = 0; i < 200; i++) {
				double a = 0.5 * (low + high);
				b = shape.lowerFront(a);
				double frozen = K_FROZEN * (TM - SURFACE) * Math.exp(-a * a / k1())
						/ (Math.sqrt(k1()) * Erf.erf(a / Math.sqrt(k1())));
				double mushy = K_MUSHY * shape.mushyAmplitude(a, b) * Math.exp(-a * a / shape.k2())
						/ Math.sqrt(shape.k2());
				if (frozen > mushy) {
					low = a;
				} else {
					high = a;
				}
			}
			double a = 0.5 * (low + high);
			return new Lunardini(a, shape.lowerFront(a), c2);
		}

		/** The front at T_f for the front at T_m given: the flux balance between mushy and thawed zones. */
		double lowerFront(double a) {
			double low = a * (1.0 + 1e-12);
			double high = a + 1.0;
			for (int i = 0; i < 200; i++) {
				double b = 0.5 * (low + high);
				double mushy = K_MUSHY * mushyAmplitude(a, b) * Math.exp(-b * b / k2()) / Math.sqrt(k2());
				double thawed = K_THAWED * (INITIAL - TF) * Math.exp(-b * b / k3())
						/ (Math.sqrt(k3()) * Erf.erfc(b / Math.sqrt(k3())));
				if (mushy > thawed) {
					low = b;
				} else {
					high = b;
				}
			}
			return 0.5 * (low + high);
		}

		double temperature(double z, double time) {
			double eta = z / (2.0 * Math.sqrt(time));
			if (eta <= a) {
				return SURFACE + (TM - SURFACE) * Erf.erf(eta / Math.sqrt(k1())) / Erf.erf(a / Math.sqrt(k1()));
			}
			if (eta <= b) {
				double amplitude = mushyAmplitude(a, b);
				return TM + amplitude * (Erf.erf(eta / Math.sqrt(k2())) - Erf.erf(a / Math.sqrt(k2())));
			}
			return INITIAL - (INITIAL - TF) * Erf.erfc(eta / Math.sqrt(k3())) / Erf.erfc(b / Math.sqrt(k3()));
		}
	}

	/** Worst temperature error above 3 m, and the errors of the two fronts [m]. */
	private record Errors(double temperature, double frozenFront, double thawedFront) {
	}

	private static double crossing(double[] tTopDown, double dz, double level) {
		for (int i = 1; i < tTopDown.length; i++) {
			if ((tTopDown[i - 1] - level) * (tTopDown[i] - level) <= 0.0 && tTopDown[i] != tTopDown[i - 1]) {
				return (i - 0.5) * dz + dz * (level - tTopDown[i - 1]) / (tTopDown[i] - tTopDown[i - 1]);
			}
		}
		return Double.NaN;
	}

	private static Errors run(int cells) {
		double dz = DEPTH / cells;
		double[] volume = new double[cells];
		Arrays.fill(volume, dz);
		double[] distance = new double[cells + 1];
		Arrays.fill(distance, dz);
		distance[0] = 0.5 * dz;
		distance[cells] = 0.5 * dz;
		LinearFreezing closure = new LinearFreezing(volume);
		List<EquationState> closures = List.of(closure);
		IBoundaryCondition top = IBoundaryCondition
				.createDiffusionBoundaryCondition(DiffusionBoundaryConditionType.TOP_DIRICHLET);
		IBoundaryCondition bottom = IBoundaryCondition
				.createDiffusionBoundaryCondition(DiffusionBoundaryConditionType.BOTTOM_DIRICHLET);
		Diffusion1DKernel kernel = new Diffusion1DKernel(top, bottom, cells, 1, 1e-3, 0.0, 100, closures);
		double[] t = new double[cells];
		Arrays.fill(t, INITIAL);
		double[] suction = new double[cells];
		int[] ids = new int[cells];
		double[] sources = new double[cells];
		double[] faces = new double[cells + 1];
		double[] energy = new double[cells];
		for (int k = 0; k < STEPS; k++) {
			for (int i = 0; i < cells; i++) {
				energy[i] = closure.equationState(t[i], 0.0, 0, i);
			}
			double[] start = t.clone();
			double[] guess = t.clone();
			// conductivities refreshed from the last iterate, as FreeThaw1D's two Picard iterations
			for (int picard = 0; picard < 3; picard++) {
				faces[0] = conductivity(guess[0]);
				faces[cells] = conductivity(guess[cells - 1]);
				for (int i = 1; i < cells; i++) {
					faces[i] = 0.5 * (conductivity(guess[i - 1]) + conductivity(guess[i]));
				}
				guess = kernel.solve(STEP, INITIAL, SURFACE, cells, faces, energy, distance, sources, start.clone(),
						suction, ids, ids);
			}
			t = guess;
		}
		Lunardini exact = Lunardini.solve();
		double time = STEPS * STEP;
		double[] topDown = new double[cells];
		double worst = 0.0;
		for (int i = 0; i < cells; i++) {
			topDown[i] = t[cells - 1 - i];
			double z = (i + 0.5) * dz;
			if (z < 3.0) {
				worst = Math.max(worst, Math.abs(topDown[i] - exact.temperature(z, time)));
			}
		}
		double frozen = crossing(topDown, dz, TM) - 2.0 * exact.a() * Math.sqrt(time);
		double thawed = crossing(topDown, dz, TF - 1e-6) - 2.0 * exact.b() * Math.sqrt(time);
		return new Errors(worst, Math.abs(frozen), Math.abs(thawed));
	}

	@Test
	public void freezingFollowsLunardini() {
		Errors e = run(750);
		assertTrue(e.toString(), e.frozenFront() < 0.005 && e.thawedFront() < 0.005);
		assertTrue(e.toString(), e.temperature() < 0.05);
	}

	@Test
	public void errorsShrinkWithTheGrid() {
		Errors coarse = run(188);
		Errors fine = run(750);
		assertTrue("coarse " + coarse + " fine " + fine, fine.temperature() < 0.5 * coarse.temperature());
	}
}
