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
package org.geoframe.whetgeo1d.io.snowsoil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;
import org.geoframe.whetgeo1d.core.snowsoil.SoilWaterStepResult;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;

/**
 * Collects the steps between two output records, as GEOtop does over
 * {@code DtPlotPoint}: masses and energies of the steps are summed, fluxes
 * [W m-2] averaged over the steps where they are defined (NaN if none), and
 * the state is the one at the end of the last step. The steps are taken to
 * have the same length; the average is over steps, not over time.
 *
 * <p>
 * The scalar quantities are kept by name, in the order of {@link #SCALARS},
 * so that the NetCDF and CSV writers share one list.
 */
final class StepAccumulator {

	/** How a scalar is aggregated between records. */
	enum Kind {
		/** summed over the steps */
		SUM,
		/** averaged over the steps where it is defined */
		MEAN,
		/** value at the end of the last step */
		STATE
	}

	/**
	 * A scalar output.
	 *
	 * @param name  NetCDF name
	 * @param units SI units
	 * @param kind  aggregation
	 * @param description long name
	 */
	record Scalar(String name, String units, Kind kind, String description) {
	}

	static final List<Scalar> SCALARS = List.of(
			// state of the pack
			new Scalar("snowDepth", "m", Kind.STATE, "snow depth HS, eta of the top face of the pack"),
			new Scalar("snowWaterEquivalent", "kg m-2", Kind.STATE, "snow water equivalent, ice plus liquid"),
			new Scalar("snowDensity", "kg m-3", Kind.STATE, "bulk density of the pack, NaN without snow"),
			new Scalar("snowTemperature", "K", Kind.STATE,
					"thickness-weighted mean temperature of the pack (as GEOtop), NaN without snow"),
			new Scalar("snowLiquidMass", "kg m-2", Kind.STATE, "liquid water held by the pack"),
			new Scalar("snowCells", "1", Kind.STATE, "number of snow cells"),
			new Scalar("surfaceAge", "1", Kind.STATE, "non-dimensional age of the snow surface"),
			new Scalar("surfaceTemperature", "K", Kind.STATE,
					"temperature of the top snow cell after the heat step; without snow, of the top soil cell"),
			new Scalar("pendingSurfaceEnergy", "J m-2", Kind.STATE, "energy carried to the next step as surface input"),
			new Scalar("soilWaterStorage", "kg m-2", Kind.STATE, "water stored in the soil, liquid and ice"),
			// surface fluxes
			new Scalar("albedo", "1", Kind.MEAN, "broadband albedo of the snow, NaN without snow"),
			new Scalar("netShortwave", "W m-2", Kind.MEAN, "absorbed shortwave SW_net, NaN without snow"),
			new Scalar("netLongwave", "W m-2", Kind.MEAN, "net longwave, eps (LW_in - sigma Ts^4), NaN without snow"),
			new Scalar("sensibleHeat", "W m-2", Kind.MEAN, "sensible heat H, positive upward, NaN without snow"),
			new Scalar("latentHeat", "W m-2", Kind.MEAN, "latent heat lambda E, positive upward, NaN without snow"),
			new Scalar("surfaceEnergyBalance", "W m-2", Kind.MEAN,
					"SW_net + LW_net - H - lambda E at the final surface temperature, positive into the surface;"
							+ " without snow the heat flux entering the column"),
			new Scalar("surfaceHeatFlux", "W m-2", Kind.MEAN,
					"heat flux entering the column at the top in the heat step, positive downward"),
			new Scalar("snowToSoilHeatFlux", "W m-2", Kind.MEAN,
					"heat flux from the snow into the soil, positive downward, NaN without snow"),
			// masses of the step
			new Scalar("snowfall", "kg m-2", Kind.SUM, "fresh snow deposited"),
			new Scalar("rainOnSnow", "kg m-2", Kind.SUM, "rain entering the pack"),
			new Scalar("rainOnBareSoil", "kg m-2", Kind.SUM,
					"water reaching the soil not through the pack: rain on bare ground"),
			new Scalar("vapour", "kg m-2", Kind.SUM, "vapour exchanged by the pack, positive removed (sublimation)"),
			new Scalar("unmetVapour", "kg m-2", Kind.SUM, "vapour the pack could not supply or take"),
			new Scalar("melt", "kg m-2", Kind.SUM, "ice melted by the heat step, negative for net refreezing"),
			new Scalar("refrozen", "kg m-2", Kind.SUM, "ice gained by refreezing percolating water and rain"),
			new Scalar("snowRunoff", "kg m-2", Kind.SUM,
					"outflow from the base of the pack: drainage and the remains of a vanished pack"),
			new Scalar("soilInput", "kg m-2", Kind.SUM, "water handed to the top of the soil"),
			new Scalar("soilInputEnergy", "J m-2", Kind.SUM, "internal energy of the water handed to the soil"),
			new Scalar("infiltration", "kg m-2", Kind.SUM, "water entering the soil at the top (Richards)"),
			new Scalar("drainage", "kg m-2", Kind.SUM, "water leaving the bottom of the soil, positive out"),
			new Scalar("soilRunoff", "kg m-2", Kind.SUM, "water the soil could not take"),
			new Scalar("evapotranspiration", "kg m-2", Kind.SUM, "water taken by evapotranspiration from the soil"),
			// restratification and convergence
			new Scalar("merges", "1", Kind.SUM, "merges done by the restratification"),
			new Scalar("splits", "1", Kind.SUM, "splits done by the restratification"),
			new Scalar("surfaceSweeps", "1", Kind.SUM, "heat solves done for the surface coupling"),
			new Scalar("steps", "1", Kind.SUM, "solver steps aggregated in the record"),
			new Scalar("heatNotConverged", "1", Kind.SUM, "steps whose heat solve failed the per-cell check"),
			new Scalar("surfaceNotConverged", "1", Kind.SUM, "steps whose surface sweeps did not converge"),
			// budgets and numerical corrections (VAL-7), each on its own
			new Scalar("snowMassClosure", "kg m-2", Kind.SUM, "mass closure of the snowpack operators"),
			new Scalar("snowEnergyClosure", "J m-2", Kind.SUM, "energy closure of the snowpack operators"),
			new Scalar("heatClosure", "J m-2", Kind.SUM,
					"storage change minus boundary inputs of the heat solve: the sum of its Newton residuals"),
			new Scalar("linearisationEnergy", "J m-2", Kind.SUM,
					"energy of the linearised surface flux beyond the nonlinear balance"),
			new Scalar("soilWaterBalanceError", "kg m-2", Kind.SUM, "balance error of Richards"),
			new Scalar("soilEnergyClosure", "J m-2", Kind.SUM, "energy closure of the soil"),
			new Scalar("soilStorageHeat", "J m-2", Kind.SUM,
					"energy created by the water above saturation of a freezing soil (correction, VAL-7)")
	// HOOK(column-balance): the whole-column balance of VAL-7 (new types of core/snowsoil) goes here,
	// as further SUM scalars; the writers pick up every entry of this list.
	);

