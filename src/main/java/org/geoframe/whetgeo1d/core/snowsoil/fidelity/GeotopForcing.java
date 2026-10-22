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

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.geoframe.whetgeo1d.core.snowsoil.forcing.ClearSkyTransmissivity;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.CloudTransmissivity;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.DailyCloudiness;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.IncomingLongwave;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.SolarGeometry;

/**
 * <b>Temporary: to be evaluated for the GEOframe components.</b> The forcing
 * of one step of a GEOtop 3.0 point simulation with one station at the
 * point, from GEOtop's meteorological file, as GEOtop computes it:
 *
 * <ul>
 * <li>T<sub>a</sub>, RH, wind, precipitation intensity and global shortwave:
 * step means of the file ({@link GeotopMeteo#stepMean}); RH and wind floored
 * at GEOtop's {@code RHmin} = 10 % and {@code Vmin} = 0.5 m s-1;</li>
 * <li>pressure: P<sub>0</sub> e<sup>-z/8500</sup> at the station
 * elevation ({@code pressure}, {@code meteo.cc});</li>
 * <li>rain and snow: {@link GeotopPrecipitation} on the intensity times
 * cos β, the vertical flux per unit area of the slope, which is the area of
 * a column normal to it ({@code energy.balance.cc:299-301}). GEOtop projects
 * a second time in a point simulation unless {@code ActualOrProjectedArea = 1}
 * ({@code energy.balance.cc:302-304}); that second cosine is an error, not
 * reproduced here (the corrected reference sets the keyword);</li>
 * <li>cloud transmissivity of the step from the global shortwave
 * ({@code find_tau_cloud_station}; {@link CloudTransmissivity#fromGlobal},
 * flat station, V = 1), and, where undefined (night), the averaged one;</li>
 * <li>averaged cloud transmissivity of the longwave: GEOtop's daily series
 * on the records of the file ({@code cloudiness}, {@code clouds.cc}): each
 * record's τ<sub>c</sub> from the measured shortwave over the interval
 * centred on it, the daylight records those where the sun clears the station
 * horizon by {@value Horizon#MOUNTAIN_TOLERANCE}° and stands
 * {@value Horizon#FLAT_TOLERANCE}° high ({@code find_sunset}), the series of
 * {@link DailyCloudiness#geotopSeries}; stepped like the other columns.
 * Where the series is undefined (before the first daylight period of the
 * file) GEOtop takes, step by step, Kimball's τ = 1 - 0.71 f<sub>cloud</sub>
 * with Walcek's cloud fraction from the relative humidity at 700 hPa
 * ({@code find_cloudfactor}, {@code meteodistr.cc:513-549};
 * {@code energy.balance.cc:447-449}), lapse rates 6.5 and 2.5 °C km-1; so does
 * this class. GEOtop computes the series only when the file has no cloud
 * column ({@code input.cc:405-408}): a column of -9999 leaves it undefined for
 * the whole run, an error of the input that the corrected reference
 * removes;</li>
 * <li>incoming longwave: V ε<sub>a</sub> σ T<sub>a</sub>⁴ with the case's
 * clear-sky emissivity ({@link IncomingLongwave});</li>
 * <li>shortwave on the slope: {@link SlopeShortwave}.</li>
 * </ul>
 *
 * Steps are given by their end, UTC; GEOtop's times are local standard time.
 */
public final class GeotopForcing {

	/** GEOtop's {@code Vmin} [m s-1]. */
	public static final double MINIMUM_WIND = 0.5;
	/** GEOtop's {@code RHmin} [%]. */
	public static final double MINIMUM_RELATIVE_HUMIDITY = 10.0;
	private static final double GEOTOP_SEA_LEVEL_PRESSURE = 101325.0;

	/** Column headers of the meteorological file, as in geotop.inpts. */
	public record Headers(String precipitation, String windSpeed, String relativeHumidity, String airTemperature,
			String shortwaveGlobal) {

		/** GEOtop's own default names. */
		public static Headers geotop() {
			return new Headers("Iprec", "WindSp", "RelHum", "AirT", "Swglobal");
		}
	}

	/**
	 * Forcing of a step.
	 *
	 * @param airTemperature        [°C]
	 * @param relativeHumidity      [%]
	 * @param windSpeed             [m s-1]
	 * @param pressure              [Pa]
	 * @param rainfall              [mm]
	 * @param snowfall              [mm]
	 * @param shortwaveGlobal       measured, horizontal [W m-2]
	 * @param shortwaveDirect       beam on the slope [W m-2]
	 * @param shortwaveDiffuse      diffuse [W m-2]
	 * @param cosIncidence          the cosine passed to the snow albedo
	 * @param longwaveIn            [W m-2]
	 * @param cloudTransmissivity   τ<sub>c</sub> of the step
	 * @param averagedTransmissivity averaged τ<sub>c</sub> of the longwave
	 */
	public record Step(double airTemperature, double relativeHumidity, double windSpeed, double pressure,
			double rainfall, double snowfall, double shortwaveGlobal, double shortwaveDirect, double shortwaveDiffuse,
			double cosIncidence, double longwaveIn, double cloudTransmissivity, double averagedTransmissivity) {
	}

