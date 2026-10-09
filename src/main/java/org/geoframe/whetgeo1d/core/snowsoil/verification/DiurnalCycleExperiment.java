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
package org.geoframe.whetgeo1d.core.snowsoil.verification;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.HeatStepResult;
import org.geoframe.whetgeo1d.core.snowsoil.NewtonSettings;
import org.geoframe.whetgeo1d.core.snowsoil.SnowHeatProperties;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilHeatDriver;
import org.geoframe.whetgeo1d.core.snowsoil.TopBoundary;
import org.geoframe.whetgeo1d.snow.RestratificationPolicy;
import org.geoframe.whetgeo1d.snow.Restratified;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;

/**
 * Fine-grid reference test of the diurnal cycle (specification A17, document
 * 003 §7): the same snow+soil column run with a coarse stratigraphy and with a
 * fine uniform grid, under a sinusoidal forcing at the surface for several
 * days. The coarse stratigraphy is whatever a {@link RestratificationPolicy}
 * builds from a sequence of equal snowfalls of uniform density, so any policy
 * (GEOtop's mass criterion, with or without the top-cell cap, a future graded
 * criterion) can be compared on the same footing.
 *
 * <p>
 * The pack is homogeneous (uniform density, uniform initial temperature) and
 * does not change during the run: no compaction, no restratification, no
 * snowfall. Coarse and fine grids describe exactly the same material, so the
 * differences are discretisation errors only.
 *
 * <p>
 * Two forcings:
 * <ul>
 * <li>{@link Forcing#SURFACE_TEMPERATURE}: T_s(t) = mean + amplitude ·
 * sin(2π t / day) imposed at the top face. The error that matters is the
 * conductive flux into the surface.</li>
 * <li>{@link Forcing#ROBIN}: G = h (T_air(t) - T_top), the linearised surface
 * energy balance of a model whose surface temperature is the temperature of
 * the top cell (GEOtop without skin). T_air follows the same sinusoid. The
 * error that matters is the temperature of the top cell.</li>
 * </ul>
 */
