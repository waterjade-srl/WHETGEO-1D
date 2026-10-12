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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

import java.util.List;
import java.util.function.UnaryOperator;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.NewtonSettings;
import org.geoframe.whetgeo1d.core.snowsoil.SnowHeatProperties;
import org.geoframe.whetgeo1d.core.snowsoil.SnowProcesses;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;
import org.geoframe.whetgeo1d.core.snowsoil.SoilWaterProperties;
import org.geoframe.whetgeo1d.core.snowsoil.StepForcing;
import org.geoframe.whetgeo1d.core.snowsoil.SurfaceCoupling;
import org.geoframe.whetgeo1d.core.snowsoil.TopBoundary;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;

/**
 * A synthetic snow season on a flat point, hourly, for the integrated step
 * (document 009): accumulation in a cold winter, a rain-on-snow event, a
 * spring warming that melts the pack out. The forcing is analytic, so the run
 * is reproducible to the bit and needs no data file.
 *
 * <ul>
 * <li>Air temperature: -6 °C until day 80, then rising linearly to +8 °C at day
 * 150, with a diurnal cycle of ± 4 °C peaking at 15:00.</li>
 * <li>Snowfall: 30 kg m-2 in 10 hours on days 2, 10, 20, 30, 40, 50 and 70,
 * from midnight.</li>
 * <li>Rain on snow: 24 kg m-2 in 12 hours on day 60 at +2 °C.</li>
 * <li>Shortwave: clear sky from 06:00 to 18:00, peak 300 W m-2 at day 0 rising
 * to 800 W m-2 at day 150, 70 % direct; a third of it under precipitation.</li>
 * <li>Longwave: ε_a σ T_a⁴, ε_a = 0.7, 0.95 under precipitation.</li>
 * <li>RH 0.8 (0.95 under precipitation), wind 2 m s-1, P = 85 kPa, measurement
 * heights 2 m.</li>
 * <li>Soil: 2 m of {@link UniformSoil} at +5 °C, bottom held at +4 °C; bare-soil
 * surface flux h (T_a - T_top), h = 10 W m-2 K-1. With soil water
 * ({@link #solverWithSoilWater}) the soil is a sandy loam at ψ = -1 m with free
 * drainage ({@link #wetSoil()}).</li>
 * </ul>
 *
 * @param days      length of the run [d]
 * @param timeDelta step [s]
 */
public record SyntheticSeason(double days, double timeDelta) {

	public static final double DAY = 86400.0;
	/** Ice content above which a soil cell counts as frozen in {@link Series#frostDepth()} [-]. */
	public static final double FROST_ICE = 1e-3;
	public static final double ALPHA = 1e5;
	public static final double BARE_SOIL_TRANSFER = 10.0;
	public static final double[] SNOWFALL_DAYS = { 2, 10, 20, 30, 40, 50, 70 };
	public static final double SNOWFALL_HOURS = 10.0;
	public static final double SNOWFALL_RATE = 3.0;
	public static final double RAIN_DAY = 60.0;
	public static final double RAIN_HOURS = 12.0;
	public static final double RAIN_RATE = 2.0;

	/** The case of document 009: 150 days, hourly. */
	public static SyntheticSeason standard() {
		return new SyntheticSeason(150.0, 3600.0);
	}

	/** Which configuration to run. */
	public enum Configuration {
		/** GEOtop 3.0 options, Cosenza conductivity. */
		FIDELITY,
		/** Recommended options as implemented, Jordan 1991 conductivity (A16 provisional). */
		RECOMMENDED
	}

	public UniformSoil soil() {
		return new UniformSoil(20, 2.0, -0.5, MELTING_TEMPERATURE + 5.0, "Weighted Harmonic Mean");
	}

	/** The soil of the runs with soil water: sandy loam at ψ = -1 m (document 011). */
	public UniformSoil wetSoil() {
		return new UniformSoil(20, 2.0, -1.0, MELTING_TEMPERATURE + 5.0, "Weighted Harmonic Mean",
				UniformSoil.Hydraulics.sandyLoam());
	}

	public double bottomTemperature() {
		return MELTING_TEMPERATURE + 4.0;
	}

	public int steps() {
		return (int) Math.round(days * DAY / timeDelta);
	}

