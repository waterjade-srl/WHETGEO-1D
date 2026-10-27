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
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.Horizon;

/**
 * The static inputs of a case of 1D runs, in one GeoPackage (one file per
 * case; the time series stay in OMS tables):
 *
 * <ul>
 * <li>{@code points}, a POINT layer: {@code id}, {@code name},
 * {@code elevation} [m], {@code lat}, {@code lon} [deg], {@code slope},
 * {@code aspect} [deg from north, clockwise], {@code svf} [-] (NULL: computed
 * from the horizon, {@link Horizon#skyViewFactor}), {@code wind_height},
 * {@code temperature_height} [m];</li>
 * <li>{@code horizon}: {@code point_id}, {@code azimuth}, {@code height}
 * [deg];</li>
 * <li>{@code soil_layers}, {@code soil_parameters},
 * {@code soil_parameter_types}, {@code initial_condition}: WHETGEO-1D's soil
 * inputs, with the columns of its CSV files, by {@code profile_id} (the
 * parameters also by {@code parameter_id}, in the order the grid refers to
 * them) and {@code ic_id};</li>
 * <li>{@code columns}: {@code id}, {@code point_id}, {@code profile_id},
 * {@code ic_id}, optionally {@code forcing}, one row per 1D run.</li>
 * </ul>
 *
 * The time series are keyed by point, as GEOframe's series by station
 * ({@code value_<point id>}). A place driven by several forcing sets (two
 * reanalyses, say) is one point with one column per set: the column names its
 * set in {@code forcing}, and the series of each set stay in their own tables.
 *
 * The layer of points is a standard GeoPackage feature table (GEOframe's
 * {@code OmsVectorReader} reads it as {@code file.gpkg#points}); the other
 * tables are GeoPackage attribute tables, read here with SQL.
 */
public final class CaseGeoPackage {

	/** A point of the case. svf is NaN where the file has none. */
	public record Point(int id, String name, double elevation, double latitude, double longitude, double slope,
			double aspect, double svf, double windHeight, double temperatureHeight) {
	}

	/**
	 * A 1D run: where, which soil profile, which initial condition, and which
	 * forcing set (null when the case has one).
	 */
	public record Column(int id, int pointId, int profileId, int initialConditionId, String forcing) {
	}

	private final Path file;

	public CaseGeoPackage(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("no case GeoPackage " + file);
		}
		this.file = file;
	}

	public Path file() {
		return file;
	}

	public Column column(int id) {
		boolean sets = !query("SELECT 1 FROM pragma_table_info('columns') WHERE name = 'forcing'").isEmpty();
		List<Object[]> r = query("SELECT point_id, profile_id, ic_id" + (sets ? ", forcing" : "")
				+ " FROM columns WHERE id = ?", id);
		if (r.isEmpty()) {
			throw new IllegalArgumentException(file + ": no column " + id);
		}
		Object[] c = r.get(0);
		return new Column(id, integer(c[0]), integer(c[1]), integer(c[2]), sets ? (String) c[3] : null);
	}

	public Point point(int id) {
		List<Object[]> r = query("SELECT name, elevation, lat, lon, slope, aspect, svf, wind_height, temperature_height "
				+ "FROM points WHERE id = ?", id);
		if (r.isEmpty()) {
			throw new IllegalArgumentException(file + ": no point " + id);
		}
		Object[] p = r.get(0);
		return new Point(id, (String) p[0], real(p[1]), real(p[2]), real(p[3]), real(p[4]), real(p[5]), real(p[6]),
				real(p[7]), real(p[8]));
	}

	/** The horizon of a point; flat if it has none. */
	public Horizon horizon(int pointId) {
		List<Object[]> r = query("SELECT azimuth, height FROM horizon WHERE point_id = ? ORDER BY azimuth", pointId);
		if (r.isEmpty()) {
			return Horizon.flat();
		}
		double[] a = new double[r.size()];
		double[] h = new double[r.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = real(r.get(i)[0]);
			h[i] = real(r.get(i)[1]);
		}
		return new Horizon(a, h);
	}

	/** svf of the point, or computed from its horizon and slope where the file has none. */
	public double skyViewFactor(Point p) {
		return Double.isNaN(p.svf()) ? horizon(p.id()).skyViewFactor(p.slope(), p.aspect()) : p.svf();
	}

	/**
	 * Writes the soil inputs of a column as WHETGEO-1D's CSV files in a new
	 * temporary folder, for {@link SoilGridInputs#read}; the caller deletes it.
	 */
	public Path soilGridFolder(Column c) throws IOException {
		Path folder = Files.createTempDirectory("snowsoil-case-grid");
		write(folder.resolve("grid_geometry.csv"),
				query("SELECT * FROM soil_layers WHERE profile_id = ? ORDER BY fid", c.profileId()), 2);
		write(folder.resolve("swrc_parameters.csv"),
				query("SELECT * FROM soil_parameters WHERE profile_id = ? ORDER BY parameter_id", c.profileId()), 3);
		write(folder.resolve("swrc_parameter_types.csv"), query("SELECT * FROM soil_parameter_types ORDER BY fid"), 1);
		write(folder.resolve("initial_condition.csv"),
				query("SELECT * FROM initial_condition WHERE ic_id = ? ORDER BY fid", c.initialConditionId()), 2);
		return folder;
	}

	/** Deletes a folder written by {@link #soilGridFolder}. */
	public static void delete(Path folder) throws IOException {
		try (Stream<Path> s = Files.list(folder)) {
			for (Path f : s.toList()) {
				Files.delete(f);
			}
		}
		Files.delete(folder);
	}

	/** The rows of the query, the column names first. */
	private List<Object[]> query(String sql, Object... args) {
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
				PreparedStatement s = c.prepareStatement(sql)) {
			for (int i = 0; i < args.length; i++) {
				s.setObject(i + 1, args[i]);
			}
			List<Object[]> rows = new ArrayList<>();
			try (ResultSet r = s.executeQuery()) {
				ResultSetMetaData m = r.getMetaData();
				if (sql.startsWith("SELECT *")) {
					Object[] names = new Object[m.getColumnCount()];
					for (int i = 0; i < names.length; i++) {
						names[i] = m.getColumnName(i + 1);
					}
					rows.add(names);
				}
				while (r.next()) {
					Object[] row = new Object[m.getColumnCount()];
					for (int i = 0; i < row.length; i++) {
						row[i] = r.getObject(i + 1);
					}
					rows.add(row);
				}
			}
			return rows;
		} catch (SQLException e) {
			throw new UncheckedIOException(new IOException(file + ": " + e.getMessage(), e));
		}
	}

	/** A CSV file from rows whose first row holds the names, without the first {@code skip} columns. */
	private static void write(Path csv, List<Object[]> rows, int skip) throws IOException {
		StringBuilder b = new StringBuilder();
		for (Object[] r : rows) {
			for (int i = skip; i < r.length; i++) {
				if (i > skip) {
					b.append(',');
				}
				Object v = r[i];
				b.append(v == null ? "nan"
						: v instanceof Double d ? String.format(Locale.ROOT, "%.17g", d) : v.toString());
			}
			b.append('\n');
		}
		Files.writeString(csv, b.toString());
	}

	private static double real(Object v) {
		return v == null ? Double.NaN : ((Number) v).doubleValue();
	}

	private static int integer(Object v) {
		return ((Number) v).intValue();
	}
}
