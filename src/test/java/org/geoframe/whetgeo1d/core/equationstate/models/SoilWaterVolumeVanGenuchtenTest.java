package org.geoframe.whetgeo1d.core.equationstate.models;

import static org.junit.Assert.assertEquals;

import org.geoframe.closureequation.closureequation.Parameters;
import org.geoframe.closureequation.closureequation.SWRCVanGenuchten;
import org.geoframe.whetgeo1d.core.state.WGGeometry;
import org.geoframe.whetgeo1d.core.state.WGProblemQuantities;
import org.junit.Test;

/** The Nested-Newton decomposition of the Van Genuchten water volume uses the x* of each cell. */
public class SoilWaterVolumeVanGenuchtenTest {

	/** Two soils of the coupled test case: n = 2.547, α = 2.991 and n = 1.342, α = 0.846. */
	private static Parameters twoSoils() {
		double[] zero = { 0.0, 0.0 };
		return new Parameters(1000.0, 920.0, 4188.0, 2117.0, 0.6, 2.29, 333700, 273.15, 278.15, -766.45,
				new double[] { 0.36689, 0.48664 }, new double[] { 0.05385, 0.13335 }, new double[] { 2500.0, 2500.0 },
				new double[] { 1000.0, 1000.0 }, new double[] { 2.5, 2.5 }, new double[] { 273.15, 273.15 },
				new double[] { 2.54723, 1.34174 }, new double[] { 2.991, 0.846 }, zero.clone(), zero.clone(),
				zero.clone(), new double[] { 5.4e-5, 9.7e-7 }, zero.clone(), zero.clone());
	}

	/**
	 * Cells in the opposite order of their parameter ids: cell 0 has id 1, cell 1
	 * has id 0. Between its x* and zero p is the slope at the cell's own x*.
	 * Reading x* by id instead of by cell took the other soil's x*.
	 */
	@Test
	public void decompositionUsesTheXStarOfTheCell() {
		Parameters parameters = twoSoils();
		WGGeometry geometry = new WGGeometry(new double[] { 0.05, 0.15 }, new double[] { 0.05, 0.1, 0.05 },
				new double[] { 0.1, 0.1 });
		int[] parameterId = { 1, 0 };
		WGProblemQuantities variables = new WGProblemQuantities(new double[] { -1.0, -1.0 },
				new double[] { 280.0, 280.0 }, new int[] { 0, 0 }, parameterId);
		SoilWaterVolumeVanGenuchten volume = new SoilWaterVolumeVanGenuchten(new SWRCVanGenuchten(parameters), geometry,
				variables);
		for (int element = 0; element < 2; element++) {
			volume.computeXStar(280.0, parameterId[element], element);
		}
		for (int element = 0; element < 2; element++) {
			int id = parameterId[element];
			double n = parameters.par1[id];
			double star = -1.0 / parameters.par2[id] * Math.pow((n - 1.0) / n, 1.0 / n);
			assertEquals(star, variables.waterSuctionStar1[element], 1e-15);
			double slope = volume.dEquationState(star, 280.0, id, element);
			for (double x = star * 0.9; x < 0.0; x -= star * 0.1) {
				assertEquals("cell " + element + " at " + x, slope, volume.p(x, 280.0, id, element), 1e-15);
				assertEquals(volume.equationState(star, 280.0, id, element) + slope * (x - star),
						volume.pIntegral(x, 280.0, id, element), 1e-15);
			}
		}
	}
}
