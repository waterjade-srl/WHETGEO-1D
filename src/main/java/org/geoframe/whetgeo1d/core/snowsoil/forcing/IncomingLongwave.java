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

import org.geoframe.whetgeo1d.snow.surface.AirProperties;
import org.geoframe.whetgeo1d.snow.surface.SaturationCurve;

/**
 * Incoming longwave where it is not measured, as GEOtop 3.0 (commit 1f1fc5af)
 * estimates it (LINK-1):
 *
 * <pre>
 * L_in = V ε_a σ T_a⁴,    ε_a = ε_clear τ_c + 1 - τ_c
 * </pre>
 *
 * with τ<sub>c</sub> the averaged cloud transmissivity ({@link DailyCloudiness})
 * ({@code longwave_radiation}, {@code radiation.cc:477-552}; called in
 * {@code energy.balance.cc:601-603} with {@code tau_cloud_av}; σ = 5.67·10⁻⁸,
 * {@code SB}, {@code radiation.cc:554-562}). The vapour pressure is
 * RH e<sub>sat</sub>(T<sub>a</sub>, P) with GEOtop's saturation over water
 * ({@code energy.balance.cc:384}; {@link SaturationCurve#WATER}).
 *
 * <p>
 * The clear-sky emissivities are GEOtop's {@code LWinParameterization}
 * formulas with the vapour pressure in mbar and T in K, as GEOtop writes them;
 * Dilley and O'Brien (1998), GEOtop's default, is the default here. Konzelmann
 * (1994) has two calibrated coefficients; it is here with GEOtop's defaults,
 * k<sub>1</sub> = 0.484 and k<sub>2</sub> = 8 ({@code parameters.cc:2227-2228}),
 * the option of the Friuli case.
 */
public final class IncomingLongwave {

	private IncomingLongwave() {
	}

	/** Clear-sky emissivity ε<sub>clear</sub>(e, T). */
	public enum ClearSkyEmissivity {
		/** Brutsaert (1975), GEOtop 1. */
		BRUTSAERT_1975,
		/** Satterlund (1979), GEOtop 2. */
		SATTERLUND_1979,
		/** Idso (1981), GEOtop 3. */
		IDSO_1981,
		/** Idso and Hodges, GEOtop 4. */
		IDSO_HODGES,
		/** Koenig-Langlo and Augstein (1994), constant 0.765, GEOtop 5. */
		KOENIG_LANGLO_AUGSTEIN_1994,
		/** Andreas and Ackley (1982), GEOtop 6. */
		ANDREAS_ACKLEY_1982,
		/**
		 * Konzelmann et al. (1994), GEOtop 7, 0.23 + k<sub>1</sub> (e/T)<sup>1/k<sub>2</sub></sup>
		 * with e in Pa and GEOtop's default k<sub>1</sub> = 0.484, k<sub>2</sub> = 8
		 * ({@code radiation.cc:514-517}).
		 */
		KONZELMANN_1994,
		/** Prata (1996), GEOtop 8. */
		PRATA_1996,
		/** Dilley and O'Brien (1998), GEOtop 9, GEOtop's default. */
		DILLEY_OBRIEN_1998;

		/**
		 * @param vapourPressure e [Pa]
		 * @param airTemperature T<sub>a</sub> [K]
		 */
		public double emissivity(double vapourPressure, double airTemperature) {
			double e = vapourPressure / 100.0;
			double t = airTemperature;
			return switch (this) {
			case BRUTSAERT_1975 -> 1.24 * Math.pow(e / t, 1.0 / 7.0);
			case SATTERLUND_1979 -> 1.08 * (1.0 - Math.exp(-Math.pow(e, t / 2016.0)));
			case IDSO_1981 -> 0.7 + 5.95e-5 * e * Math.exp(1500.0 / t);
			case IDSO_HODGES -> {
				double idso = 0.7 + 5.95e-5 * e * Math.exp(1500.0 / t);
				yield -0.792 + 3.161 * idso - 1.573 * idso * idso;
			}
			case KOENIG_LANGLO_AUGSTEIN_1994 -> 0.765;
			case ANDREAS_ACKLEY_1982 -> 0.601 + 5.95e-5 * e * Math.exp(1500.0 / t);
			case KONZELMANN_1994 -> 0.23 + 0.484 * Math.pow(e * 100.0 / t, 1.0 / 8.0);
			case PRATA_1996 -> 1.0 - (1.0 + 46.5 * e / t) * Math.exp(-Math.sqrt(1.2 + 3.0 * 46.5 * e / t));
			case DILLEY_OBRIEN_1998 -> (59.38 + 113.7 * Math.pow(t / 273.16, 6.0)
					+ 96.96 * Math.sqrt((465.0 * e / t) / 25.0)) / (5.67e-8 * Math.pow(t, 4));
			};
		}
	}

	/** ε<sub>a</sub> = ε<sub>clear</sub> τ<sub>c</sub> + 1 - τ<sub>c</sub>. */
	public static double atmosphereEmissivity(double clearSkyEmissivity, double cloudTransmissivity) {
		if (!(cloudTransmissivity >= 0.0 && cloudTransmissivity <= 1.0)) {
			throw new IllegalArgumentException("cloud transmissivity must be in [0, 1], got " + cloudTransmissivity);
		}
		return clearSkyEmissivity * cloudTransmissivity + 1.0 - cloudTransmissivity;
	}

	/**
	 * L<sub>in</sub> [W m-2].
	 *
	 * @param model               clear-sky emissivity
	 * @param airTemperature      [K]
	 * @param relativeHumidity    [0, 1]
	 * @param pressure            [Pa]
	 * @param cloudTransmissivity averaged τ<sub>c</sub> in [0, 1]
	 * @param skyViewFactor       V, 1 on flat ground
	 */
	public static double of(ClearSkyEmissivity model, double airTemperature, double relativeHumidity, double pressure,
			double cloudTransmissivity, double skyViewFactor) {
		double e = relativeHumidity * SaturationCurve.WATER.pressure(airTemperature, pressure);
		double epsilon = atmosphereEmissivity(model.emissivity(e, airTemperature), cloudTransmissivity);
		return skyViewFactor * epsilon * AirProperties.STEFAN_BOLTZMANN * Math.pow(airTemperature, 4);
	}
}
