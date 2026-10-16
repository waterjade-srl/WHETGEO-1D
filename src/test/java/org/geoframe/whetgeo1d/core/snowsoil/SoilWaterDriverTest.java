package org.geoframe.whetgeo1d.core.snowsoil;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.RichardsBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.solvers.RichardsSolver1D;
import org.geoframe.whetgeo1d.solvers.RichardsSolverWithRootWaterUptake1D;
import org.junit.Test;

/** Richards under the snow, and the energy the water carries in the soil. */
public class SoilWaterDriverTest {

	private static final UniformSoil LOAM = new UniformSoil(20, 2.0, -1.0, 278.15, "Weighted Harmonic Mean",
			UniformSoil.Hydraulics.sandyLoam());
	private static final double[] RAIN = { 0, 0, 2, 5, 12, 3, 0, 0, 0, 1, 0, 0, 30, 20, 0, 0, 0, 0, 0, 4, 4, 4, 0, 0 };

	/** Same suctions as WHETGEO's RichardsSolver1D with a Neumann top, to the bit. */
	@Test
	public void reproducesRichardsSolverBitwise() {
		SoilWaterDriver driver = new SoilWaterDriver(LOAM.setup(), SoilWaterProperties.vanGenuchtenFreeDrainage());
		RichardsSolver1D reference = richardsSolver();
		HashMap<Integer, double[]> top = new HashMap<>();
		reference.inTopBC = top;
		for (int k = 0; k < 2 * RAIN.length; k++) {
			double rain = RAIN[k % RAIN.length];
			top.put(0, new double[] { rain });
			reference.solve();
			SoilWaterStepResult result = driver.step(rain, 0.0, LOAM.temperatures(), 3600.0);
			assertArrayEquals("step " + k, reference.outWaterSuctions, result.suctions(), 0.0);
			assertEquals(rain, result.infiltration(), 1e-12 * Math.max(rain, 1.0));
			// Newton tolerance 1e-11 m per cell is 1e-8 kg m-2
			assertEquals(0.0, result.storageChange() - result.infiltration() + result.drainage(), 1e-7);
			assertEquals(result.balanceError(), result.storageChange() - result.infiltration() + result.drainage(),
					1e-9);
		}
	}

	/**
	 * With evapotranspiration below the top cell, the same suctions as WHETGEO's
	 * RichardsSolverWithRootWaterUptake1D fed the same stressedETs, to the bit.
	 * WHETGEO's root solver ignores the sink of the top cell, and at its first
	 * step it overwrites stressedETs with zeros (it allocates the array there):
	 * the comparison starts with no sink.
	 */
	@Test
	public void reproducesRootWaterUptakeBitwise() {
		SoilWaterDriver driver = new SoilWaterDriver(LOAM.setup(), SoilWaterProperties.vanGenuchtenFreeDrainage());
		RichardsSolverWithRootWaterUptake1D reference = rootSolver();
		HashMap<Integer, double[]> top = new HashMap<>();
		reference.inTopBC = top;
		double[] sinks = new double[LOAM.cells()];
		for (int i = 12; i < LOAM.cells() - 1; i++) {
			sinks[i] = 0.02 * (i - 11);
		}
		for (int k = 0; k < 2 * RAIN.length; k++) {
			double rain = RAIN[k % RAIN.length];
			double[] applied = k == 0 ? new double[sinks.length] : sinks;
			top.put(0, new double[] { rain });
			reference.stressedETs = applied.clone();
			reference.solve();
			SoilWaterStepResult result = driver.step(rain, 0.0, LOAM.temperatures(), null, applied, 3600.0);
			assertArrayEquals("step " + k, reference.outWaterSuctions, result.suctions(), 0.0);
			assertArrayEquals(applied, result.sinks(), 1e-15);
			assertEquals(0.0, result.storageChange() - result.infiltration() + result.drainage()
					+ result.evapotranspiration(), 1e-7);
		}
	}

