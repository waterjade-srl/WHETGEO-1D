package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class LiquidWaterPercolationTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final LiquidWaterPercolation RECOMMENDED = new LiquidWaterPercolation(
			LiquidWaterPercolation.Parameters.recommended(), EOS);
	private static final LiquidWaterPercolation GEOTOP = new LiquidWaterPercolation(
			LiquidWaterPercolation.Parameters.geotop(), EOS);
	private static final double HOUR = 3600.0;

	/** Wet cell at the melting point: U = L_f · liquid, then at equilibrium. */
	private static SnowCell wet(double ice, double liquid, double thickness) {
		return EOS.equilibrate(new SnowCell(ice, liquid, thickness, LATENT_HEAT_FUSION * liquid));
	}

	private static SnowCell cold(double mass, double thickness, double celsius) {
		return SnowCell.atTemperature(EOS, mass, thickness, MELTING_TEMPERATURE + celsius);
	}

	@Test
	public void isothermalPackDrainsDownToTheRetentionCapacity() {
		List<SnowCell> before = List.of(wet(40, 2.0, 0.15), wet(30, 0.5, 0.12), wet(20, 3.0, 0.1));
		Percolated after = RECOMMENDED.apply(before, HOUR);
		// hand-computed (python): W_r = 0.02 (1 - w_i / (917 h)) h 1000, top down
		// top 3.0 -> 1.563795, passes 1.436205; middle 1.936205 -> 1.745692, passes 0.190513;
		// bottom 2.190513 -> 2.127590, drains 0.062923
		assertEquals(2.127590, after.cells().get(0).liquidMass(), 1e-4);
		assertEquals(1.745692, after.cells().get(1).liquidMass(), 1e-4);
		assertEquals(1.563795, after.cells().get(2).liquidMass(), 1e-4);
		assertEquals(0.062923, after.drainedMass(), 1e-4);
		for (SnowCell cell : after.cells()) {
			assertEquals(RECOMMENDED.retentionCapacity(cell), cell.liquidMass(), 1e-5);
		}
		// the water leaves at the melting point
		assertEquals(MELTING_TEMPERATURE, after.drainedTemperature(), 1e-3);
		assertEquals(0.0, after.refrozenMass(), 1e-4);
		assertConserved(before, after, 0.0, 0.0);
	}

	@Test
	public void rainRefreezesInTheFirstColdCellAndWarmsIt() {
		SnowCell bottom = cold(40, 0.15, -2.0);
		List<SnowCell> before = List.of(bottom, cold(20, 0.1, -5.0));
		double rain = 0.5;
		double rainEnergy = LiquidWaterPercolation.liquidWaterEnergy(rain, MELTING_TEMPERATURE);
		Percolated after = RECOMMENDED.apply(before, HOUR, rain, rainEnergy);
		// hand-computed: (20 · 2117 · (-5) + 0.5 · 333700) / (20.5 · 2117) = -1.033446 K
		assertEquals(MELTING_TEMPERATURE - 1.033446, EOS.temperature(after.cells().get(1)), 1e-5);
		// all of it, but for the 2e-9 kg m-2 of liquid the regularised curve keeps at -1 degC
		assertEquals(0.5, after.refrozenMass(), 1e-8);
		assertEquals(0.0, after.drainedMass(), 0.0);
		assertEquals(0.0, after.drainedEnergy(), 0.0);
		assertEquals(bottom, after.cells().get(0));
		assertConserved(before, after, rain, rainEnergy);
	}

	@Test
	public void waterBeyondTheColdContentGoesOnToTheNextColdCell() {
		// top: 5 kg at -1 degC holds 10585 J of cold content, 0.0317 kg of refreezing
		List<SnowCell> before = List.of(cold(20, 0.1, -5.0), cold(5, 0.05, -1.0));
		double rainEnergy = LiquidWaterPercolation.liquidWaterEnergy(1.0, MELTING_TEMPERATURE);
		Percolated after = RECOMMENDED.apply(before, HOUR, 1.0, rainEnergy);
		SnowCell top = after.cells().get(1);
		assertEquals(MELTING_TEMPERATURE, EOS.temperature(top), 1e-3);
		assertEquals(RECOMMENDED.retentionCapacity(top), top.liquidMass(), 1e-5);
		assertTrue(EOS.temperature(after.cells().get(0)) > MELTING_TEMPERATURE - 5.0);
		assertTrue(EOS.temperature(after.cells().get(0)) < MELTING_TEMPERATURE - 1.0);
		assertEquals(0.0, after.drainedMass(), 0.0);
		assertConserved(before, after, 1.0, rainEnergy);
	}

	@Test
	public void rainOnBareGroundDrainsWithItsEnergy() {
		double energy = LiquidWaterPercolation.liquidWaterEnergy(2.0, MELTING_TEMPERATURE + 1.0);
		assertEquals(675776.0, energy, 1e-9); // 2 · (333700 + 4188)
		Percolated after = RECOMMENDED.apply(List.of(), HOUR, 2.0, energy);
		assertTrue(after.cells().isEmpty());
		assertEquals(2.0, after.drainedMass(), 0.0);
		assertEquals(energy, after.drainedEnergy(), 0.0);
		assertEquals(MELTING_TEMPERATURE + 1.0, after.drainedTemperature(), 1e-12);
		assertEquals(0.0, after.refrozenMass(), 0.0);
	}

	@Test
	public void sameDrainageAsGeotopWithoutRefreezing() {
		List<SnowCell> before = List.of(wet(40, 2.0, 0.15), wet(0.3, 0.2, 0.01), wet(30, 0.5, 0.12),
				wet(20, 3.0, 0.1));
		for (double dt : new double[] { 10.0, 60.0, HOUR }) {
			double rain = 1.5;
			Percolated after = GEOTOP.apply(before, dt, rain, LiquidWaterPercolation.liquidWaterEnergy(rain,
					EOS.temperature(before.get(before.size() - 1))));
			double[] geotop = geotopWBsnow(before, dt, rain, 0.02, 0.5);
			// the only difference is the re-equilibration on the regularised freezing curve,
			// O(c (T - T_m) / L_f) ~ 1e-7 of the mass of each cell
			assertEquals("dt " + dt, geotop[geotop.length - 1], after.drainedMass(), 5e-5);
			for (int i = 0; i < before.size(); i++) {
				assertEquals("dt " + dt + " cell " + i, geotop[i], after.cells().get(i).liquidMass(), 5e-5);
			}
		}
	}

	/**
	 * GEOtop's percolation, written from {@code WBsnow} (snow.cc:1093-1128) in its
	 * units (Dzl in mm), on the masses of the cells: liquid of each cell, then
	 * the drained water.
	 */
	private static double[] geotopWBsnow(List<SnowCell> cells, double dt, double rain, double sr,
			double thinIce) {
		double[] out = new double[cells.size() + 1];
		double wdt = rain;
		for (int l = cells.size() - 1; l >= 0; l--) {
			double wIce = cells.get(l).iceMass();
			double wLiq = cells.get(l).liquidMass() + wdt;
			double dzl = 1e3 * cells.get(l).thickness();
			wdt = 0.0;
			if (wIce > thinIce) {
				double th = wLiq / (1.0e-3 * dzl * 1000.0);
				double thi = wIce / (1.0e-3 * dzl * 917.0);
				double se = (th - sr * (1.0 - thi)) / ((1.0 - thi) - sr * (1.0 - thi));
				se = Math.min(Math.max(se, 0.0), 1.0);
				if (th > sr * (1.0 - thi)) {
					wdt += Math.min(5.0 * Math.pow(se, 3.0) * dt, th - sr * (1.0 - thi)) * dzl * 1.e-3 * 1000.0;
				}
				wLiq -= wdt;
			} else {
				wdt += wLiq;
				wLiq = 0.0;
			}
			out[l] = wLiq;
		}
		out[cells.size()] = wdt;
		return out;
	}

	@Test
	public void rateLimiterBindsOnlyInTheTailAtHourlySteps() {
		// one wet cell, 20 kg on 0.1 m: a = (1 - θ_i)(1 - S_r) = 0.766260 (python)
		// binds for Se < sqrt(a / (5 · 3600)) = 0.006525; holds back at most
		// (2/3) a Se* h ρ_w = 0.192431 kg m-2, Se* = sqrt(a / (15 · 3600)) = 0.003767
		LiquidWaterPercolation limited = new LiquidWaterPercolation(
				new LiquidWaterPercolation.Parameters(0.02, true, 0.0), EOS);
		double capacity = 1.563795;
		double worst = 0.0;
		for (int k = 0; k <= 400; k++) {
			double se = Math.pow(10.0, -5.0 + 5.0 * k / 400.0);
			double liquid = capacity + se * 0.766260 * 100.0;
			List<SnowCell> cell = List.of(wet(20, liquid, 0.1));
			double free = RECOMMENDED.apply(cell, HOUR).drainedMass();
			double slow = limited.apply(cell, HOUR).drainedMass();
			double heldBack = free - slow;
			assertTrue(heldBack >= -1e-12);
			if (se > 0.0066) {
				assertEquals("Se " + se, 0.0, heldBack, 1e-9);
			}
			worst = Math.max(worst, heldBack);
		}
		assertEquals(0.192431, worst, 2e-3);
		assertTrue(worst <= 0.192432);
	}

	@Test
	public void thinCellsDrainAllTheirLiquidAsInGeotop() {
		List<SnowCell> before = List.of(wet(0.4, 0.1, 0.005));
		Percolated geotop = GEOTOP.apply(before, HOUR);
		assertEquals(0.1, geotop.drainedMass(), 1e-5);
		assertEquals(0.4, geotop.cells().get(0).iceMass(), 1e-5);
		// recommended: the cell keeps W_r = 0.02 (1 - 0.4 / (917 · 0.005)) · 5 = 0.091276 (python)
		Percolated recommended = RECOMMENDED.apply(before, HOUR);
		assertEquals(0.008724, recommended.drainedMass(), 1e-5);
	}

	@Test
	public void overSaturatedCellsAfterMeltShrinkageDrainToTheirCapacity() {
		// after melt shrinkage: 3 kg ice + 27 kg water on 1.2 cm, θ_w = 2.25, θ_i + θ_w > 1;
		// W_r = 0.02 (1 - 3 / (917 · 0.012)) · 12 = 0.174571 (python), drains 26.825429
		SnowCell slushy = wet(3, 27, 0.012);
		// ice denser than ρ_i: θ_i = 12 / (917 · 0.012) = 1.09, no pore volume, retains nothing
		SnowCell overPacked = wet(12, 1, 0.012);
		for (LiquidWaterPercolation percolation : List.of(RECOMMENDED, GEOTOP)) {
			for (double dt : new double[] { 60.0, HOUR }) {
				Percolated one = percolation.apply(List.of(slushy), dt);
				assertEquals(26.825429, one.drainedMass(), 1e-4);
				assertEquals(0.174571, one.cells().get(0).liquidMass(), 1e-4);
				assertEquals(0.012, one.cells().get(0).thickness(), 0.0);
				assertConserved(List.of(slushy), one, 0.0, 0.0);

				List<SnowCell> before = List.of(cold(30, 0.12, -2.0), overPacked, slushy);
				Percolated column = percolation.apply(before, dt, 1.0,
						LiquidWaterPercolation.liquidWaterEnergy(1.0, MELTING_TEMPERATURE));
				// all drains; re-equilibration a few 1e-5 K below T_m leaves ~2e-4 kg m-2 of liquid
				// on the regularised freezing curve (θ_w ~ 1e-5)
				assertEquals(0.0, column.cells().get(1).liquidMass(), 1e-3);
				for (int i = 0; i < before.size(); i++) {
					assertEquals(before.get(i).thickness(), column.cells().get(i).thickness(), 0.0);
				}
				assertConserved(before, column, 1.0, LiquidWaterPercolation.liquidWaterEnergy(1.0,
						MELTING_TEMPERATURE));
			}
		}
	}

	@Test
	public void anIceFreeCellDrainsAndDisappears() {
		SnowCell slush = new SnowCell(0.0, 1.0, 0.01,
				LiquidWaterPercolation.liquidWaterEnergy(1.0, MELTING_TEMPERATURE + 0.5));
		List<SnowCell> before = List.of(wet(30, 0.5, 0.12), slush);
		Percolated after = RECOMMENDED.apply(before, HOUR);
		assertEquals(1, after.cells().size());
		assertEquals(0.12, after.cells().get(0).thickness(), 0.0);
		assertConserved(before, after, 0.0, 0.0);
	}

	@Test
	public void conservesMassAndEnergyOnRandomColumns() {
		Random random = new Random(42);
		for (int trial = 0; trial < 3000; trial++) {
			LiquidWaterPercolation percolation = random.nextBoolean() ? RECOMMENDED : GEOTOP;
			List<SnowCell> before = new ArrayList<>();
			int n = random.nextInt(11);
			for (int i = 0; i < n; i++) {
				double ice = 0.2 + 50.0 * random.nextDouble();
				double thickness = ice / (100.0 + 400.0 * random.nextDouble());
				double kind = random.nextDouble();
				if (kind < 0.4) {
					before.add(cold(ice, thickness, -0.001 - 15.0 * random.nextDouble()));
				} else if (kind < 0.97) {
					before.add(wet(ice, 0.15 * ice * random.nextDouble(), thickness));
				} else {
					double liquid = 0.1 + random.nextDouble();
					before.add(new SnowCell(0.0, liquid, 0.01, LiquidWaterPercolation.liquidWaterEnergy(liquid,
							MELTING_TEMPERATURE + random.nextDouble())));
				}
			}
			double rain = random.nextDouble() < 0.5 ? 0.0 : 10.0 * random.nextDouble();
			double rainEnergy = LiquidWaterPercolation.liquidWaterEnergy(rain,
					MELTING_TEMPERATURE + 3.0 * random.nextDouble());
			double dt = random.nextBoolean() ? HOUR : 1.0 + 900.0 * random.nextDouble();
			Percolated after = percolation.apply(before, dt, rain, rainEnergy);
			assertConserved(before, after, rain, rainEnergy);
			if (after.cells().size() == before.size()) {
				for (int i = 0; i < before.size(); i++) {
					assertEquals(before.get(i).thickness(), after.cells().get(i).thickness(), 0.0);
				}
			}
			assertTrue(after.drainedMass() >= 0.0);
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsSaturationOfOne() {
		new LiquidWaterPercolation.Parameters(1.0, false, 0.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsRainEnergyWithoutRain() {
		RECOMMENDED.apply(List.of(), HOUR, 0.0, 100.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonPositiveTimeStep() {
		RECOMMENDED.apply(List.of(), 0.0);
	}

	private static void assertConserved(List<SnowCell> before, Percolated after, double rain, double rainEnergy) {
		double massIn = SnowColumn.totalMass(before) + rain;
		double massOut = SnowColumn.totalMass(after.cells()) + after.drainedMass();
		assertEquals(massIn, massOut, 1e-13 * Math.max(1.0, massIn));
		double scale = Math.abs(rainEnergy);
		for (SnowCell cell : before) {
			scale += Math.abs(cell.internalEnergy());
		}
		double energyIn = SnowColumn.internalEnergy(before) + rainEnergy;
		double energyOut = SnowColumn.internalEnergy(after.cells()) + after.drainedEnergy();
		assertEquals(energyIn, energyOut, 1e-13 * Math.max(1.0, scale));
	}
}
