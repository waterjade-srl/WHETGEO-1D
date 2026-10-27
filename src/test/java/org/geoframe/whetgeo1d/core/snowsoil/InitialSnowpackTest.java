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
package org.geoframe.whetgeo1d.core.snowsoil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.geoframe.whetgeo1d.snow.GradedThicknessRestratification;
import org.geoframe.whetgeo1d.snow.MassCriterionRestratification;
import org.geoframe.whetgeo1d.snow.RestratificationPolicy;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.SnowColumn;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SurfaceSnowAge;
import org.junit.Test;

/**
 * {@link InitialSnowpack}: a declared pack becomes the cells of the model
 * with its mass and internal energy, whatever the restratification and the
 * number of declared layers.
 */
public class InitialSnowpackTest {

	private static final SnowEquationOfState EOS = new SnowEquationOfState(1e5);
	private static final RestratificationPolicy GEOTOP = new MassCriterionRestratification(
			MassCriterionRestratification.Parameters.geotopDefaults(), EOS);
	private static final RestratificationPolicy GRADED = new GradedThicknessRestratification(
			GradedThicknessRestratification.Parameters.proposed(), EOS);

	private static double energy(List<SnowCell> cells) {
		return cells.stream().mapToDouble(SnowCell::internalEnergy).sum();
	}

	private static double thickness(List<SnowCell> cells) {
		return cells.stream().mapToDouble(SnowCell::thickness).sum();
	}

	/** GEOtop's initial keywords (ARF_1D: 23.78 mm at 208.72 kg m-3, -21.47 °C), both restratifications. */
	@Test
	public void bulkConservesMassEnergyAndThickness() {
		InitialSnowpack.Description d = InitialSnowpack.Description.bulk(23.78, 208.72, 273.15 - 21.47, 0.0);
		for (RestratificationPolicy policy : List.of(GEOTOP, GRADED)) {
			List<SnowCell> cells = InitialSnowpack.cells(d, EOS, policy, 11).cells();
			assertTrue(!cells.isEmpty());
			assertEquals(23.78, SnowColumn.totalMass(cells), 1e-12);
			assertEquals(d.internalEnergy(), energy(cells), 1e-9 * Math.abs(d.internalEnergy()));
			assertEquals(23.78 / 208.72, thickness(cells), 1e-12);
			for (SnowCell c : cells) {
				assertEquals(273.15 - 21.47, EOS.temperature(c), 1e-6);
			}
		}
	}

	/** A deep pack is split by the restratification into several cells, mass and energy kept. */
	@Test
	public void deepPackIsSplit() {
		InitialSnowpack.Description d = InitialSnowpack.Description.bulk(400.0, 300.0, 268.15, 0.0);
		for (RestratificationPolicy policy : List.of(GEOTOP, GRADED)) {
			List<SnowCell> cells = InitialSnowpack.cells(d, EOS, policy, 11).cells();
			assertTrue(policy + ": " + cells.size() + " cells", cells.size() > 1);
			assertEquals(400.0, SnowColumn.totalMass(cells), 1e-9);
			assertEquals(d.internalEnergy(), energy(cells), 1e-9 * Math.abs(d.internalEnergy()));
		}
	}

	/**
	 * Liquid water declared in a cold layer refreezes and warms it; where its
	 * latent heat exceeds the cold content, the layer ends at 0 °C with the
	 * liquid U / L. U is kept and the phases are at equilibrium.
	 */
	@Test
	public void liquidInAColdLayerRefreezes() {
		// 1 kg m-2 of liquid in 50 kg m-2 at -10 °C: cold content 1.08 MJ m-2, latent heat 0.33: all refreezes
		InitialSnowpack.Description cold = new InitialSnowpack.Description(
				List.of(new InitialSnowpack.Layer(0.2, 250.0, 263.15, 1.0)), 0.0);
		List<SnowCell> cells = InitialSnowpack.cells(cold, EOS, GRADED, 11).cells();
		assertEquals(cold.internalEnergy(), energy(cells), 1e-9 * Math.abs(cold.internalEnergy()));
		assertTrue(cells.stream().mapToDouble(SnowCell::liquidMass).sum() < 1e-6);
		for (SnowCell c : cells) {
			assertTrue(EOS.temperature(c) > 263.15 && EOS.temperature(c) < 273.15);
		}
		// 5 kg m-2 at -5 °C: latent heat 1.67 MJ m-2 against a cold content of 0.58: 0 °C, liquid U / L
		InitialSnowpack.Description warm = new InitialSnowpack.Description(
				List.of(new InitialSnowpack.Layer(0.2, 250.0, 268.15, 5.0)), 0.0);
		cells = InitialSnowpack.cells(warm, EOS, GRADED, 11).cells();
		assertEquals(warm.internalEnergy(), energy(cells), 1e-9 * Math.abs(warm.internalEnergy()));
		assertEquals(warm.internalEnergy() / 333700.0, cells.stream().mapToDouble(SnowCell::liquidMass).sum(), 1e-3);
		for (SnowCell c : cells) {
			assertEquals(273.15, EOS.temperature(c), 1e-3);
		}
	}

	/** More layers than the pack can hold are merged, mass and energy kept. */
	@Test
	public void layersBeyondTheCapacityAreMerged() {
		List<InitialSnowpack.Layer> layers = new ArrayList<>();
		for (int i = 0; i < 15; i++) {
			layers.add(new InitialSnowpack.Layer(0.02 + 0.01 * i, 150.0 + 20.0 * i, 263.15 + 0.5 * i, 0.0));
		}
		InitialSnowpack.Description d = new InitialSnowpack.Description(layers, 3.0);
		for (RestratificationPolicy policy : List.of(GEOTOP, GRADED)) {
			List<SnowCell> cells = InitialSnowpack.cells(d, EOS, policy, 5).cells();
			assertTrue(cells.size() <= 11);
			assertEquals(d.mass(), SnowColumn.totalMass(cells), 1e-9);
			assertEquals(d.internalEnergy(), energy(cells), 1e-9 * Math.abs(d.internalEnergy()));
		}
	}

	/** The age is GEOtop's conversion with the temperature of the top layer. */
	@Test
	public void ageAsGeotop() {
		InitialSnowpack.Description d = InitialSnowpack.Description.bulk(50.0, 200.0, 263.15, 4.0);
		double age = InitialSnowpack.cells(d, EOS, GEOTOP, 10).surfaceAge();
		assertEquals(SurfaceSnowAge.fromSeconds(4.0 * 86400.0, 263.15), age, 0.0);
	}

	@Test
	public void noSnow() {
		InitialSnowpack.Snowpack p = InitialSnowpack.cells(InitialSnowpack.Description.bulk(0.0, 200.0, 263.15, 0.0),
				EOS, GEOTOP, 10);
		assertTrue(p.cells().isEmpty());
		assertEquals(0.0, p.surfaceAge(), 0.0);
	}

	@Test
	public void impossibleLayers() {
		assertThrows(IllegalArgumentException.class, () -> new InitialSnowpack.Layer(0.1, 200.0, 274.0, 0.0));
		assertThrows(IllegalArgumentException.class, () -> new InitialSnowpack.Layer(0.1, 950.0, 263.15, 0.0));
		assertThrows(IllegalArgumentException.class, () -> new InitialSnowpack.Layer(0.1, 200.0, 263.15, 25.0));
		assertThrows(IllegalArgumentException.class, () -> new InitialSnowpack.Layer(0.0, 200.0, 263.15, 0.0));
	}
}
