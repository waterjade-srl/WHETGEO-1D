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
package org.geoframe.whetgeo1d.core.snowsoil.fidelity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.geoframe.whetgeo1d.core.snowsoil.SnowProcesses;
import org.geoframe.whetgeo1d.snow.GeotopCompaction;
import org.geoframe.whetgeo1d.snow.MassCriterionRestratification;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.junit.Test;

public class GeotopSnowParametersTest {

	private static final SnowProcesses FIDELITY = SnowProcesses.geotop(new SnowEquationOfState(1e5), 2.5, 2.0);

	@Test
	public void keywordsOfMatschInGeotopUnits() {
		SnowProcesses p = GeotopSnowParameters.parse("FreshSnowReflVis=0.88, FreshSnowReflNIR=0.63; "
				+ "SnowAgingCoeffVis=0.1108, AlbExtParSnow=182, IrriducibleWatSatSnow=0.0595, SnowEmissiv=0.99, "
				+ "SnowRoughness=1.6, DrySnowDefRate=1.01, WetSnowDefRate=2.49, SnowViscosity=1e6, "
				+ "MaxWaterEqSnowLayerContent=10, SWEbottom=50, SWEtop=2000, MaxSnowLayersMiddle=50, PointSlope=21.4")
				.apply(FIDELITY);
		assertEquals(0.88, p.albedo().parameters().freshVisible(), 0.0);
		assertEquals(0.63, p.albedo().parameters().freshNearInfrared(), 0.0);
		assertEquals(0.1108, p.albedo().parameters().ageingVisible(), 0.0);
		assertEquals(0.5, p.albedo().parameters().ageingNearInfrared(), 0.0);
		assertEquals(0.182, p.albedo().parameters().extinctionDepth(), 1e-15);
		assertEquals(0.0595, p.percolation().parameters().irreducibleSaturation(), 0.0);
		assertEquals(1.0, p.percolation().parameters().thinCellIce(), 1e-15);
		assertEquals(0.99, p.surfaceBalance().emissivity(), 0.0);
		assertEquals(1.6e-3, p.turbulence().parameters().roughnessLength(), 1e-15);
		GeotopCompaction.Parameters c = ((GeotopCompaction) p.compaction()).parameters();
		assertEquals(1.01, c.dryDeformationRate(), 0.0);
		assertEquals(2.49, c.wetDeformationRate(), 0.0);
		assertEquals(1.0, c.minimumIceMass(), 1e-15);
		MassCriterionRestratification.Parameters m = ((MassCriterionRestratification) p.restratification())
				.parameters();
		assertEquals(5 + 200 + 50, m.maxCells());
		assertEquals(Math.toRadians(21.4), p.slopeAngle(), 0.0);
		// untouched
		assertEquals(2.5, p.turbulence().parameters().windHeight(), 0.0);
		assertSame(FIDELITY.surfaceCoupling(), p.surfaceCoupling());
	}

	@Test
	public void emptyChangesNothing() {
		assertSame(FIDELITY, GeotopSnowParameters.parse("").apply(FIDELITY));
		assertSame(FIDELITY, GeotopSnowParameters.parse(null).apply(FIDELITY));
	}

	@Test
	public void unknownKeywordIsAnError() {
		assertThrows(IllegalArgumentException.class, () -> GeotopSnowParameters.parse("ThresSnowSoilRough=100"));
		assertThrows(IllegalArgumentException.class, () -> GeotopSnowParameters.parse("SnowEmissiv"));
	}
}
