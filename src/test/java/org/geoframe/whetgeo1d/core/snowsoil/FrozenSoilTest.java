package org.geoframe.whetgeo1d.core.snowsoil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.geoframe.closureequation.closureequation.Parameters;
import org.geoframe.closureequation.closureequation.SWRCVanGenuchten;
import org.geoframe.closureequation.closureequation.SWRCVanGenuchtenDallAmico;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.verification.UniformSoil;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.junit.Test;

/** Frozen soil (A9): retention with ice, the two potentials, the decomposition, a freezing front. */
public class FrozenSoilTest {

	private static final UniformSoil LOAM = new UniformSoil(40, 2.0, -1.0, 275.15, "Weighted Harmonic Mean",
			UniformSoil.Hydraulics.sandyLoam());
	private static final Parameters PARAMETERS = LOAM.parameters();
	private static final IceAwareRetention RETENTION = new IceAwareRetention(PARAMETERS);

	/** The freezing curve is WHETGEO's Dall'Amico closure. */
	@Test
	public void freezingCurveIsWhetgeosDallAmico() {
		SWRCVanGenuchtenDallAmico closure = new SWRCVanGenuchtenDallAmico(PARAMETERS);
		for (double psi : new double[] { -50.0, -5.0, -1.0, -0.3, -0.01, 0.0 }) {
			for (double t = 263.15; t <= 274.15; t += 0.25) {
				assertEquals(closure.f(psi, t, 0), RETENTION.freezingLiquid(psi, t, 0), 1e-15);
			}
		}
	}

	/** Without ice the retention is WHETGEO's Van Genuchten, storage above saturation included. */
	@Test
	public void iceFreeRetentionIsWhetgeosVanGenuchten() {
		SWRCVanGenuchten closure = new SWRCVanGenuchten(PARAMETERS);
		for (double psi = -100.0; psi <= 2.0; psi += 0.37) {
			assertEquals(closure.f(psi, 280.0, 0), RETENTION.liquid(psi, 0.0, 0), 1e-15);
		}
	}

	/**
	 * GEOtop's two potentials: below T* the liquid sits at the Clapeyron
	 * potential of the curve, and the total water still gives back ψ_w0.
	 */
	@Test
	public void liquidPotentialIsClapeyronAndTotalGivesBackPsiW0() {
		for (double psiW0 : new double[] { -3.0, -1.0, -0.2 }) {
			double melting = RETENTION.meltingTemperature(psiW0);
			for (double t : new double[] { melting - 0.01, melting - 0.5, melting - 3.0 }) {
				double liquid = RETENTION.freezingLiquid(psiW0, t, 0);
				double total = RETENTION.totalWater(psiW0, 0);
				double ice = total - liquid;
				assertTrue(ice > 0.0);
				double clapeyron = psiW0 + PARAMETERS.latentHeatFusion / (9.81 * melting) * (t - melting);
				assertEquals(clapeyron, RETENTION.suction(liquid, ice, 0), 1e-9 * Math.abs(clapeyron));
				assertEquals(liquid, RETENTION.liquid(clapeyron, ice, 0), 1e-12);
				assertEquals(psiW0, RETENTION.totalWaterSuction(liquid + ice, 0), 1e-9 * Math.abs(psiW0));
			}
		}
	}

	/** The ice cuts the curve at θ_s - θ_i, without rescaling it (GEOtop teta_psi). */
	@Test
	public void iceCutsTheCurveWithoutRescaling() {
		double ice = 0.1;
		double saturation = RETENTION.saturationSuction(ice, 0);
		assertEquals(PARAMETERS.thetaS[0] - ice, RETENTION.liquid(saturation, ice, 0), 1e-12);
		assertEquals(RETENTION.liquid(saturation - 0.5, 0.0, 0), RETENTION.liquid(saturation - 0.5, ice, 0), 0.0);
		assertEquals(PARAMETERS.thetaS[0] - ice + 1e-4 * 0.2, RETENTION.liquid(saturation + 0.2, ice, 0), 1e-12);
		assertEquals(Math.pow(10.0, -7.0 * ice / (0.41 - 0.065)), RETENTION.impedance(ice, 7.0, 0), 1e-15);
	}

	/** p and q of the water volume with ice are non-decreasing, and p integrates to pIntegral. */
	@Test
	public void decompositionIsJordanForAnyIce() {
		double[] ice = new double[1];
		var geometry = new org.geoframe.whetgeo1d.core.state.WGGeometry(new double[] { 0.5 },
				new double[] { 0.5, 0.5 }, new double[] { 0.1 });
		IceReducedWaterVolume volume = new IceReducedWaterVolume(RETENTION, geometry, ice);
		for (double iceContent : new double[] { 0.0, 0.05, 0.2, 0.33 }) {
			ice[0] = iceContent;
			volume.computeXStar(273.0, 0, 0);
			double previousP = -1.0;
			double previousQ = -1.0;
			double h = 1e-3;
			for (double psi = -20.0; psi <= 0.5; psi += h) {
				double p = volume.p(psi, 273.0, 0, 0);
				double q = volume.q(psi, 273.0, 0, 0);
				assertTrue("p at " + psi + " ice " + iceContent, p >= previousP - 1e-15);
				assertTrue("q at " + psi + " ice " + iceContent, q >= previousQ - 1e-12 && q >= -1e-15);
				double integral = (volume.pIntegral(psi + 1e-7, 273.0, 0, 0) - volume.pIntegral(psi - 1e-7, 273.0, 0, 0))
						/ 2e-7;
				assertEquals(p, integral, 1e-6 * Math.max(p, 1e-6) + 1e-9);
				previousP = p;
				previousQ = q;
			}
		}
	}

