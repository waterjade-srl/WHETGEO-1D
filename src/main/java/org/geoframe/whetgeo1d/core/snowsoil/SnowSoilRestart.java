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
package org.geoframe.whetgeo1d.core.snowsoil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.geoframe.whetgeo1d.snow.SnowCell;

/**
 * Restart state of a snow+soil column (ARCH-5): the state of the solver, the
 * time it belongs to and the diagnostics of the run so far, so that a run
 * resumed from it is the continuous run to the bit
 * ({@link StepController#resume}). The physics does no I/O (ARCH-6): this class
 * encodes to and decodes from streams the caller opens; the component chooses
 * the files, as GEOtop's recovery files ({@code RecoverSim},
 * {@code ContinuousRecovery}).
 *
 * <p>
 * <b>Format</b>, version {@value #FORMAT_VERSION}, big-endian
 * ({@link DataOutputStream}); every double as its raw IEEE 754 bits
 * ({@link Double#doubleToRawLongBits}), so that NaN payloads survive too:
 *
 * <pre>
 * int    magic 0x57475353 ("WGSS")
 * int    format version
 * double time [s]
 * UTF    configuration: a free label of the caller (e.g. a hash of the configuration),
 *        checked on resume
 * int    snow cells m; m × (ice mass, liquid mass, thickness, internal energy)
 * double surface age, pending surface energy
 * array  soil temperatures, soil heat suctions, Richards suctions (length -1: none), ψ_w0
 * long   intervals, sub-steps, rejections, degraded steps
 * int    number of balance terms k; k doubles of the total, k of the maxima
 * </pre>
 *
 * An array is its length (int) and its doubles. A new field (e.g. the liquid
 * held out of equilibrium of STA-3, or the per-cell fields of SCOPE-3) means a
 * new version; {@link #read} rejects versions it does not know.
 *
 * @param time          time of the state [s], in the time of the controller
 * @param configuration free label of the caller, checked on resume; empty for
 *                      none
 * @param state         the state of the solver
 * @param diagnostics   the diagnostics of the run up to {@code time}
 */
public record SnowSoilRestart(double time, String configuration, SnowSoilState state, RunDiagnostics diagnostics) {

	/** Version of the format written by {@link #write}. */
	public static final int FORMAT_VERSION = 1;
	private static final int MAGIC = 0x57475353;

	public SnowSoilRestart {
		configuration = configuration == null ? "" : configuration;
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(diagnostics, "diagnostics");
	}

	/** Writes the restart state; the stream is flushed, not closed. */
	public void write(OutputStream stream) throws IOException {
		DataOutputStream out = new DataOutputStream(stream);
		out.writeInt(MAGIC);
		out.writeInt(FORMAT_VERSION);
		writeDouble(out, time);
		out.writeUTF(configuration);
		List<SnowCell> snow = state.snow();
		out.writeInt(snow.size());
		for (SnowCell cell : snow) {
			writeDouble(out, cell.iceMass());
			writeDouble(out, cell.liquidMass());
			writeDouble(out, cell.thickness());
			writeDouble(out, cell.internalEnergy());
		}
		writeDouble(out, state.surfaceAge());
		writeDouble(out, state.pendingSurfaceEnergy());
		writeArray(out, state.soilTemperatures());
		writeArray(out, state.soilHeatSuctions());
		writeArray(out, state.soilWaterSuctions());
		writeArray(out, state.totalSuctions());
		out.writeLong(diagnostics.intervals());
		out.writeLong(diagnostics.substeps());
		out.writeLong(diagnostics.rejections());
		out.writeLong(diagnostics.degradedSteps());
		double[] total = diagnostics.total().toArray();
		double[] max = diagnostics.maxAbs().toArray();
		out.writeInt(total.length);
		for (double v : total) {
			writeDouble(out, v);
		}
		for (double v : max) {
			writeDouble(out, v);
		}
		out.flush();
	}

	/**
	 * Reads a restart state written by {@link #write}.
	 *
	 * @throws IOException if the stream is not a restart state, or has a
	 *                     version this code does not read
	 */
	public static SnowSoilRestart read(InputStream stream) throws IOException {
		DataInputStream in = new DataInputStream(stream);
		if (in.readInt() != MAGIC) {
			throw new IOException("not a snow+soil restart state");
		}
		int version = in.readInt();
		if (version != FORMAT_VERSION) {
			throw new IOException("restart format version " + version + ", this code reads " + FORMAT_VERSION);
		}
		double time = readDouble(in);
		String configuration = in.readUTF();
		int cells = in.readInt();
		if (cells < 0) {
			throw new IOException("negative number of snow cells: " + cells);
		}
		List<SnowCell> snow = new ArrayList<>(cells);
		for (int j = 0; j < cells; j++) {
			snow.add(new SnowCell(readDouble(in), readDouble(in), readDouble(in), readDouble(in)));
		}
		double age = readDouble(in);
		double pending = readDouble(in);
		double[] temperatures = readArray(in);
		double[] heatSuctions = readArray(in);
		double[] waterSuctions = readArray(in);
		double[] totalSuctions = readArray(in);
		if (temperatures == null || heatSuctions == null || totalSuctions == null) {
			throw new IOException("missing soil arrays");
		}
		long intervals = in.readLong();
		long substeps = in.readLong();
		long rejections = in.readLong();
		long degraded = in.readLong();
		int terms = in.readInt();
		if (terms != ColumnBalance.NAMES.size()) {
			throw new IOException("restart has " + terms + " balance terms, expected " + ColumnBalance.NAMES.size());
		}
		double[] total = new double[terms];
		double[] max = new double[terms];
		for (int i = 0; i < terms; i++) {
			total[i] = readDouble(in);
		}
		for (int i = 0; i < terms; i++) {
			max[i] = readDouble(in);
		}
		SnowSoilState state = new SnowSoilState(snow, age, pending, temperatures, heatSuctions, waterSuctions,
				totalSuctions);
		return new SnowSoilRestart(time, configuration, state, new RunDiagnostics(intervals, substeps, rejections,
				degraded, ColumnBalance.fromArray(total), ColumnBalance.fromArray(max)));
	}

	/** The encoded state, in memory. */
	public byte[] toBytes() {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			write(bytes);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return bytes.toByteArray();
	}

	/** Decodes {@link #toBytes()}. */
	public static SnowSoilRestart fromBytes(byte[] bytes) throws IOException {
		return read(new ByteArrayInputStream(bytes));
	}

	private static void writeDouble(DataOutputStream out, double value) throws IOException {
		out.writeLong(Double.doubleToRawLongBits(value));
	}

	private static double readDouble(DataInputStream in) throws IOException {
		return Double.longBitsToDouble(in.readLong());
	}

	private static void writeArray(DataOutputStream out, double[] values) throws IOException {
		if (values == null) {
			out.writeInt(-1);
			return;
		}
		out.writeInt(values.length);
		for (double v : values) {
			writeDouble(out, v);
		}
	}

	private static double[] readArray(DataInputStream in) throws IOException {
		int n = in.readInt();
		if (n < -1) {
			throw new IOException("invalid array length " + n);
		}
		if (n == -1) {
			return null;
		}
		double[] values = new double[n];
		for (int i = 0; i < n; i++) {
			values[i] = readDouble(in);
		}
		return values;
	}
}
