package org.geoframe.whetgeo1d.core.snowsoil;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.closureequation.equationstate.EquationState;
import org.geoframe.numerical.newtonalgorithm.NestedNewtonThomas;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.equationstate.models.SnowInternalEnergy;
import org.geoframe.whetgeo1d.core.snowsoil.verification.SnowSlabConduction;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.core.state.WGProblemQuantities;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.junit.Test;

/** Snow cells in the heat system: verification against closed forms and conservation. */
public class SnowSoilHeatDriverSnowTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final SnowThermalConductivity LAW = SnowThermalConductivity.CALONNE_2011;

	private static List<SnowCell> uniformSnow(int cells, double massPerCell, double thickness, double temperature) {
		List<SnowCell> snow = new ArrayList<>();
		for (int j = 0; j < cells; j++) {
			snow.add(SnowCell.atTemperature(EOS, massPerCell, thickness, temperature));
		}
		return snow;
	}

	/**
	 * Cold dry snow slab, Dirichlet on both faces, against the Fourier series;
	 * Δt ∝ Δz² so that both error terms scale as Δz²: second order.
	 */
	@Test
	public void coldSnowSlabConvergesToTheAnalyticalSolutionAtSecondOrder() {
		SnowSlabConduction slab = new SnowSlabConduction(0.5, 300.0, 268.15, 253.15, LAW);
		double previous = Double.NaN;
		for (int cells : new int[] { 10, 20, 40 }) {
			double timeDelta = 600.0 * Math.pow(10.0 / cells, 2);
			SnowSlabConduction.Errors errors = slab.run(cells, timeDelta, 6 * 3600.0);
			if (!Double.isNaN(previous)) {
				double ratio = previous / errors.maxError();
				assertTrue("convergence ratio " + ratio + " at N = " + cells, ratio > 3.8 && ratio < 4.3);
			}
			assertTrue(errors.maxOuterIterations() <= 3);
			previous = errors.maxError();
		}
		assertTrue("error at N = 40: " + previous, previous < 0.015);
	}

	/**
	 * Steady state through snow over soil with Dirichlet faces: the flux must be
	 * ΔT over the series resistance H_soil/λ_soil + H_snow/λ_snow, which the
	 * thickness-weighted harmonic mean at the snow–soil face reproduces exactly
	 * for piecewise-constant conductivity (§4.1).
	 */
	@Test
	public void steadyFluxThroughSnowAndSoilIsTheSeriesConductance() {
		UniformSoil soil = new UniformSoil(10, 0.5, -0.5, 271.15, "Weighted Harmonic Mean");
		double top = 253.15;
		double bottom = 271.15;
		SnowHeatProperties properties = new SnowHeatProperties(EOS, LAW, 8);

		// soil conductivity from a soil-only steady state
		SnowSoilHeatDriver soilOnly = new SnowSoilHeatDriver(soil.setup(), properties,
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		HeatStepResult bare = null;
		for (int s = 0; s < 20; s++) {
			bare = soilOnly.step(List.of(), new TopBoundary.SurfaceTemperature(top), bottom, 1e7);
		}
		double soilConductivity = -bare.surfaceFlux() * soil.depth() / (bottom - top);

		List<SnowCell> snow = new ArrayList<>();
		double[] thickness = { 0.05, 0.08, 0.03, 0.10, 0.02 };
		double snowDepth = 0.0;
		for (double h : thickness) {
			snow.add(SnowCell.atTemperature(EOS, 250.0 * h, h, 263.15));
			snowDepth += h;
		}
		double snowConductivity = LAW.conductivity(snow.get(0));
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(soil.setup(), properties,
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		HeatStepResult result = null;
		for (int s = 0; s < 20; s++) {
			result = driver.step(snow, new TopBoundary.SurfaceTemperature(top), bottom, 1e7);
			snow = result.snow();
		}
		double expected = (bottom - top) / (soil.depth() / soilConductivity + snowDepth / snowConductivity);
		double[] fluxes = result.conductiveFluxes();
		for (double flux : fluxes) {
			assertEquals(expected, flux, 1e-9 * expected);
		}
		assertEquals(expected, -result.snowToSoilFlux(), 1e-9 * expected);
	}

	/** Warm surface on cold snow over soil: melt at the top, energy conserved. */
	@Test
	public void meltingUnderAWarmSurfaceConservesEnergy() {
		UniformSoil soil = new UniformSoil(20, 1.0, -0.5, 274.15, "max");
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(soil.setup(), new SnowHeatProperties(EOS, LAW, 11),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		List<SnowCell> snow = uniformSnow(5, 10.0, 0.05, 271.15);
		double mass = SnowColumn.totalMass(snow);
		double snowEnergy = SnowColumn.internalEnergy(snow);
		double cumulativeClosure = 0.0;
		double cumulativeInput = 0.0;
		double cumulativeStorage = 0.0;
		double snowToSoil = 0.0;
		double topInput = 0.0;
		for (int s = 0; s < 96; s++) {
			HeatStepResult result = driver.step(snow, new TopBoundary.SurfaceTemperature(276.15), 275.15, 900.0);
			snow = result.snow();
			EnergyBudget budget = result.energy();
			assertTrue("step " + s + ": " + result.newton(), result.newton().converged());
			assertFalse(result.newton().hitIterationCap());
			assertTrue("closure " + budget.closureError(), Math.abs(budget.closureError()) < 0.1);
			cumulativeClosure += budget.closureError();
			cumulativeInput += budget.topInput() + budget.bottomInput();
			cumulativeStorage += budget.storageChange();
			snowToSoil += 900.0 * result.snowToSoilFlux();
			topInput += budget.topInput();
		}
		assertEquals(0.0, cumulativeClosure / cumulativeInput, 1e-6);
		assertEquals(cumulativeInput, cumulativeStorage, 1.0);
		assertEquals(mass, SnowColumn.totalMass(snow), 1e-12 * mass);
		SnowCell topCell = snow.get(snow.size() - 1);
		assertTrue("liquid in the top cell " + topCell.liquidMass(), topCell.liquidMass() > 2.0);
		assertTrue(EOS.temperature(topCell) < MELTING_TEMPERATURE);
		assertTrue(EOS.temperature(topCell) > MELTING_TEMPERATURE - 1e-3);
		// the snow alone: what entered at the surface minus what went into the soil
		assertEquals(topInput - snowToSoil, SnowColumn.internalEnergy(snow) - snowEnergy, 1.0);
		assertTrue("the soil warms the snow: " + snowToSoil, snowToSoil < 0.0);
	}

	/**
	 * The same melt with WHETGEO's plain absolute tolerance (κ = 0): near 0 °C
	 * τ = 10⁻³ J m-2 is below round-off, NestedNewtonThomas runs to its cap and
	 * says nothing; the driver reports it. With the floor (default settings)
	 * no step reaches the cap.
	 */
	@Test
	public void absoluteToleranceAloneRunsTheLibraryToItsCap() {
		int[] caps = new int[2];
		NewtonSettings[] settings = { new NewtonSettings(1e-3, 0.0, 50, 1), NewtonSettings.defaults() };
		for (int k = 0; k < 2; k++) {
			UniformSoil soil = new UniformSoil(20, 1.0, -0.5, 274.15, "max");
			SnowSoilHeatDriver driver = new SnowSoilHeatDriver(soil.setup(), new SnowHeatProperties(EOS, LAW, 11),
					DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, settings[k]);
			List<SnowCell> snow = uniformSnow(5, 10.0, 0.05, 271.15);
			for (int s = 0; s < 96; s++) {
				HeatStepResult result = driver.step(snow, new TopBoundary.SurfaceTemperature(276.15), 275.15, 900.0);
				snow = result.snow();
				if (result.newton().hitIterationCap()) {
					caps[k]++;
					assertTrue(result.newton().outerIterations() >= 50);
				}
			}
		}
		assertTrue("caps without the floor: " + caps[0], caps[0] > 5);
		assertEquals(0, caps[1]);
	}

	/**
	 * Flux with its derivative, G = h (T_air - T): the reported surface flux is
	 * the linearised flux at the new temperature, and energy closes.
	 */
	@Test
	public void linearisedFluxIsImplicitInTheTopTemperature() {
		double h = 15.0;
		double air = 258.15;
		SnowSoilHeatDriver driver = SnowSoilHeatDriver.snowOnly(new SnowHeatProperties(EOS, LAW, 6),
				DiffusionBoundaryConditionType.BOTTOM_NEUMANN, NewtonSettings.defaults());
		List<SnowCell> snow = uniformSnow(6, 6.0, 0.03, 268.15);
		double top = EOS.temperature(snow.get(5));
		for (int s = 0; s < 48; s++) {
			HeatStepResult result = driver.step(snow, new TopBoundary.SurfaceFlux(h * (air - top), -h), 1.0, 1800.0);
			snow = result.snow();
			double[] t = result.temperatures();
			top = t[t.length - 1];
			assertEquals(h * (air - top), result.surfaceFlux(), 1e-9);
			assertEquals(1800.0, result.energy().bottomInput(), 1e-12);
			assertTrue(Math.abs(result.energy().closureError()) < 1e-3);
		}
		assertTrue(top < 268.15 && top > air);
	}

	/** A large h drives the top cell to the air temperature, as a Dirichlet face would. */
	@Test
	public void stiffFluxApproachesTheDirichletCondition() {
		SnowSoilHeatDriver robin = SnowSoilHeatDriver.snowOnly(new SnowHeatProperties(EOS, LAW, 4),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		List<SnowCell> snow = uniformSnow(4, 5.0, 0.025, 268.15);
		HeatStepResult result = robin.step(snow, new TopBoundary.SurfaceFlux(1e9 * (253.15 - 268.15), -1e9), 268.15,
				600.0);
		double[] t = result.temperatures();
		assertEquals(253.15, t[t.length - 1], 1e-6);
	}

	/** One active cell: implicit Euler in closed form, without NestedNewtonThomas. */
	@Test
	public void singleCellColumnMatchesTheClosedForm() {
		SnowSoilHeatDriver driver = SnowSoilHeatDriver.snowOnly(new SnowHeatProperties(EOS, LAW, 1),
				DiffusionBoundaryConditionType.BOTTOM_NEUMANN, NewtonSettings.defaults());
		SnowCell cell = SnowCell.atTemperature(EOS, 20.0, 0.1, 263.15);
		double k = LAW.conductivity(cell);
		double dt = 3600.0;
		HeatStepResult result = driver.step(List.of(cell), new TopBoundary.SurfaceTemperature(253.15), 0.0, dt);

		double capacity = 20.0 * SPECIFIC_HEAT_ICE;
		double a = k * dt / 0.05;
		double expected = (capacity * 263.15 + a * 253.15) / (capacity + a);
		assertEquals(expected, result.temperatures()[0], 1e-6);
		assertTrue(result.newton().singleCell());
		assertTrue(result.newton().converged());
		assertEquals(0.0, result.energy().closureError(), 1e-3);
	}

	/** One soil cell under no snow, through the same guard. */
	@Test
	public void singleSoilCellIsSolved() {
		UniformSoil soil = new UniformSoil(1, 0.1, -0.5, 275.15, "max");
		SnowSoilHeatDriver driver = new SnowSoilHeatDriver(soil.setup(), new SnowHeatProperties(EOS, LAW, 3),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		HeatStepResult result = driver.step(List.of(), new TopBoundary.SurfaceTemperature(270.15), 275.15, 3600.0);
		double t = result.temperatures()[0];
		assertTrue(t < 275.15 && t > 270.15);
		assertTrue(result.newton().converged());
		HeatStepResult withSnow = driver.step(uniformSnow(2, 5.0, 0.03, 268.15),
				new TopBoundary.SurfaceTemperature(265.15), 275.15, 3600.0);
		assertTrue(withSnow.newton().converged());
	}

	/**
	 * Characterises numerical 1.0.4: NestedNewtonThomas reads x[KMAX - 2] for
	 * the top row (line 143), so it cannot solve one cell. If this test starts
	 * failing the library has been fixed and the guard can go.
	 */
	@Test
	public void nestedNewtonThomasCannotSolveOneCell() {
		double[] mass = { 10.0 };
		WGProblemQuantities variables = new WGProblemQuantities(new double[] { 0.0 }, new double[] { 263.15 },
				new int[] { 0 }, new int[] { 0 });
		EquationState snow = new SnowInternalEnergy(null, variables, new SnowEquationOfState[] { EOS }, mass);
		snow.computeXStar(0.0, 0, 0);
		NestedNewtonThomas solver = new NestedNewtonThomas(1, 1e-3, 50, 1, List.of(snow), 0.0);
		solver.set(new double[] { 263.15 }, new double[] { 0.0 }, new double[] { 1.0 }, new double[] { 0.0 },
				new double[] { 0.0 }, new double[] { snow.equationState(263.15, 0.0, 0, 0) + 263.15 }, 1,
				new int[] { 0 }, new int[] { 0 });
		try {
			solver.solver();
			fail("NestedNewtonThomas now solves KMAX = 1");
		} catch (ArrayIndexOutOfBoundsException expected) {
			// numerical 1.0.4
		}
	}

	/** Capacity is a hard limit; inside it the result does not depend on it. */
	@Test
	public void capacityLimitsTheSnowCellsButNotTheResult() {
		UniformSoil soil = new UniformSoil(8, 0.4, -0.5, 271.15, "max");
		List<SnowCell> snow = uniformSnow(4, 5.0, 0.04, 266.15);
		SnowSoilHeatDriver tight = new SnowSoilHeatDriver(soil.setup(), new SnowHeatProperties(EOS, LAW, 4),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		SnowSoilHeatDriver roomy = new SnowSoilHeatDriver(soil.setup(), new SnowHeatProperties(EOS, LAW, 40),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults());
		HeatStepResult a = tight.step(snow, new TopBoundary.SurfaceTemperature(258.15), 272.15, 3600.0);
		HeatStepResult b = roomy.step(snow, new TopBoundary.SurfaceTemperature(258.15), 272.15, 3600.0);
		assertArrayEquals(a.temperatures(), b.temperatures(), 0.0);
		try {
			tight.step(uniformSnow(5, 5.0, 0.04, 266.15), new TopBoundary.SurfaceTemperature(258.15), 272.15,
					3600.0);
			fail("five snow cells in a capacity of four");
		} catch (IllegalArgumentException expected) {
			// capacity exceeded
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void snowOnlyColumnNeedsSnow() {
		SnowSoilHeatDriver.snowOnly(new SnowHeatProperties(EOS, LAW, 3), DiffusionBoundaryConditionType.BOTTOM_NEUMANN,
				NewtonSettings.defaults()).step(List.of(), new TopBoundary.SurfaceTemperature(260.0), 0.0, 60.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void positiveFluxDerivativeIsRejected() {
		new TopBoundary.SurfaceFlux(10.0, 1.0);
	}
}
