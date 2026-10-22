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
package org.geoframe.whetgeo1d.io.snowsoil;

import static org.junit.Assert.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** The soil grid from a folder of WHETGEO-1D CSV inputs, with an initial condition given at every cell. */
public class SoilGridInputsTest {

	@Test
	public void initialConditionThroughAllItsPoints() throws Exception {
		Path dir = Files.createTempDirectory("snowsoil-grid");
		Files.writeString(dir.resolve("swrc_parameter_types.csv"), "val1,val2\nn,par1\nalpha,par2\n");
		Files.writeString(dir.resolve("grid_geometry.csv"),
				"Type,eta,K,equationStateID,parameterID\nL,0,1,0,1\nL,-0.1,1,0,1\nL,-0.3,1,0,1\nL,-0.6,nan,nan,nan\n");
		// one point per cell centre, not on a line: each cell must get its own value
		Files.writeString(dir.resolve("initial_condition.csv"),
				"eta,psi0,t0\n-0.05,-1.0,280.0\n-0.2,-0.5,275.0\n-0.45,0.0,271.0\n");
		Files.writeString(dir.resolve("swrc_parameters.csv"),
				"spDensity,spConductivity,spSpecificHeatCapacity,thetaS,thetaR,meltingT,alpha,n,alphaSpecificStorage,"
						+ "betaSpecificStorage,Ks\n2200,2.5,1000,0.8,0.2,273.15,30,1.8,1e-8,0,1e-5\n");
		SoilGridInputs g = SoilGridInputs.read(dir.toString());
		// index 0 at the bottom
		assertEquals(-0.45, g.eta[0], 1e-12);
		assertEquals(271.0, g.temperature[0], 1e-12);
		assertEquals(275.0, g.temperature[1], 1e-12);
		assertEquals(280.0, g.temperature[2], 1e-12);
		assertEquals(0.0, g.psi[0], 1e-12);
		assertEquals(-0.5, g.psi[1], 1e-12);
		assertEquals(-1.0, g.psi[2], 1e-12);
	}
}
