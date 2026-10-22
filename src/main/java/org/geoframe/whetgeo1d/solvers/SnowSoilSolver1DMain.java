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
package org.geoframe.whetgeo1d.solvers;

import static org.geoframe.whetgeo1d.snow.SnowConstants.MELTING_TEMPERATURE;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.geoframe.closureequation.closureequation.Parameters;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.DiffusionBoundaryConditionType;
import org.geoframe.whetgeo1d.core.boundaryconditions.IBoundaryCondition.RichardsBoundaryConditionType;
import org.geoframe.whetgeo1d.core.snowsoil.IceAwareRetention;
import org.geoframe.whetgeo1d.core.snowsoil.NewtonSettings;
import org.geoframe.whetgeo1d.core.snowsoil.SnowHeatProperties;
import org.geoframe.whetgeo1d.core.snowsoil.SnowProcesses;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilSolver;
import org.geoframe.whetgeo1d.core.snowsoil.SnowSoilStepResult;
import org.geoframe.whetgeo1d.core.snowsoil.SoilHeatSetup;
import org.geoframe.whetgeo1d.core.snowsoil.SoilWaterProperties;
import org.geoframe.whetgeo1d.core.snowsoil.StepForcing;
import org.geoframe.whetgeo1d.core.snowsoil.TopBoundary;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.CloudTransmissivity;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.DailyCloudiness;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.IncomingLongwave;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.PrecipitationPartition;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.SolarGeometry;
import org.geoframe.whetgeo1d.core.snowsoil.forcing.StandardAtmosphere;
import org.geoframe.whetgeo1d.core.utils.EnumUtils;
import org.geoframe.whetgeo1d.io.snowsoil.RunHeader;
import org.geoframe.whetgeo1d.io.snowsoil.SnowSoilOutputWriter;
import org.geoframe.whetgeo1d.io.snowsoil.SoilGridInputs;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SnowThermalConductivity;
import org.geoframe.whetgeo1d.snow.surface.MoninObukhov;
import org.geoframe.whetgeo1d.snow.surface.StabilityCorrection;
import org.geoframe.whetgeo1d.snow.surface.SurfaceForcing;
import org.hortonmachine.gears.libs.modules.HMModel;

import oms3.annotations.Author;
import oms3.annotations.Bibliography;
import oms3.annotations.Description;
import oms3.annotations.Documentation;
import oms3.annotations.Execute;
import oms3.annotations.Finalize;
import oms3.annotations.In;
import oms3.annotations.Keywords;
import oms3.annotations.License;
import oms3.annotations.Out;
import oms3.annotations.Unit;

