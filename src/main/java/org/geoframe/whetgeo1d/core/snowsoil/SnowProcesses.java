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

import org.geoframe.whetgeo1d.snow.CompactionModel;
import org.geoframe.whetgeo1d.snow.GeotopCompaction;
import org.geoframe.whetgeo1d.snow.LiquidWaterPercolation;
import org.geoframe.whetgeo1d.snow.MassCriterionRestratification;
import org.geoframe.whetgeo1d.snow.RestratificationPolicy;
import org.geoframe.whetgeo1d.snow.SnowEquationOfState;
import org.geoframe.whetgeo1d.snow.SurfaceSnowAge;
import org.geoframe.whetgeo1d.snow.TopCellThicknessCap;
import org.geoframe.whetgeo1d.snow.surface.MoninObukhov;
import org.geoframe.whetgeo1d.snow.surface.SnowAlbedo;
import org.geoframe.whetgeo1d.snow.surface.SnowSublimation;
import org.geoframe.whetgeo1d.snow.surface.SurfaceEnergyBalance;

/**
 * The snowpack operators of a {@link SnowSoilSolver}, with the two sets of
 * defaults of the specification (§1, §6.4).
 *
 * @param equationOfState       U(T; w) of the snow, the same object the heat
 *                              driver uses
 * @param surfaceBalance        options of the surface energy balance
 * @param turbulence            Monin–Obukhov resistances
 * @param albedo                two-band albedo
 * @param restoringSnowfall     snowfall that restores the fresh-snow albedo
 *                              [kg m-2]
 * @param compaction            settling of the cells
 * @param slopeAngle            terrain slope [rad]
 * @param percolation           bucket percolation
 * @param sublimationThickness  thickness rule of the vapour exchange
 * @param restratification      merge and split of the cells
 * @param precipitation         temperature of rain and fresh snow (A18)
 * @param surfaceCoupling       how the surface balance enters the heat step
 * @param thinSnow              GEOtop's soil roughness and emissivity under a
 *                              thin pack ({@link ThinSnow}); fidelity only
 * @param skin                  the skin node of the surface balance, over snow
 *                              and over bare soil ({@link SkinNode}, SURF-1/2)
 */
