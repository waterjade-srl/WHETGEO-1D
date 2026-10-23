package org.geoframe.whetgeo1d.core.snowsoil;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Series;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.junit.Test;

/** The integrated step of snowpack and soil heat, on the synthetic season of document 009. */
public class SnowSoilSolverTest {

	private static final SyntheticSeason SEASON = SyntheticSeason.standard();

	/** Every step closes mass and energy of the pack to round-off, in both configurations. */
	@Test
	public void everyStepClosesTheBudgets() {
		for (Configuration configuration : Configuration.values()) {
			Series s = SEASON.run(configuration);
			for (int k = 0; k < s.time().length; k++) {
				assertEquals(configuration + " mass, step " + k, 0.0, s.massClosure()[k], 1e-11);
				assertEquals(configuration + " energy, step " + k, 0.0, s.energyClosure()[k], 1e-6);
				assertTrue(configuration + " heat step " + k + " converged", s.converged()[k]);
				assertTrue(configuration + " surface sweeps, step " + k, s.surfaceConverged()[k]);
				assertEquals(configuration + " linearisation, step " + k, 0.0, s.linearisation()[k], 1e-2);
			}
		}
	}

	/** Over the season, what fell is on the ground, in the air or in the soil. */
	@Test
	public void seasonConservesWater() {
		for (Configuration configuration : Configuration.values()) {
			Series s = SEASON.run(configuration);
			double input = 0.0;
			for (int k = 0; k < s.time().length; k++) {
				double time = k * SEASON.timeDelta();
				input += SEASON.snowfall(time) + SEASON.rainfall(time);
			}
			double output = 0.0;
			for (int k = 0; k < s.time().length; k++) {
				output += s.vapour()[k] + s.soilInput()[k];
			}
			int last = s.time().length - 1;
			assertEquals(7 * 30.0 + 24.0, input, 1e-9);
			assertEquals(configuration.toString(), input, s.swe()[last] + output, 1e-9);
		}
	}

	/** The pack builds up, the cell count stays within capacity, and spring melts it out. */
	@Test
	public void packAccumulatesAndMeltsOut() {
		for (Configuration configuration : Configuration.values()) {
			Series s = SEASON.run(configuration);
			double maxSwe = 0.0;
			int maxCells = 0;
			for (int k = 0; k < s.time().length; k++) {
				maxSwe = Math.max(maxSwe, s.swe()[k]);
				maxCells = Math.max(maxCells, s.cells()[k]);
			}
			assertTrue(configuration + " SWE peak " + maxSwe, maxSwe > 200.0 && maxSwe < 234.0);
			assertTrue(configuration + " cells " + maxCells, maxCells <= (configuration == Configuration.FIDELITY ? 10 : 11));
			int day110 = (int) (110 * SyntheticSeason.DAY / SEASON.timeDelta());
			assertTrue(configuration + " snow left at day 110", s.swe()[day110] > 0.0);
			assertEquals(configuration + " melted out", 0.0, s.swe()[s.time().length - 1], 0.0);
		}
	}

	/**
	 * Rain on the cold pack (§6.6 "pioggia su manto freddo"): in the first hour
	 * nothing reaches the soil; the rain warms the top cells to 0 °C, part of it
	 * refreezes and the rest stays as retained liquid.
	 */
	@Test
	public void rainOnColdSnowRefreezesAndIsRetained() {
		SnowSoilSolver solver = SEASON.solver(Configuration.RECOMMENDED);
		int first = (int) (SyntheticSeason.RAIN_DAY * SyntheticSeason.DAY / SEASON.timeDelta());
		for (int k = 0; k <= first; k++) {
			double[] soil = solver.soilTemperatures();
			double liquidBefore = liquid(solver.snow());
			StepForcing forcing = SEASON.forcing(k * SEASON.timeDelta(), soil[soil.length - 1]);
			SnowSoilStepResult result = solver.step(forcing);
			if (k == first) {
				assertTrue("pack below 0 °C before the rain", result.surfaceTemperature() < MELTING_TEMPERATURE - 1.0);
				assertEquals(SyntheticSeason.RAIN_RATE, forcing.rainfall(), 0.0);
				assertEquals(0.0, result.soilInputMass(), 0.0);
				assertTrue("refrozen " + result.refrozen(), result.refrozen() > 0.25 * forcing.rainfall());
				double retained = liquid(result.snow()) - liquidBefore;
				assertTrue("retained " + retained, retained > 0.25 * forcing.rainfall());
				assertEquals(forcing.rainfall() - result.melt(), result.refrozen() + retained, 1e-9);
			}
		}
	}