/**
 * OMS3 component of the snow+soil column (GEO2GEO prototype, phase 1.1): one
 * {@link SnowSoilSolver} driven by GEOframe time series, one step per
 * {@code @Execute}.
 *
 * <p>
 * <b>Configuration</b> (ARCH-6, PRI-1). {@code configuration} picks one of the
 * two default sets, {@code fidelity} (GEOtop 3.0 options,
 * {@link SnowProcesses#geotop}) or {@code recommended}
 * ({@link SnowProcesses#recommended}); the few options exposed here
 * (conductivity of the snow, stability correction, capacity of snow cells,
 * measurement heights) are read as strings from the .sim and turned into the
 * enums and immutable records of the snow library, where the snow parameters
 * live. Nothing goes into ClosureEquation's {@code Parameters}, which holds the
 * soil only.
 *
 * <p>
 * <b>Soil grid</b> (ARCH-5, COL-3). The soil comes from WHETGEO-1D's inputs:
 * either the arrays of the grid readers connected as in the other WHETGEO-1D
 * solvers ({@code z}, {@code spaceDeltaZ}, {@code controlVolume},
 * {@code psiIC}, {@code temperature}, the ids and the parameter arrays), or a
 * path in {@code soilGridFile} to a GeoPackage, a NetCDF grid or a folder of
 * WHETGEO-1D's CSV inputs ({@link SoilGridInputs}). Positions are in η, zero
 * at the soil surface; the solver's z is built once, here. The column reserves
 * {@code maxSnowCells} cells above the soil (default 10 in fidelity, 11 in
 * recommended, COL-3, SNOW-1).
 *
 * <p>
 * <b>Forcing</b> (LINK-1, SNOW-5), as GEOframe OMS time series
 * ({@code HashMap<Integer, double[]>} keyed by {@code stationID}), each value
 * the one of the step that ends at {@code inCurrentDate} (UTC, as the OMS
 * readers give it); -9999 and NaN mean missing:
 *
 * <ul>
 * <li>air temperature [°C], relative humidity [%], wind speed [m s-1] —
 * required;</li>
 * <li>pressure [Pa]; where missing, the standard atmosphere at
 * {@code elevation} ({@link StandardAtmosphere});</li>
 * <li>shortwave on the horizontal [W m-2], either beam and diffuse, or
 * global, split into beam and diffuse with Erbs' diffuse fraction of
 * τ<sub>c</sub> τ<sub>atm</sub> as GEOtop ({@link CloudTransmissivity});</li>
 * <li>incoming longwave [W m-2]; where missing, estimated as GEOtop from the
 * air and the averaged cloud transmissivity ({@link IncomingLongwave}), which
 * is taken from {@code inCloudTransmissivity} [-] if given, otherwise from the
 * measured shortwave with the causal daily mean of
 * {@link DailyCloudiness.Running};</li>
 * <li>precipitation [mm in the step]: rain and snow already split
 * ({@code inRainfall}, {@code inSnowfall}), or the total ({@code inPrecipitation})
 * split by {@code precipitationPartition} on the air temperature
 * ({@link PrecipitationPartition});</li>
 * <li>precipitation temperature [°C], optional: it replaces the wet bulb in
 * the recommended configuration (SNOW-5).</li>
 * </ul>
 *
 * The fidelity path of DN-14 (forcing from GEOtop's hourly point output) is
 * this same component fed with GEOtop's columns: rain and snow split,
 * T<sub>a</sub>, RH, wind, pressure, SW beam and diffuse, LW in, and GEOtop's
 * {@code Tsurface} as a Dirichlet bare-soil top.
 *
 * <p>
 * <b>Boundary conditions</b> (COL-6). Heat, top: with snow on the ground the
 * surface energy balance of the snow library; without snow the bare-soil top
 * chosen by {@code bareSoilTopType}: {@code Dirichlet} (soil surface
 * temperature [°C]), {@code Neumann} (flux into the soil [W m-2]) or
 * {@code BulkTransfer}, h (T<sub>a</sub> - T<sub>top</sub>) with
 * h = {@code bareSoilTransfer}. Heat, bottom: {@code Bottom Dirichlet}
 * [°C], {@code Bottom Neumann} [W m-2, positive into the column] or
 * {@code Bottom No Gradient}. Water: the top of Richards is the water leaving
 * the pack, or the rain on bare soil, switching to Dirichlet at saturation
 * ({@code SoilWaterDriver}); the bottom is any of WHETGEO-1D's Richards
 * conditions ({@code soilWaterBottomType}), its value as in
 * {@code RichardsSolver1D}. Every value is a constant ({@code ...Value}) or
 * a time series ({@code in...}), the series winning where it has a value.
 *
 * <p>
 * <b>Heat-only mode.</b> {@code soilWaterMode = Fixed} keeps the suction of
 * the soil at its initial value (no Richards), as GEOtop with
 * {@code WaterBalance = 0}; the water leaving the pack is then only reported.
 *
 * <p>
 * <b>Outputs.</b> Each step goes to every {@link SnowSoilOutputWriter} of
 * {@link #createWriters()}, after a {@link RunHeader} with the code version,
 * the configuration, SHA-256 of the input files and the soil grid (VAL-7). The
 * main scalars and profiles are also {@code @Out} fields, for chaining.
 *
 * <p>
 * Not here: non-convergence handling and restart (the step controller of phase
 * 1.4 replaces {@link #advance}), evapotranspiration sinks from GEOET, an
 * initial snowpack, a slope.
 */
@Description("Snow and soil column: snowpack on WHETGEO-1D's soil, heat of snow and soil as one system, Richards and frozen soil, GEOtop 3.0 physics in the fidelity configuration.")
@Documentation("")
@Author(name = "WHETGEO-1D contributors (GEO2GEO)", contact = "")
@Keywords("Snow, Frozen soil, Heat diffusion, Richards, Surface energy balance, GEOtop")
@Bibliography("Casulli and Zanolli (2010), Endrizzi et al. (2014), Tubini et al. (2021)")
@License("General Public License Version 3 (GPLv3)")
public class SnowSoilSolver1DMain extends HMModel {

	/** The two default sets of the snow library (PRI-1). */
	public enum Configuration {
		FIDELITY, RECOMMENDED
	}

	/** Water in the soil. */
	public enum SoilWaterMode {
		/** Richards under the snow, frozen soil included. */
		RICHARDS,
		/** Suction fixed at its initial value: heat only, GEOtop's WaterBalance = 0. */
		FIXED
	}

	/** Heat condition at the top of the soil when there is no snow. */
	public enum BareSoilTop {
		/** Imposed soil-surface temperature [°C]. */
		DIRICHLET,
		/** Imposed flux into the soil [W m-2]. */
		NEUMANN,
		/** h (T_a - T_top), linearised with derivative -h. */
		BULK_TRANSFER
	}

	/** How total precipitation is split. */
	public enum Partition {
		/** GEOtop's linear ramp between T_snow and T_rain. */
		GEOTOP_LINEAR,
		/** Kavetski et al. (2006), as RainSnowSep. */
		KAVETSKI
	}

	private static final double NODATA = -9999.0;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	/*
	 * CONFIGURATION
	 */
	@Description("Default set of the snow library: fidelity (GEOtop 3.0) or recommended.")
	@In
	public String configuration = "recommended";