	private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

	static {
		for (Scalar s : SCALARS) {
			KINDS.put(s.name(), s.kind());
		}
	}

	private final OutputOptions options;
	private final Map<String, double[]> sums = new LinkedHashMap<>();
	private int steps;

	private List<SnowCell> snow = List.of();
	private SurfaceFluxes bareSoil;
	private double bareSoilTemperature = Double.NaN;
	private double[] soilTemperature;
	private double[] soilIce;
	private double[] soilSuction;
	private double[] soilTheta;
	private double[] faceWater;

	StepAccumulator(OutputOptions options) {
		this.options = options;
		reset();
	}

	private void reset() {
		for (Scalar s : SCALARS) {
			// [0] sum or state, [1] number of defined values
			sums.put(s.name(), new double[] { s.kind() == Kind.STATE ? Double.NaN : 0.0, 0.0 });
		}
		steps = 0;
		faceWater = null;
	}

	private void put(String name, double value) {
		double[] slot = sums.get(name);
		switch (KINDS.get(name)) {
		case STATE -> {
			slot[0] = value;
			slot[1] = 1;
		}
		case SUM, MEAN -> {
			if (!Double.isNaN(value)) {
				slot[0] += value;
				slot[1] += 1;
			}
		}
		}
	}

	/** The bare-soil surface fluxes of the next step added, or null. */
	void bareSoilSurface(SurfaceFluxes fluxes, double surfaceTemperature) {
		bareSoil = fluxes;
		bareSoilTemperature = surfaceTemperature;
	}

