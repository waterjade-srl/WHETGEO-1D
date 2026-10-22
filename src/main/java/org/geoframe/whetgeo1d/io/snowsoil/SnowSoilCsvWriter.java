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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;
import org.geoframe.whetgeo1d.snow.SnowCell;

/**
 * Point outputs in CSV for the comparisons with GEOtop: the files, columns,
 * names and units of GEOtop 3.0's {@code PointOutputFile} and soil and snow
 * profiles, so that a comparison script aligns the columns by name.
 *
 * <ul>
 * <li>{@code point.csv}: the GEOtop columns this column has a counterpart for,
 * with GEOtop's names and units (mm, °C, W/m2), then the other quantities of
 * the step with names in GEOtop's style ({@code name[units]});</li>
 * <li>{@code soilTz.csv}, {@code psiz.csv}, {@code thetaliq.csv},
 * {@code thetaice.csv}: soil profiles as GEOtop's
 * {@code SoilAveragedTempProfileFile}, {@code SoilLiqWaterPressProfileFile},
 * {@code SoilLiqContentProfileFile}, {@code SoilIceContentProfileFile}: one
 * column per cell, named by the depth of its centre in mm, from the surface
 * down; °C, mm, -, -;</li>
 * <li>{@code snowT.csv}, {@code snowDepth.csv}, {@code snowLiq.csv},
 * {@code snowIce.csv}: snow profiles as GEOtop's layer files, columns
 * {@code L1}… with L1 the bottom cell, as in GEOtop; °C, layer thickness in mm,
 * kg/m2, kg/m2.</li>
 * </ul>
 *
 * The first column is GEOtop's {@code Date12[DDMMYYYYhhmm]}, the end of the
 * output step in the zone of {@link OutputOptions#zone()}, the second the same
 * instant in seconds since 1970-01-01 UTC. Missing values are GEOtop's
 * -9999; numbers are written with the shortest decimal form that reads back
 * to the same double.
 *
 * <p>
 * Units. ARCH-5 keeps unit conversions in the adapters that compare with
 * GEOtop; this writer is one, and converts only on the way out: the NetCDF
 * output of {@link SnowSoilNetcdfWriter} stays in SI.
 *
 * <p>
 * Every file starts with the {@link RunHeader} as comment lines beginning with
 * {@code #} (read with {@code pandas.read_csv(..., comment="#")}).
 */
public final class SnowSoilCsvWriter implements SnowSoilOutputWriter {

	/** GEOtop's no-value. */
	public static final double NO_VALUE = -9999.0;

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

	/** A column of the point file: header and value of a record. */
	private record Column(String header, ToDoubleFunction<StepAccumulator.Record> value) {
	}

	private final Path directory;
	private final OutputOptions options;
	private final StepAccumulator accumulator;
	private final List<Column> point = new ArrayList<>();
	private final Map<String, BufferedWriter> files = new LinkedHashMap<>();
	private long previous = Long.MIN_VALUE;
	private int soilCells;

	/** Writes the files in {@code directory}, created if needed; existing files are replaced. */
	public SnowSoilCsvWriter(Path directory, OutputOptions options) {
		this.directory = Objects.requireNonNull(directory, "directory");
		this.options = Objects.requireNonNull(options, "options");
		this.accumulator = new StepAccumulator(options);
		pointColumns();
	}

	private void geotop(String header, String scalar, double scale, double offset) {
		point.add(new Column(header, r -> r.get(scalar) * scale + offset));
	}