	@Description("Snow thermal conductivity (Cosenza, Sturm 1997, Jordan 1991, ...); empty: Cosenza in fidelity, Jordan 1991 in recommended.")
	@In
	public String snowThermalConductivity = "";

	@Description("Stability correction of the turbulent fluxes over snow (Both, Unstable only, Stable only, Neutral); empty: the configuration's.")
	@In
	public String stabilityCorrection = "";

	@Description("Capacity of snow cells above the soil (COL-3); not positive: 10 in fidelity, 11 in recommended.")
	@In
	public int maxSnowCells = -1;

	@Description("Smoothing α of the snow equation of state (STA-1).")
	@In
	public double alphaSnow = 1e5;

	@Description("Height of the wind measurement.")
	@In
	@Unit("m")
	public double windHeight = 2.0;

	@Description("Height of the air temperature measurement.")
	@In
	@Unit("m")
	public double temperatureHeight = 2.0;

	@Description("Albedo of the soil under thin snow, visible and near infrared.")
	@In
	public double groundAlbedo = 0.2;

	@Description("Absolute tolerance of the heat Newton.")
	@In
	@Unit("J m-2")
	public double heatTolerance = 1e-3;

	@Description("Iteration cap of the heat Newton.")
	@In
	public int heatMaxIterations = 50;

	@Description("Picard iterations of the heat step.")
	@In
	public int heatPicardIterations = 1;

	@Description("Water in the soil: Richards, or Fixed (heat only, GEOtop WaterBalance = 0).")
	@In
	public String soilWaterMode = "Richards";

	/*
	 * SITE
	 */
	@Description("Latitude of the column.")
	@In
	@Unit("deg")
	public double latitude;

	@Description("Longitude of the column, east positive.")
	@In
	@Unit("deg")
	public double longitude;

	@Description("Offset of the local standard time from UTC, for the solar geometry.")
	@In
	@Unit("h")
	public double standardTime = 0.0;

	@Description("Elevation of the column, for the pressure where it is missing.")
	@In
	@Unit("m")
	public double elevation = 0.0;

	@Description("Clear-sky emissivity of the longwave estimate (GEOtop LWinParameterization).")
	@In
	public String clearSkyEmissivity = "Dilley OBrien 1998";

	@Description("Cloud transmissivity used before the first daylight step, where no average exists yet.")
	@In
	public double defaultCloudTransmissivity = 1.0;

	@Description("Split of total precipitation: Geotop Linear or Kavetski.")
	@In
	public String precipitationPartition = "Geotop Linear";

	@Description("Temperature above which precipitation is all rain (Geotop Linear).")
	@In
	@Unit("°C")
	public double rainThreshold = 3.0;

	@Description("Temperature below which precipitation is all snow (Geotop Linear).")
	@In
	@Unit("°C")
	public double snowThreshold = -1.0;

	@Description("Kavetski: melting temperature, smoothing, rain and snow correction factors.")
	@In
	public double kavetskiMeltingTemperature = 0.0;
	@In
	public double kavetskiSmoothing = 1.0;
	@In
	public double rainCorrection = 1.0;
	@In
	public double snowCorrection = 1.0;

	/*
	 * SOIL GRID: either a file, or the arrays of WHETGEO-1D's grid readers
	 */
	@Description("GeoPackage, NetCDF grid or folder of CSV inputs of WHETGEO-1D; empty: use the arrays below.")
	@In
	public String soilGridFile = "";

	@In
	@Unit("m")
	public double[] eta;
	@In
	@Unit("m")
	public double[] z;
	@In
	@Unit("m")
	public double[] spaceDeltaZ;
	@In
	@Unit("m")
	public double[] controlVolume;
	@In
	@Unit("m")
	public double[] psiIC;
	@In
	@Unit("K")
	public double[] temperature;
	@In
	public int[] inEquationStateID;
	@In
	public int[] inParameterID;
	@In
	public double[] ks;
	@In
	public double[] thetaS;
	@In
	public double[] thetaR;
	@In
	public double[] par1SWRC;
	@In
	public double[] par2SWRC;
	@In
	public double[] par3SWRC;
	@In
	public double[] par4SWRC;
	@In
	public double[] par5SWRC;
	@In
	public double[] alphaSpecificStorage;
	@In
	public double[] betaSpecificStorage;
	@In
	public double[] soilParticlesDensity;
	@In
	public double[] specificThermalCapacitySoilParticles;
	@In
	public double[] thermalConductivitySoilParticles;
	@In
	public double[] meltingTemperature;

	@Description("Thermal conductivity of water; GEOtop 0.567, WHETGEO-1D 0.6.")
	@In
	@Unit("W m-1 K-1")
	public double thermalConductivityWater = 0.6;

	@Description("Coefficient for water suction dependence on temperature")
	@In
	@Unit("K")
	public double beta0 = -766.45;