	/** Adds one step. */
	void add(SnowSoilSolver solver, SnowSoilStepResult r) {
		steps++;
		snow = r.snow();
		soilTemperature = solver.soilTemperatures();
		soilIce = solver.soilIce();
		soilSuction = solver.soilSuctions();
		soilTheta = options.soilWaterContent() == null ? null : options.soilWaterContent().liquid(solver);

		double hs = r.snowDepth();
		double swe = r.snowWaterEquivalent();
		double weighted = 0.0;
		double liquid = 0.0;
		for (SnowCell c : snow) {
			weighted += options.equationOfState().temperature(c) * c.thickness();
			liquid += c.liquidMass();
		}
		put("snowDepth", hs);
		put("snowWaterEquivalent", swe);
		put("snowDensity", snow.isEmpty() ? Double.NaN : swe / hs);
		put("snowTemperature", snow.isEmpty() ? Double.NaN : weighted / hs);
		put("snowLiquidMass", liquid);
		put("snowCells", snow.size());
		put("surfaceAge", r.surfaceAge());
		double ts = r.surfaceTemperature();
		if (Double.isNaN(ts) && bareSoil != null) {
			ts = bareSoilTemperature;
		}
		if (Double.isNaN(ts) && soilTemperature.length > 0) {
			ts = soilTemperature[soilTemperature.length - 1];
		}
		put("surfaceTemperature", ts);
		put("pendingSurfaceEnergy", r.pendingSurfaceEnergy());
		put("soilWaterStorage", solver.soilWaterStorage());

		SurfaceFluxes f = r.surface() != null ? r.surface() : bareSoil;
		bareSoil = null;
		double topFlux = r.heat().surfaceFlux();
		put("albedo", r.albedo());
		put("netShortwave", f == null ? Double.NaN : f.netShortwave());
		put("netLongwave", f == null ? Double.NaN : f.netLongwave());
		put("sensibleHeat", f == null ? Double.NaN : f.sensibleHeat());
		put("latentHeat", f == null ? Double.NaN : f.latentHeatFlux());
		put("surfaceEnergyBalance", r.surface() == null ? topFlux : f.energyBalance());
		put("surfaceHeatFlux", topFlux);
		put("snowToSoilHeatFlux", r.heat().snowToSoilFlux());

		put("snowfall", r.budget().snowfall());
		put("rainOnSnow", r.budget().rain());
		put("rainOnBareSoil", r.soilInputMass() - r.budget().toSoilMass());
		put("vapour", r.budget().vapour());
		put("unmetVapour", r.unmetVapour());
		put("melt", r.melt());
		put("refrozen", r.refrozen());
		put("snowRunoff", r.budget().toSoilMass());
		put("soilInput", r.soilInputMass());
		put("soilInputEnergy", r.soilInputEnergy());
		SoilWaterStepResult w = r.soilWater();
		put("infiltration", w == null ? Double.NaN : w.infiltration());
		put("drainage", w == null ? Double.NaN : w.drainage());
		put("soilRunoff", w == null ? Double.NaN : w.runoff());
		put("evapotranspiration", w == null ? Double.NaN : w.evapotranspiration());

		put("merges", r.merges());
		put("splits", r.splits());
		put("surfaceSweeps", r.surfaceSweeps());
		put("steps", 1);
		put("heatNotConverged", r.heat().newton().converged() ? 0 : 1);
		put("surfaceNotConverged", r.surfaceConverged() ? 0 : 1);

		put("snowMassClosure", r.budget().massClosure());
		put("snowEnergyClosure", r.budget().energyClosure());
		put("heatClosure", r.heat().energy().closureError());
		put("linearisationEnergy", r.linearisationEnergy());
		put("soilWaterBalanceError", w == null ? Double.NaN : w.balanceError());
		put("soilEnergyClosure", r.soilEnergyClosure());
		put("soilStorageHeat", r.soilStorageHeat());

		if (w != null) {
			if (faceWater == null) {
				faceWater = new double[w.faceWater().length];
			}
			for (int i = 0; i < faceWater.length; i++) {
				faceWater[i] += w.faceWater()[i];
			}
		}
	}

	/** Steps added since the last record. */
	int steps() {
		return steps;
	}

	/** The record of the steps added, and a fresh start for the next one. */
	Record take() {
		Map<String, Double> values = new LinkedHashMap<>();
		for (Scalar s : SCALARS) {
			double[] slot = sums.get(s.name());
			double v = switch (s.kind()) {
			case STATE -> slot[0];
			case SUM -> slot[1] > 0 ? slot[0] : Double.NaN;
			case MEAN -> slot[1] > 0 ? slot[0] / slot[1] : Double.NaN;
			};
			values.put(s.name(), v);
		}
		Record rec = new Record(values, snow, soilTemperature, soilIce, soilSuction, soilTheta, faceWater);
		reset();
		return rec;
	}

	/**
	 * One output record.
	 *
	 * @param scalars         by the names of {@link #SCALARS}
	 * @param snow            the pack at the end, index 0 at the bottom
	 * @param soilTemperature [K], bottom first
	 * @param soilIce         θ_i [-], bottom first
	 * @param soilSuction     ψ [m], bottom first
	 * @param soilTheta       θ_w [-], bottom first; null when not available
	 * @param faceWater       water through the soil faces summed over the
	 *                        steps [kg m-2], positive upward, face 0 at the
	 *                        bottom; null without soil water
	 */
	record Record(Map<String, Double> scalars, List<SnowCell> snow, double[] soilTemperature, double[] soilIce,
			double[] soilSuction, double[] soilTheta, double[] faceWater) {

		double get(String name) {
			return scalars.get(name);
		}
	}

	/**
	 * Whether a step ending at {@code epochSeconds}, after one ending at
	 * {@code previous} (Long.MIN_VALUE for the first), closes a record: always
	 * with a zero interval, otherwise when it reaches or crosses a multiple of
	 * the interval.
	 */
	static boolean closesRecord(long previous, long epochSeconds, long interval) {
		if (interval == 0) {
			return true;
		}
		if (Math.floorMod(epochSeconds, interval) == 0) {
			return true;
		}
		return previous != Long.MIN_VALUE && Math.floorDiv(epochSeconds, interval) > Math.floorDiv(previous, interval);
	}
}
