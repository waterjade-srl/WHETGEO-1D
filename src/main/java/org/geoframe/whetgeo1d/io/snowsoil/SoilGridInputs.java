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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.geoframe.whetgeo1d.io.Whetgeo1DInputsHandler;
import org.hortonmachine.dbs.compat.ADb;
import org.hortonmachine.dbs.compat.EDb;
import org.hortonmachine.gears.io.geoframe.ReadNetCDFHeatDiffusionGrid1D;

/**
 * The soil grid of the snow+soil component, read from WHETGEO-1D's own inputs
 * (ARCH-5): index 0 at the bottom, η of the cell centres (zero at the soil
 * surface, negative in the soil), z = η - η<sub>bottom</sub> for the solver,
 * initial suction and temperature, ids, and the parameter arrays indexed by
 * parameter id. Three sources:
 *
 * <ul>
 * <li>a GeoPackage of WHETGEO-1D ({@link Whetgeo1DInputsHandler}, parameter
 * ids from 1);</li>
 * <li>a folder of WHETGEO-1D's CSV inputs ({@code grid_geometry.csv},
 * {@code initial_condition.csv}, {@code swrc_parameters.csv},
 * {@code swrc_parameter_types.csv}), turned into a temporary GeoPackage by
 * {@link Whetgeo1DInputsHandler#createDbFromCsv};</li>
 * <li>a NetCDF grid of WHETGEO-1D ({@link ReadNetCDFHeatDiffusionGrid1D}).</li>
 * </ul>
 *
 * Zero-thickness pseudo cells (the pond of WHETGEO-1D's coupled top) are
 * rejected: the snow+soil column has its own top.
 */
public final class SoilGridInputs {

	public double[] eta;
	public double[] z;
	public double[] spaceDelta;
	public double[] controlVolume;
	public double[] psi;
	public double[] temperature;
	public int[] equationStateID;
	public int[] parameterID;
	public double[] soilParticlesDensity;
	public double[] soilParticlesThermalConductivity;
	public double[] soilParticlesSpecificHeatCapacity;
	public double[] thetaS;
	public double[] thetaR;
	public double[] meltingTemperature;
	public double[] ks;
	public double[] alphaSS;
	public double[] betaSS;
	public double[] par1SWRC;
	public double[] par2SWRC;
	public double[] par3SWRC;
	public double[] par4SWRC;
	public double[] par5SWRC;

	private SoilGridInputs() {
	}

	/** Reads a .gpkg, a .nc or a folder of CSV inputs. */
	public static SoilGridInputs read(String path) throws Exception {
		File file = new File(path);
		SoilGridInputs g;
		if (file.isDirectory()) {
			g = fromCsvFolder(file);
		} else if (path.toLowerCase().endsWith(".nc")) {
			g = fromNetCdf(path);
		} else {
			try (Whetgeo1DInputsHandler handler = new Whetgeo1DInputsHandler(path)) {
				handler.read();
				g = fromHandler(handler);
			}
		}
		g.check();
		return g;
	}

	private static SoilGridInputs fromCsvFolder(File folder) throws Exception {
		Path tmp = Files.createTempFile("snowsoil-grid", ".gpkg");
		Files.delete(tmp);
		try {
			try (ADb db = EDb.GEOPACKAGE.getDb()) {
				db.open(tmp.toString());
				Whetgeo1DInputsHandler.createDbFromCsv(folder.getAbsolutePath(), db);
				try (Whetgeo1DInputsHandler handler = new Whetgeo1DInputsHandler(db)) {
					handler.read();
					return fromHandler(handler);
				}
			}
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	private static SoilGridInputs fromHandler(Whetgeo1DInputsHandler h) {
		SoilGridInputs g = new SoilGridInputs();
		g.eta = h.eta;
		g.z = h.z;
		g.spaceDelta = h.spaceDelta;
		g.controlVolume = h.controlVolume;
		g.psi = h.psi;
		g.temperature = h.temperatureIC;
		g.equationStateID = h.equationStateID;
		g.parameterID = h.parameterID;
		g.soilParticlesDensity = h.soilParticlesDensity;
		g.soilParticlesThermalConductivity = h.soilParticlesThermalConductivity;
		g.soilParticlesSpecificHeatCapacity = h.soilParticlesSpecificHeatCapacity;
		g.thetaS = h.thetaS;
		g.thetaR = h.thetaR;
		g.meltingTemperature = h.meltingTemperature;
		g.ks = h.Ks;
		g.alphaSS = h.alphaSS;
		g.betaSS = h.betaSS;
		g.par1SWRC = h.par1SWRC;
		g.par2SWRC = h.par2SWRC;
		g.par3SWRC = h.par3SWRC;
		g.par4SWRC = h.par4SWRC;
		g.par5SWRC = h.par5SWRC;
		return g;
	}

	private static SoilGridInputs fromNetCdf(String path) throws Exception {
		ReadNetCDFHeatDiffusionGrid1D r = new ReadNetCDFHeatDiffusionGrid1D();
		r.gridFilename = path;
		r.read();
		SoilGridInputs g = new SoilGridInputs();
		g.eta = r.eta;
		g.z = r.z;
		g.spaceDelta = r.spaceDelta;
		g.controlVolume = r.controlVolume;
		g.psi = r.psi;
		g.temperature = r.temperatureIC;
		g.equationStateID = r.equationStateID;
		g.parameterID = r.parameterID;
		g.soilParticlesDensity = r.soilParticlesDensity;
		g.soilParticlesThermalConductivity = r.soilParticlesThermalConductivity;
		g.soilParticlesSpecificHeatCapacity = r.soilParticlesSpecificHeatCapacity;
		g.thetaS = r.thetaS;
		g.thetaR = r.thetaR;
		g.meltingTemperature = r.meltingTemperature;
		g.ks = r.Ks;
		g.alphaSS = r.alphaSS;
		g.betaSS = r.betaSS;
		g.par1SWRC = r.par1SWRC;
		g.par2SWRC = r.par2SWRC;
		g.par3SWRC = r.par3SWRC;
		g.par4SWRC = r.par4SWRC;
		g.par5SWRC = r.par5SWRC;
		return g;
	}

	private void check() {
		int n = controlVolume.length;
		if (n < 1) {
			throw new IllegalArgumentException("the soil grid has no cell");
		}
		for (double v : controlVolume) {
			if (!(v > 0.0)) {
				throw new IllegalArgumentException(
						"zero-thickness (pseudo) cells are not supported by the snow+soil column");
			}
		}
		int m = thetaS.length;
		if (alphaSS == null) {
			alphaSS = new double[m];
		}
		if (betaSS == null) {
			betaSS = new double[m];
		}
		if (meltingTemperature == null) {
			meltingTemperature = new double[m];
			java.util.Arrays.fill(meltingTemperature, 273.15);
		}
	}
}