	private final GeotopMeteo meteo;
	private final Headers headers;
	private final SolarGeometry site;
	private final double standardTime;
	private final double pressure;
	private final double projection;
	private final GeotopPrecipitation precipitation;
	private final IncomingLongwave.ClearSkyEmissivity emissivity;
	private final double skyViewFactor;
	private final SlopeShortwave shortwave;
	private final CloudTransmissivity clouds;
	private final double[] averagedSeries;
	private final double elevation;

	/**
	 * @param meteo          the meteorological file
	 * @param headers        its column names
	 * @param latitude       [deg]
	 * @param longitude      [deg], east positive
	 * @param standardTime   offset of local standard time from UTC [h]
	 * @param elevation      of station and point [m]
	 * @param slope          [deg]
	 * @param aspect         [deg] from north clockwise
	 * @param stationHorizon horizon of the station, for the cloudiness
	 * @param pointHorizon   horizon of the point, for the beam
	 * @param skyViewFactor  V of the point
	 * @param precipitation  split and corrections
	 * @param emissivity     clear-sky emissivity ({@code LWinParameterization})
	 */
	public GeotopForcing(GeotopMeteo meteo, Headers headers, double latitude, double longitude, double standardTime,
			double elevation, double slope, double aspect, Horizon stationHorizon, Horizon pointHorizon,
			double skyViewFactor, GeotopPrecipitation precipitation, IncomingLongwave.ClearSkyEmissivity emissivity) {
		this.meteo = Objects.requireNonNull(meteo, "meteo");
		this.headers = Objects.requireNonNull(headers, "headers");
		this.site = SolarGeometry.ofDegrees(latitude, longitude, standardTime);
		this.standardTime = standardTime;
		this.pressure = GEOTOP_SEA_LEVEL_PRESSURE * Math.exp(-elevation / 8500.0);
		this.precipitation = Objects.requireNonNull(precipitation, "precipitation");
		this.emissivity = Objects.requireNonNull(emissivity, "emissivity");
		this.skyViewFactor = skyViewFactor;
		this.projection = Math.cos(Math.toRadians(slope));
		this.elevation = elevation;
		ClearSkyTransmissivity clearSky = ClearSkyTransmissivity.geotop();
		this.clouds = new CloudTransmissivity(site, clearSky, 1.0, 0.0);
		this.shortwave = new SlopeShortwave(site, clearSky, Math.toRadians(slope), Math.toRadians(aspect),
				skyViewFactor, pointHorizon);
		this.averagedSeries = averagedSeries(stationHorizon);
	}

	/** Pressure of the point [Pa]. */
	public double pressure() {
		return pressure;
	}

	/** Day of the year with its fraction of a JDfrom0 (local standard time). */
	static double dayOfYear(double julianDayFrom0) {
		double days = julianDayFrom0 - GeotopMeteo.EPOCH_JULIAN_DAY;
		long day = (long) Math.floor(days);
		LocalDateTime date = LocalDateTime.ofEpochSecond(day * 86400L, 0, ZoneOffset.UTC);
		return date.getDayOfYear() - 1 + (days - day);
	}

	/** GEOtop's averaged cloud transmissivity of each record of the file. */
	private double[] averagedSeries(Horizon stationHorizon) {
		double[] jd = meteo.julianDays();
		int n = jd.length;
		double[] tau = new double[n];
		boolean[] daylight = new boolean[n];
		double[] sw = meteo.column(headers.shortwaveGlobal());
		double[] rh = meteo.column(headers.relativeHumidity());
		double[] ta = meteo.column(headers.airTemperature());
		SlopeShortwave flat = new SlopeShortwave(site, ClearSkyTransmissivity.geotop(), 0.0, 0.0, 1.0,
				Horizon.flat());
		for (int k = 0; k < n; k++) {
			double begin = k == 0 ? jd[0] - 0.5 * (jd[1] - jd[0]) : 0.5 * (jd[k - 1] + jd[k]);
			double end = k == n - 1 ? jd[k] + 0.5 * (jd[k] - jd[k - 1]) : 0.5 * (jd[k] + jd[k + 1]);
			double record = dayOfYear(jd[k]);
			double b = record - (jd[k] - begin);
			double e = record + (end - jd[k]);
			SolarGeometry.Sun sun = SolarGeometry.sun(record);
			double height = site.height(record, sun);
			double azimuth = flat.azimuth(record, sun);
			daylight[k] = !stationHorizon.inShadow(Math.toDegrees(height), Math.toDegrees(azimuth),
					Horizon.MOUNTAIN_TOLERANCE, Horizon.FLAT_TOLERANCE);
			double humidity = Double.isNaN(rh[k]) ? 0.4 : Math.max(rh[k] / 100.0, 0.01);
			double t = Double.isNaN(ta[k]) ? 0.0 : ta[k];
			tau[k] = Double.isNaN(sw[k]) ? Double.NaN
					: clouds.fromGlobal(b, e, record, sw[k], pressure, humidity, t + MELTING_TEMPERATURE);
		}
		return DailyCloudiness.geotopSeries(tau, daylight, DailyCloudiness.GEOTOP_DIVISIONS);
	}