	/** The solver of the given configuration, with no snow and age zero. */
	public SnowSoilSolver solver(Configuration configuration) {
		return solver(configuration, (SurfaceCoupling) null);
	}

	/** Same, with another surface coupling (null keeps the configuration's). */
	public SnowSoilSolver solver(Configuration configuration, SurfaceCoupling coupling) {
		return solver(configuration, coupling == null ? p -> p : p -> p.withSurfaceCoupling(coupling));
	}

	/**
	 * Same, with the processes of the configuration changed, for instance to
	 * another restratification policy (at most 11 cells).
	 */
	public SnowSoilSolver solver(Configuration configuration, UnaryOperator<SnowProcesses> change) {
		return solver(configuration, change, configuration == Configuration.FIDELITY ? 10 : 11);
	}

	/** Same, with the snow capacity of the heat column given. */
	public SnowSoilSolver solver(Configuration configuration, UnaryOperator<SnowProcesses> change, int maxSnowCells) {
		return solver(configuration, change, maxSnowCells, null);
	}

	/**
	 * The solver of the given configuration with Richards in the soil
	 * (document 011): Van Genuchten, free drainage.
	 */
	public SnowSoilSolver solverWithSoilWater(Configuration configuration) {
		return solver(configuration, p -> p, configuration == Configuration.FIDELITY ? 10 : 11,
				SoilWaterProperties.vanGenuchtenFreeDrainage());
	}

	/**
	 * The solver with Richards in a freezing soil (document 012): the sandy loam
	 * with WHETGEO's freezing curve.
	 */
	public SnowSoilSolver solverWithFrozenSoil(Configuration configuration) {
		return solver(configuration, p -> p, configuration == Configuration.FIDELITY ? 10 : 11,
				SoilWaterProperties.vanGenuchtenFreeDrainage(), true);
	}

	/** Same, with the hydraulic side of the soil given (null: fixed suction). */
	public SnowSoilSolver solver(Configuration configuration, UnaryOperator<SnowProcesses> change, int maxSnowCells,
			SoilWaterProperties soilWater) {
		return solver(configuration, change, maxSnowCells, soilWater, false);
	}

	private SnowSoilSolver solver(Configuration configuration, UnaryOperator<SnowProcesses> change, int maxSnowCells,
			SoilWaterProperties soilWater, boolean freezingSoil) {
		SnowEquationOfState eos = new SnowEquationOfState(ALPHA);
		boolean fidelity = configuration == Configuration.FIDELITY;
		SnowProcesses processes = fidelity ? SnowProcesses.geotop(eos, 2.0, 2.0)
				: SnowProcesses.recommended(eos, 2.0, 2.0);
		processes = change.apply(processes);
		SnowHeatProperties heat = new SnowHeatProperties(eos,
				fidelity ? SnowThermalConductivity.COSENZA : SnowThermalConductivity.JORDAN_1991, maxSnowCells);
		UniformSoil column = soilWater == null ? soil() : wetSoil();
		return new SnowSoilSolver(freezingSoil ? column.freezingSetup() : column.setup(), soilWater, heat, DiffusionBoundaryConditionType.BOTTOM_DIRICHLET,
				NewtonSettings.defaults(), processes, List.of(), 0.0);
	}

	/** Air temperature at time t [K]. */
	public double airTemperature(double time) {
		double day = time / DAY;
		if (raining(time)) {
			return MELTING_TEMPERATURE + 2.0;
		}
		double mean = -6.0 + 14.0 * Math.max(0.0, day - 80.0) / 70.0;
		double hour = (time % DAY) / 3600.0;
		return MELTING_TEMPERATURE + mean + 4.0 * Math.sin(2.0 * Math.PI * (hour - 9.0) / 24.0);
	}

	/** Snowfall in the step starting at t [kg m-2]. */
	public double snowfall(double time) {
		for (double day : SNOWFALL_DAYS) {
			double start = day * DAY;
			if (time >= start && time < start + SNOWFALL_HOURS * 3600.0) {
				return SNOWFALL_RATE * timeDelta / 3600.0;
			}
		}
		return 0.0;
	}

	/** Rainfall in the step starting at t [kg m-2]. */
	public double rainfall(double time) {
		return raining(time) ? RAIN_RATE * timeDelta / 3600.0 : 0.0;
	}

