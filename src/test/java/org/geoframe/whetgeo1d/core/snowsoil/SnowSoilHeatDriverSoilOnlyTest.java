package org.geoframe.whetgeo1d.core.snowsoil;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.solvers.HeatDiffusionSolver1D;
import org.junit.Test;

/**
 * With no snow the driver must be WHETGEO's soil heat solver: same closures,
 * same kernel, same tolerance, bitwise the same temperatures.
 */
public class SnowSoilHeatDriverSoilOnlyTest {

	private static final double STEP = 3600.0;
	private static final double TOLERANCE = 1e-6;

	private static final UniformSoil SOIL = new UniformSoil(20, 1.0, -0.5, 278.15, "max");

	private static SnowHeatProperties snowProperties() {
		return new SnowHeatProperties(new SnowEquationOfState(1e5), SnowThermalConductivity.CALONNE_2011, 11);
	}

	private static HeatDiffusionSolver1D whetgeoSolver(DiffusionBoundaryConditionType top,
			DiffusionBoundaryConditionType bottom) {
		HeatDiffusionSolver1D solver = new HeatDiffusionSolver1D();
		solver.z = SOIL.z();
		solver.spaceDeltaZ = SOIL.spaceDeltaZ();
		solver.controlVolume = SOIL.controlVolume();
		solver.psiIC = SOIL.suctions();
		solver.temperature = SOIL.temperatures();
		solver.soilParticlesDensity = new double[] { UniformSoil.PARTICLE_DENSITY };
		solver.thermalConductivitySoilParticles = new double[] { UniformSoil.PARTICLE_CONDUCTIVITY };
		solver.specificThermalCapacitySoilParticles = new double[] { UniformSoil.PARTICLE_HEAT_CAPACITY };
		solver.meltingTemperature = new double[] { 273.15 };
		solver.ks = new double[] { 1e-5 };
		solver.thetaS = new double[] { UniformSoil.THETA_S };
		solver.thetaR = new double[] { UniformSoil.THETA_R };
		solver.par1SWRC = new double[] { UniformSoil.VG_N };
		solver.par2SWRC = new double[] { UniformSoil.VG_ALPHA };
		solver.par3SWRC = new double[] { 0.0 };
		solver.par4SWRC = new double[] { 0.0 };
		solver.par5SWRC = new double[] { 0.0 };
		solver.alphaSpecificStorage = new double[] { 0.0 };
		solver.betaSpecificStorage = new double[] { 0.0 };
		solver.inEquationStateID = SOIL.zeros();
		solver.inParameterID = SOIL.zeros();
		solver.beta0 = -766.45;
		solver.referenceTemperatureSWRC = 278.15;
		solver.typeClosureEquation = new String[] { "Van Genuchten" };
		solver.typeEquationState = new String[] { "SoilInternalEnergy" };
		solver.typeThermalConductivity = new String[] { "Cosenza" };
		solver.interfaceThermalConductivityModel = "max";
		solver.topBCType = top;
		solver.bottomBCType = bottom;
		solver.delta = 0;
		solver.tTimeStep = STEP;
		solver.timeDelta = STEP;
		solver.newtonTolerance = TOLERANCE;
		solver.nestedNewton = 1;
		solver.picardIteration = 1;
		solver.stationID = 0;
		return solver;
	}

	private static double topValue(int step) {
		return 5.0 + 10.0 * Math.sin(2.0 * Math.PI * step / 24.0);
	}