	@Description("Reference temperature for soil water content")
	@In
	@Unit("K")
	public double referenceTemperatureSWRC = 278.15;

	@Description("Heat closures of the soil, per equation-state id.")
	@In
	public String[] typeClosureEquation = { "Van Genuchten Dall Amico" };
	@In
	public String[] typeEquationState = { "FreezingSoilInternalEnergy" };
	@In
	public String[] typeThermalConductivity = { "Cosenza" };
	@In
	public String interfaceThermalConductivityModel = "Weighted Harmonic Mean";

	@Description("Water closures of the soil, per equation-state id.")
	@In
	public String[] typeWaterClosureEquation = { "Van Genuchten" };
	@In
	public String[] typeWaterEquationState = { "Van Genuchten" };
	@In
	public String[] typeUHCModel = { "Mualem Van Genuchten" };
	@In
	public String typeUHCTemperatureModel = "notemperature";
	@In
	public String interfaceHydraulicConductivityModel = "max";
	@In
	public double waterNewtonTolerance = 1e-11;
	@In
	public int waterMaxIterations = 50;
	@In
	@Unit("s")
	public double waterMaxSubstep = 1800.0;
	@Description("Ω of the impedance of ice on the hydraulic conductivity (GEOtop 7).")
	@In
	public double iceImpedance = IceAwareRetention.GEOTOP_IMPEDANCE;

	/*
	 * BOUNDARY CONDITIONS (COL-6)
	 */
	@Description("Heat at the top of the bare soil: Dirichlet, Neumann or Bulk Transfer.")
	@In
	public String bareSoilTopType = "Bulk Transfer";
	@Description("Constant value of the bare-soil top: temperature [°C] or flux [W m-2].")
	@In
	public double bareSoilTopValue = Double.NaN;
	@Description("Transfer coefficient of the Bulk Transfer top.")
	@In
	@Unit("W m-2 K-1")
	public double bareSoilTransfer = 10.0;
	@In
	public Map<Integer, double[]> inTopBC;

	@Description("Heat at the bottom: Bottom Dirichlet, Bottom Neumann, Bottom No Gradient.")
	@In
	public String bottomBCType = "Bottom No Gradient";
	@Description("Constant value at the bottom: temperature [°C] or flux into the column [W m-2].")
	@In
	public double bottomBCValue = 0.0;
	@In
	public Map<Integer, double[]> inBottomBC;

	@Description("Bottom of Richards: any of WHETGEO-1D's (Bottom Free Drainage, Bottom Impervious, Bottom Dirichlet, ...).")
	@In
	public String soilWaterBottomType = "Bottom Free Drainage";
	@In
	public double soilWaterBottomValue = 0.0;
	@In
	public Map<Integer, double[]> inSoilWaterBottomBC;

	/*
	 * FORCING
	 */
	@Description("The station ID in the timeseries files")
	@In
	public int stationID;
	@Description("End of the step, yyyy-MM-dd HH:mm UTC, as the OMS readers give it")
	@In
	public String inCurrentDate;
	@Description("Step length")
	@In
	@Unit("s")
	public double tTimeStep = 3600.0;

	@In
	@Unit("°C")
	public Map<Integer, double[]> inAirTemperature;
	@In
	@Unit("%")
	public Map<Integer, double[]> inRelativeHumidity;
	@In
	@Unit("m s-1")
	public Map<Integer, double[]> inWindSpeed;
	@In
	@Unit("Pa")
	public Map<Integer, double[]> inPressure;
	@In
	@Unit("W m-2")
	public Map<Integer, double[]> inShortwaveGlobal;
	@In
	@Unit("W m-2")
	public Map<Integer, double[]> inShortwaveDirect;
	@In
	@Unit("W m-2")
	public Map<Integer, double[]> inShortwaveDiffuse;
	@In
	@Unit("W m-2")
	public Map<Integer, double[]> inLongwaveIn;
	@In
	@Unit("-")
	public Map<Integer, double[]> inCloudTransmissivity;
	@In
	@Unit("mm")
	public Map<Integer, double[]> inPrecipitation;
	@In
	@Unit("mm")
	public Map<Integer, double[]> inRainfall;
	@In
	@Unit("mm")
	public Map<Integer, double[]> inSnowfall;
	@In
	@Unit("°C")
	public Map<Integer, double[]> inPrecipitationTemperature;

	/*
	 * RUN HEADER AND OUTPUT
	 */
	@Description("Version of the code for the run header; empty: the package version.")
	@In
	public String codeVersion = "";
	@Description("Input files to hash in the run header, separated by commas (the soil grid file is added).")
	@In
	public String inputFiles = "";
	@Description("Where the output writers write (folder or file prefix).")
	@In
	public String outputPath = "";
	@Description("Writers to use instead of the default ones of createWriters(), e.g. in tests.")
	@In
	public List<SnowSoilOutputWriter> writers;