	private static double liquid(List<SnowCell> cells) {
		double sum = 0.0;
		for (SnowCell cell : cells) {
			sum += cell.liquidMass();
		}
		return sum;
	}

	/**
	 * Linearised at the start of the step only, the surface flux puts energy in
	 * the column that the balance at the final temperature does not give; the
	 * sweeps remove it.
	 */
	@Test
	public void semiImplicitSurfaceLeavesALinearisationError() {
		Series s = SEASON.run(Configuration.FIDELITY, SurfaceCoupling.semiImplicit());
		double sum = 0.0;
		double worst = 0.0;
		for (int k = 0; k < s.time().length; k++) {
			sum += s.linearisation()[k];
			worst = Math.max(worst, Math.abs(s.linearisation()[k]));
			// one solve, two when GEOtop's skin rises above 0 °C and the step is solved again without it
			assertTrue(s.sweeps()[k] <= 2);
		}
		assertTrue("worst step " + worst, worst > 1e4);
		assertTrue("season " + sum, sum > 1e6);
	}

	/** Without snow the solver is the heat driver with the bare-soil boundary, bit for bit. */
	@Test
	public void withoutSnowItIsTheHeatDriver() {
		SnowSoilSolver solver = SEASON.solver(Configuration.FIDELITY);
		SnowEquationOfState eos = new SnowEquationOfState(SyntheticSeason.ALPHA);
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(SEASON.soil().setup(),
				new SnowHeatProperties(eos, SnowThermalConductivity.COSENZA, 10),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		double start = 100 * SyntheticSeason.DAY;
		for (int k = 0; k < 48; k++) {
			double time = start + k * SEASON.timeDelta();
			double[] soil = solver.soilTemperatures();
			StepForcing forcing = SEASON.forcing(time, soil[soil.length - 1]);
			SnowSoilStepResult result = solver.step(forcing);
			HeatStepResult alone = driver.step(List.of(), forcing.bareSoilTop(), forcing.bottomValue(),
					forcing.timeDelta());
			assertArrayEquals(alone.soilTemperatures(), result.heat().soilTemperatures(), 0.0);
			assertEquals(1, result.surfaceSweeps());
			assertTrue(result.snow().isEmpty());
		}
	}

	/** Snow falling on bare ground becomes one cell with the fresh snow's mass and energy. */
	@Test
	public void snowfallOnBareGroundMakesOneCell() {
		SnowSoilSolver solver = SEASON.solver(Configuration.FIDELITY);
		double time = SyntheticSeason.SNOWFALL_DAYS[0] * SyntheticSeason.DAY;
		double[] soil = solver.soilTemperatures();
		StepForcing forcing = SEASON.forcing(time, soil[soil.length - 1]);
		SnowSoilStepResult result = solver.step(forcing);
		assertEquals(1, result.snow().size());
		SnowCell cell = result.snow().get(0);
		assertEquals(forcing.snowfall(), cell.totalMass(), 1e-12);
		assertEquals(result.budget().snowfallEnergy(), cell.internalEnergy(), 0.0);
		assertTrue(result.budget().snowfallEnergy() < 0.0);
		assertTrue(Double.isNaN(result.surfaceTemperature()));
	}
}
