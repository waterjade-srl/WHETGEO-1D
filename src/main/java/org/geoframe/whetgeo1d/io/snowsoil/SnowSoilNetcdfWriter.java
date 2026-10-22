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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;
import org.geoframe.whetgeo1d.snow.SnowCell;
import org.geoframe.whetgeo1d.snow.surface.SurfaceFluxes;

import ucar.ma2.Array;
import ucar.ma2.ArrayDouble;
import ucar.ma2.ArrayInt;
import ucar.ma2.DataType;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.Variable;
import ucar.nc2.write.NetcdfFormatWriter;

/**
 * NetCDF output of the snow+soil column, in the layout of the NetCDF outputs
 * of WHETGEO-1D read by the GEOframe notebooks (dimensions {@code time},
 * {@code depth}, {@code dualDepth}; η coordinates, zero at the soil surface,
 * negative in the soil), with the snow added.
 *
 * <p>
 * <b>Format.</b> NetCDF-3 classic, written by netcdf-java without native
 * libraries and read by xarray with the {@code scipy} engine, as the existing
 * notebooks do.
 *
 * <p>
 * <b>Snow profiles.</b> The number of snow cells changes in time. The snow
 * variables have a fixed dimension {@code snowCell} of
 * {@link OutputOptions#maxSnowCells()} cells, index 0 at the bottom, with the
 * cells above the pack set to the fill value NaN, and {@code snowCells(time)}
 * gives the active ones. Each cell carries its η (centre, bottom and top
 * faces) and thickness, so a profile of cells of variable thickness is drawn
 * from the file alone. A ragged (CF contiguous) layout would need a second
 * unlimited dimension, which NetCDF-3 does not have, or NetCDF-4, which
 * netcdf-java writes only through the native library; with the capacity of the
 * column fixed by the configuration (COL-3) the padded array is small and
 * reads as a plain 2-D array. A pack with more cells than the capacity is an
 * error.
 *
 * <p>
 * <b>Header.</b> The {@link RunHeader} goes into global attributes: code
 * version, configuration, then every attribute in order, the names made valid
 * for NetCDF (characters other than letters, digits and {@code _.@+-} become
 * {@code _}).
 *
 * <p>
 * Units are SI as the solver (ARCH-5): K, m, kg m-2, J m-2, W m-2. Masses and
 * energies are summed over the steps of a record, fluxes averaged, states at
 * the end of the record (see the {@code cell_methods} of each variable).
 */
public final class SnowSoilNetcdfWriter implements SnowSoilOutputWriter {

	private final Path file;
	private final OutputOptions options;
	private final StepAccumulator accumulator;

	private NetcdfFormatWriter writer;
	private int soilCells;
	private int record;
	private long previous = Long.MIN_VALUE;

	/** Writes the file at {@code file}, replacing it if it exists. */
	public SnowSoilNetcdfWriter(Path file, OutputOptions options) {
		this.file = Objects.requireNonNull(file, "file");
		this.options = Objects.requireNonNull(options, "options");
		this.accumulator = new StepAccumulator(options);
	}