	/**
	 * Nested Newton on one cell whose solution lies just below the corner
	 * ψ_sat(θ_i), started at x* = ψ_sat as the solver starts it. q must be zero
	 * at x* itself: with the slope jump there the inner problem is not
	 * monotone, and the iteration stalled 0.05 m from the solution.
	 */
	@Test
	public void nestedNewtonConvergesAtTheIceCorner() {
		double[] ice = { 0.1852 };
		var geometry = new org.geoframe.whetgeo1d.core.state.WGGeometry(new double[] { 0.05 },
				new double[] { 0.05, 0.05 }, new double[] { 0.1 });
		IceReducedWaterVolume volume = new IceReducedWaterVolume(RETENTION, geometry, ice);
		volume.computeXStar(273.0, 0, 0);
		double corner = RETENTION.saturationSuction(ice[0], 0);
		assertEquals(0.0, volume.q(corner, 273.0, 0, 0), 0.0);
		assertEquals(0.0, volume.qIntegral(corner, 273.0, 0, 0), 0.0);
		for (double a : new double[] { 1e-7, 1e-4, 1e-2 }) {
			for (double offset : new double[] { -0.3, -1e-4, 1e-6, 1e-3, 0.1 }) {
				double solution = corner + offset;
				double b = volume.equationState(solution, 273.0, 0, 0) + a * solution;
				double x = volume.initialGuess(-0.047, 0, 0);
				int outer = 0;
				while (outer < 50 && Math.abs(volume.equationState(x, 273.0, 0, 0) + a * x - b) >= 1e-11) {
					double xo = x;
					for (int j = 0; j < 50; j++) {
						double f = volume.pIntegral(x, 273.0, 0, 0)
								- (volume.qIntegral(xo, 273.0, 0, 0) + volume.q(xo, 273.0, 0, 0) * (x - xo)) + a * x - b;
						if (Math.abs(f) < 1e-11) {
							break;
						}
						x -= f / (volume.p(x, 273.0, 0, 0) - volume.q(xo, 273.0, 0, 0) + a);
					}
					outer++;
				}
				assertTrue("a " + a + " offset " + offset + ": " + outer + " outer iterations", outer < 10);
				assertEquals("a " + a + " offset " + offset, solution, x, 1e-9);
			}
		}
	}

	/** The fidelity season on the freezing soil: Richards closes at every step. */
	@Test
	public void fidelitySeasonOnFrozenSoilClosesWater() {
		var season = org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.standard();
		var series = season.run(season
				.solverWithFrozenSoil(org.geoframe.whetgeo1d.core.snowsoil.verification.SyntheticSeason.Configuration.FIDELITY));
		double ice = 0.0;
		for (int k = 0; k < series.time().length; k++) {
			assertEquals("step " + k, 0.0, series.soilWaterError()[k], 1e-6);
			ice = Math.max(ice, series.soilIce()[k]);
		}
		assertTrue("the soil freezes: " + ice, ice > 10.0);
	}

	private static SnowSoilSolver frontSolver(boolean water) {
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		return new SnowSoilSolver(LOAM.freezingSetup(), water ? SoilWaterProperties.vanGenuchtenFreeDrainage() : null,
				new SnowHeatProperties(eos, SnowThermalConductivity.JORDAN_1991, 11),
				DiffusionBoundaryConditionType.BOTTOM_DIRICHLET, NewtonSettings.defaults(),
				SnowProcesses.recommended(eos, 2.0, 2.0), List.of(), 0.0);
	}

	private static StepForcing frontForcing() {
		return new StepForcing(SurfaceForcing.withoutBeam(0.0, 250.0, 268.15, 0.8, 2.0, 85000.0), 0.0, 0.0, 0.2, 0.2,
				new TopBoundary.SurfaceTemperature(268.15), 275.15, 3600.0);
	}

	/**
	 * Freezing front: surface held at -5 °C over a wet loam at +2 °C for ten
	 * days. Water and energy close at every step, ice forms at the top, and the
	 * cryosuction draws water into the frozen layer: more total water there than
	 * in the same soil with no water movement.
	 */
	@Test
	public void freezingFrontClosesBudgetsAndDrawsWaterUp() {
		SnowSoilSolver frozen = frontSolver(true);
		SnowSoilSolver still = frontSolver(false);
		double storage = frozen.soilWaterStorage();
		double out = 0.0;
		for (int k = 0; k < 240; k++) {
			SnowSoilStepResult r = frozen.step(frontForcing());
			still.step(frontForcing());
			out += r.soilWater().drainage() + r.soilWater().runoff();
			assertEquals("energy, step " + k, 0.0, r.soilEnergyClosure(), 1e-2);
			assertEquals("water, step " + k, 0.0, r.soilWater().balanceError(), 1e-6);
		}
		assertEquals(storage, frozen.soilWaterStorage() + out, 1e-5);
		double[] ice = frozen.soilIce();
		int top = ice.length - 1;
		assertTrue("ice at the top " + ice[top], ice[top] > 0.03);
		double totalTop = RETENTION.totalWater(frozen.soilSuctions()[top], 0);
		double totalStill = RETENTION.totalWater(still.soilSuctions()[top], 0);
		assertTrue("cryosuction " + totalTop + " vs " + totalStill, totalTop > totalStill + 0.005);
		assertTrue(still.soilIce()[top] > 0.03);
	}
}