	/**
	 * Evaporation from the top cell of a drying soil: the balance closes with the
	 * sink, and the sink stops at the wilting point (ψ = -150 m), not at θ_r,
	 * where ψ would run to -∞.
	 */
	@Test
	public void evaporationStopsAtWiltingPoint() {
		UniformSoil thin = new UniformSoil(10, 0.1, -1.0, 278.15, "Weighted Harmonic Mean",
				UniformSoil.Hydraulics.sandyLoam());
		SoilWaterDriver driver = new SoilWaterDriver(thin.setup(), SoilWaterProperties.vanGenuchtenFreeDrainage());
		double[] sinks = new double[thin.cells()];
		sinks[thin.cells() - 1] = 2.0;
		double evaporated = 0.0;
		double drained = 0.0;
		double storage = driver.storage();
		for (int k = 0; k < 48; k++) {
			SoilWaterStepResult result = driver.step(0.0, 0.0, thin.temperatures(), null, sinks, 3600.0);
			assertEquals("step " + k, 0.0, result.balanceError(), 1e-7);
			assertTrue(result.evapotranspiration() <= 2.0 + 1e-12);
			evaporated += result.evapotranspiration();
			drained += result.drainage();
		}
		assertEquals(storage, driver.storage() + evaporated + drained, 1e-6);
		// 10 cm of sandy loam above θ_r hold at most 0.345 · 100 kg m-2: 96 cannot be given
		assertTrue("evaporated " + evaporated, evaporated < 0.345 * 100.0);
		double[] psi = driver.suctions();
		double top = driver.retention().liquid(psi[thin.cells() - 1], 0.0, 0);
		double wilting = driver.retention().liquid(SoilWaterDriver.WILTING_SUCTION, 0.0, 0);
		assertTrue("top cell at " + top, top >= wilting - 1e-9);
		assertTrue("top cell dried to the wilting point: ψ = " + psi[thin.cells() - 1],
				psi[thin.cells() - 1] > 1.01 * SoilWaterDriver.WILTING_SUCTION && psi[thin.cells() - 1] < -10.0);
	}

	/**
	 * Bare soil, frozen at the bottom, with rain and evaporation from the top and
	 * transpiration from the root zone: water and soil energy close at every
	 * step, the evaporated water leaving with its energy.
	 */
	@Test
	public void evapotranspirationClosesSoilBudgets() {
		UniformSoil soil = new UniformSoil(40, 2.0, -1.0, 274.15, "Weighted Harmonic Mean",
				UniformSoil.Hydraulics.sandyLoam());
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		SnowSoilSolver solver = new SnowSoilSolver(soil.freezingSetup(), SoilWaterProperties.vanGenuchtenFreeDrainage(),
				new SnowHeatProperties(eos, SnowThermalConductivity.JORDAN_1991, 11),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(),
				SnowProcesses.recommended(eos, 2.0, 2.0), java.util.List.of(), 0.0);
		double storage = solver.soilWaterStorage();
		double in = 0.0;
		double out = 0.0;
		for (int k = 0; k < 96; k++) {
			double hour = k % 24;
			double rain = RAIN[k % RAIN.length];
			double[] sinks = new double[soil.cells()];
			sinks[soil.cells() - 1] = hour > 8 && hour < 18 ? 0.3 : 0.0;
			for (int i = soil.cells() - 11; i < soil.cells() - 1; i++) {
				sinks[i] = 0.02;
			}
			StepForcing forcing = new StepForcing(
					org.geoframe.whetgeo1d.snow.surface.SurfaceForcing.withoutBeam(0.0, 300.0, 285.0, 0.6, 2.0, 85000.0),
					0.0, rain, 0.2, 0.2, new TopBoundary.SurfaceTemperature(285.0 + 5.0 * Math.sin(hour / 24 * 6.28)),
					267.15, 3600.0, sinks);
			SnowSoilStepResult r = solver.step(forcing);
			in += rain;
			out += r.soilWater().drainage() + r.soilWater().runoff() + r.soilWater().evapotranspiration();
			assertEquals("energy, step " + k, 0.0, r.soilEnergyClosure(), 1e-2);
			assertEquals("water, step " + k, 0.0, r.soilWater().balanceError(), 1e-6);
		}
		assertEquals(storage + in, solver.soilWaterStorage() + out, 1e-5);
		assertTrue("evapotranspiration happened", out > 20.0);
	}