	/**
	 * The forcing of the step that ends at {@code epochSeconds}.
	 *
	 * @param epochSeconds end of the step, seconds since 1970-01-01 UTC
	 * @param timeStep     Δt [s]
	 */
	public Step step(long epochSeconds, double timeStep) {
		double end = GeotopMeteo.julianDay(epochSeconds, standardTime);
		double begin = end - timeStep / 86400.0;
		double ta = meteo.stepMean(headers.airTemperature(), begin, end);
		double rh = Math.max(meteo.stepMean(headers.relativeHumidity(), begin, end), MINIMUM_RELATIVE_HUMIDITY);
		double wind = Math.max(meteo.stepMean(headers.windSpeed(), begin, end), MINIMUM_WIND);
		// per unit area of the slope: P cos β
		double intensity = Math.max(0.0, meteo.stepMean(headers.precipitation(), begin, end)) * projection;
		double global = Math.max(0.0, meteo.stepMean(headers.shortwaveGlobal(), begin, end));
		GeotopPrecipitation.Split split = precipitation.split(intensity, ta, timeStep);

		double averaged = stepMeanOfSeries(begin, end);
		averaged = Double.isNaN(averaged) ? kimball(ta, rh / 100.0, elevation) : Math.max(0.0, Math.min(1.0, averaged));

		double dayEnd = dayOfYear(end);
		double dayBegin = dayEnd - timeStep / 86400.0;
		double taK = ta + MELTING_TEMPERATURE;
		double humidity = rh / 100.0;
		double stepTau = clouds.fromGlobal(dayBegin, dayEnd, 0.5 * (dayBegin + dayEnd), global, pressure,
				Math.max(humidity, 0.01), taK);
		if (Double.isNaN(stepTau)) {
			stepTau = averaged;
		}
		SlopeShortwave.Result sw = shortwave.step(dayBegin, dayEnd, stepTau, pressure, humidity, taK);
		double lw = IncomingLongwave.of(emissivity, taK, humidity, pressure, averaged, skyViewFactor);
		return new Step(ta, rh, wind, pressure, split.rain(), split.snow(), global, sw.beam(), sw.diffuse(),
				sw.cosIncidence(), lw, stepTau, averaged);
	}

	/** GEOtop's averaged cloud transmissivity at the records of the file, NaN where undefined. */
	public double[] averagedCloudTransmissivity() {
		return averagedSeries.clone();
	}

	/**
	 * Kimball's (1928) cloud transmissivity with Walcek's (1994) cloud fraction
	 * from the relative humidity at 700 hPa, as GEOtop's
	 * {@code find_cloudfactor} (MicroMet, Liston and Elder 2006).
	 *
	 * @param airTemperature   [°C]
	 * @param relativeHumidity [0, 1]
	 * @param elevation        [m]
	 */
	public static double kimball(double airTemperature, double relativeHumidity, double elevation) {
		double zRef = 3000.0;
		double pressRatio = 0.7;
		double fMax = 78.0 + 80.0 / 15.5;
		double oneMinusRHe = 0.196 + (0.76 - 80.0 / 2834.0) * (1.0 - pressRatio);
		double f1 = fMax * (pressRatio - 0.1) / 0.6 / 100.0;
		double p = 1013.25 * Math.exp(-elevation / 8500.0);
		double dew = dewPoint(airTemperature, Math.max(0.1, relativeHumidity), p);
		double dew700 = dew - (zRef - elevation) * 2.5e-3;
		double air700 = airTemperature - (zRef - elevation) * 6.5e-3;
		double rh700 = Math.max(0.0, Math.min(1.0, saturation(dew700, p) / saturation(air700, p)));
		double cloud = Math.max(0.0, Math.min(1.0, f1 * Math.exp((rh700 - 1.0) / oneMinusRHe)));
		return 1.0 - 0.71 * cloud;
	}

	/** GEOtop's {@code SatVapPressure} [mbar], T [°C], P [mbar]. */
	private static double saturation(double t, double p) {
		return 6.1121 * (1.0007 + 3.46e-6 * p) * Math.exp(17.502 * t / (240.97 + t));
	}

	/** GEOtop's {@code Tdew} [°C]. */
	private static double dewPoint(double t, double rh, double p) {
		double a = 6.1121 * (1.0007 + 3.46e-6 * p);
		double e = saturation(t, p) * Math.min(rh, 1.0);
		return 240.97 * Math.log(e / a) / (17.502 - Math.log(e / a));
	}

	private double stepMeanOfSeries(double begin, double end) {
		double[] jd = meteo.julianDays();
		double sum = 0.0;
		for (int i = 1; i < jd.length && jd[i - 1] < end; i++) {
			double a = Math.max(jd[i - 1], begin);
			double b = Math.min(jd[i], end);
			if (b > a) {
				if (Double.isNaN(averagedSeries[i])) {
					return Double.NaN;
				}
				sum += averagedSeries[i] * (b - a);
			}
		}
		return sum / (end - begin);
	}
}
