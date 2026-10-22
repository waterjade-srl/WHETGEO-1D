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

import java.util.Locale;
import java.util.Objects;

/**
 * One rejected attempt of a step (SOL-5, item 5): which solver failed, when,
 * where and by how much. The {@link StepController} logs one for every
 * rejection, whether a finer sub-step then succeeds or not.
 *
 * @param time      start of the attempted step [s], in the time of the
 *                  controller
 * @param timeDelta length of the attempted step [s]
 * @param halvings  how many times the original interval had been halved for
 *                  this attempt (0 = the whole interval)
 * @param reason    the failed criterion
 * @param cell      the cell named: active index (0 at the bottom, soil first)
 *                  for the heat solve; -1 when the criterion is not about a
 *                  cell
 * @param soilCells number of soil cells, to tell soil from snow in {@code cell}
 * @param value     the failed quantity: the worst residual ratio of the heat
 *                  solve, the last change of the surface temperature [K], the
 *                  Richards balance error [kg m-2]; NaN for an invalid state
 * @param detail    a description, the message of the exception for an invalid
 *                  state
 */
public record StepRejection(double time, double timeDelta, int halvings, Reason reason, int cell, int soilCells,
		double value, String detail) {

	/** The criteria of acceptance (see {@link StepController}). */
	public enum Reason {
		/** The heat solve failed the per-cell residual criterion after the polish (SOL-3 change 2, DN-07). */
		HEAT_NOT_CONVERGED("heat"),
		/** The surface sweeps ended without the surface temperature settling ({@link SurfaceCoupling}). */
		SURFACE_NOT_CONVERGED("surface"),
		/** The Richards balance error of the step exceeds the tolerance. */
		RICHARDS_NOT_CONVERGED("Richards"),
		/** A solver threw on a non-finite or inadmissible value, or the end state is not finite. */
		INVALID_STATE("state"),
		/** An extra criterion of the caller ({@link StepController.Check}). */
		EXTRA_CHECK("check");

		private final String solver;

		Reason(String solver) {
			this.solver = solver;
		}

		/** Short name of the solver or criterion, for messages. */
		public String solver() {
			return solver;
		}
	}

	public StepRejection {
		Objects.requireNonNull(reason, "reason");
		detail = detail == null ? "" : detail;
	}

	/** The cell, named as soil or snow cell, bottom first; empty when there is none. */
	public String cellName() {
		if (cell < 0) {
			return "";
		}
		return cell < soilCells ? "soil cell " + cell : "snow cell " + (cell - soilCells);
	}

	/** One line for a log: solver, time, step, cell, value, detail. */
	public String describe() {
		StringBuilder line = new StringBuilder();
		line.append(reason.solver()).append(" not accepted at t = ").append(String.format(Locale.ROOT, "%.1f", time))
				.append(" s, step ").append(String.format(Locale.ROOT, "%.3f", timeDelta)).append(" s (halvings ")
				.append(halvings).append(')');
		if (cell >= 0) {
			line.append(", ").append(cellName());
		}
		if (!Double.isNaN(value)) {
			line.append(", value ").append(String.format(Locale.ROOT, "%.6g", value));
		}
		if (!detail.isEmpty()) {
			line.append(": ").append(detail);
		}
		return line.toString();
	}
}