public record SnowProcesses(SnowEquationOfState equationOfState, SurfaceEnergyBalance.Parameters surfaceBalance,
		MoninObukhov turbulence, SnowAlbedo albedo, double restoringSnowfall, CompactionModel compaction,
		double slopeAngle, LiquidWaterPercolation percolation, SnowSublimation.ThicknessRule sublimationThickness,
		RestratificationPolicy restratification, PrecipitationTemperature precipitation,
		SurfaceCoupling surfaceCoupling, ThinSnow thinSnow, SkinNode skin, ShortwavePenetration shortwavePenetration) {

	/** Processes without shortwave penetration ({@link ShortwavePenetration#NONE}). */
	public SnowProcesses(SnowEquationOfState equationOfState, SurfaceEnergyBalance.Parameters surfaceBalance,
			MoninObukhov turbulence, SnowAlbedo albedo, double restoringSnowfall, CompactionModel compaction,
			double slopeAngle, LiquidWaterPercolation percolation, SnowSublimation.ThicknessRule sublimationThickness,
			RestratificationPolicy restratification, PrecipitationTemperature precipitation,
			SurfaceCoupling surfaceCoupling, ThinSnow thinSnow, SkinNode skin) {
		this(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction, slopeAngle,
				percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, thinSnow, skin,
				ShortwavePenetration.NONE);
	}

	/** Processes without a skin node ({@link SkinNode#NONE}). */
	public SnowProcesses(SnowEquationOfState equationOfState, SurfaceEnergyBalance.Parameters surfaceBalance,
			MoninObukhov turbulence, SnowAlbedo albedo, double restoringSnowfall, CompactionModel compaction,
			double slopeAngle, LiquidWaterPercolation percolation, SnowSublimation.ThicknessRule sublimationThickness,
			RestratificationPolicy restratification, PrecipitationTemperature precipitation,
			SurfaceCoupling surfaceCoupling, ThinSnow thinSnow) {
		this(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction, slopeAngle,
				percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, thinSnow,
				SkinNode.NONE);
	}

	/** Processes without a thin-snow regime and without a skin node. */
	public SnowProcesses(SnowEquationOfState equationOfState, SurfaceEnergyBalance.Parameters surfaceBalance,
			MoninObukhov turbulence, SnowAlbedo albedo, double restoringSnowfall, CompactionModel compaction,
			double slopeAngle, LiquidWaterPercolation percolation, SnowSublimation.ThicknessRule sublimationThickness,
			RestratificationPolicy restratification, PrecipitationTemperature precipitation,
			SurfaceCoupling surfaceCoupling) {
		this(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction, slopeAngle,
				percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, ThinSnow.none());
	}

	public SnowProcesses {
		Objects.requireNonNull(equationOfState, "equationOfState");
		Objects.requireNonNull(surfaceBalance, "surfaceBalance");
		Objects.requireNonNull(turbulence, "turbulence");
		Objects.requireNonNull(albedo, "albedo");
		Objects.requireNonNull(compaction, "compaction");
		Objects.requireNonNull(percolation, "percolation");
		Objects.requireNonNull(sublimationThickness, "sublimationThickness");
		Objects.requireNonNull(restratification, "restratification");
		Objects.requireNonNull(precipitation, "precipitation");
		Objects.requireNonNull(surfaceCoupling, "surfaceCoupling");
		Objects.requireNonNull(thinSnow, "thinSnow");
		Objects.requireNonNull(skin, "skin");
		Objects.requireNonNull(shortwavePenetration, "shortwavePenetration");
		if (!(restoringSnowfall > 0.0)) {
			throw new IllegalArgumentException("restoringSnowfall must be positive, got " + restoringSnowfall);
		}
		if (!Double.isFinite(slopeAngle)) {
			throw new IllegalArgumentException("slopeAngle must be finite, got " + slopeAngle);
		}
	}

	/**
	 * Fidelity configuration, GEOtop 3.0 defaults: GEOtop's balance and
	 * turbulence, albedo, compaction, rate-limited percolation, mass criterion
	 * with no cap, sublimation at constant thickness, precipitation as GEOtop;
	 * flat ground; surface balance implicit in the surface temperature, as
	 * GEOtop solves it; GEOtop's thin-snow regime ({@link ThinSnow#geotop()});
	 * GEOtop's skin node ({@link SkinNode#GEOTOP}).
	 *
	 * @param windHeight        height of the wind measurement [m]
	 * @param temperatureHeight height of the air temperature measurement [m]
	 */
	public static SnowProcesses geotop(SnowEquationOfState eos, double windHeight, double temperatureHeight) {
		return new SnowProcesses(eos, SurfaceEnergyBalance.Parameters.geotop(),
				new MoninObukhov(MoninObukhov.Parameters.geotop(windHeight, temperatureHeight)),
				new SnowAlbedo(SnowAlbedo.Parameters.geotopDefaults()), SurfaceSnowAge.DEFAULT_RESTORING_SNOWFALL,
				new GeotopCompaction(GeotopCompaction.Parameters.geotopDefaults(), eos), 0.0,
				new LiquidWaterPercolation(LiquidWaterPercolation.Parameters.geotop(), eos),
				SnowSublimation.ThicknessRule.UNCHANGED,
				new MassCriterionRestratification(MassCriterionRestratification.Parameters.geotopDefaults(), eos),
				PrecipitationTemperature.GEOTOP, SurfaceCoupling.implicit(), ThinSnow.geotop(), SkinNode.GEOTOP,
				ShortwavePenetration.GEOTOP);
	}

	/**
	 * Recommended configuration as far as it is implemented: phase-consistent
	 * balance, r_v capped, compaction with no minimum ice per cell, percolation
	 * without the rate limiter, GEOtop's mass
	 * criterion with the 2 cm cap on the top cell, precipitation at the wet-bulb
	 * temperature, the skin node held at 0 °C over snow ({@link SkinNode#CAPPED},
	 * SURF-2). The open choices of document 008 keep GEOtop's behaviour
	 * (thickness unchanged by sublimation).
	 */
	public static SnowProcesses recommended(SnowEquationOfState eos, double windHeight, double temperatureHeight) {
		return new SnowProcesses(eos, SurfaceEnergyBalance.Parameters.recommended(),
				new MoninObukhov(MoninObukhov.Parameters.recommended(windHeight, temperatureHeight)),
				new SnowAlbedo(SnowAlbedo.Parameters.geotopDefaults()), SurfaceSnowAge.DEFAULT_RESTORING_SNOWFALL,
				new GeotopCompaction(GeotopCompaction.Parameters.recommended(), eos), 0.0,
				new LiquidWaterPercolation(LiquidWaterPercolation.Parameters.recommended(), eos),
				SnowSublimation.ThicknessRule.UNCHANGED,
				new TopCellThicknessCap(
						new MassCriterionRestratification(MassCriterionRestratification.Parameters.geotopDefaults(), eos),
						TopCellThicknessCap.DEFAULT_MAX_THICKNESS, eos),
				PrecipitationTemperature.WET_BULB, SurfaceCoupling.implicit(), ThinSnow.none(), SkinNode.CAPPED,
				// SNOW-7: COMPOUNDED agreed, off for now: with the 2 cm surface cell it does not converge in the
				// time step at hourly steps (the surface cell melts under a cold skin); to be revised
				ShortwavePenetration.NONE);
	}

	/** Same processes with another restratification policy. */
	public SnowProcesses withRestratification(RestratificationPolicy policy) {
		return new SnowProcesses(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction,
				slopeAngle, percolation, sublimationThickness, policy, precipitation, surfaceCoupling, thinSnow, skin,
				shortwavePenetration);
	}

	/** Same processes with another surface coupling. */
	public SnowProcesses withSurfaceCoupling(SurfaceCoupling coupling) {
		return new SnowProcesses(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction,
				slopeAngle, percolation, sublimationThickness, restratification, precipitation, coupling, thinSnow, skin,
				shortwavePenetration);
	}

	/** Same processes with another compaction. */
	public SnowProcesses withCompaction(CompactionModel model) {
		return new SnowProcesses(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, model,
				slopeAngle, percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, thinSnow,
				skin, shortwavePenetration);
	}

	/** Same processes with another thin-snow regime. */
	public SnowProcesses withThinSnow(ThinSnow regime) {
		return new SnowProcesses(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction,
				slopeAngle, percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, regime, skin,
				shortwavePenetration);
	}

	/** Same processes with another skin node (SURF-1/2; {@link SkinNode#NONE} for sensitivity runs). */
	public SnowProcesses withSkin(SkinNode node) {
		return new SnowProcesses(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction,
				slopeAngle, percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, thinSnow,
				node, shortwavePenetration);
	}

	/** Same processes on a terrain slope [rad] (the overburden of the compaction). */
	public SnowProcesses withSlopeAngle(double angle) {
		return new SnowProcesses(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction,
				angle, percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, thinSnow,
				skin, shortwavePenetration);
	}

	/** Same processes with another shortwave penetration (SNOW-7; {@link ShortwavePenetration#NONE} for sensitivity runs). */
	public SnowProcesses withShortwavePenetration(ShortwavePenetration penetration) {
		return new SnowProcesses(equationOfState, surfaceBalance, turbulence, albedo, restoringSnowfall, compaction,
				slopeAngle, percolation, sublimationThickness, restratification, precipitation, surfaceCoupling, thinSnow,
				skin, penetration);
	}
}