	@Override
	public void open(RunHeader header) throws IOException {
		if (writer != null) {
			throw new IllegalStateException("already open");
		}
		if (file.getParent() != null) {
			Files.createDirectories(file.getParent());
		}
		Files.deleteIfExists(file);
		soilCells = header.soilDepth().length;
		int snowCells = options.maxSnowCells();

		NetcdfFormatWriter.Builder b = NetcdfFormatWriter.createNewNetcdf3(file.toString()).setFill(true);
		b.addAttribute(new Attribute("Conventions", "CF-1.8"));
		b.addAttribute(new Attribute("title", "WHETGEO-1D snow+soil column"));
		b.addAttribute(new Attribute("code_version", header.codeVersion()));
		b.addAttribute(new Attribute("configuration", header.configuration()));
		Set<String> used = new HashSet<>(Set.of("Conventions", "title", "code_version", "configuration"));
		for (Map.Entry<String, String> e : header.attributes().entrySet()) {
			String name = netcdfName(e.getKey());
			String unique = name;
			for (int k = 2; !used.add(unique); k++) {
				unique = name + "_" + k;
			}
			b.addAttribute(new Attribute(unique, e.getValue()));
		}
		b.addAttribute(new Attribute("vertical_coordinate",
				"eta [m]: zero at the soil surface, negative in the soil, positive in the snow; index 0 at the bottom"));
		b.addAttribute(new Attribute("output_interval_s", (double) options.intervalSeconds()));

		Dimension time = b.addUnlimitedDimension("time");
		Dimension snow = b.addDimension("snowCell", snowCells);
		Variable.Builder<?> t = b.addVariable("time", DataType.DOUBLE, List.of(time));
		t.addAttribute(new Attribute("units", "seconds since 1970-01-01 00:00:00 UTC"));
		t.addAttribute(new Attribute("long_name", "end of the output step"));
		t.addAttribute(new Attribute("calendar", "standard"));
		b.addVariable("snowCell", DataType.INT, List.of(snow))
				.addAttribute(new Attribute("long_name", "index of the snow cell, 0 at the bottom"));

		if (soilCells > 0) {
			Dimension depth = b.addDimension("depth", soilCells);
			Dimension dual = b.addDimension("dualDepth", soilCells + 1);
			variable(b, "depth", List.of(depth), "m", "eta of the soil cell centres", null);
			variable(b, "dualDepth", List.of(dual), "m", "eta of the soil cell faces", null);
			variable(b, "soilThickness", List.of(depth), "m", "thickness of the soil cells", null);
			variable(b, "T", List.of(time, depth), "K", "soil temperature", "time: point");
			variable(b, "Tmean", List.of(time, depth), "K", "soil temperature averaged over the record", "time: mean");
			variable(b, "thetaIce", List.of(time, depth), "1", "soil ice content, volume of water equivalent",
					"time: point");
			variable(b, "psi", List.of(time, depth), "m",
					"soil water suction; psi_w0, the potential of the total water, in a freezing soil", "time: point");
			if (options.soilWaterContent() != null) {
				variable(b, "theta", List.of(time, depth), "1", "soil liquid water content", "time: point");
			}
			variable(b, "waterFlux", List.of(time, dual), "kg m-2", "water through the soil faces, positive upward",
					"time: sum");
		}

		for (StepAccumulator.Scalar s : StepAccumulator.SCALARS) {
			variable(b, s.name(), List.of(time), s.units(), s.description(), cellMethod(s.kind()));
		}

		List<Dimension> profile = List.of(time, snow);
		variable(b, "snowCellEta", profile, "m", "eta of the snow cell centres", "time: point");
		variable(b, "snowCellEtaBottom", profile, "m", "eta of the bottom face of the snow cells", "time: point");
		variable(b, "snowCellEtaTop", profile, "m", "eta of the top face of the snow cells", "time: point");
		variable(b, "snowCellThickness", profile, "m", "thickness of the snow cells", "time: point");
		variable(b, "snowCellIce", profile, "kg m-2", "ice in the snow cells", "time: point");
		variable(b, "snowCellLiquid", profile, "kg m-2", "liquid water in the snow cells", "time: point");
		variable(b, "snowCellDensity", profile, "kg m-3", "bulk density of the snow cells", "time: point");
		variable(b, "snowCellTemperature", profile, "K", "temperature of the snow cells", "time: point");
		variable(b, "snowCellLiquidContent", profile, "1", "volumetric liquid water content of the snow cells",
				"time: point");
		variable(b, "snowCellInternalEnergy", profile, "J m-2",
				"internal energy of the snow cells, referred to liquid water at 273.15 K", "time: point");

		writer = b.build();
		try {
			ArrayInt.D1 index = new ArrayInt.D1(snowCells, false);
			for (int k = 0; k < snowCells; k++) {
				index.set(k, k);
			}
			writer.write("snowCell", index);
			if (soilCells > 0) {
				double[] eta = new double[soilCells];
				for (int i = 0; i < soilCells; i++) {
					eta[i] = -header.soilDepth()[i];
				}
				writer.write("depth", Array.makeFromJavaArray(eta));
				writer.write("dualDepth", Array.makeFromJavaArray(faces(header.soilThickness())));
				writer.write("soilThickness", Array.makeFromJavaArray(header.soilThickness().clone()));
			}
		} catch (InvalidRangeException e) {
			throw new IOException(e);
		}
	}

	@Override
	public void write(long epochSeconds, SnowSoilSolver solver, SnowSoilStepResult result) throws IOException {
		if (writer == null) {
			throw new IllegalStateException("open() not called");
		}
		if (result.snow().size() > options.maxSnowCells()) {
			throw new IOException("the pack has " + result.snow().size() + " cells, more than the "
					+ options.maxSnowCells() + " of the output (OutputOptions.maxSnowCells)");
		}
		accumulator.add(solver, result);
		boolean closes = StepAccumulator.closesRecord(previous, epochSeconds, options.intervalSeconds());
		previous = epochSeconds;
		if (closes) {
			writeRecord(epochSeconds, accumulator.take());
		}
	}