	private boolean raining(double time) {
		double start = RAIN_DAY * DAY;
		return time >= start && time < start + RAIN_HOURS * 3600.0;
	}

	/** Surface forcing of the step starting at t, at the middle of the step. */
	public SurfaceForcing surface(double time) {
		double middle = time + 0.5 * timeDelta;
		double day = middle / DAY;
		double hour = (middle % DAY) / 3600.0;
		boolean precipitation = snowfall(time) > 0.0 || rainfall(time) > 0.0;
		double elevation = hour > 6.0 && hour < 18.0 ? Math.sin(Math.PI * (hour - 6.0) / 12.0) : 0.0;
		double shortwave = (300.0 + 500.0 * day / 150.0) * elevation * (precipitation ? 1.0 / 3.0 : 1.0);
		double airTemperature = airTemperature(middle);
		double emissivity = precipitation ? 0.95 : 0.7;
		return new SurfaceForcing(0.7 * shortwave, 0.3 * shortwave, elevation,
				emissivity * AirProperties.emissivePower(airTemperature), airTemperature, precipitation ? 0.95 : 0.8,
				2.0, 85000.0);
	}

	/** Forcing of the step starting at t, with the soil top temperature for the bare-soil flux. */
	public StepForcing forcing(double time, double soilTopTemperature) {
		SurfaceForcing surface = surface(time);
		TopBoundary bare = new TopBoundary.SurfaceFlux(
				BARE_SOIL_TRANSFER * (surface.airTemperature() - soilTopTemperature), -BARE_SOIL_TRANSFER);
		return new StepForcing(surface, snowfall(time), rainfall(time), 0.2, 0.2, bare, bottomTemperature(),
				timeDelta);
	}

	/**
	 * Time series of a run, one entry per step, at the end of the step.
	 *
	 * @param time               [s]
	 * @param swe                [kg m-2]
	 * @param depth              [m]
	 * @param cells              number of snow cells
	 * @param surfaceTemperature top-cell temperature after the heat step [K],
	 *                           NaN without snow
	 * @param airTemperature     [K]
	 * @param albedo             broadband, NaN without snow
	 * @param melt               ice melted in the heat step [kg m-2]
	 * @param refrozen           ice refrozen by percolation [kg m-2]
	 * @param vapour             vapour exchanged, positive removed [kg m-2]
	 * @param soilInput          water entering the soil [kg m-2]
	 * @param soilTopTemperature [K]
	 * @param interfaceFlux      snow → soil heat flux [W m-2], NaN without snow
	 * @param massClosure        [kg m-2]
	 * @param energyClosure      [J m-2]
	 * @param heatClosure        closure of the heat step of the whole column
	 *                           [J m-2]
	 * @param linearisation      [J m-2]
	 * @param converged          the heat step passed the per-cell check
	 * @param outer              outer Newton iterations of the heat step, last
	 *                           sweep
	 * @param remaps             merges plus splits of the restratification
	 * @param sweeps             heat solves of the step
	 * @param surfaceConverged   the surface sweeps converged
	 * @param soilStorage        water stored in the soil [kg m-2], NaN without
	 *                           soil water
	 * @param soilDrainage       water leaving the bottom of the soil [kg m-2]
	 * @param soilWaterError     Richards balance error of the step [kg m-2]
	 * @param soilEnergyClosure  soil energy closure of the step [J m-2]
	 * @param soilEnergy         internal energy of the soil [J m-2]
	 * @param topSoilSuction     ψ of the top soil cell [m]
	 * @param soilRunoff         water the soil could not take [kg m-2]
	 * @param soilStorageHeat    energy created by the water above saturation in
	 *                           the heat step [J m-2]
	 * @param soilIce            ice in the soil, water equivalent [kg m-2]
	 * @param frostDepth         depth of the bottom of the deepest soil cell
	 *                           with more than {@value #FROST_ICE} of ice [m], 0
	 *                           without
	 */
	public record Series(double[] time, double[] swe, double[] depth, int[] cells, double[] surfaceTemperature,
			double[] airTemperature, double[] albedo, double[] melt, double[] refrozen, double[] vapour,
			double[] soilInput, double[] soilTopTemperature, double[] interfaceFlux, double[] massClosure,
			double[] energyClosure, double[] heatClosure, double[] linearisation, boolean[] converged, int[] outer,
			int[] remaps, int[] sweeps, boolean[] surfaceConverged, double[] soilStorage, double[] soilDrainage,
			double[] soilWaterError, double[] soilEnergyClosure, double[] soilEnergy, double[] topSoilSuction,
			double[] soilRunoff, double[] soilStorageHeat, double[] soilIce, double[] frostDepth) {
	}

