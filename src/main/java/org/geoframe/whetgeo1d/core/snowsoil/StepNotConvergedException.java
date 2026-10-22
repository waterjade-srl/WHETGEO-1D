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

import java.util.List;

/**
 * A step that fails even at the finest sub-step (SOL-5, item 4): the run stops
 * and names the solver, the time, the cell and the residual. The solver is
 * left in the state at the start of the interval, the last accepted state, so
 * that a restart can still be written from it.
 */
public final class StepNotConvergedException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final transient StepRejection failure;
	private final transient List<StepRejection> rejections;

	StepNotConvergedException(StepRejection failure, List<StepRejection> rejections, Throwable cause) {
		super("snow+soil step not converged after " + failure.halvings() + " halvings: " + failure.describe(), cause);
		this.failure = failure;
		this.rejections = List.copyOf(rejections);
	}

	/** The rejection at the finest sub-step, the one that stopped the run. */
	public StepRejection failure() {
		return failure;
	}

	/** Every rejection of the interval, in order. */
	public List<StepRejection> rejections() {
		return rejections;
	}
}