	@Out
	@Unit("kg m-2")
	public double outSnowWaterEquivalent;
	@Out
	@Unit("m")
	public double outSnowDepth;
	@Out
	public int outSnowCells;
	@Out
	@Unit("K")
	public double outSurfaceTemperature;
	@Out
	@Unit("K")
	public double[] outSoilTemperature;
	@Out
	@Unit("m")
	public double[] outSoilSuction;
	@Out
	@Unit("-")
	public double[] outSoilIce;
	@Out
	@Unit("kg m-2")
	public double outSoilInput;
	@Out
	@Unit("kg m-2")
	public double outRainfall;
	@Out
	@Unit("kg m-2")
	public double outSnowfall;
	@Out
	@Unit("Pa")
	public double outPressure;
	@Out
	@Unit("W m-2")
	public double outLongwaveIn;
	@Out
	@Unit("-")
	public double outCloudTransmissivity;
	@Out
	public SnowSoilStepResult outStepResult;
	@Out
	public RunHeader outRunHeader;

	private SnowSoilSolver solver;
	private List<SnowSoilOutputWriter> activeWriters;
	private SolarGeometry site;
	private CloudTransmissivity clouds;
	private DailyCloudiness.Running cloudiness;
	private IncomingLongwave.ClearSkyEmissivity emissivity;
	private PrecipitationPartition partition;
	private BareSoilTop bareSoilTop;
	private DiffusionBoundaryConditionType bottomType;
	private double[] soilDepth;
	private double[] soilThickness;

	/** One step of the column. */
	@Execute
	public void solve() throws Exception {
		if (solver == null) {
			setup();
		}
		long end = epochSeconds(inCurrentDate);
		StepForcing forcing = forcing(end);
		SnowSoilStepResult result = advance(forcing);
		publish(result);
		for (SnowSoilOutputWriter writer : activeWriters) {
			writer.write(end, solver, result);
		}
	}

	/** Closes the writers at the end of the run. */
	@Finalize
	public void close() throws IOException {
		if (activeWriters == null) {
			return;
		}
		IOException failure = null;
		for (SnowSoilOutputWriter writer : activeWriters) {
			try {
				writer.close();
			} catch (IOException e) {
				failure = e;
			}
		}
		activeWriters = null;
		if (failure != null) {
			throw failure;
		}
	}

	/** The column, for writers and tests; null before the first step. */
	public SnowSoilSolver solver() {
		return solver;
	}

	/**
	 * Advances the column by one step. MERGE POINT (phase 1.4): this is the
	 * only call of {@link SnowSoilSolver#step}; the step controller (rollback
	 * and halved substeps on non-convergence, restart snapshots) replaces its
	 * body.
	 */
	private SnowSoilStepResult advance(StepForcing forcing) {
		return solver.step(forcing);
	}

	/**
	 * The writers of the run. MERGE POINT (phase 1.3): the NetCDF and
	 * GEOtop-like CSV implementations of {@link SnowSoilOutputWriter} are
	 * created here from {@link #outputPath}; until then only the writers given
	 * in {@link #writers} are used.
	 */
	protected List<SnowSoilOutputWriter> createWriters() {
		if (writers != null) {
			return new ArrayList<>(writers);
		}
		return new ArrayList<>();
	}

	/* ---------------------------------------------------------------- setup */

