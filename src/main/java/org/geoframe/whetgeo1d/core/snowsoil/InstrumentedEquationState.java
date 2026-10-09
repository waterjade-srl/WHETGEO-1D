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

import java.util.ArrayList;
import java.util.List;

import org.geoframe.closureequation.equationstate.EquationState;

/**
 * Transparent wrapper of an {@link EquationState}, to get from
 * NestedNewtonThomas (numerical 1.0.4) what it does not expose, without
 * changing the library.
 *
 * <ul>
 * <li><b>Iteration counts.</b> In {@code NestedNewtonThomas.solver()} the outer
 * loop evaluates {@code equationState(…, element = 0)} once per iteration
 * (line 137) and the inner loop {@code qIntegral(…, element = 0)} once per
 * iteration (line 189); nothing else calls them on this list. Counting those
 * two calls gives the outer residual evaluations and the inner iterations.</li>
 * <li><b>Warm start.</b> The solver always starts from
 * {@code initialGuess} (line 120), which for snow is min(T, x*): a cell past
 * the inflection is sent back to x*. When {@link Instrumentation#warmStart} is
 * set, the wrapper returns the current temperature instead, so a few extra
 * iterations can polish a converged solution.</li>
 * </ul>
 *
 * Every other method delegates, {@code q} and {@code qIntegral} included, so
 * without warm start the numbers are bitwise those of the wrapped closure.
 */
final class InstrumentedEquationState extends EquationState {

	/** State shared by all the wrappers of one column. */
	static final class Instrumentation {
		private int outer;
		private int inner;
		private boolean warmStart;

		void reset(boolean warm) {
			outer = 0;
			inner = 0;
			warmStart = warm;
		}

		void endWarmStart() {
			warmStart = false;
		}

		/** Outer residual evaluations, the final converged check included. */
		int outer() {
			return outer;
		}

		/** Inner (linearised) iterations in total. */
		int inner() {
			return inner;
		}
	}

	private final EquationState delegate;
	private final Instrumentation instrumentation;

	private InstrumentedEquationState(EquationState delegate, Instrumentation instrumentation) {
		super(null);
		this.delegate = delegate;
		this.instrumentation = instrumentation;
	}

	/** Wraps every closure of the list with the same instrumentation. */
	static List<EquationState> wrap(List<EquationState> closures, Instrumentation instrumentation) {
		List<EquationState> wrapped = new ArrayList<>(closures.size());
		for (EquationState closure : closures) {
			wrapped.add(new InstrumentedEquationState(closure, instrumentation));
		}
		return wrapped;
	}

	@Override
	public double equationState(double x, double y, int id, int element) {
		if (element == 0) {
			instrumentation.outer++;
		}
		return delegate.equationState(x, y, id, element);
	}

	@Override
	public double qIntegral(double x, double y, int id, int element) {
		if (element == 0) {
			instrumentation.inner++;
		}
		return delegate.qIntegral(x, y, id, element);
	}

	@Override
	public double initialGuess(double x, int id, int element) {
		return instrumentation.warmStart ? x : delegate.initialGuess(x, id, element);
	}

	@Override
	public double q(double x, double y, int id, int element) {
		return delegate.q(x, y, id, element);
	}

	@Override
	public double dEquationState(double x, double y, int id, int element) {
		return delegate.dEquationState(x, y, id, element);
	}

	@Override
	public double ddEquationState(double x, double y, int id, int element) {
		return delegate.ddEquationState(x, y, id, element);
	}

	@Override
	public double p(double x, double y, int id, int element) {
		return delegate.p(x, y, id, element);
	}

	@Override
	public double pIntegral(double x, double y, int id, int element) {
		return delegate.pIntegral(x, y, id, element);
	}

	@Override
	public void computeXStar(double y, int id, int element) {
		delegate.computeXStar(y, id, element);
	}
}
