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
package org.geoframe.whetgeo1d.snow.surface;

import static org.geoframe.whetgeo1d.snow.SnowConstants.LATENT_HEAT_FUSION;
import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_ICE;
import static org.geoframe.whetgeo1d.snow.SnowConstants.SPECIFIC_HEAT_WATER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.surface.SnowSublimation.ThicknessRule;
import org.junit.Test;

public class SnowSublimationTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);

	/** Bottom 40 kg at -5 degC on 0.15 m, top 10 kg at -10 degC on 0.05 m. */
	private static List<SnowCell> coldColumn() {
		return List.of(SnowCell.atTemperature(EOS, 40.0, 0.15, MELTING_TEMPERATURE - 5.0),
				SnowCell.atTemperature(EOS, 10.0, 0.05, MELTING_TEMPERATURE - 10.0));
	}

	/** Bottom 40 kg at -5 degC; top 9 kg of ice and 1 kg of water at 0 degC. */
	private static List<SnowCell> wetTop() {
		SnowCell wet = EOS.equilibrate(new SnowCell(9.0, 1.0, 0.04, LATENT_HEAT_FUSION));
		return List.of(SnowCell.atTemperature(EOS, 40.0, 0.15, MELTING_TEMPERATURE - 5.0), wet);
	}

	private static void assertConserved(List<SnowCell> before, Sublimated s) {
		double m0 = SnowColumn.totalMass(before);
		double u0 = SnowColumn.internalEnergy(before);
		double scale = Math.max(1.0, Math.abs(u0));
		assertEquals("mass", m0, SnowColumn.totalMass(s.cells()) + s.exchangedMass(), 1e-12 * Math.max(1.0, m0));
		assertEquals("energy", u0, SnowColumn.internalEnergy(s.cells()) + s.exchangedEnergy() + s.residualEnergy(),
				1e-12 * scale);
	}

	@Test
	public void sublimationTakesIceWithItsEnergy() {
		List<SnowCell> before = coldColumn();
		Sublimated s = SnowSublimation.apply(before, 2.0, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		// 2 kg of ice at -10 degC: 2 · 2117 · (-10) = -42340 J/m2
		assertEquals(2.0, s.exchangedMass(), 0.0);
		assertEquals(-42340.0, s.exchangedEnergy(), 1e-6);
		SnowCell top = s.cells().get(1);
		assertEquals(8.0, top.totalMass(), 1e-12);
		assertEquals(0.05, top.thickness(), 0.0);
		assertEquals("temperature unchanged", MELTING_TEMPERATURE - 10.0, EOS.temperature(top), 1e-9);
		assertEquals(before.get(0), s.cells().get(0));
		assertConserved(before, s);
		Sublimated shrunk = SnowSublimation.apply(before, 2.0, VapourPhase.ICE, ThicknessRule.CONSTANT_DENSITY, EOS);
		assertEquals(0.04, shrunk.cells().get(1).thickness(), 1e-15);
	}

	@Test
	public void evaporationFromADryCellReportsThePhaseMismatch() {
		// liquid declared on a dry cell: the ice leaves as ice, the temperature does not change, and the
		// melting it would need, 2 (L_f + c_w (-10) - c_i (-10)) = 625980 J/m2, is reported
		List<SnowCell> before = coldColumn();
		Sublimated s = SnowSublimation.apply(before, 2.0, VapourPhase.LIQUID, ThicknessRule.UNCHANGED, EOS);
		assertEquals(-42340.0, s.exchangedEnergy(), 1e-4);
		assertEquals(2.0 * (LATENT_HEAT_FUSION - 10.0 * SPECIFIC_HEAT_WATER + 10.0 * SPECIFIC_HEAT_ICE),
				s.phaseMismatchEnergy(), 1e-4);
		assertEquals(MELTING_TEMPERATURE - 10.0, EOS.temperature(s.cells().get(1)), 1e-9);
		assertConserved(before, s);
	}

	@Test
	public void wetSurfaceEvaporatesItsWater() {
		List<SnowCell> before = wetTop();
		Sublimated s = SnowSublimation.apply(before, 0.5, VapourPhase.LIQUID, ThicknessRule.UNCHANGED, EOS);
		SnowCell top = s.cells().get(1);
		// re-equilibration on the regularised curve moves ~3e-6 kg
		assertEquals(9.0, top.iceMass(), 1e-5);
		assertEquals(0.5, top.liquidMass(), 1e-5);
		assertEquals(0.0, s.phaseMismatchEnergy(), 0.0);
		assertEquals(0.5 * LATENT_HEAT_FUSION, s.exchangedEnergy(), 0.1);
		assertConserved(before, s);
	}

	@Test
	public void geotopTakesIceFromAWetSurface() {
		// GEOtop at Ts >= 0: lambda = L_e but ice is removed; the cell keeps its water and the vapour is short of
		// L_f per kg: 0.5 · 333700 = 166850 J/m2 not accounted for
		List<SnowCell> before = wetTop();
		Sublimated s = SnowSublimation.apply(before, 0.5, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		SnowCell top = s.cells().get(1);
		assertEquals(8.5, top.iceMass(), 1e-5);
		assertEquals(1.0, top.liquidMass(), 1e-5);
		// the cell sits 1.5e-5 K below 0 degC on the regularised curve: c_i dT m = -0.03 J
		assertEquals(0.0, s.exchangedEnergy(), 0.1);
		double vapourEnergyGeotop = s.exchangedEnergy() + VapourPhase.LIQUID.latentHeat(MELTING_TEMPERATURE) * 0.5;
		double vapourEnergyConsistent = 0.5 * (VapourPhase.LIQUID.specificEnergy(MELTING_TEMPERATURE)
				+ VapourPhase.LIQUID.latentHeat(MELTING_TEMPERATURE));
		assertEquals(166850.0, vapourEnergyConsistent - vapourEnergyGeotop, 0.1);
		assertConserved(before, s);
	}

	@Test
	public void consumesTheTopCellAndContinuesBelow() {
		List<SnowCell> before = coldColumn();
		Sublimated s = SnowSublimation.apply(before, 12.0, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		assertEquals(1, s.cells().size());
		assertEquals(38.0, s.cells().get(0).totalMass(), 1e-12);
		assertEquals(0.15, s.cells().get(0).thickness(), 0.0);
		// 10 kg at -10 degC and 2 kg at -5 degC
		assertEquals(-211700.0 - 21170.0, s.exchangedEnergy(), 1e-3);
		assertEquals(0.0, s.unmetMass(), 0.0);
		assertConserved(before, s);
	}

	@Test
	public void waterOfAConsumedCellLeavesAsWater() {
		// top: 1.5 kg ice + 0.5 kg water at 0 degC; 2.5 kg declared as ice. The top cell goes (its water with
		// L_f per kg, a mismatch of -0.5 L_f), then 0.5 kg of ice at -5 degC from below
		SnowCell wet = EOS.equilibrate(new SnowCell(1.5, 0.5, 0.01, 0.5 * LATENT_HEAT_FUSION));
		List<SnowCell> before = List.of(SnowCell.atTemperature(EOS, 40.0, 0.15, MELTING_TEMPERATURE - 5.0), wet);
		Sublimated s = SnowSublimation.apply(before, 2.5, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		assertEquals(1, s.cells().size());
		assertEquals(39.5, s.cells().get(0).totalMass(), 1e-12);
		assertEquals(MELTING_TEMPERATURE - 5.0, EOS.temperature(s.cells().get(0)), 1e-6);
		assertEquals(-0.5 * LATENT_HEAT_FUSION, s.phaseMismatchEnergy(), 0.1);
		assertEquals(0.5 * LATENT_HEAT_FUSION - 0.5 * 5.0 * SPECIFIC_HEAT_ICE, s.exchangedEnergy(), 0.1);
		assertConserved(before, s);
	}

	@Test
	public void moreThanTheColumnIsUnmet() {
		List<SnowCell> before = coldColumn();
		Sublimated s = SnowSublimation.apply(before, 60.0, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		assertTrue(s.cells().isEmpty());
		assertEquals(50.0, s.exchangedMass(), 1e-12);
		assertEquals(10.0, s.unmetMass(), 1e-12);
		assertConserved(before, s);
	}

	@Test
	public void depositionAddsIceAtTheCellTemperature() {
		List<SnowCell> before = coldColumn();
		Sublimated s = SnowSublimation.apply(before, -1.0, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		assertEquals(-1.0, s.exchangedMass(), 0.0);
		assertEquals(21170.0, s.exchangedEnergy(), 1e-6);
		assertEquals(11.0, s.cells().get(1).totalMass(), 1e-12);
		assertEquals(MELTING_TEMPERATURE - 10.0, EOS.temperature(s.cells().get(1)), 1e-9);
		assertEquals(0.05, s.cells().get(1).thickness(), 0.0);
		assertConserved(before, s);
		Sublimated grown = SnowSublimation.apply(before, -1.0, VapourPhase.ICE, ThicknessRule.CONSTANT_DENSITY, EOS);
		assertEquals(0.055, grown.cells().get(1).thickness(), 1e-15);
	}

	@Test
	public void condensationOnAColdCellReleasesItsLatentHeat() {
		List<SnowCell> before = coldColumn();
		Sublimated s = SnowSublimation.apply(before, -0.2, VapourPhase.LIQUID, ThicknessRule.UNCHANGED, EOS);
		// 0.2 kg of water at -10 degC refreezes: U = -211700 + 0.2 (333700 - 41880) over 10.2 kg of ice
		double u = -211700.0 + 0.2 * (LATENT_HEAT_FUSION - 10.0 * SPECIFIC_HEAT_WATER);
		assertEquals(MELTING_TEMPERATURE + u / (10.2 * SPECIFIC_HEAT_ICE), EOS.temperature(s.cells().get(1)), 1e-6);
		assertConserved(before, s);
	}

	@Test
	public void emptyColumnOrNothingToDo() {
		Sublimated frost = SnowSublimation.apply(List.of(), -0.3, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		assertEquals(-0.3, frost.unmetMass(), 0.0);
		assertEquals(0.0, frost.exchangedMass(), 0.0);
		Sublimated none = SnowSublimation.apply(coldColumn(), 0.0, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
		assertEquals(coldColumn(), none.cells());
	}

	@Test
	public void conservesMassAndEnergyOnRandomColumns() {
		Random random = new Random(2026);
		for (int trial = 0; trial < 3000; trial++) {
			List<SnowCell> cells = new ArrayList<>();
			int n = random.nextInt(6);
			for (int i = 0; i < n; i++) {
				double mass = 0.2 + 30.0 * random.nextDouble();
				double t = MELTING_TEMPERATURE - 20.0 * random.nextDouble() * random.nextDouble();
				SnowCell cell = SnowCell.atTemperature(EOS, mass, mass / (100.0 + 400.0 * random.nextDouble()), t);
				if (random.nextDouble() < 0.3) {
					double water = 0.1 * mass * random.nextDouble();
					cell = EOS.equilibrate(cell.addMass(0.0, water, water * LATENT_HEAT_FUSION, 0.0));
				}
				cells.add(cell);
			}
			double request = random.nextDouble() < 0.2 ? -2.0 * random.nextDouble() : 40.0 * Math.pow(random.nextDouble(), 3);
			VapourPhase phase = random.nextBoolean() ? VapourPhase.ICE : VapourPhase.LIQUID;
			ThicknessRule rule = random.nextBoolean() ? ThicknessRule.UNCHANGED : ThicknessRule.CONSTANT_DENSITY;
			Sublimated s = SnowSublimation.apply(cells, request, phase, rule, EOS);
			assertConserved(cells, s);
			assertEquals(request, s.exchangedMass() + s.unmetMass(), 1e-12 * Math.max(1.0, Math.abs(request)));
			for (SnowCell cell : s.cells()) {
				assertTrue(cell.thickness() > 0.0);
			}
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNonFiniteMass() {
		SnowSublimation.apply(coldColumn(), Double.NaN, VapourPhase.ICE, ThicknessRule.UNCHANGED, EOS);
	}
}
