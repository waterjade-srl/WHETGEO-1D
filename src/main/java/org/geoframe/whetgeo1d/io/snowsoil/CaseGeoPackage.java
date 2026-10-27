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
import java.util.Optional;
import java.util.stream.Stream;

import org.geoframe.whetgeo1d.core.snowsoil.InitialSnowpack;
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
 * {@code ic_id}, optionally {@code forcing}, one row per 1D run;</li>
 * <li>optionally, named grids of a soil profile, written by
 * {@code tools/casegrid} with GEOframe's {@code WHETGEO1D_GridCreator}:
 * {@code grids} ({@code grid_id}, {@code profile_id}, {@code method} and its
 * parameters) and {@code grid_cells} ({@code grid_id}, {@code cell} from 1 at
 * the top, {@code eta_top}, {@code eta_bottom} [m], {@code equationStateID},
 * {@code parameterID}). Without a grid, the cells are those of
 * {@code soil_layers}: K uniform cells per layer;</li>
 * <li>optionally, the snow at the start, by {@code ic_id}:
 * {@code snow_initial} ({@code swe} [kg m-2], {@code density} [kg m-3],
 * {@code temperature} [K], {@code age} [d]: one layer, as GEOtop's initial
 * keywords) and {@code snow_initial_layers} ({@code layer} from 1 at the top,
 * {@code thickness} [m], {@code density} [kg m-3], {@code temperature} [K],
 * {@code liquid} [kg m-2]), which, when present, replace the layer of
 * {@code snow_initial} and keep only its age ({@link InitialSnowpack}).</li>
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

	/** A cell of a named grid, η of its faces [m]. */
	public record GridCell(double etaTop, double etaBottom, int equationStateId, int parameterId) {
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

	/** Whether the file has a table. */
	public boolean hasTable(String name) {
		return !query("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", name).isEmpty();
	}

	/** Profile of a named grid. */
	public int gridProfile(int gridId) {
		List<Object[]> r = hasTable("grids") ? query("SELECT profile_id FROM grids WHERE grid_id = ?", gridId) : List.of();
		if (r.isEmpty()) {
			throw new IllegalArgumentException(file + ": no grid " + gridId);
		}
		return integer(r.get(0)[0]);
	}

	/** The cells of a named grid, top first. */
	public List<GridCell> gridCells(int gridId) {
		gridProfile(gridId);
		List<GridCell> cells = new ArrayList<>();
		for (Object[] r : query("SELECT eta_top, eta_bottom, equationStateID, parameterID FROM grid_cells "
				+ "WHERE grid_id = ? ORDER BY cell", gridId)) {
			cells.add(new GridCell(real(r[0]), real(r[1]), integer(r[2]), integer(r[3])));
		}
		return cells;
	}

	/**
	 * The snow at the start of the initial condition {@code icId}: its layers
	 * from {@code snow_initial_layers}, or the one layer of {@code snow_initial};
	 * empty when the case has none for it.
	 */
	public Optional<InitialSnowpack.Description> snowInitial(int icId) {
		List<Object[]> bulk = hasTable("snow_initial")
				? query("SELECT swe, density, temperature, age FROM snow_initial WHERE ic_id = ?", icId)
				: List.of();
		List<Object[]> rows = hasTable("snow_initial_layers")
				? query("SELECT layer, thickness, density, temperature, liquid FROM snow_initial_layers "
						+ "WHERE ic_id = ? ORDER BY layer", icId)
				: List.of();
		double age = bulk.isEmpty() || bulk.get(0)[3] == null ? 0.0 : real(bulk.get(0)[3]);
		if (!rows.isEmpty()) {
			List<InitialSnowpack.Layer> layers = new ArrayList<>();
			for (int i = 0; i < rows.size(); i++) {
				Object[] r = rows.get(i);
				if (integer(r[0]) != i + 1) {
					throw new IllegalArgumentException(file + ": snow_initial_layers of ic " + icId
							+ " must be numbered 1, 2, ... from the top");
				}
				layers.add(new InitialSnowpack.Layer(real(r[1]), real(r[2]), real(r[3]),
						r[4] == null ? 0.0 : real(r[4])));
			}
			return Optional.of(new InitialSnowpack.Description(layers, age));
		}
		if (bulk.isEmpty() || bulk.get(0)[0] == null) {
			return Optional.empty();
		}
		Object[] b = bulk.get(0);
		return Optional.of(InitialSnowpack.Description.bulk(real(b[0]), real(b[1]), real(b[2]), age));
	}

	/**
	 * What does not hold together in the case, one line per problem; empty when
	 * it is consistent. Checks that every column refers to a point, a profile and
	 * an initial condition that exist; that the initial condition covers the soil;
	 * that the layers refer to parameters that exist; that every named grid
	 * covers its profile without gaps, with each cell in the layer of its centre;
	 * and that the initial snow is a pack that can exist.
	 */
	public List<String> check() {
		List<String> problems = new ArrayList<>();
		for (Object[] c : query("SELECT id, point_id, profile_id, ic_id FROM columns ORDER BY id")) {
			int id = integer(c[0]), point = integer(c[1]), profile = integer(c[2]), ic = integer(c[3]);
			if (query("SELECT 1 FROM points WHERE id = ?", point).isEmpty()) {
				problems.add("column " + id + ": no point " + point);
			}
			double[] bounds = profileBounds(profile);
			if (bounds == null) {
				problems.add("column " + id + ": no soil profile " + profile);
				continue;
			}
			List<Object[]> etas = query("SELECT MIN(eta), MAX(eta) FROM initial_condition WHERE ic_id = ?", ic);
			if (etas.get(0)[0] == null) {
				problems.add("column " + id + ": no initial condition " + ic);
			} else if (real(etas.get(0)[0]) > bounds[0] + 1e-9 || real(etas.get(0)[1]) < bounds[1] - 1e-9) {
				problems.add("column " + id + ": initial condition " + ic + " covers " + etas.get(0)[1] + " to "
						+ etas.get(0)[0] + " m, the soil " + bounds[1] + " to " + bounds[0] + " m");
			}
		}
		for (Object[] r : query("SELECT DISTINCT l.profile_id, l.parameterID FROM soil_layers l WHERE l.parameterID "
				+ "IS NOT NULL AND NOT EXISTS (SELECT 1 FROM soil_parameters p WHERE p.profile_id = l.profile_id "
				+ "AND p.parameter_id = l.parameterID)")) {
			problems.add("soil profile " + r[0] + ": parameter " + r[1] + " missing");
		}
		if (hasTable("grids")) {
			for (Object[] g : query("SELECT grid_id FROM grids ORDER BY grid_id")) {
				checkGrid(integer(g[0]), problems);
			}
		}
		for (String table : List.of("snow_initial", "snow_initial_layers")) {
			if (hasTable(table)) {
				for (Object[] r : query("SELECT DISTINCT ic_id FROM " + table)) {
					int ic = integer(r[0]);
					if (query("SELECT 1 FROM initial_condition WHERE ic_id = ?", ic).isEmpty()) {
						problems.add(table + ": no initial condition " + ic);
					}
					try {
						snowInitial(ic);
					} catch (IllegalArgumentException e) {
						problems.add("initial snow of ic " + ic + ": " + e.getMessage());
					}
				}
			}
		}
		return problems;
	}

	private void checkGrid(int gridId, List<String> problems) {
		int profile = gridProfile(gridId);
		double[] bounds = profileBounds(profile);
		if (bounds == null) {
			problems.add("grid " + gridId + ": no soil profile " + profile);
			return;
		}
		List<GridCell> cells = gridCells(gridId);
		if (cells.isEmpty()) {
			problems.add("grid " + gridId + ": no cells");
			return;
		}
		if (Math.abs(cells.get(0).etaTop() - bounds[1]) > 1e-9
				|| Math.abs(cells.get(cells.size() - 1).etaBottom() - bounds[0]) > 1e-9) {
			problems.add("grid " + gridId + ": from " + cells.get(0).etaTop() + " to "
					+ cells.get(cells.size() - 1).etaBottom() + " m, the soil from " + bounds[1] + " to " + bounds[0]
					+ " m");
		}
		List<Object[]> layers = query("SELECT eta, parameterID FROM soil_layers WHERE profile_id = ? ORDER BY fid",
				profile);
		for (int i = 0; i < cells.size(); i++) {
			GridCell c = cells.get(i);
			if (!(c.etaBottom() < c.etaTop())) {
				problems.add("grid " + gridId + ", cell " + (i + 1) + ": empty");
			}
			if (i > 0 && Math.abs(c.etaTop() - cells.get(i - 1).etaBottom()) > 1e-9) {
				problems.add("grid " + gridId + ", cell " + (i + 1) + ": not adjacent to the cell above");
			}
			double centre = 0.5 * (c.etaTop() + c.etaBottom());
			for (int j = 0; j + 1 < layers.size(); j++) {
				if (centre <= real(layers.get(j)[0]) && centre > real(layers.get(j + 1)[0])
						&& integer(layers.get(j)[1]) != c.parameterId()) {
					problems.add("grid " + gridId + ", cell " + (i + 1) + ": parameter " + c.parameterId()
							+ ", the layer of its centre has " + layers.get(j)[1]);
				}
			}
		}
	}

	/** Bottom and top η of a soil profile [m], null if it does not exist. */
	private double[] profileBounds(int profile) {
		List<Object[]> r = query("SELECT MIN(eta), MAX(eta) FROM soil_layers WHERE profile_id = ?", profile);
		return r.get(0)[0] == null ? null : new double[] { real(r.get(0)[0]), real(r.get(0)[1]) };
	}

	/**
	 * Writes the soil inputs of a column as WHETGEO-1D's CSV files in a new
	 * temporary folder, for {@link SoilGridInputs#read}; the caller deletes it.
	 */
	public Path soilGridFolder(Column c) throws IOException {
		return soilGridFolder(c, 0, c.initialConditionId());
	}

	/**
	 * As {@link #soilGridFolder(Column)}, on the named grid {@code gridId} (0:
	 * the cells of {@code soil_layers}) and with the initial condition
	 * {@code icId}. A named grid goes to WHETGEO-1D's reader as one layer of one
	 * cell per cell.
	 */
	public Path soilGridFolder(Column c, int gridId, int icId) throws IOException {
		if (gridId != 0 && gridProfile(gridId) != c.profileId()) {
			throw new IllegalArgumentException(file + ": grid " + gridId + " is of profile " + gridProfile(gridId)
					+ ", column " + c.id() + " of profile " + c.profileId());
		}
		if (query("SELECT 1 FROM initial_condition WHERE ic_id = ?", icId).isEmpty()) {
			throw new IllegalArgumentException(file + ": no initial condition " + icId);
		}
		Path folder = Files.createTempDirectory("snowsoil-case-grid");
		if (gridId == 0) {
			write(folder.resolve("grid_geometry.csv"),
					query("SELECT * FROM soil_layers WHERE profile_id = ? ORDER BY fid", c.profileId()), 2);
		} else {
			List<Object[]> rows = new ArrayList<>();
			rows.add(new Object[] { "Type", "eta", "K", "equationStateID", "parameterID" });
			List<GridCell> cells = gridCells(gridId);
			for (GridCell cell : cells) {
				rows.add(new Object[] { "L", cell.etaTop(), 1, cell.equationStateId(), cell.parameterId() });
			}
			rows.add(new Object[] { "L", cells.get(cells.size() - 1).etaBottom(), null, null, null });
			write(folder.resolve("grid_geometry.csv"), rows, 0);
		}
		write(folder.resolve("swrc_parameters.csv"),
				query("SELECT * FROM soil_parameters WHERE profile_id = ? ORDER BY parameter_id", c.profileId()), 3);
		write(folder.resolve("swrc_parameter_types.csv"), query("SELECT * FROM soil_parameter_types ORDER BY fid"), 1);
		write(folder.resolve("initial_condition.csv"),
				query("SELECT * FROM initial_condition WHERE ic_id = ? ORDER BY fid", icId), 2);
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

	/** Checks a case: {@code java ... CaseGeoPackage case.gpkg}; exit status 1 when it has problems. */
	public static void main(String[] args) {
		List<String> problems = new CaseGeoPackage(Path.of(args[0])).check();
		problems.forEach(System.out::println);
		System.out.println(args[0] + ": " + (problems.isEmpty() ? "consistent" : problems.size() + " problems"));
		if (!problems.isEmpty()) {
			System.exit(1);
		}
	}

	private static double real(Object v) {
		return v == null ? Double.NaN : ((Number) v).doubleValue();
	}

	private static int integer(Object v) {
		return ((Number) v).intValue();
	}
}
