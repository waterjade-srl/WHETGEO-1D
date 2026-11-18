/*
 * GNU GPL v3 License
 *
 * Copyright 2026 WHETGEO-1D contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.geoframe.whetgeo1d.snow;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SnowCellTest {

	private static final double REL = 1e-14;

	private final SnowCell lower = new SnowCell(80.0, 2.0, 0.25, -1.5e6);
	private final SnowCell upper = new SnowCell(15.0, 0.0, 0.12, -4.0e5);

	@Test
	public void mergeConservesExtensiveQuantities() {
		SnowCell merged = lower.merge(upper);
		assertEquals(95.0, merged.iceMass(), 0.0);
		assertEquals(2.0, merged.liquidMass(), 0.0);
		assertEquals(0.37, merged.thickness(), 1e-15);
		assertEquals(-1.9e6, merged.internalEnergy(), 0.0);
	}

	@Test
	public void splitPiecesAddUpToTheParent() {
		SnowCell[] pieces = lower.split(0.2, 0.3, 0.5);
		double ice = 0, liquid = 0, depth = 0, energy = 0;
		for (SnowCell piece : pieces) {
			ice += piece.iceMass();
			liquid += piece.liquidMass();
			depth += piece.thickness();
			energy += piece.internalEnergy();
			assertEquals(lower.density(), piece.density(), 1e-12 * lower.density());
		}
		assertEquals(lower.iceMass(), ice, REL * lower.iceMass());
		assertEquals(lower.liquidMass(), liquid, REL * lower.liquidMass());
		assertEquals(lower.thickness(), depth, REL * lower.thickness());
		assertEquals(lower.internalEnergy(), energy, REL * Math.abs(lower.internalEnergy()));
	}

	@Test
	public void splitThenMergeIsTheIdentity() {
		SnowCell[] pieces = lower.split(0.6, 0.4);
		SnowCell back = pieces[0].merge(pieces[1]);
		assertEquals(lower.iceMass(), back.iceMass(), REL * lower.iceMass());
		assertEquals(lower.liquidMass(), back.liquidMass(), REL * lower.liquidMass());
		assertEquals(lower.thickness(), back.thickness(), REL * lower.thickness());
		assertEquals(lower.internalEnergy(), back.internalEnergy(), REL * Math.abs(lower.internalEnergy()));
	}

	@Test
	public void addAndRemoveMass() {
		SnowCell grown = upper.addMass(5.0, 0.0, -1.0e5, 0.05);
		assertEquals(20.0, grown.iceMass(), 0.0);
		assertEquals(0.17, grown.thickness(), 1e-15);
		assertEquals(-5.0e5, grown.internalEnergy(), 0.0);

		SnowCell drained = lower.removeMass(0.0, 1.5, 1.5 * 333700.0, 0.0);
		assertEquals(0.5, drained.liquidMass(), 1e-15);
		assertEquals(lower.internalEnergy() - 1.5 * 333700.0, drained.internalEnergy(), 0.0);
	}

	@Test
	public void derivedQuantities() {
		SnowCell cell = new SnowCell(91.7, 10.0, 0.5, 0.0);
		assertEquals(203.4, cell.density(), 1e-12);
		assertEquals(0.2, cell.iceVolumeFraction(), 1e-15);
		assertEquals(0.02, cell.liquidVolumeFraction(), 1e-15);
		assertEquals(0.8, cell.porosity(), 1e-15);
	}

	@Test
	public void atTemperatureIsAtEquilibrium() {
		SnowEquationOfState eos = new SnowEquationOfState(1e5);
		SnowCell cell = SnowCell.atTemperature(eos, 50.0, 0.2, SnowConstants.MELTING_TEMPERATURE - 4e-6);
		assertEquals(cell.liquidMass(), eos.equilibrate(cell).liquidMass(), 1e-9);
		assertEquals(SnowConstants.MELTING_TEMPERATURE - 4e-6, eos.temperature(cell), 1e-12);
	}

	@Test(expected = IllegalArgumentException.class)
	public void removingTooMuchFails() {
		upper.removeMass(16.0, 0.0, 0.0, 0.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void massLessCellIsRejected() {
		new SnowCell(0.0, 0.0, 0.1, 0.0);
	}

	@Test(expected = IllegalArgumentException.class)
	public void splitFractionsMustSumToOne() {
		lower.split(0.5, 0.4);
	}
}
