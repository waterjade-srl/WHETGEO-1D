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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SnowColumnTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);

	@Test
	public void freshSnowOnBareGroundKeepsItsOwnTemperature() {
		SnowCell fresh = SnowCell.atTemperature(EOS, 4.0, 0.04, MELTING_TEMPERATURE - 3.0);
		List<SnowCell> cells = SnowColumn.depositFreshSnow(List.of(), fresh, EOS);
		assertEquals(1, cells.size());
		assertEquals(MELTING_TEMPERATURE - 3.0, EOS.temperature(cells.get(0)), 1e-9);
	}

	@Test
	public void freshSnowJoinsTheTopCellConservingEnergy() {
		SnowCell bottom = SnowCell.atTemperature(EOS, 30.0, 0.1, MELTING_TEMPERATURE - 8.0);
		SnowCell top = SnowCell.atTemperature(EOS, 5.0, 0.03, MELTING_TEMPERATURE - 1e-6);
		SnowCell fresh = SnowCell.atTemperature(EOS, 2.0, 0.02, MELTING_TEMPERATURE - 6.0);
		List<SnowCell> before = List.of(bottom, top);
		List<SnowCell> after = SnowColumn.depositFreshSnow(before, fresh, EOS);
		assertEquals(2, after.size());
		assertEquals(bottom, after.get(0));
		assertEquals(SnowColumn.totalMass(before) + 2.0, SnowColumn.totalMass(after), 1e-12);
		assertEquals(SnowColumn.internalEnergy(before) + fresh.internalEnergy(), SnowColumn.internalEnergy(after),
				1e-6);
		assertEquals(0.05, after.get(1).thickness(), 1e-15);
		// the wet top cell is cooled by the fresh snow and its liquid refreezes
		assertTrue(after.get(1).liquidMass() < top.liquidMass());
	}

	@Test
	public void totals() {
		List<SnowCell> cells = List.of(new SnowCell(10, 1, 0.1, -100), new SnowCell(4, 0, 0.05, -50));
		assertEquals(15.0, SnowColumn.totalMass(cells), 0.0);
		assertEquals(-150.0, SnowColumn.internalEnergy(cells), 0.0);
		assertEquals(0.15, SnowColumn.depth(cells), 1e-15);
	}
}
