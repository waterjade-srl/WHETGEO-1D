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
/**
 * <b>Temporary: to be evaluated for the GEOframe components.</b> Input tools
 * that give the snow+soil column a forcing coherent with GEOtop 3.0 (commit
 * 1f1fc5af) for the fidelity runs on GEOtop's own cases (VAL-1, VAL-2),
 * starting from GEOtop's input files: how GEOtop reads and steps its
 * meteorological file, its rain/snow split with the gauge corrections, the
 * shortwave on a slope with the horizon, the averaged cloud transmissivity of
 * the longwave.
 *
 * <p>
 * In the GEOframe chain these belong to the components upstream of the
 * column (OmsTimeSeriesIteratorReader, RainSnowSep, ShortWaveRadiationBalance,
 * LongWaveRadiationBalance); here they reproduce GEOtop, quirks included, so
 * that a difference between the port and GEOtop is not a difference of
 * forcing. Whether any of this moves to those components is open (LINK-1,
 * ORG-4). Every class names the GEOtop routine it follows; the formulas
 * shared with the component's own estimates
 * ({@link org.geoframe.whetgeo1d.core.snowsoil.forcing}) are reused from
 * there.
 */
package org.geoframe.whetgeo1d.core.snowsoil.fidelity;
