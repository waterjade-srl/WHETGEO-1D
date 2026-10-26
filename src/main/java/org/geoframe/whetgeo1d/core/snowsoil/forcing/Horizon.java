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
package org.geoframe.whetgeo1d.core.snowsoil.forcing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A GEOtop
 * horizon file (azimuth from north clockwise and horizon height, degrees) and
 * GEOtop's shadow test on it ({@code shadows_point},
 * {@code radiation.cc:757-826}): the horizon height at the solar azimuth is
 * interpolated linearly between the two bracketing lines, across north when
 * needed; the point is in shadow when the sun is below
 * max(tol<sub>flat</sub>, horizon + tol<sub>mount</sub>).
 *
 * @param azimuth [deg], increasing
 * @param height  [deg]
 */
public record Horizon(double[] azimuth, double[] height) {

	/** GEOtop's {@code Tol_h_mount} [deg], used by the cloudiness of the station. */
	public static final double MOUNTAIN_TOLERANCE = 2.0;
	/** GEOtop's {@code Tol_h_flat} [deg], used by the cloudiness of the station. */
	public static final double FLAT_TOLERANCE = 12.0;

	public Horizon {
		if (azimuth.length != height.length || azimuth.length == 0) {
			throw new IllegalArgumentException("azimuth and height must have the same, positive length");
		}
	}

	/** GEOtop's horizon of a point with no horizon file: one line, height 0 ({@code input.cc:3656-3660}). */
	public static Horizon flat() {
		return new Horizon(new double[] { 0.0 }, new double[] { 0.0 });
	}

	/** Reads a horizon file: '!' comments, a header, then azimuth,height lines. */
	public static Horizon read(Path file) throws IOException {
		List<double[]> rows = new ArrayList<>();
		boolean header = false;
		for (String line : Files.readAllLines(file)) {
			String l = line.trim();
			if (l.isEmpty() || l.startsWith("!")) {
				continue;
			}
			if (!header) {
				header = true;
				continue;
			}
			String[] p = l.split(",");
			rows.add(new double[] { Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()) });
		}
		double[] a = new double[rows.size()];
		double[] h = new double[rows.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = rows.get(i)[0];
			h[i] = rows.get(i)[1];
		}
		return new Horizon(a, h);
	}

	/**
	 * Reads the horizon of one station from a GEOframe OMS table with a column
	 * {@code azimuth} and one column {@code value_<id>} per station, as
	 * NivoGEO-1D's {@code HorizonReader}: lines starting with a comma are data,
	 * the {@code @H} line names the columns, the rest is header.
	 *
	 * @param file the table
	 * @param id   the station
	 */
	public static Horizon readTable(Path file, int id) throws IOException {
		List<String> names = null;
		List<double[]> rows = new ArrayList<>();
		for (String line : Files.readAllLines(file)) {
			String[] p = line.split(",", -1);
			if (p[0].trim().equals("@H")) {
				names = new ArrayList<>();
				for (int i = 1; i < p.length; i++) {
					names.add(p[i].trim());
				}
			} else if (line.startsWith(",") && names != null) {
				int a = names.indexOf("azimuth");
				int h = names.indexOf("value_" + id);
				if (a < 0 || h < 0) {
					throw new IOException(file + ": no columns azimuth and value_" + id);
				}
				rows.add(new double[] { Double.parseDouble(p[a + 1].trim()), Double.parseDouble(p[h + 1].trim()) });
			}
		}
		if (rows.isEmpty()) {
			throw new IOException(file + ": no horizon lines");
		}
		rows.sort((x, y) -> Double.compare(x[0], y[0]));
		double[] a = new double[rows.size()];
		double[] h = new double[rows.size()];
		for (int i = 0; i < a.length; i++) {
			a[i] = rows.get(i)[0];
			h[i] = rows.get(i)[1];
		}
		return new Horizon(a, h);
	}

	/** Horizon height at a solar azimuth [deg]. */
	public double heightAt(double solarAzimuth) {
		int n = azimuth.length;
		int ibeg = 0;
		int iend = 0;
		if (solarAzimuth >= azimuth[n - 1] || solarAzimuth < azimuth[0]) {
			iend = 0;
			ibeg = n - 1;
		} else {
			for (int i = 1; i <= n - 1; i++) {
				if (solarAzimuth >= azimuth[i - 1] && solarAzimuth < azimuth[i]) {
					iend = i;
					ibeg = i - 1;
				}
			}
		}
		double w;
		if (iend > ibeg) {
			w = (solarAzimuth - azimuth[ibeg]) / (azimuth[iend] - azimuth[ibeg]);
		} else if (solarAzimuth > azimuth[ibeg]) {
			w = (solarAzimuth - azimuth[ibeg]) / (azimuth[iend] + 360.0 - azimuth[ibeg]);
		} else {
			w = (solarAzimuth - (azimuth[ibeg] - 360.0)) / (azimuth[iend] - (azimuth[ibeg] - 360.0));
		}
		return w * height[iend] + (1.0 - w) * height[ibeg];
	}

	/**
	 * {@code shadows_point}.
	 *
	 * @param solarHeight   [deg]
	 * @param solarAzimuth  [deg]
	 * @param mountainTolerance [deg]
	 * @param flatTolerance [deg]
	 */
	public boolean inShadow(double solarHeight, double solarAzimuth, double mountainTolerance, double flatTolerance) {
		return solarHeight < flatTolerance || solarHeight < heightAt(solarAzimuth) + mountainTolerance;
	}
}
