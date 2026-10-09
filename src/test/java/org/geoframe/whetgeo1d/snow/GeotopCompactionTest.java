package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.ICE_DENSITY;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.WATER_DENSITY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Expected values are computed independently from GEOtop's formulas
 * ({@code snow.cc:88-134}) in GEOtop units (mm, °C), with Python:
 * {@code D * exp((CR1 + CR2) * Dt)}.
 */
public class GeotopCompactionTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final GeotopCompaction COMPACTION = new GeotopCompaction(
			GeotopCompaction.Parameters.geotopDefaults(), EOS);
	private static final double HOUR = 3600.0;

	private static double celsius(double t) {
		return MELTING_TEMPERATURE + t;
	}

	private static SnowCell cold(double mass, double thickness, double t) {
		return SnowCell.atTemperature(EOS, mass, thickness, celsius(t));
	}

	@Test
	public void geotopDefaults() {
		GeotopCompaction.Parameters p = GeotopCompaction.Parameters.geotopDefaults();
		assertEquals(0.7, p.maxIceVolumeFraction(), 0.0);
		assertEquals(1.0, p.dryDeformationRate(), 0.0);
		assertEquals(100.0, p.densityCutoff(), 0.0);
		assertEquals(1.5, p.wetDeformationRate(), 0.0);
		assertEquals(1.0e6, p.viscosity(), 0.0);
		assertEquals(0.5, p.minimumIceMass(), 0.0);
	}

	@Test
	public void drySnowBelowTheDensityCutoff() {
		// 10 kg on 125 mm (80 kg m-3), -10 °C, own load only: CR = -2.575091463404831e-06 s-1
		SnowCell cell = new SnowCell(10.0, 0.0, 0.125, 0.0);
		assertEquals(-2.575091463404831e-06, COMPACTION.rate(cell, celsius(-10.0), 10.0), 1e-18);
		SnowCell out = COMPACTION.compact(List.of(cold(10.0, 0.125, -10.0)), HOUR, 0.0).get(0);
		assertEquals(0.12384656347009938, out.thickness(), 1e-12);
	}

	@Test
	public void drySnowAboveTheDensityCutoffWithALoad() {
		// 30 kg on 100 mm (300 kg m-3), -10 °C, 50 kg above: load 80 kg m-2
		SnowCell cell = new SnowCell(30.0, 0.0, 0.1, 0.0);
		assertEquals(-3.6414257332178856e-08, COMPACTION.rate(cell, celsius(-10.0), 80.0), 1e-20);
		List<SnowCell> column = List.of(cold(30.0, 0.1, -10.0), cold(50.0, 1.0, -10.0));
		assertEquals(0.09998689172656967, COMPACTION.compact(column, HOUR, 0.0).get(0).thickness(), 1e-12);
	}

	@Test
	public void wetSnowDeformsFaster() {
		// 20 kg ice on 100 mm at 0 °C; with 0.5 kg of liquid (θ_w = 0.005 > 0.001) c1 is multiplied by 1.5
		assertEquals(-2.4793355455925954e-07,
				COMPACTION.rate(new SnowCell(20.0, 0.5, 0.1, 0.0), MELTING_TEMPERATURE, 20.5), 1e-19);
		assertEquals(-2.28950662755519e-07,
				COMPACTION.rate(new SnowCell(20.0, 0.0, 0.1, 0.0), MELTING_TEMPERATURE, 20.0), 1e-19);
		// θ_w = 0.0009 is still dry
		double justDry = COMPACTION.rate(new SnowCell(20.0, 0.09, 0.1, 0.0), MELTING_TEMPERATURE, 20.0);
		assertEquals(-2.28950662755519e-07, justDry, 1e-19);
	}

	@Test
	public void warmSnowSettlesFasterThanColdSnow() {
		// 15 kg on 100 mm, own load: -1 °C and -20 °C
		SnowCell cell = new SnowCell(15.0, 0.0, 0.1, 0.0);
		assertEquals(-7.070755893716904e-07, COMPACTION.rate(cell, celsius(-1.0), 15.0), 1e-19);
		assertEquals(-2.2124163674075566e-07, COMPACTION.rate(cell, celsius(-20.0), 15.0), 1e-19);
		assertEquals(0.09974577648453042,
				COMPACTION.compact(List.of(cold(15.0, 0.1, -1.0)), HOUR, 0.0).get(0).thickness(), 1e-12);
		assertEquals(0.0999203847205686,
				COMPACTION.compact(List.of(cold(15.0, 0.1, -20.0)), HOUR, 0.0).get(0).thickness(), 1e-12);
	}

	@Test
	public void slopeReducesTheLoad() {
		List<SnowCell> column = List.of(cold(30.0, 0.1, -10.0), cold(50.0, 1.0, -10.0));
		List<SnowCell> out = COMPACTION.compact(column, HOUR, Math.toRadians(30.0));
		assertEquals(0.09998863873216403, out.get(0).thickness(), 1e-12);
	}

	@Test
	public void loadIncludesTheCellAndEverythingAbove() {
		// bottom 30 kg on 100 mm, top 10 kg on 125 mm, -5 °C, one day: loads 40 and 10 kg m-2
		List<SnowCell> column = List.of(cold(30.0, 0.1, -5.0), cold(10.0, 0.125, -5.0));
		List<SnowCell> out = COMPACTION.compact(column, 86400.0, 0.0);
		assertEquals(0.09976482594612736, out.get(0).thickness(), 1e-12);
		assertEquals(0.09368079705129094, out.get(1).thickness(), 1e-12);
	}

	@Test
	public void changesOnlyThicknesses() {
		Random random = new Random(7);
		List<SnowCell> column = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			double mass = 1.0 + 20.0 * random.nextDouble();
			double density = 80.0 + 500.0 * random.nextDouble();
			column.add(cold(mass, mass / density, -15.0 * random.nextDouble()));
		}
		column.add(new SnowCell(5.0, 0.3, 0.05, EOS.internalEnergy(MELTING_TEMPERATURE - 1e-6, 5.3)));
		for (int step = 0; step < 500; step++) {
			List<SnowCell> out = COMPACTION.compact(column, HOUR, 0.2);
			assertEquals(column.size(), out.size());
			for (int i = 0; i < column.size(); i++) {
				SnowCell before = column.get(i);
				SnowCell after = out.get(i);
				assertEquals(before.iceMass(), after.iceMass(), 0.0);
				assertEquals(before.liquidMass(), after.liquidMass(), 0.0);
				assertEquals(before.internalEnergy(), after.internalEnergy(), 0.0);
				assertTrue(after.thickness() <= before.thickness());
				assertTrue(after.thickness() >= after.iceMass() / ICE_DENSITY + after.liquidMass() / WATER_DENSITY);
				assertTrue(after.iceVolumeFraction() <= 0.7 * (1 + 1e-12) || after == before);
			}
			column = new ArrayList<>(out);
		}
	}

	@Test
	public void stopsAtTheMaximumIceFraction() {
		// 600 kg m-3 of ice, very long step: compaction stops at 0.7 · 917 = 641.9 kg m-3
		SnowCell cell = cold(60.0, 0.1, -1.0);
		SnowCell out = COMPACTION.compact(List.of(cell, cold(1000.0, 2.0, -1.0)), 1e9, 0.0).get(0);
		assertEquals(out.iceMass() / (0.7 * ICE_DENSITY), out.thickness(), 1e-15);
		// a cell already beyond the limit is left as it is, never thickened
		SnowCell dense = cold(70.0, 0.1, -1.0);
		assertSame(dense, COMPACTION.compact(List.of(dense), 1e9, 0.0).get(0));
	}

	@Test
	public void neverCompactsBelowTheVolumeOfIceAndWater() {
		// 50 kg ice, 70 kg liquid on 0.15 m: ice and water fill 0.1245 m, above the 0.0779 m of the ice limit
		SnowCell wet = new SnowCell(50.0, 70.0, 0.15, EOS.internalEnergy(MELTING_TEMPERATURE - 1e-7, 120.0));
		SnowCell out = COMPACTION.compact(List.of(wet), 1e9, 0.0).get(0);
		assertEquals(50.0 / ICE_DENSITY + 70.0 / WATER_DENSITY, out.thickness(), 1e-15);
	}

	@Test
	public void lightCellsAreNotCompacted() {
		SnowCell light = cold(0.5, 0.01, -5.0);
		assertSame(light, COMPACTION.compact(List.of(light), HOUR, 0.0).get(0));
	}

	@Test
	public void settlingOfFreshSnowOverAMonth() {
		// 10 cells of 10 kg at 100 kg m-3 (1 m), -2 °C at the bottom to -11 °C on top, 30 days hourly, flat.
		// Independent Python run of the GEOtop formulas: depth 0.389288078271823 m, densities 314.27 and 196.48
		List<SnowCell> column = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			column.add(cold(10.0, 0.1, -2.0 - i));
		}
		for (int step = 0; step < 720; step++) {
			column = COMPACTION.compact(column, HOUR, 0.0);
		}
		assertEquals(0.389288078271823, SnowColumn.depth(column), 1e-9);
		assertEquals(314.2723185280924, column.get(0).density(), 1e-6);
		assertEquals(196.48091087722943, column.get(9).density(), 1e-6);
		for (int i = 1; i < 10; i++) {
			assertTrue(column.get(i).density() < column.get(i - 1).density());
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsAnIceFractionAboveOne() {
		new GeotopCompaction.Parameters(1.2, 1.0, 100.0, 1.5, 1e6, 0.5);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsANegativeTimeStep() {
		COMPACTION.compact(List.of(cold(10.0, 0.1, -5.0)), -1.0, 0.0);
	}
}