	private void setup() throws Exception {
		Configuration config = EnumUtils.fromString(Configuration.class, configuration);
		boolean fidelity = config == Configuration.FIDELITY;
		SoilGridInputs grid = soilGridFile == null || soilGridFile.isBlank() ? null : SoilGridInputs.read(soilGridFile);
		if (grid != null) {
			takeGrid(grid);
		}
		if (z == null || controlVolume == null || psiIC == null || temperature == null) {
			throw new IllegalArgumentException("no soil grid: give soilGridFile or the grid arrays");
		}
		int n = controlVolume.length;
		soilThickness = controlVolume.clone();
		soilDepth = new double[n];
		double top = z[n - 1] + 0.5 * controlVolume[n - 1];
		for (int i = 0; i < n; i++) {
			soilDepth[i] = eta != null ? -eta[i] : top - z[i];
		}

		int m = thetaS.length;
		Parameters parameters = new Parameters(1000.0, 920.0, 4188.0, 2117.0, thermalConductivityWater, 2.29,
				333700, 273.15, referenceTemperatureSWRC, beta0, thetaS, thetaR, soilParticlesDensity,
				specificThermalCapacitySoilParticles, thermalConductivitySoilParticles,
				orFill(meltingTemperature, m, 273.15), par1SWRC, par2SWRC, orFill(par3SWRC, m, NODATA),
				orFill(par4SWRC, m, NODATA), orFill(par5SWRC, m, NODATA), ks, orFill(alphaSpecificStorage, m, 0.0),
				orFill(betaSpecificStorage, m, 0.0));
		SoilHeatSetup soil = new SoilHeatSetup(z.clone(), spaceDeltaZ.clone(), controlVolume.clone(), psiIC.clone(),
				temperature.clone(), inEquationStateID.clone(), inParameterID.clone(), typeClosureEquation,
				typeEquationState, typeThermalConductivity, interfaceThermalConductivityModel, parameters);

		SoilWaterProperties water = null;
		if (EnumUtils.fromString(SoilWaterMode.class, soilWaterMode) == SoilWaterMode.RICHARDS) {
			water = new SoilWaterProperties(typeWaterClosureEquation, typeWaterEquationState, typeUHCModel,
					typeUHCTemperatureModel, interfaceHydraulicConductivityModel,
					RichardsBoundaryConditionType.fromString(soilWaterBottomType), waterNewtonTolerance,
					waterMaxIterations, 1, waterMaxSubstep, iceImpedance);
		}

		SnowEquationOfState eos = new SnowEquationOfState(alphaSnow);
		SnowProcesses processes = fidelity ? SnowProcesses.geotop(eos, windHeight, temperatureHeight)
				: SnowProcesses.recommended(eos, windHeight, temperatureHeight);
		if (stabilityCorrection != null && !stabilityCorrection.isBlank()) {
			processes = withStability(processes, EnumUtils.fromString(StabilityCorrection.class, stabilityCorrection));
		}
		SnowThermalConductivity conductivity = snowThermalConductivity == null || snowThermalConductivity.isBlank()
				? (fidelity ? SnowThermalConductivity.COSENZA : SnowThermalConductivity.JORDAN_1991)
				: EnumUtils.fromString(SnowThermalConductivity.class, snowThermalConductivity);
		int capacity = maxSnowCells > 0 ? maxSnowCells : (fidelity ? 10 : 11);
		SnowHeatProperties heat = new SnowHeatProperties(eos, conductivity, capacity);
		bottomType = DiffusionBoundaryConditionType.fromString(bottomBCType);
		if (bottomType.name().startsWith("TOP")) {
			throw new IllegalArgumentException("bottomBCType must be a bottom condition, got " + bottomBCType);
		}
		NewtonSettings newton = new NewtonSettings(heatTolerance, NewtonSettings.defaults().roundOffSafety(),
				heatMaxIterations, heatPicardIterations);
		solver = new SnowSoilSolver(soil, water, heat, bottomType, newton, processes, List.of(), 0.0);

		site = SolarGeometry.ofDegrees(latitude, longitude, standardTime);
		clouds = CloudTransmissivity.flat(site);
		cloudiness = new DailyCloudiness.Running();
		emissivity = EnumUtils.fromString(IncomingLongwave.ClearSkyEmissivity.class, clearSkyEmissivity);
		partition = EnumUtils.fromString(Partition.class, precipitationPartition) == Partition.KAVETSKI
				? new PrecipitationPartition.Kavetski(kavetskiMeltingTemperature + MELTING_TEMPERATURE,
						kavetskiSmoothing, rainCorrection, snowCorrection)
				: new PrecipitationPartition.GeotopLinear(rainThreshold + MELTING_TEMPERATURE,
						snowThreshold + MELTING_TEMPERATURE);
		bareSoilTop = EnumUtils.fromString(BareSoilTop.class, bareSoilTopType);

		Map<String, String> attributes = new LinkedHashMap<>();
		attributes.put("configuration", config.name().toLowerCase());
		attributes.put("snowThermalConductivity", conductivity.name());
		attributes.put("stabilityCorrection", processes.turbulence().parameters().stability().name());
		attributes.put("maxSnowCells", Integer.toString(capacity));
		attributes.put("alphaSnow", Double.toString(alphaSnow));
		attributes.put("windHeight", Double.toString(windHeight));
		attributes.put("temperatureHeight", Double.toString(temperatureHeight));
		attributes.put("soilWaterMode", EnumUtils.fromString(SoilWaterMode.class, soilWaterMode).name());
		attributes.put("bareSoilTop", bareSoilTop.name());
		attributes.put("bottomBC", bottomType.name());
		attributes.put("soilWaterBottomBC", water == null ? "none" : water.bottomType().name());
		attributes.put("latitude", Double.toString(latitude));
		attributes.put("longitude", Double.toString(longitude));
		attributes.put("standardTime", Double.toString(standardTime));
		attributes.put("elevation", Double.toString(elevation));
		attributes.put("clearSkyEmissivity", emissivity.name());
		attributes.put("precipitationPartition", partition.toString());
		attributes.put("timeStep", Double.toString(tTimeStep));
		for (String file : inputFileList()) {
			attributes.put("sha256:" + Path.of(file).getFileName(), sha256(Path.of(file)));
		}
		outRunHeader = new RunHeader(version(), config.name().toLowerCase(), attributes, soilDepth, soilThickness);
		activeWriters = createWriters();
		for (SnowSoilOutputWriter writer : activeWriters) {
			writer.open(outRunHeader);
		}
	}

