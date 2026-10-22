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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

/**
 * Global transmissivity of a cloudless atmosphere, Iqbal (1983, §7.5), as
 * GEOtop 3.0 computes it ({@code atm_transmittance},
 * {@code radiation.cc:411-475}, commit 1f1fc5af): Rayleigh scattering, ozone,
 * mixed gases, water vapour and aerosols for the direct beam, the diffuse
 * from Rayleigh and aerosol scattering, and the multiple reflection between
 * ground and sky. Water vapour from the precipitable water
 * w = 0.493 RH e<sup>26.23 - 5416/T</sup>/T [cm].
 *
 * <p>
 * GEOtop's arguments are in mbar and °C; here pressure is in Pa and
 * temperature in K (ARCH-5), converted at the entry.
 *
 * @param ozone        thickness of the ozone layer [cm] ({@code Lozone},
 *                     GEOtop default 0.3)
 * @param angstromAlpha Ångström α of the aerosols (GEOtop {@code alpha_iqbal},
 *                     1.3)
 * @param angstromBeta Ångström β of the aerosols (GEOtop {@code beta_iqbal},
 *                     0.1)
 * @param groundAlbedo albedo of the ground for the multiple reflection; GEOtop
 *                     passes 0 for the cloud transmissivity
 *                     ({@code input.cc:419-424})
 */
public record ClearSkyTransmissivity(double ozone, double angstromAlpha, double angstromBeta, double groundAlbedo) {

	private static final double SEA_LEVEL_MBAR = 1013.25;
	/** Single-scattering albedo of the aerosols ({@code w0}). */
	private static final double W0 = 0.9;
	/** Forward-scattering fraction of the aerosols ({@code Fc}). */
	private static final double FC = 0.84;

	public ClearSkyTransmissivity {
		if (!(ozone >= 0.0) || !(angstromBeta >= 0.0) || !Double.isFinite(angstromAlpha)
				|| !(groundAlbedo >= 0.0 && groundAlbedo < 1.0)) {
			throw new IllegalArgumentException("invalid Iqbal parameters");
		}
	}

	/** GEOtop's defaults ({@code parameters.cc:2229-2231}) with the ground albedo of the cloud transmissivity, 0. */
	public static ClearSkyTransmissivity geotop() {
		return new ClearSkyTransmissivity(0.3, 1.3, 0.1, 0.0);
	}

	/**
	 * @param sunHeight        angle of the sun above the horizon [rad]
	 * @param pressure         [Pa]
	 * @param relativeHumidity [0, 1]
	 * @param airTemperature   [K]
	 * @return τ<sub>atm</sub> [-]
	 */
	public double transmissivity(double sunHeight, double pressure, double relativeHumidity, double airTemperature) {
		double p = pressure / 100.0;
		double t = airTemperature - MELTING_TEMPERATURE;
		double mr = 1.0 / (Math.sin(sunHeight) + 0.15 * Math.pow(3.885 + Math.toDegrees(sunHeight), -1.253));
		double ma = mr * p / SEA_LEVEL_MBAR;
		double w = 0.493 * relativeHumidity * Math.exp(26.23 - 5416.0 / (t + MELTING_TEMPERATURE))
				/ (t + MELTING_TEMPERATURE);
		double u1 = w * mr;
		double u3 = ozone * mr;
		double a = angstromAlpha;
		double b = angstromBeta;

		double tauR = Math.exp(-.0903 * Math.pow(ma, .84) * (1. + ma - Math.pow(ma, 1.01)));
		double tauO = 1. - (.1611 * u3 * Math.pow(1. + 139.48 * u3, -.3035) - .002715 * u3 / (1. + .044 * u3 + .0003 * u3 * u3));
		double tauG = Math.exp(-.0127 * Math.pow(ma, .26));
		double tauW = 1. - 2.4959 * u1 / (Math.pow(1. + 79.034 * u1, .6828) + 6.385 * u1);
		double tauA = .12445 * a - 0.0162 + (1.003 - .125 * a) * Math.exp(-b * ma * (1.089 * a + .5123));
		double tauAA = 1. - (1. - W0) * (1. - ma + Math.pow(ma, 1.06)) * (1. - tauA);
		double rhoA = .0685 + (1. - FC) * (1. - tauA / tauAA);

		double direct = .9751 * tauR * tauO * tauG * tauW * tauA;
		double rayleigh = .79 * tauO * tauG * tauW * tauAA * .5 * (1. - tauR) / (1. - ma + Math.pow(ma, 1.02));
		double aerosol = .79 * tauO * tauG * tauW * tauAA * FC * (1. - tauA / tauAA) / (1. - ma + Math.pow(ma, 1.02));
		double multiple = (direct + rayleigh + aerosol) * groundAlbedo * rhoA / (1. - groundAlbedo * rhoA);
		return direct + rayleigh + aerosol + multiple;
	}
}
