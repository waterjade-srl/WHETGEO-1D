package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

/**
 * Expected columns are worked out by hand from GEOtop's rules with the default
 * parameters: m = 5 kg m-2, 4 bottom cells, elastic cells at indices 4 and 5,
 * 4 top cells, 10 cells at most.
 */
public class MassCriterionRestratificationTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final MassCriterionRestratification POLICY = new MassCriterionRestratification(
			MassCriterionRestratification.Parameters.geotopDefaults(), EOS);
	private static final double DENSITY = 250.0;
	private static final double COLD = MELTING_TEMPERATURE - 5.0;

	/** Cold cell of the given mass at the reference density; at -5 degC it is ice to 1e-11. */
	private static SnowCell cell(double mass) {
		return SnowCell.atTemperature(EOS, mass, mass / DENSITY, COLD);
	}

	private static List<SnowCell> column(double... masses) {
		List<SnowCell> cells = new ArrayList<>();
		for (double m : masses) {
			cells.add(cell(m));
		}
		return cells;
	}

	private static void assertIce(double[] expected, List<SnowCell> cells) {
		assertEquals("number of cells", expected.length, cells.size());
		for (int i = 0; i < expected.length; i++) {
			assertEquals("ice of cell " + i, expected[i], cells.get(i).iceMass(), 1e-9);
		}
	}

	private static void assertConserved(List<SnowCell> before, Restratified after) {
		List<SnowCell> all = new ArrayList<>(after.cells());
		after.releasedToSoil().ifPresent(all::add);
		double mass = SnowColumn.totalMass(before);
		assertEquals(mass, SnowColumn.totalMass(all), 1e-13 * mass);
		double energy = Math.abs(SnowColumn.internalEnergy(before)) + mass * LATENT_HEAT_FUSION;
		assertEquals(SnowColumn.internalEnergy(before), SnowColumn.internalEnergy(all), 1e-13 * energy);
		assertEquals(SnowColumn.depth(before), SnowColumn.depth(all), 1e-13 * SnowColumn.depth(before));
	}

	@Test
	public void defaultParametersAreGeotops() {
		MassCriterionRestratification.Parameters p = POLICY.parameters();
		assertEquals(4, p.bottomCells());
		assertEquals(10, p.maxCells());
	}

	@Test
	public void heavyTopCellGrowsANewCell() {
		List<SnowCell> in = column(12.0);
		Restratified once = POLICY.apply(in);
		assertIce(new double[] { 5.0, 7.0 }, once.cells());
		Restratified twice = POLICY.apply(once.cells());
		assertIce(new double[] { 5.0, 5.0, 2.0 }, twice.cells());
		assertEquals(1, twice.splits());
		assertConserved(in, once);
	}

	@Test
	public void topCellJustAboveTargetDoesNotSplit() {
		assertIce(new double[] { 5.4 }, POLICY.apply(column(5.4)).cells());
	}

	@Test
	public void smallCellMergesWithUpperNeighbour() {
		List<SnowCell> in = column(10.0, 0.3, 8.0);
		Restratified out = POLICY.apply(in);
		// 0.3 joins 8.0, then the top cell (8.3) grows a new cell
		assertIce(new double[] { 10.0, 5.0, 3.3 }, out.cells());
		assertEquals(1, out.merges());
		assertEquals(1, out.splits());
		assertConserved(in, out);
	}

	@Test
	public void smallTopCellMergesWithLowerNeighbour() {
		Restratified out = POLICY.apply(column(6.0, 0.2));
		assertIce(new double[] { 5.0, 1.2 }, out.cells());
	}

	@Test
	public void fullColumnMergesLightestElasticPairBeforeGrowing() {
		List<SnowCell> in = column(5, 5, 5, 5, 9, 7, 5, 5, 5, 8);
		Restratified out = POLICY.apply(in);
		// elastic pairs: (4,5) = 16, (5,6) = 12 -> merge 5 and 6; then the top (8) grows
		assertIce(new double[] { 5, 5, 5, 5, 9, 12, 5, 5, 5, 3 }, out.cells());
		assertConserved(in, out);
	}

	@Test
	public void heavyElasticCellSplitsUpwards() {
		List<SnowCell> in = column(5, 5, 5, 5, 12, 5, 5);
		Restratified out = POLICY.apply(in);
		assertIce(new double[] { 5, 5, 5, 5, 7, 5, 5, 5 }, out.cells());
		assertConserved(in, out);
	}

	@Test
	public void thinPackBecomesOneCell() {
		Restratified out = POLICY.apply(column(0.2, 0.25));
		assertIce(new double[] { 0.45 }, out.cells());
		assertFalse(out.releasedToSoil().isPresent());
	}

	@Test
	public void vanishingPackIsReleasedToTheSoil() {
		List<SnowCell> in = column(2e-6, 2e-6);
		Restratified out = POLICY.apply(in);
		assertTrue(out.cells().isEmpty());
		assertTrue(out.releasedToSoil().isPresent());
		assertEquals(4e-6, out.releasedToSoil().get().totalMass(), 1e-18);
		assertConserved(in, out);
	}

	@Test
	public void mergeRefreezesLiquidAtEquilibrium() {
		SnowCell cold = cell(10.0);
		SnowCell wet = new SnowCell(0.0, 0.3, 0.001, 0.3 * LATENT_HEAT_FUSION);
		List<SnowCell> in = List.of(cold, wet);
		Restratified out = POLICY.apply(in);
		// the wet cell (no ice) joins the cold one; the merged 10.3 kg top cell then grows a cell
		// at equilibrium (about -0.27 degC) a liquid fraction of ~1e-9 remains: the split leaves
		// exactly m of ice below, with its share of that liquid
		assertEquals(2, out.cells().size());
		assertEquals(5.0, out.cells().get(0).iceMass(), 1e-12);
		assertEquals(10.3, SnowColumn.totalMass(out.cells()), 1e-12);
		for (SnowCell merged : out.cells()) {
			assertTrue("the liquid refreezes", merged.liquidMass() < 1e-6 * merged.totalMass());
			assertTrue(EOS.temperature(merged) < MELTING_TEMPERATURE);
		}
		assertConserved(in, out);
	}

	@Test
	public void mergeAboveMeltingDoesNotFail() {
		SnowCell warm = new SnowCell(0.0, 6.0, 0.02, 6.0 * (LATENT_HEAT_FUSION + 4188.0 * 0.5));
		SnowCell small = new SnowCell(0.0, 0.2, 0.001, 0.2 * LATENT_HEAT_FUSION);
		List<SnowCell> in = List.of(warm, small);
		Restratified out = POLICY.apply(in);
		assertTrue(EOS.temperature(out.cells().get(0)) > MELTING_TEMPERATURE);
		assertConserved(in, out);
	}

	/** A season of random snowfalls, each followed by a restratification. */
	@Test
	public void conservationOverManyCalls() {
		Random random = new Random(7);
		List<SnowCell> cells = List.of();
		for (int step = 0; step < 2000; step++) {
			if (random.nextDouble() < 0.4) {
				double mass = 3.0 * random.nextDouble();
				double t = MELTING_TEMPERATURE - 15.0 * random.nextDouble();
				SnowCell fresh = SnowCell.atTemperature(EOS, mass + 1e-3, (mass + 1e-3) / 100.0, t);
				cells = SnowColumn.depositFreshSnow(cells, fresh, EOS);
			}
			Restratified out = POLICY.apply(cells);
			assertConserved(cells, out);
			assertTrue(out.cells().size() <= POLICY.parameters().maxCells());
			cells = out.cells();
		}
		assertEquals(POLICY.parameters().maxCells(), cells.size());
	}
}