	private void takeGrid(SoilGridInputs g) {
		eta = g.eta;
		z = g.z;
		spaceDeltaZ = g.spaceDelta;
		controlVolume = g.controlVolume;
		psiIC = g.psi;
		temperature = g.temperature;
		inEquationStateID = g.equationStateID;
		inParameterID = g.parameterID;
		ks = g.ks;
		thetaS = g.thetaS;
		thetaR = g.thetaR;
		par1SWRC = g.par1SWRC;
		par2SWRC = g.par2SWRC;
		par3SWRC = g.par3SWRC;
		par4SWRC = g.par4SWRC;
		par5SWRC = g.par5SWRC;
		alphaSpecificStorage = g.alphaSS;
		betaSpecificStorage = g.betaSS;
		soilParticlesDensity = g.soilParticlesDensity;
		specificThermalCapacitySoilParticles = g.soilParticlesSpecificHeatCapacity;
		thermalConductivitySoilParticles = g.soilParticlesThermalConductivity;
		meltingTemperature = g.meltingTemperature;
	}

	private static SnowProcesses withStability(SnowProcesses p, StabilityCorrection stability) {
		MoninObukhov.Parameters t = p.turbulence().parameters();
		MoninObukhov turbulence = new MoninObukhov(new MoninObukhov.Parameters(t.windHeight(), t.temperatureHeight(),
				t.roughnessLength(), t.displacementHeight(), stability, t.scalarRoughness(), t.roughnessRatio(),
				t.maxIterations(), t.minimumWindSpeed(), t.maximumResistance(), t.capVapourResistance()));
		return new SnowProcesses(p.equationOfState(), p.surfaceBalance(), turbulence, p.albedo(), p.restoringSnowfall(),
				p.compaction(), p.slopeAngle(), p.percolation(), p.sublimationThickness(), p.restratification(),
				p.precipitation(), p.surfaceCoupling());
	}

	private List<String> inputFileList() {
		List<String> files = new ArrayList<>();
		if (soilGridFile != null && !soilGridFile.isBlank() && Files.isRegularFile(Path.of(soilGridFile))) {
			files.add(soilGridFile);
		} else if (soilGridFile != null && !soilGridFile.isBlank() && Files.isDirectory(Path.of(soilGridFile))) {
			try (var stream = Files.list(Path.of(soilGridFile))) {
				stream.filter(Files::isRegularFile).map(Path::toString).sorted().forEach(files::add);
			} catch (IOException e) {
				throw new IllegalStateException("cannot list " + soilGridFile, e);
			}
		}
		if (inputFiles != null) {
			for (String f : inputFiles.split(",")) {
				if (!f.isBlank()) {
					files.add(f.trim());
				}
			}
		}
		return files;
	}

	private String version() {
		if (codeVersion != null && !codeVersion.isBlank()) {
			return codeVersion;
		}
		String v = SnowSoilSolver1DMain.class.getPackage().getImplementationVersion();
		return v == null ? "whetgeo1d (version unknown)" : v;
	}

	/** SHA-256 of a file, hexadecimal. */
	static String sha256(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			int read;
			while ((read = in.read(buffer)) > 0) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (Exception e) {
			throw new IllegalArgumentException("cannot hash input file " + file, e);
		}
	}

	private static double[] orFill(double[] values, int length, double fill) {
		if (values != null) {
			return values;
		}
		double[] a = new double[length];
		java.util.Arrays.fill(a, fill);
		return a;
	}

	/* -------------------------------------------------------------- forcing */