	private static RichardsSolverWithRootWaterUptake1D rootSolver() {
		RichardsSolverWithRootWaterUptake1D solver = new RichardsSolverWithRootWaterUptake1D();
		RichardsSolver1D base = richardsSolver();
		solver.z = base.z;
		solver.spaceDeltaZ = base.spaceDeltaZ;
		solver.controlVolume = base.controlVolume;
		solver.psiIC = base.psiIC;
		solver.temperature = base.temperature;
		solver.ks = base.ks;
		solver.thetaS = base.thetaS;
		solver.thetaR = base.thetaR;
		solver.thetaWP = base.thetaR.clone();
		solver.thetaFC = base.thetaS.clone();
		solver.par1SWRC = base.par1SWRC;
		solver.par2SWRC = base.par2SWRC;
		solver.par3SWRC = base.par3SWRC;
		solver.par4SWRC = base.par4SWRC;
		solver.par5SWRC = base.par5SWRC;
		solver.alphaSpecificStorage = base.alphaSpecificStorage;
		solver.betaSpecificStorage = base.betaSpecificStorage;
		solver.inEquationStateID = base.inEquationStateID;
		solver.inParameterID = base.inParameterID;
		solver.beta0 = base.beta0;
		solver.referenceTemperatureSWRC = base.referenceTemperatureSWRC;
		solver.maxPonding = base.maxPonding;
		solver.typeClosureEquation = base.typeClosureEquation;
		solver.typeEquationState = base.typeEquationState;
		solver.typeUHCModel = base.typeUHCModel;
		solver.typeUHCTemperatureModel = base.typeUHCTemperatureModel;
		solver.interfaceHydraulicConductivityModel = base.interfaceHydraulicConductivityModel;
		solver.topBCType = base.topBCType;
		solver.bottomBCType = base.bottomBCType;
		solver.tTimeStep = base.tTimeStep;
		solver.timeDelta = base.timeDelta;
		solver.newtonTolerance = base.newtonTolerance;
		solver.nestedNewton = base.nestedNewton;
		solver.picardIteration = base.picardIteration;
		solver.stationID = base.stationID;
		return solver;
	}

	private static RichardsSolver1D richardsSolver() {
		RichardsSolver1D solver = new RichardsSolver1D();
		solver.z = LOAM.z();
		solver.spaceDeltaZ = LOAM.spaceDeltaZ();
		solver.controlVolume = LOAM.controlVolume();
		solver.psiIC = LOAM.suctions();
		solver.temperature = LOAM.temperatures();
		UniformSoil.Hydraulics h = LOAM.hydraulics();
		solver.ks = new double[] { h.ks() };
		solver.thetaS = new double[] { h.thetaS() };
		solver.thetaR = new double[] { h.thetaR() };
		solver.par1SWRC = new double[] { h.n() };
		solver.par2SWRC = new double[] { h.alpha() };
		solver.par3SWRC = new double[] { 0.0 };
		solver.par4SWRC = new double[] { 0.0 };
		solver.par5SWRC = new double[] { 0.0 };
		solver.alphaSpecificStorage = new double[] { h.alphaSpecificStorage() };
		solver.betaSpecificStorage = new double[] { 0.0 };
		solver.inEquationStateID = LOAM.zeros();
		solver.inParameterID = LOAM.zeros();
		solver.beta0 = -766.45;
		solver.referenceTemperatureSWRC = 278.15;
		solver.maxPonding = 0.0;
		solver.typeClosureEquation = new String[] { "Van Genuchten" };
		solver.typeEquationState = new String[] { "Van Genuchten" };
		solver.typeUHCModel = new String[] { "Mualem Van Genuchten" };
		solver.typeUHCTemperatureModel = "notemperature";
		solver.interfaceHydraulicConductivityModel = "max";
		solver.topBCType = RichardsBoundaryConditionType.TOP_NEUMANN;
		solver.bottomBCType = RichardsBoundaryConditionType.BOTTOM_FREE_DRAINAGE;
		solver.tTimeStep = 3600;
		solver.timeDelta = 1800;
		solver.newtonTolerance = 1e-11;
		solver.nestedNewton = 1;
		solver.picardIteration = 1;
		solver.stationID = 0;
		return solver;
	}

