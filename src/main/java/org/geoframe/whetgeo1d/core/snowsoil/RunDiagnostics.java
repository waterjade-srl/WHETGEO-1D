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

import java.util.Objects;

/**
 * Diagnostics of a run so far (VAL-7): the whole-column balance summed over
 * the accepted steps, the largest magnitude of every term in one accepted
 * step, and the counts of SOL-5. Immutable; the {@link StepController} keeps
 * the current one and writes it into the restart state, so a resumed run
 * carries on the same sums to the bit.
 *
 * @param intervals     intervals advanced (calls of
 *                      {@link StepController#advance})
 * @param substeps      steps accepted by the solver, sub-steps included: equal
 *                      to {@code intervals} when nothing was rejected
 * @param rejections    attempts rejected and rolled back
 * @param degradedSteps sub-steps accepted although they failed at the finest
 *                      level ({@link StepControlSettings.FailurePolicy#FLAG})
 * @param total         sum over the accepted steps
 * @param maxAbs        term by term, the largest magnitude in one accepted step
 */
public record RunDiagnostics(long intervals, long substeps, long rejections, long degradedSteps, ColumnBalance total,
		ColumnBalance maxAbs) {

	/** Nothing run yet. */
	public static final RunDiagnostics EMPTY = new RunDiagnostics(0, 0, 0, 0, ColumnBalance.ZERO, ColumnBalance.ZERO);

	public RunDiagnostics {
		Objects.requireNonNull(total, "total");
		Objects.requireNonNull(maxAbs, "maxAbs");
	}

	/** With one more accepted (sub-)step. */
	RunDiagnostics withStep(ColumnBalance balance, boolean degraded) {
		return new RunDiagnostics(intervals, substeps + 1, rejections, degradedSteps + (degraded ? 1 : 0),
				total.plus(balance), maxAbs.maxAbs(balance));
	}

	/** With one more rejected attempt. */
	RunDiagnostics withRejection() {
		return new RunDiagnostics(intervals, substeps, rejections + 1, degradedSteps, total, maxAbs);
	}

	/** With one more interval completed. */
	RunDiagnostics withInterval() {
		return new RunDiagnostics(intervals + 1, substeps, rejections, degradedSteps, total, maxAbs);
	}

	/** Whether two diagnostics are the same to the bit. */
	public boolean bitwiseEquals(RunDiagnostics other) {
		if (other == null || intervals != other.intervals || substeps != other.substeps
				|| rejections != other.rejections || degradedSteps != other.degradedSteps) {
			return false;
		}
		return sameBits(total.toArray(), other.total.toArray()) && sameBits(maxAbs.toArray(), other.maxAbs.toArray());
	}

	private static boolean sameBits(double[] a, double[] b) {
		for (int i = 0; i < a.length; i++) {
			if (Double.doubleToRawLongBits(a[i]) != Double.doubleToRawLongBits(b[i])) {
				return false;
			}
		}
		return true;
	}
}