	private void writeRecord(long epochSeconds, StepAccumulator.Record r) throws IOException {
		int[] origin = { record, 0 };
		int[] scalarOrigin = { record };
		try {
			writer.write("time", scalarOrigin, row1(epochSeconds));
			for (StepAccumulator.Scalar s : StepAccumulator.SCALARS) {
				writer.write(s.name(), scalarOrigin, row1(r.get(s.name())));
			}
			if (soilCells > 0) {
				writer.write("T", origin, row(r.soilTemperature()));
				writer.write("Tmean", origin, row(r.soilTemperatureMean()));
				writer.write("thetaIce", origin, row(r.soilIce()));
				writer.write("psi", origin, row(r.soilSuction()));
				if (options.soilWaterContent() != null) {
					writer.write("theta", origin, row(r.soilTheta()));
				}
				double[] face = r.faceWater();
				if (face == null) {
					face = new double[soilCells + 1];
					Arrays.fill(face, Double.NaN);
				}
				writer.write("waterFlux", origin, row(face));
			}
			int n = options.maxSnowCells();
			double[][] p = new double[10][n];
			for (double[] a : p) {
				Arrays.fill(a, Double.NaN);
			}
			double bottom = 0.0;
			List<SnowCell> snow = r.snow();
			for (int k = 0; k < snow.size(); k++) {
				SnowCell c = snow.get(k);
				p[0][k] = bottom + 0.5 * c.thickness();
				p[1][k] = bottom;
				p[2][k] = bottom + c.thickness();
				p[3][k] = c.thickness();
				p[4][k] = c.iceMass();
				p[5][k] = c.liquidMass();
				p[6][k] = c.density();
				p[7][k] = options.equationOfState().temperature(c);
				p[8][k] = c.liquidVolumeFraction();
				p[9][k] = c.internalEnergy();
				bottom += c.thickness();
			}
			String[] names = { "snowCellEta", "snowCellEtaBottom", "snowCellEtaTop", "snowCellThickness", "snowCellIce",
					"snowCellLiquid", "snowCellDensity", "snowCellTemperature", "snowCellLiquidContent",
					"snowCellInternalEnergy" };
			for (int v = 0; v < names.length; v++) {
				writer.write(names[v], origin, row(p[v]));
			}
		} catch (InvalidRangeException e) {
			throw new IOException(e);
		}
		record++;
	}

	@Override
	public void bareSoilSurface(SurfaceFluxes fluxes, double surfaceTemperature) {
		accumulator.bareSoilSurface(fluxes, surfaceTemperature);
	}

	@Override
	public void close() throws IOException {
		if (writer != null) {
			writer.close();
			writer = null;
		}
	}

	/** η of the faces from the thicknesses, the top face at zero, index 0 at the bottom. */
	static double[] faces(double[] thickness) {
		double[] faces = new double[thickness.length + 1];
		for (int i = thickness.length - 1; i >= 0; i--) {
			faces[i] = faces[i + 1] - thickness[i];
		}
		return faces;
	}

	/** A name valid for NetCDF-3. */
	static String netcdfName(String key) {
		String name = key.replaceAll("[^A-Za-z0-9_.@+-]", "_");
		if (name.isEmpty() || !Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
			name = "_" + name;
		}
		return name;
	}

	private static String cellMethod(StepAccumulator.Kind kind) {
		return switch (kind) {
		case SUM -> "time: sum";
		case MEAN -> "time: mean";
		case STATE -> "time: point";
		};
	}

	private static void variable(NetcdfFormatWriter.Builder b, String name, List<Dimension> dims, String units,
			String longName, String cellMethods) {
		Variable.Builder<?> v = b.addVariable(name, DataType.DOUBLE, dims);
		v.addAttribute(new Attribute("units", units));
		v.addAttribute(new Attribute("long_name", longName));
		if (cellMethods != null) {
			v.addAttribute(new Attribute("cell_methods", cellMethods));
			v.addAttribute(new Attribute("_FillValue", Double.NaN));
		}
	}

	private static Array row1(double value) {
		ArrayDouble.D1 a = new ArrayDouble.D1(1);
		a.set(0, value);
		return a;
	}

	private static Array row(double[] values) {
		ArrayDouble.D2 a = new ArrayDouble.D2(1, values.length);
		for (int i = 0; i < values.length; i++) {
			a.set(0, i, values[i]);
		}
		return a;
	}
}