	private static SnowSoilHeatDriver heatDriver() {
		return new SnowSoilHeatDriver(LOAM.setup(),
				new SnowHeatProperties(new SnowEquationOfState(1e5), SnowThermalConductivity.JORDAN_1991, 4),
				DiffusionBoundaryConditionType.BOTTOM_NO_GRADIENT, NewtonSettings.defaults());
	}

	/** Water at the soil temperature moves through a uniform column without changing any temperature. */
	@Test
	public void waterAtTheSoilTemperatureChangesNoTemperature() {
		SnowSoilHeatDriver heat = heatDriver();
		SoilWaterDriver water = new SoilWaterDriver(LOAM.setup(), SoilWaterProperties.vanGenuchtenFreeDrainage());
		double[] before = heat.soilTemperatures();
		SoilWaterStepResult step = water.step(20.0, 0.0, before, 3600.0);
		heat.transportSoilWater(step.suctions(), step.faceWater(), 20.0 * heat.liquidEnergy(278.15));
		double[] after = heat.soilTemperatures();
		for (int i = 0; i < after.length; i++) {
			// to the Richards mass error, 1e-8 kg m-2
			assertEquals("cell " + i, 278.15, after[i], 1e-7);
		}
		assertArrayEquals(step.suctions(), heat.soilSuctions(), 0.0);
	}

	/** Warm water warms the top, and the soil gains exactly the energy in minus the energy drained. */
	@Test
	public void warmWaterConservesEnergy() {
		SnowSoilHeatDriver heat = heatDriver();
		SoilWaterDriver water = new SoilWaterDriver(LOAM.setup(), SoilWaterProperties.vanGenuchtenFreeDrainage());
		for (int k = 0; k < 6; k++) {
			double energyBefore = heat.soilInternalEnergy();
			SoilWaterStepResult step = water.step(15.0, 0.0, heat.soilTemperatures(), 3600.0);
			double in = 15.0 * heat.liquidEnergy(288.15);
			double out = heat.transportSoilWater(step.suctions(), step.faceWater(), in);
			assertEquals(in - out, heat.soilInternalEnergy() - energyBefore, 1e-9 * Math.abs(energyBefore));
		}
		double[] t = heat.soilTemperatures();
		assertTrue("top warmed: " + t[t.length - 1], t[t.length - 1] > 278.15 + 0.1);
		assertEquals(278.15, t[0], 1e-6);
	}

	/** The solver with soil water closes the soil energy, with the snow on top. */
	@Test
	public void snowSoilSolverClosesSoilBudgets() {
		SyntheticSeason season = SyntheticSeason.standard();
		SnowSoilSolver solver = season.solverWithSoilWater(SyntheticSeason.Configuration.RECOMMENDED);
		double storage = solver.soilWaterStorage();
		double precipitation = 0.0;
		double vapour = 0.0;
		double drainage = 0.0;
		int steps = (int) (75 * 86400 / season.timeDelta());
		for (int k = 0; k < steps; k++) {
			double time = k * season.timeDelta();
			double[] soil = solver.soilTemperatures();
			SnowSoilStepResult r = solver.step(season.forcing(time, soil[soil.length - 1]));
			precipitation += season.snowfall(time) + season.rainfall(time);
			vapour += r.budget().vapour();
			drainage += r.soilWater().drainage();
			assertEquals("soil energy, step " + k, 0.0, r.soilEnergyClosure(), 1e-5);
			assertEquals("water, step " + k, 0.0, r.soilWater().balanceError(), 1e-6);
		}
		double swe = SnowColumn.totalMass(solver.snow());
		assertEquals(precipitation, swe + vapour + solver.soilWaterStorage() - storage + drainage, 1e-5);
	}
}