	@Test
	public void dirichletTopAndBottomReproduceHeatDiffusionSolver1DBitwise() {
		HeatDiffusionSolver1D whetgeo = whetgeoSolver(DiffusionBoundaryConditionType.TOP_DIRICHLET,
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET);
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(SOIL.setup(), snowProperties(),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults().withAbsoluteTolerance(TOLERANCE));

		for (int step = 0; step < 72; step++) {
			double top = topValue(step);
			double bottom = 8.0;
			whetgeo.inTopBC = Map.of(0, new double[] { top });
			whetgeo.inBottomBC = Map.of(0, new double[] { bottom });
			whetgeo.solve();
			HeatStepResult result = driver.step(List.of(), new TopBoundary.SurfaceTemperature(top + 273.15),
					bottom + 273.15, STEP);

			assertArrayEquals("step " + step, whetgeo.outTemperature, result.soilTemperatures(), 0.0);
			assertArrayEquals("fluxes, step " + step, whetgeo.outDiffusionHeatFlux, result.conductiveFluxes(), 0.0);
			assertTrue(result.newton().converged());
			assertEquals(Double.NaN, result.snowToSoilFlux(), 0.0);
		}
	}

	@Test
	public void neumannTopAndBottomReproduceHeatDiffusionSolver1DBitwise() {
		HeatDiffusionSolver1D whetgeo = whetgeoSolver(DiffusionBoundaryConditionType.TOP_NEUMANN,
				DiffusionBoundaryConditionType.BOTTOM_NEUMANN);
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(SOIL.setup(), snowProperties(),
				DiffusionBoundaryConditionType.BOTTOM_NEUMANN, NewtonSettings.defaults().withAbsoluteTolerance(TOLERANCE));

		for (int step = 0; step < 72; step++) {
			// HeatDiffusionSolver1D reads Neumann values as energy per tTimeStep
			double top = 3600.0 * 40.0 * Math.sin(2.0 * Math.PI * step / 24.0);
			double bottom = 3600.0 * 0.5;
			whetgeo.inTopBC = Map.of(0, new double[] { top });
			whetgeo.inBottomBC = Map.of(0, new double[] { bottom });
			whetgeo.solve();
			HeatStepResult result = driver.step(List.of(), TopBoundary.SurfaceFlux.constant(top / STEP),
					bottom / STEP, STEP);

			assertArrayEquals("step " + step, whetgeo.outTemperature, result.soilTemperatures(), 0.0);
			// WHETGEO's diagnostics: the bottom flux enters the column upwards and the balance closes
			assertEquals(bottom / STEP, whetgeo.outHeatFluxBottom, 0.0);
			assertEquals(result.energy().bottomInput(), STEP * whetgeo.outHeatFluxBottom, 1e-9);
			assertEquals("step " + step, 0.0, whetgeo.outErrorInternalEnergy, 1e-3);
		}
	}

	/**
	 * Snow switched on and off from the top: once the snow is gone the column is
	 * again the soil-only column, geometry at the boundary included.
	 */
	@Test
	public void soilOnlyStepAfterSnowEqualsAFreshSoilOnlyDriver() {
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(SOIL.setup(), snowProperties(),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		List<SnowCell> snow = new ArrayList<>();
		for (int j = 0; j < 4; j++) {
			snow.add(SnowCell.atTemperature(eos, 10.0, 0.05, 268.15));
		}
		for (int step = 0; step < 6; step++) {
			List<SnowCell> active = snow.subList(0, 4 - step % 5);
			HeatStepResult result = driver.step(active, new TopBoundary.SurfaceTemperature(265.0), 280.0, STEP);
			assertTrue(result.newton().converged());
		}
		double[] soil = driver.soilTemperatures();

		UniformSoil restarted = SOIL;
		SoilHeatSetup setup = restarted.setup();
		System.arraycopy(soil, 0, setup.temperature(), 0, soil.length);
		SnowSoilHeatDriver fresh = new SnowSoilHeatDriver(setup, snowProperties(),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());

		HeatStepResult afterSnow = driver.step(List.of(), new TopBoundary.SurfaceTemperature(275.0), 280.0, STEP);
		HeatStepResult reference = fresh.step(List.of(), new TopBoundary.SurfaceTemperature(275.0), 280.0, STEP);
		assertArrayEquals(reference.temperatures(), afterSnow.temperatures(), 0.0);
		assertArrayEquals(reference.conductiveFluxes(), afterSnow.conductiveFluxes(), 0.0);
	}
}