	private void pointColumns() {
		// GEOtop's columns, its names and units (no canopy: under = over canopy)
		geotop("Psnow_over_canopy[mm]", "snowfall", 1.0, 0.0);
		point.add(new Column("Prain_over_canopy[mm]", r -> r.get("rainOnSnow") + r.get("rainOnBareSoil")));
		geotop("Psnow_under_canopy[mm]", "snowfall", 1.0, 0.0);
		point.add(new Column("Prain_under_canopy[mm]", r -> r.get("rainOnSnow") + r.get("rainOnBareSoil")));
		geotop("Prain_rain_on_snow[mm]", "rainOnSnow", 1.0, 0.0);
		geotop("Tsurface[C]", "surfaceTemperature", 1.0, -MELTING_TEMPERATURE);
		geotop("Surface_Energy_balance[W/m2]", "surfaceEnergyBalance", 1.0, 0.0);
		geotop("Soil_heat_flux[W/m2]", "surfaceHeatFlux", 1.0, 0.0);
		geotop("SWnet[W/m2]", "netShortwave", 1.0, 0.0);
		geotop("LWnet[W/m2]", "netLongwave", 1.0, 0.0);
		geotop("H[W/m2]", "sensibleHeat", 1.0, 0.0);
		geotop("LE[W/m2]", "latentHeat", 1.0, 0.0);
		geotop("snow_depth[mm]", "snowDepth", 1e3, 0.0);
		geotop("snow_water_equivalent[mm]", "snowWaterEquivalent", 1.0, 0.0);
		geotop("snow_density[kg/m3]", "snowDensity", 1.0, 0.0);
		geotop("snow_temperature[C]", "snowTemperature", 1.0, -MELTING_TEMPERATURE);
		geotop("snow_melted[mm]", "melt", 1.0, 0.0);
		geotop("snow_subl[mm]", "vapour", 1.0, 0.0);
		// the rest of the step, GEOtop's style; kg m-2 written as mm
		for (StepAccumulator.Scalar s : StepAccumulator.SCALARS) {
			if (MAPPED.contains(s.name())) {
				continue;
			}
			String units = switch (s.units()) {
			case "kg m-2" -> "mm";
			case "K" -> "C";
			case "1" -> "-";
			default -> s.units().replace(" m-2", "/m2").replace(" m-3", "/m3").replace(" ", "");
			};
			double offset = s.units().equals("K") ? -MELTING_TEMPERATURE : 0.0;
			String name = s.name();
			point.add(new Column(name + "[" + units + "]", r -> r.get(name) + offset));
		}
	}

	/** Scalars already written under a GEOtop name. */
	private static final List<String> MAPPED = List.of("snowfall", "rainOnSnow", "surfaceTemperature",
			"surfaceEnergyBalance", "surfaceHeatFlux", "netShortwave", "netLongwave", "sensibleHeat", "latentHeat",
			"snowDepth", "snowWaterEquivalent", "snowDensity", "snowTemperature", "melt", "vapour");

	/** Headers of the point file, in order, the two date columns included. */
	public List<String> pointHeaders() {
		List<String> h = new ArrayList<>(List.of("Date12[DDMMYYYYhhmm]", "time[s]"));
		point.forEach(c -> h.add(c.header()));
		return h;
	}

	@Override
	public void open(RunHeader header) throws IOException {
		if (!files.isEmpty()) {
			throw new IllegalStateException("already open");
		}
		Files.createDirectories(directory);
		soilCells = header.soilDepth().length;
		StringBuilder preamble = new StringBuilder();
		preamble.append("# code_version: ").append(oneLine(header.codeVersion())).append('\n');
		preamble.append("# configuration: ").append(oneLine(header.configuration())).append('\n');
		for (Map.Entry<String, String> e : header.attributes().entrySet()) {
			preamble.append("# ").append(oneLine(e.getKey())).append(": ").append(oneLine(e.getValue())).append('\n');
		}
		preamble.append("# output_interval_s: ").append(options.intervalSeconds()).append('\n');
		preamble.append("# dates: end of the output step, UTC").append(options.zone().getId().equals("Z") ? ""
				: options.zone().getId()).append("; no value: -9999\n");

		open("point.csv", preamble, pointHeaders());
		if (soilCells > 0) {
			List<String> depths = new ArrayList<>(List.of("Date12[DDMMYYYYhhmm]", "time[s]"));
			for (int i = soilCells - 1; i >= 0; i--) {
				depths.add(String.format(Locale.ROOT, "%f", 1e3 * header.soilDepth()[i]));
			}
			open("soilTz.csv", preamble.toString() + "# soil temperature [C]; columns: depth of the cell centres [mm]\n",
					depths);
			open("psiz.csv", preamble.toString()
					+ "# soil water suction [mm], psi_w0 in a freezing soil; columns: depth of the cell centres [mm]\n",
					depths);
			if (options.soilWaterContent() != null) {
				open("thetaliq.csv", preamble.toString()
						+ "# soil liquid water content [-]; columns: depth of the cell centres [mm]\n", depths);
			}
			open("thetaice.csv",
					preamble.toString() + "# soil ice content [-]; columns: depth of the cell centres [mm]\n", depths);
		}
		List<String> layers = new ArrayList<>(List.of("Date12[DDMMYYYYhhmm]", "time[s]"));
		for (int k = 1; k <= options.maxSnowCells(); k++) {
			layers.add("L" + k);
		}
		open("snowT.csv", preamble.toString() + "# snow temperature [C]; L1 is the bottom cell\n", layers);
		open("snowDepth.csv", preamble.toString() + "# snow layer thickness [mm]; L1 is the bottom cell\n", layers);
		open("snowLiq.csv", preamble.toString() + "# snow liquid water [kg/m2]; L1 is the bottom cell\n", layers);
		open("snowIce.csv", preamble.toString() + "# snow ice [kg/m2]; L1 is the bottom cell\n", layers);
	}