	/** Runs the season with the given configuration. */
	public Series run(Configuration configuration) {
		return run(solver(configuration));
	}

	/** Same, with the surface coupling replaced. */
	public Series run(Configuration configuration, SurfaceCoupling coupling) {
		return run(solver(configuration, coupling));
	}

	/** Runs the season with the given solver, which must start with no snow. */
	public Series run(SnowSoilSolver solver) {
		int n = steps();
		Series s = new Series(new double[n], new double[n], new double[n], new int[n], new double[n], new double[n],
				new double[n], new double[n], new double[n], new double[n], new double[n], new double[n],
				new double[n], new double[n], new double[n], new double[n], new double[n], new boolean[n], new int[n],
				new int[n], new int[n], new boolean[n], new double[n], new double[n], new double[n], new double[n],
				new double[n], new double[n], new double[n], new double[n], new double[n], new double[n]);
		for (int k = 0; k < n; k++) {
			double time = k * timeDelta;
			double[] soil = solver.soilTemperatures();
			StepForcing forcing = forcing(time, soil[soil.length - 1]);
			SnowSoilStepResult r = solver.step(forcing);
			double[] soilAfter = solver.soilTemperatures();
			s.time()[k] = time + timeDelta;
			s.swe()[k] = r.snowWaterEquivalent();
			s.depth()[k] = r.snowDepth();
			s.cells()[k] = r.snow().size();
			s.surfaceTemperature()[k] = r.surfaceTemperature();
			s.airTemperature()[k] = forcing.surface().airTemperature();
			s.albedo()[k] = r.albedo();
			s.melt()[k] = r.melt();
			s.refrozen()[k] = r.refrozen();
			s.vapour()[k] = r.budget().vapour();
			s.soilInput()[k] = r.soilInputMass();
			s.soilTopTemperature()[k] = soilAfter[soilAfter.length - 1];
			s.interfaceFlux()[k] = r.heat().snowToSoilFlux();
			s.massClosure()[k] = r.budget().massClosure();
			s.energyClosure()[k] = r.budget().energyClosure();
			s.heatClosure()[k] = r.heat().energy().closureError();
			s.linearisation()[k] = r.linearisationEnergy();
			s.converged()[k] = r.heat().newton().converged();
			s.outer()[k] = r.heat().newton().outerIterations();
			s.remaps()[k] = r.merges() + r.splits();
			s.sweeps()[k] = r.surfaceSweeps();
			s.surfaceConverged()[k] = r.surfaceConverged();
			s.soilStorage()[k] = solver.soilWaterStorage();
			s.soilDrainage()[k] = r.soilWater() == null ? Double.NaN : r.soilWater().drainage();
			s.soilWaterError()[k] = r.soilWater() == null ? Double.NaN : r.soilWater().balanceError();
			s.soilEnergyClosure()[k] = r.soilEnergyClosure();
			s.soilEnergy()[k] = solver.soilInternalEnergy();
			double[] psi = solver.soilSuctions();
			s.topSoilSuction()[k] = psi[psi.length - 1];
			double[] ice = solver.soilIce();
			UniformSoil grid = wetSoil();
			double iceMass = 0.0;
			double frost = 0.0;
			for (int i = 0; i < ice.length; i++) {
				iceMass += ice[i] * grid.cellSize() * 1000.0;
				if (ice[i] > FROST_ICE) {
					frost = Math.max(frost, grid.depth() - i * grid.cellSize());
				}
			}
			s.soilRunoff()[k] = r.soilWater() == null ? Double.NaN : r.soilWater().runoff();
			s.soilStorageHeat()[k] = r.soilStorageHeat();
			s.soilIce()[k] = iceMass;
			s.frostDepth()[k] = frost;
		}
		return s;
	}
}
