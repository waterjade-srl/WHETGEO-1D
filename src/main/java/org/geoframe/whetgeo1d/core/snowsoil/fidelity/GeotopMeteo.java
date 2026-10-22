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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>Temporary: to be evaluated for the GEOframe components.</b> A GEOtop
 * meteorological file ({@code meteoNNNN.txt}) as GEOtop 3.0 reads and steps
 * it.
 *
 * <ul>
 * <li><b>Time.</b> GEOtop dates its records with the column named by
 * {@code HeaderJulianDayfrom0Meteo} when the file has it, otherwise with the
 * date column. Times are GEOtop's JDfrom0, days from year 0 in local standard
 * time, with 01/01/1970 00:00 = 719529 ({@code times.cc}). When the two
 * columns of a file disagree GEOtop follows the JDfrom0 column; in
 * Matsch_P2_Ref_007 that column is one day behind the dates, so GEOtop's
 * forcing at a date is the record dated one day later.</li>
 * <li><b>Stepping.</b> Without {@code LinearInterpolation} (the default)
 * GEOtop averages over each step [t<sub>b</sub>, t<sub>e</sub>] the piecewise
 * constant series in which record i holds over (t<sub>i-1</sub>,
 * t<sub>i</sub>] ({@code time_interp_constant},
 * {@code meteodata.cc:136-236}); a step inside one interval takes the value of
 * the record that closes it.</li>
 * <li><b>No value.</b> -9999 (or a missing column) gives NaN for the
 * steps it covers.</li>
 * </ul>
 *
 * @param julianDays JDfrom0 of each record, as GEOtop reads it
 * @param columns    the columns by header, in file order
 */
public record GeotopMeteo(double[] julianDays, Map<String, double[]> columns) {

	/** JDfrom0 of 01/01/1970 00:00. */
	public static final double EPOCH_JULIAN_DAY = 719529.0;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
	private static final double NO_VALUE = -9999.0;

	/**
	 * Reads the file.
	 *
	 * @param dateHeader      header of the date column ({@code HeaderDateDDMMYYYYhhmmMeteo})
	 * @param julianDayHeader header of the JDfrom0 column
	 *                        ({@code HeaderJulianDayfrom0Meteo}); null or
	 *                        absent from the file: the dates are used
	 */
	public static GeotopMeteo read(Path file, String dateHeader, String julianDayHeader) throws IOException {
		List<String> lines = Files.readAllLines(file);
		String[] header = Arrays.stream(lines.get(0).split(",")).map(String::trim).toArray(String[]::new);
		int dateColumn = Arrays.asList(header).indexOf(dateHeader);
		int jdColumn = julianDayHeader == null ? -1 : Arrays.asList(header).indexOf(julianDayHeader);
		if (dateColumn < 0 && jdColumn < 0) {
			throw new IOException("no date column " + dateHeader + " in " + file);
		}
		List<String[]> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			if (!line.isBlank()) {
				rows.add(line.split(","));
			}
		}
		double[] jd = new double[rows.size()];
		Map<String, double[]> columns = new LinkedHashMap<>();
		for (int c = 0; c < header.length; c++) {
			if (c != dateColumn) {
				columns.put(header[c], new double[rows.size()]);
			}
		}
		for (int k = 0; k < rows.size(); k++) {
			String[] r = rows.get(k);
			jd[k] = jdColumn >= 0 ? Double.parseDouble(r[jdColumn].trim())
					: julianDay(LocalDateTime.parse(r[dateColumn].trim(), DATE));
			for (int c = 0; c < header.length; c++) {
				if (c != dateColumn) {
					double v = c < r.length && !r[c].isBlank() ? Double.parseDouble(r[c].trim()) : NO_VALUE;
					columns.get(header[c])[k] = v == NO_VALUE ? Double.NaN : v;
				}
			}
		}
		return new GeotopMeteo(jd, columns);
	}

	/** GEOtop's JDfrom0 of a local standard time. */
	public static double julianDay(LocalDateTime localStandardTime) {
		return localStandardTime.toLocalDate().toEpochDay() + EPOCH_JULIAN_DAY
				+ localStandardTime.toLocalTime().toSecondOfDay() / 86400.0;
	}

	/**
	 * JDfrom0 of an instant.
	 *
	 * @param epochSeconds seconds since 1970-01-01 UTC
	 * @param standardTime offset of the local standard time from UTC [h]
	 */
	public static double julianDay(long epochSeconds, double standardTime) {
		return EPOCH_JULIAN_DAY + (epochSeconds + standardTime * 3600.0) / 86400.0;
	}

	/** The column of that header, NaN where -9999; null if absent. */
	public double[] column(String header) {
		return columns.get(header);
	}

	/**
	 * Mean of a column over the step [begin, end] (JDfrom0), GEOtop's
	 * {@code time_interp_constant}: record i holds over (t<sub>i-1</sub>,
	 * t<sub>i</sub>]. NaN if the step is outside the records, the column is
	 * absent or a record it covers has no value.
	 */
	public double stepMean(String header, double begin, double end) {
		double[] v = columns.get(header);
		if (v == null || !(end > begin) || begin < julianDays[0] || end > julianDays[julianDays.length - 1]) {
			return Double.NaN;
		}
		double sum = 0.0;
		for (int i = 1; i < julianDays.length && julianDays[i - 1] < end; i++) {
			double a = Math.max(julianDays[i - 1], begin);
			double b = Math.min(julianDays[i], end);
			if (b > a) {
				if (Double.isNaN(v[i])) {
					return Double.NaN;
				}
				sum += v[i] * (b - a);
			}
		}
		return sum / (end - begin);
	}
}
