package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class TopCellThicknessCapTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final MassCriterionRestratification MASS = new MassCriterionRestratification(
			MassCriterionRestratification.Parameters.geotopDefaults(), EOS);
	private static final TopCellThicknessCap CAP = new TopCellThicknessCap(MASS,
			TopCellThicknessCap.DEFAULT_MAX_THICKNESS, EOS);
	private static final double COLD = MELTING_TEMPERATURE - 5.0;

	private static SnowCell cell(double mass, double thickness) {
		return SnowCell.atTemperature(EOS, mass, thickness, COLD);
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
	public void thickSurfaceCellPassesItsExcessDown() {
		List<SnowCell> in = List.of(cell(20.0, 0.2), cell(1.0, 0.05));
		Restratified out = CAP.apply(in);
		// excess 0.03 m (0.6 kg) joins the cell below (20.6 kg), which then grows a cell: 5 + 15.6
		assertEquals(3, out.cells().size());
		assertEquals(5.0, out.cells().get(0).iceMass(), 1e-9);
		assertEquals(15.6, out.cells().get(1).iceMass(), 1e-9);
		assertEquals(0.02, out.cells().get(2).thickness(), 1e-15);
		assertEquals(0.4, out.cells().get(2).iceMass(), 1e-9);
		assertConserved(in, out);
	}

	@Test
	public void surfaceCellWithinToleranceIsLeftAlone() {
		List<SnowCell> in = List.of(cell(8.0, 0.08), cell(0.4, 0.021));
		Restratified out = CAP.apply(in);
		assertEquals(0.021, out.cells().get(out.cells().size() - 1).thickness(), 1e-15);
	}

	@Test
	public void thinnedSurfaceCellIsMergedAndRecreated() {
		List<SnowCell> in = List.of(cell(10.0, 0.1), cell(0.01, 0.001));
		Restratified out = CAP.apply(in);
		// 10.01 kg on 0.101 m: the mass criterion gives 5 + 5.01, then a 2 cm surface cell is cut
		assertEquals(3, out.cells().size());
		assertEquals(5.0, out.cells().get(0).iceMass(), 1e-9);
		assertEquals(0.02, out.cells().get(2).thickness(), 1e-15);
		assertEquals(5.01, out.cells().get(1).iceMass() + out.cells().get(2).iceMass(), 1e-9);
		assertConserved(in, out);
	}

	@Test
	public void singleCellIsCapped() {
		List<SnowCell> in = List.of(cell(0.6, 0.05));
		Restratified out = CAP.apply(in);
		assertEquals(2, out.cells().size());
		assertEquals(0.03, out.cells().get(0).thickness(), 1e-15);
		assertEquals(0.02, out.cells().get(1).thickness(), 1e-15);
		assertConserved(in, out);
	}

	@Test
	public void columnHoldsAtMostOneCellMoreThanTheMassCriterion() {
		Random random = new Random(11);
		List<SnowCell> cells = List.of();
		int max = 0;
		for (int step = 0; step < 3000; step++) {
			if (random.nextDouble() < 0.4) {
				double mass = 3.0 * random.nextDouble() + 1e-3;
				SnowCell fresh = SnowCell.atTemperature(EOS, mass, mass / 100.0,
						MELTING_TEMPERATURE - 10.0 * random.nextDouble());
				cells = SnowColumn.depositFreshSnow(cells, fresh, EOS);
			}
			Restratified out = CAP.apply(cells);
			assertConserved(cells, out);
			cells = out.cells();
			max = Math.max(max, cells.size());
			if (cells.isEmpty()) {
				continue;
			}
			SnowCell top = cells.get(cells.size() - 1);
			assertTrue("surface cell thinner than the cap band",
					top.thickness() <= TopCellThicknessCap.DEFAULT_MAX_THICKNESS * (1 + TopCellThicknessCap.TOLERANCE)
							+ 1e-15);
		}
		assertEquals(MASS.parameters().maxCells() + 1, max);
	}
}
