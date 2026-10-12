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

import org.geoframe.closureequation.equationstate.EquationState;
import org.geoframe.whetgeo1d.core.state.WGGeometry;

/**
 * Water volume of a soil cell holding ice, for Richards1DKernel: the liquid
 * θ_w(ψ; θ_i) of {@link IceAwareRetention} times the control volume, with the
 * ice of each cell held fixed during the solve (GEOtop: no phase change inside
 * the water balance).
 *
 * <p>
 * Nested-Newton decomposition. The ice-free curve is convex up to its
 * inflection ψ* and concave above; with ice it is cut at ψ_sat(θ_i), where its
 * slope drops to S_s. With x* = min(ψ*, ψ_sat) the convex part is p = f′ below
 * x*, and p = max(f′(x*⁻), f′(ψ)) above it, f′(x*⁻) the slope of the ice-free
 * curve at x*. p is non-decreasing and q = p - f′ is non-decreasing too: the
 * Jordan decomposition the solver needs, whatever the ice.
 */
final class IceReducedWaterVolume extends EquationState {

	private final IceAwareRetention retention;
	private final WGGeometry geometry;
	private final double[] ice;
	private final double[] xStar;

	/**
	 * @param retention liquid retention with ice
	 * @param geometry  control volumes
	 * @param ice       θ_i of each element, read at every call
	 */
	IceReducedWaterVolume(IceAwareRetention retention, WGGeometry geometry, double[] ice) {
		// the closure of the base class is not used: the retention is IceAwareRetention
		super(null);
		this.retention = retention;
		this.geometry = geometry;
		this.ice = ice;
		this.xStar = new double[ice.length];
	}

	@Override
	public double equationState(double x, double y, int id, int element) {
		return retention.liquid(x, ice[element], id) * geometry.controlVolume[element];
	}

	@Override
	public double dEquationState(double x, double y, int id, int element) {
		return retention.dLiquid(x, ice[element], id) * geometry.controlVolume[element];
	}

	@Override
	public double ddEquationState(double x, double y, int id, int element) {
		double h = 1e-6 * Math.max(1.0, Math.abs(x));
		return (dEquationState(x + h, y, id, element) - dEquationState(x - h, y, id, element)) / (2.0 * h);
	}

	@Override
	public double p(double x, double y, int id, int element) {
		double star = xStar[element];
		if (x < star) {
			return dEquationState(x, y, id, element);
		}
		return Math.max(leftSlope(star, id, element), dEquationState(x, y, id, element));
	}

	@Override
	public double pIntegral(double x, double y, int id, int element) {
		double star = xStar[element];
		if (x <= star) {
			return equationState(x, y, id, element);
		}
		double slope = leftSlope(star, id, element);
		double storage = retention.storage(id) * geometry.controlVolume[element];
		double saturation = retention.saturationSuction(ice[element], id);
		if (storage <= slope || x <= saturation) {
			return equationState(star, y, id, element) + slope * (x - star);
		}
		// storage steeper than the curve at x*: above saturation p follows the storage
		return equationState(star, y, id, element) + slope * (saturation - star) + storage * (x - saturation);
	}

	/**
	 * q = p - f′, zero up to x* included. At x* = ψ_sat the curve has a corner
	 * and f′ there is the slope of the storage on the right: computing q as
	 * p - f′ would give the jump of the slope at x* itself, where the nested
	 * Newton starts ({@link #initialGuess}), and the inner problem would not be
	 * monotone.
	 */
	@Override
	public double q(double x, double y, int id, int element) {
		if (x <= xStar[element]) {
			return 0.0;
		}
		return p(x, y, id, element) - dEquationState(x, y, id, element);
	}

	@Override
	public double qIntegral(double x, double y, int id, int element) {
		if (x <= xStar[element]) {
			return 0.0;
		}
		return pIntegral(x, y, id, element) - equationState(x, y, id, element);
	}

	/** Slope of the volume just below x*, where the curve may be cut. */
	private double leftSlope(double star, int id, int element) {
		return retention.dIceFree(star, id) * geometry.controlVolume[element];
	}

	@Override
	public void computeXStar(double y, int id, int element) {
		xStar[element] = Math.min(retention.inflection(id), retention.saturationSuction(ice[element], id));
	}

	@Override
	public double initialGuess(double x, int id, int element) {
		return Math.min(x, xStar[element]);
	}
}