public record DiurnalCycleExperiment(UniformSoil soil, double snowDensity, double snowMass, int snowfalls,
		double initialSnowTemperature, double bottomTemperature, Forcing forcing, double meanTemperature,
		double amplitude, double transferCoefficient, double days, double spinUpDays, double timeDelta,
		SnowThermalConductivity law, double alpha) {

	public static final double DAY = 86400.0;

	/** Kind of surface forcing. */
	public enum Forcing {
		SURFACE_TEMPERATURE, ROBIN
	}

	/**
	 * The case of document 007: 90 kg m-2 of snow at 150 kg m-3 (0,6 m) deposited
	 * in 30 snowfalls, at -5 °C over 1 m of soil at -2 °C (bottom held at -1 °C);
	 * surface at -10 ± 6 °C; 5 days with steps of 10 minutes, the first 2 days
	 * discarded. Everything stays below 0 °C: the case is pure conduction, so
	 * that the differences between grids are discretisation errors and not
	 * differences in where the snow melts.
	 */
	public static DiurnalCycleExperiment standard(Forcing forcing) {
		return new DiurnalCycleExperiment(new UniformSoil(20, 1.0, -0.5, 271.15, "Weighted Harmonic Mean"), 150.0,
				90.0, 30, 268.15, 272.15, forcing, 263.15, 6.0, 10.0, 5.0, 2.0, 600.0,
				SnowThermalConductivity.CALONNE_2011, 1e5);
	}

	/** Same experiment with another forcing. */
	public DiurnalCycleExperiment withForcing(Forcing other) {
		return new DiurnalCycleExperiment(soil, snowDensity, snowMass, snowfalls, initialSnowTemperature,
				bottomTemperature, other, meanTemperature, amplitude, transferCoefficient, days, spinUpDays, timeDelta,
				law, alpha);
	}

	public SnowEquationOfState equationOfState() {
		return new SnowEquationOfState(alpha);
	}

	/** Depth of the pack [m]. */
	public double snowDepth() {
		return snowMass / snowDensity;
	}

	/**
	 * Stratigraphy built by the policy: equal snowfalls of uniform density, each
	 * deposited on the top cell and followed by restratification repeated until
	 * the column stops changing (GEOtop calls it at every step).
	 */
	public List<SnowCell> stratigraphy(RestratificationPolicy policy) {
		SnowEquationOfState eos = equationOfState();
		double mass = snowMass / snowfalls;
		List<SnowCell> cells = new ArrayList<>();
		for (int s = 0; s < snowfalls; s++) {
			SnowCell fresh = SnowCell.atTemperature(eos, mass, mass / snowDensity, initialSnowTemperature);
			cells = SnowColumn.depositFreshSnow(cells, fresh, eos);
			for (int call = 0; call < 100; call++) {
				Restratified next = policy.apply(cells);
				cells = next.cells();
				if (next.merges() == 0 && next.splits() == 0) {
					break;
				}
			}
		}
		return cells;
	}

	/** Uniform grid of cells as close as possible to {@code thickness}. */
	public List<SnowCell> uniformGrid(double thickness) {
		SnowEquationOfState eos = equationOfState();
		int n = Math.max(1, (int) Math.round(snowDepth() / thickness));
		double h = snowDepth() / n;
		List<SnowCell> cells = new ArrayList<>(n);
		for (int j = 0; j < n; j++) {
			cells.add(SnowCell.atTemperature(eos, snowDensity * h, h, initialSnowTemperature));
		}
		return cells;
	}

	/** Forcing temperature at time t: T_s or T_air [K]. */
	public double forcingTemperature(double time) {
		return meanTemperature + amplitude * Math.sin(2.0 * Math.PI * time / DAY);
	}

	/**
	 * Time series of one run, sampled at the end of every step after the
	 * spin-up.
	 *
	 * @param time             [s]
	 * @param snowHeights      height of the snow nodes above the soil [m]
	 * @param snowTemperatures [sample][snow cell] [K]
	 * @param surfaceFlux      heat flux into the surface, positive downwards [W m-2]
	 * @param interfaceFlux    snow → soil flux, positive downwards [W m-2]
	 * @param topTemperature   temperature of the top cell [K]
	 * @param maxClosureError  largest |energy closure| of a step [J m-2]
	 * @param maxOuter         largest number of outer iterations of a step
	 * @param allConverged     every step passed the per-cell check
	 */
	public record Series(double[] time, double[] snowHeights, double[][] snowTemperatures, double[] surfaceFlux,
			double[] interfaceFlux, double[] topTemperature, double maxClosureError, int maxOuter,
			boolean allConverged) {
	}

	/** Runs the column with the given snow cells (index 0 at the bottom). */
	public Series run(List<SnowCell> stratigraphy) {
		SnowEquationOfState eos = equationOfState();
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(soil.setup(),
				new SnowHeatProperties(eos, law, stratigraphy.size()), DiffusionBoundaryConditionType.BOTTOM_DIRICHLET,
				NewtonSettings.defaults());
		int steps = (int) Math.round(days * DAY / timeDelta);
		int skip = (int) Math.round(spinUpDays * DAY / timeDelta);
		int samples = steps - skip;
		double[] time = new double[samples];
		double[][] temperatures = new double[samples][];
		double[] surface = new double[samples];
		double[] interfaceFlux = new double[samples];
		double[] top = new double[samples];
		double maxClosure = 0.0;
		int maxOuter = 0;
		boolean converged = true;

		List<SnowCell> snow = stratigraphy;
		int nSoil = soil.cells();
		double topTemperature = eos.temperature(snow.get(snow.size() - 1));
		for (int s = 0; s < steps; s++) {
			double end = (s + 1) * timeDelta;
			TopBoundary boundary = switch (forcing) {
			case SURFACE_TEMPERATURE -> new TopBoundary.SurfaceTemperature(forcingTemperature(end));
			case ROBIN -> new TopBoundary.SurfaceFlux(
					transferCoefficient * (forcingTemperature(end) - topTemperature), -transferCoefficient);
			};
			HeatStepResult result = driver.step(snow, boundary, bottomTemperature, timeDelta);
			snow = result.snow();
			double[] t = result.temperatures();
			topTemperature = t[t.length - 1];
			maxClosure = Math.max(maxClosure, Math.abs(result.energy().closureError()));
			maxOuter = Math.max(maxOuter, result.newton().outerIterations());
			converged &= result.newton().converged();
			if (s >= skip) {
				int k = s - skip;
				time[k] = end;
				double[] snowT = new double[snow.size()];
				System.arraycopy(t, nSoil, snowT, 0, snow.size());
				temperatures[k] = snowT;
				surface[k] = result.surfaceFlux();
				interfaceFlux[k] = result.snowToSoilFlux();
				top[k] = topTemperature;
			}
		}
		return new Series(time, nodeHeights(stratigraphy), temperatures, surface, interfaceFlux, top, maxClosure,
				maxOuter, converged);
	}

	/** Heights of the cell centres above the soil surface [m]. */
	public static double[] nodeHeights(List<SnowCell> cells) {
		double[] z = new double[cells.size()];
		double base = 0.0;
		for (int j = 0; j < cells.size(); j++) {
			z[j] = base + 0.5 * cells.get(j).thickness();
			base += cells.get(j).thickness();
		}
		return z;
	}

	/**
	 * Errors of a run against the reference, over the samples after the spin-up.
	 * Profile errors compare every coarse node with the reference profile
	 * interpolated linearly at the same height.
	 *
	 * @param surfaceFluxRms        [W m-2]
	 * @param surfaceFluxMax        [W m-2]
	 * @param referenceSurfaceFluxRms RMS of the reference surface flux, the scale
	 *                              of the signal [W m-2]
	 * @param interfaceFluxRms      [W m-2]
	 * @param interfaceFluxMax      [W m-2]
	 * @param profileRms            [K]
	 * @param profileMax            [K]
	 * @param topTemperatureRms     top cell against the reference top cell [K]
	 * @param topTemperatureMax     [K]
	 * @param topAmplitudeRatio     diurnal range of the top-cell temperature over
	 *                              that of the reference [-]
	 */
	public record Errors(double surfaceFluxRms, double surfaceFluxMax, double referenceSurfaceFluxRms,
			double interfaceFluxRms, double interfaceFluxMax, double profileRms, double profileMax,
			double topTemperatureRms, double topTemperatureMax, double topAmplitudeRatio) {
	}

	/** Compares a run with the reference run. */
	public static Errors compare(Series run, Series reference) {
		int samples = run.time().length;
		if (reference.time().length != samples) {
			throw new IllegalArgumentException("runs have different samples");
		}
		double sf2 = 0, sfMax = 0, ref2 = 0, if2 = 0, ifMax = 0, p2 = 0, pMax = 0, t2 = 0, tMax = 0;
		int profilePoints = 0;
		double runMin = Double.MAX_VALUE, runMax = -Double.MAX_VALUE;
		double refMin = Double.MAX_VALUE, refMax = -Double.MAX_VALUE;
		for (int k = 0; k < samples; k++) {
			double sf = run.surfaceFlux()[k] - reference.surfaceFlux()[k];
			sf2 += sf * sf;
			sfMax = Math.max(sfMax, Math.abs(sf));
			ref2 += reference.surfaceFlux()[k] * reference.surfaceFlux()[k];
			double fi = run.interfaceFlux()[k] - reference.interfaceFlux()[k];
			if2 += fi * fi;
			ifMax = Math.max(ifMax, Math.abs(fi));
			double tt = run.topTemperature()[k] - reference.topTemperature()[k];
			t2 += tt * tt;
			tMax = Math.max(tMax, Math.abs(tt));
			runMin = Math.min(runMin, run.topTemperature()[k]);
			runMax = Math.max(runMax, run.topTemperature()[k]);
			refMin = Math.min(refMin, reference.topTemperature()[k]);
			refMax = Math.max(refMax, reference.topTemperature()[k]);
			for (int j = 0; j < run.snowHeights().length; j++) {
				double e = run.snowTemperatures()[k][j]
						- interpolate(reference.snowHeights(), reference.snowTemperatures()[k], run.snowHeights()[j]);
				p2 += e * e;
				pMax = Math.max(pMax, Math.abs(e));
				profilePoints++;
			}
		}
		return new Errors(Math.sqrt(sf2 / samples), sfMax, Math.sqrt(ref2 / samples), Math.sqrt(if2 / samples), ifMax,
				Math.sqrt(p2 / profilePoints), pMax, Math.sqrt(t2 / samples), tMax,
				(runMax - runMin) / (refMax - refMin));
	}

	/** Linear interpolation, constant beyond the end nodes. */
	static double interpolate(double[] x, double[] y, double at) {
		if (at <= x[0]) {
			return y[0];
		}
		int last = x.length - 1;
		if (at >= x[last]) {
			return y[last];
		}
		int lo = 0;
		int hi = last;
		while (hi - lo > 1) {
			int mid = (lo + hi) >>> 1;
			if (x[mid] <= at) {
				lo = mid;
			} else {
				hi = mid;
			}
		}
		double w = (at - x[lo]) / (x[hi] - x[lo]);
		return y[lo] + w * (y[hi] - y[lo]);
	}
}
