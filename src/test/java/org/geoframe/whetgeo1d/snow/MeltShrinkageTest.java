package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.util.List;

import org.junit.Test;

public class MeltShrinkageTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);

	@Test
	public void keepsThePartialIceDensity() {
		SnowCell before = new SnowCell(30.0, 0.0, 0.12, -30.0 * 2117.0 * 2.0);
		// 6 kg melt at constant thickness: 24 kg ice, 6 kg liquid
		SnowCell melted = new SnowCell(24.0, 6.0, 0.12, LATENT_HEAT_FUSION * 6.0);
		SnowCell out = MeltShrinkage.apply(before.iceMass(), melted);
		assertEquals(0.096, out.thickness(), 1e-15);
		assertEquals(before.iceMass() / before.thickness(), out.iceMass() / out.thickness(), 1e-12);
		assertEquals(melted.iceMass(), out.iceMass(), 0.0);
		assertEquals(melted.liquidMass(), out.liquidMass(), 0.0);
		assertEquals(melted.internalEnergy(), out.internalEnergy(), 0.0);
	}

	@Test
	public void refreezingDoesNotThicken() {
		SnowCell cell = new SnowCell(20.0, 0.0, 0.1, EOS.internalEnergy(MELTING_TEMPERATURE - 3.0, 20.0));
		assertSame(cell, MeltShrinkage.apply(15.0, cell));
		assertSame(cell, MeltShrinkage.apply(20.0, cell));
	}

	@Test
	public void cellWithoutIceKeepsItsThickness() {
		SnowCell water = new SnowCell(0.0, 5.0, 0.05, LATENT_HEAT_FUSION * 5.0);
		assertSame(water, MeltShrinkage.apply(5.0, water));
	}

	@Test
	public void appliesCellByCellToAColumn() {
		List<SnowCell> before = List.of(new SnowCell(10.0, 0.0, 0.05, 0.0), new SnowCell(8.0, 0.0, 0.08, 0.0));
		List<SnowCell> after = List.of(new SnowCell(10.0, 0.0, 0.05, 0.0), new SnowCell(2.0, 6.0, 0.08, 0.0));
		List<SnowCell> out = MeltShrinkage.apply(before, after);
		assertEquals(0.05, out.get(0).thickness(), 0.0);
		assertEquals(0.02, out.get(1).thickness(), 1e-15);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsColumnsOfDifferentSize() {
		MeltShrinkage.apply(List.of(new SnowCell(1.0, 0.0, 0.01, 0.0)), List.of());
	}
}
