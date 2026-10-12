package org.geoframe.whetgeo1d.snow;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/** Graded thickness criterion: conservation, bands, capacity, stability. */
public class GradedThicknessRestratificationTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final GradedThicknessRestratification.Parameters P = GradedThicknessRestratification.Parameters
			.proposed();
	private static final GradedThicknessRestratification POLICY = new GradedThicknessRestratification(P, EOS);

	/** Snowfalls of 1 cm at 100 kg m-3 at -5 °C, each deposited and restratified, as GEOtop does per step. */
	private static List<SnowCell> accumulate(int snowfalls, double temperature) {
		List<SnowCell> cells = new ArrayList<>();
		for (int s = 0; s < snowfalls; s++) {
			cells = SnowColumn.depositFreshSnow(cells, SnowCell.atTemperature(EOS, 1.0, 0.01, temperature), EOS);
			cells = POLICY.apply(cells).cells();
		}
		return cells;
	}

	private static void assertConserved(List<SnowCell> before, Restratified after) {
		double mass = SnowColumn.totalMass(after.cells());
		double energy = SnowColumn.internalEnergy(after.cells());
		double depth = SnowColumn.depth(after.cells());
		if (after.releasedToSoil().isPresent()) {
			mass += after.releasedToSoil().get().totalMass();
			energy += after.releasedToSoil().get().internalEnergy();
			depth += after.releasedToSoil().get().thickness();
		}
		assertEquals(SnowColumn.totalMass(before), mass, 1e-12 * SnowColumn.totalMass(before));
		assertEquals(SnowColumn.internalEnergy(before), energy, 1e-9 * Math.abs(SnowColumn.internalEnergy(before)));
		assertEquals(SnowColumn.depth(before), depth, 1e-12 * SnowColumn.depth(before));
	}

	@Test
	public void targetsGrowGeometricallyUpToTheCap() {
		assertEquals(0.02, P.target(0), 1e-15);
		assertEquals(0.03, P.target(1), 1e-15);
		assertEquals(0.045, P.target(2), 1e-15);
		assertEquals(0.5, P.target(9), 0.0);
		double sum = 0.0;
		for (int k = 0; k < P.maxCells(); k++) {
			sum += P.target(k);
		}
		assertEquals(1.98515625, sum, 1e-12);
	}

	/** A growing pack keeps every cell but the bottom one inside its band, thin at the top. */
	@Test
	public void accumulationKeepsCellsInTheirBands() {
		for (int snowfalls : new int[] { 3, 10, 40, 120, 400 }) {
			List<SnowCell> cells = accumulate(snowfalls, MELTING_TEMPERATURE - 5.0);
			assertTrue(cells.size() <= P.maxCells());
			for (int k = 0; k < cells.size(); k++) {
				SnowCell cell = cells.get(cells.size() - 1 - k);
				boolean bottomOfFullColumn = k == cells.size() - 1 && cells.size() == P.maxCells();
				if (cells.size() > 1) {
					assertTrue(snowfalls + " cell " + k + " too thin: " + cell.thickness(),
							cell.thickness() >= P.lowerBand() * P.target(k));
				}
				if (!bottomOfFullColumn) {
					assertTrue(snowfalls + " cell " + k + " too thick: " + cell.thickness(),
							cell.thickness() <= P.upperBand() * P.target(k));
				}
			}
			assertEquals(snowfalls * 1.0, SnowColumn.totalMass(cells), 1e-9);
		}
	}

	@Test
	public void appliedTwiceChangesNothing() {
		List<SnowCell> cells = accumulate(57, MELTING_TEMPERATURE - 3.0);
		Restratified again = POLICY.apply(cells);
		assertEquals(0, again.merges());
		assertEquals(0, again.splits());
		assertEquals(cells, again.cells());
	}

	/** One thick cell is cut into graded pieces in one call, all conserved. */
	@Test
	public void thickCellIsGradedInOneCall() {
		List<SnowCell> cells = List.of(SnowCell.atTemperature(EOS, 60.0, 0.6, MELTING_TEMPERATURE - 4.0));
		Restratified r = POLICY.apply(cells);
		assertConserved(cells, r);
		List<SnowCell> out = r.cells();
		assertEquals(0.02, out.get(out.size() - 1).thickness(), 1e-12);
		assertEquals(0.03, out.get(out.size() - 2).thickness(), 1e-12);
		assertEquals(0.045, out.get(out.size() - 3).thickness(), 1e-12);
		assertEquals(0, r.merges());
		assertEquals(out.size() - 1, r.splits());
	}

	/** In a full column the excess at the top travels down to the bottom cell. */
	@Test
	public void fullColumnPassesTheExcessDown() {
		List<SnowCell> cells = new ArrayList<>(accumulate(300, MELTING_TEMPERATURE - 5.0));
		assertEquals(P.maxCells(), cells.size());
		int top = cells.size() - 1;
		SnowCell heavy = cells.get(top).merge(SnowCell.atTemperature(EOS, 30.0, 0.3, MELTING_TEMPERATURE - 5.0));
		cells.set(top, EOS.equilibrate(heavy));
		Restratified r = POLICY.apply(cells);
		assertConserved(cells, r);
		assertEquals(P.maxCells(), r.cells().size());
		assertEquals(0.02, r.cells().get(top).thickness(), 1e-12);
	}

	/** Melting from the top: a thin surface cell joins the one below, then the top is re-cut. */
	@Test
	public void thinSurfaceCellIsMergedAndRecut() {
		List<SnowCell> cells = new ArrayList<>(accumulate(80, MELTING_TEMPERATURE - 2.0));
		int top = cells.size() - 1;
		cells.set(top, cells.get(top).withThickness(0.004));
		Restratified r = POLICY.apply(cells);
		assertConserved(cells, r);
		assertTrue(r.merges() >= 1);
		SnowCell surface = r.cells().get(r.cells().size() - 1);
		assertTrue(surface.thickness() >= P.lowerBand() * P.target(0));
		assertTrue(surface.thickness() <= P.upperBand() * P.target(0));
	}

	@Test
	public void lightPackIsReleasedToTheSoil() {
		List<SnowCell> cells = List.of(SnowCell.atTemperature(EOS, 2e-6, 1e-5, MELTING_TEMPERATURE - 1.0),
				SnowCell.atTemperature(EOS, 1e-6, 1e-5, MELTING_TEMPERATURE - 1.0));
		Restratified r = POLICY.apply(cells);
		assertTrue(r.cells().isEmpty());
		assertTrue(r.releasedToSoil().isPresent());
		assertConserved(cells, r);
	}

	@Test
	public void singleThinCellIsKept() {
		List<SnowCell> cells = List.of(SnowCell.atTemperature(EOS, 0.5, 0.005, MELTING_TEMPERATURE - 1.0));
		Restratified r = POLICY.apply(cells);
		assertEquals(cells, r.cells());
		assertFalse(r.releasedToSoil().isPresent());
	}

	@Test(expected = IllegalArgumentException.class)
	public void narrowUpperBandIsRejected() {
		new GradedThicknessRestratification.Parameters(0.02, 1.5, 0.5, 10, 0.5, 1.5, 0.0);
	}
}