	private void open(String name, CharSequence preamble, List<String> headers) throws IOException {
		BufferedWriter w = Files.newBufferedWriter(directory.resolve(name), StandardCharsets.UTF_8);
		w.append(preamble);
		w.append(String.join(",", headers)).append('\n');
		files.put(name, w);
	}

	@Override
	public void write(long epochSeconds, SnowSoilSolver solver, SnowSoilStepResult result) throws IOException {
		if (files.isEmpty()) {
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
		String date = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), options.zone()).format(DATE)
				+ "," + epochSeconds;
		StringBuilder line = new StringBuilder(date);
		for (Column c : point) {
			line.append(',').append(number(c.value().applyAsDouble(r)));
		}
		files.get("point.csv").append(line).append('\n');

		if (soilCells > 0) {
			profile("soilTz.csv", date, r.soilTemperature(), -MELTING_TEMPERATURE, 1.0);
			profile("psiz.csv", date, r.soilSuction(), 0.0, 1e3);
			if (r.soilTheta() != null) {
				profile("thetaliq.csv", date, r.soilTheta(), 0.0, 1.0);
			}
			profile("thetaice.csv", date, r.soilIce(), 0.0, 1.0);
		}
		int n = options.maxSnowCells();
		double[][] p = new double[4][n];
		for (double[] a : p) {
			java.util.Arrays.fill(a, Double.NaN);
		}
		List<SnowCell> snow = r.snow();
		for (int k = 0; k < snow.size(); k++) {
			SnowCell c = snow.get(k);
			p[0][k] = options.equationOfState().temperature(c) - MELTING_TEMPERATURE;
			p[1][k] = 1e3 * c.thickness();
			p[2][k] = c.liquidMass();
			p[3][k] = c.iceMass();
		}
		String[] names = { "snowT.csv", "snowDepth.csv", "snowLiq.csv", "snowIce.csv" };
		for (int v = 0; v < names.length; v++) {
			StringBuilder s = new StringBuilder(date);
			for (double x : p[v]) {
				s.append(',').append(number(x));
			}
			files.get(names[v]).append(s).append('\n');
		}
	}

	/** A soil profile from the surface down, bottom-first values converted as v · scale + offset. */
	private void profile(String name, String date, double[] values, double offset, double scale) throws IOException {
		StringBuilder s = new StringBuilder(date);
		for (int i = values.length - 1; i >= 0; i--) {
			s.append(',').append(number(values[i] * scale + offset));
		}
		files.get(name).append(s).append('\n');
	}

	private static String number(double v) {
		return Double.isFinite(v) ? Double.toString(v) : "-9999.0";
	}

	private static String oneLine(String s) {
		return s.replace('\n', ' ').replace('\r', ' ');
	}

	@Override
	public void close() throws IOException {
		IOException first = null;
		for (BufferedWriter w : files.values()) {
			try {
				w.close();
			} catch (IOException e) {
				first = first == null ? e : first;
			}
		}
		files.clear();
		if (first != null) {
			throw first;
		}
	}
}