	private StepForcing forcing(long end) {
		double dt = tTimeStep;
		double ta = required(inAirTemperature, "air temperature") + MELTING_TEMPERATURE;
		double rh = Math.max(0.0, Math.min(1.0, required(inRelativeHumidity, "relative humidity") / 100.0));
		double wind = Math.max(0.0, required(inWindSpeed, "wind speed"));
		double p = value(inPressure);
		if (Double.isNaN(p)) {
			p = StandardAtmosphere.pressure(elevation);
		}

		double jdEnd = site.julianDay(end);
		double jdBegin = jdEnd - dt / 86400.0;
		double jdMid = 0.5 * (jdBegin + jdEnd);
		SolarGeometry.Sun sun = SolarGeometry.sun(jdMid);
		double height = site.height(jdMid, sun);
		boolean daylight = height >= DailyCloudiness.DAYLIGHT_HEIGHT;

		double direct = value(inShortwaveDirect);
		double diffuse = value(inShortwaveDiffuse);
		double stepTau;
		if (!Double.isNaN(direct) && !Double.isNaN(diffuse)) {
			direct = Math.max(0.0, direct);
			diffuse = Math.max(0.0, diffuse);
			stepTau = clouds.fromBeamAndDiffuse(jdBegin, jdEnd, jdMid, direct, diffuse, p, rh, ta);
		} else {
			double global = Math.max(0.0, required(inShortwaveGlobal, "shortwave (global, or direct and diffuse)"));
			stepTau = clouds.fromGlobal(jdBegin, jdEnd, jdMid, global, p, rh, ta);
			if (Double.isNaN(stepTau) || global <= 0.0) {
				direct = 0.0;
				diffuse = global;
			} else {
				double kd = CloudTransmissivity.diffuseFraction(
						stepTau * clouds.atmosphericTransmissivity(jdBegin, jdEnd, jdMid, p, rh, ta));
				diffuse = kd * global;
				direct = global - diffuse;
			}
		}
		double cos = Math.sin(height);
		if (direct > 0.0 && cos <= 0.0) {
			// the step holds a sliver of daylight but its middle is below the horizon
			cos = 1e-3;
		}

		double tau = value(inCloudTransmissivity);
		double averaged = cloudiness.add(end, stepTau, daylight);
		if (Double.isNaN(tau)) {
			tau = Double.isNaN(averaged) ? defaultCloudTransmissivity : averaged;
		}
		tau = Math.max(0.0, Math.min(1.0, tau));
		double lw = value(inLongwaveIn);
		if (Double.isNaN(lw)) {
			lw = IncomingLongwave.of(emissivity, ta, rh, p, tau, 1.0);
		}
		outPressure = p;
		outLongwaveIn = lw;
		outCloudTransmissivity = tau;
		SurfaceForcing surface = new SurfaceForcing(direct, diffuse, Math.max(0.0, Math.min(1.0, cos)), lw, ta, rh,
				wind, p);

		double rain = value(inRainfall);
		double snow = value(inSnowfall);
		if (Double.isNaN(rain) || Double.isNaN(snow)) {
			double total = Math.max(0.0, required(inPrecipitation, "precipitation (total, or rain and snow)"));
			PrecipitationPartition.Split split = partition.split(total, ta);
			rain = split.rain();
			snow = split.snow();
		}
		rain = Math.max(0.0, rain);
		snow = Math.max(0.0, snow);
		outRainfall = rain;
		outSnowfall = snow;
		double precipitationTemperature = value(inPrecipitationTemperature);
		if (!Double.isNaN(precipitationTemperature)) {
			precipitationTemperature += MELTING_TEMPERATURE;
		}

		double[] soilT = solver.soilTemperatures();
		TopBoundary top = switch (bareSoilTop) {
		case DIRICHLET -> new TopBoundary.SurfaceTemperature(series(inTopBC, bareSoilTopValue, "bare-soil top")
				+ MELTING_TEMPERATURE);
		case NEUMANN -> TopBoundary.SurfaceFlux.constant(series(inTopBC, bareSoilTopValue, "bare-soil top"));
		case BULK_TRANSFER -> new TopBoundary.SurfaceFlux(bareSoilTransfer * (ta - soilT[soilT.length - 1]),
				-bareSoilTransfer);
		};
		double bottom = switch (bottomType) {
		case BOTTOM_DIRICHLET -> series(inBottomBC, bottomBCValue, "bottom") + MELTING_TEMPERATURE;
		case BOTTOM_NEUMANN -> series(inBottomBC, bottomBCValue, "bottom");
		default -> 0.0;
		};
		double waterBottom = series(inSoilWaterBottomBC, soilWaterBottomValue, "soil water bottom");
		return new StepForcing(surface, snow, rain, groundAlbedo, groundAlbedo, top, bottom, dt, null,
				precipitationTemperature, waterBottom);
	}

	private void publish(SnowSoilStepResult r) {
		outStepResult = r;
		outSnowWaterEquivalent = r.snowWaterEquivalent();
		outSnowDepth = r.snowDepth();
		outSnowCells = r.snow().size();
		outSoilTemperature = solver.soilTemperatures();
		outSurfaceTemperature = Double.isNaN(r.surfaceTemperature()) ? outSoilTemperature[outSoilTemperature.length - 1]
				: r.surfaceTemperature();
		outSoilSuction = solver.soilSuctions();
		outSoilIce = solver.soilIce();
		outSoilInput = r.soilInputMass();
	}

	private double value(Map<Integer, double[]> series) {
		if (series == null) {
			return Double.NaN;
		}
		double[] v = series.get(stationID);
		if (v == null || v.length == 0 || v[0] == NODATA || Double.isNaN(v[0])) {
			return Double.NaN;
		}
		return v[0];
	}

	private double required(Map<Integer, double[]> series, String name) {
		double v = value(series);
		if (Double.isNaN(v)) {
			throw new IllegalArgumentException(name + " missing at " + inCurrentDate + " (station " + stationID + ")");
		}
		return v;
	}

	private double series(Map<Integer, double[]> series, double constant, String name) {
		double v = value(series);
		if (!Double.isNaN(v)) {
			return v;
		}
		if (Double.isNaN(constant)) {
			throw new IllegalArgumentException(name + " condition has no value at " + inCurrentDate);
		}
		return constant;
	}

	private static long epochSeconds(String date) {
		if (date == null) {
			throw new IllegalArgumentException("inCurrentDate is not set");
		}
		return LocalDateTime.parse(date.trim(), DATE).toEpochSecond(ZoneOffset.UTC);
	}
}
