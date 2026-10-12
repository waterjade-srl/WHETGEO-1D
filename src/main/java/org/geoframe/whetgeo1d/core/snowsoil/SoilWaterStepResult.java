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

/**
 * Outcome of one Richards step of the soil, as masses per unit area over the
 * whole step [kg m-2].
 *
 * @param suctions      ψ at the end of the step [m], bottom first
 * @param faceWater     water through each face in the step, positive upwards;
 *                      face i is the bottom face of cell i, face n the soil
 *                      surface
 * @param infiltration  water entering at the top
 * @param drainage      water leaving at the bottom, positive out
 * @param storageChange change of the water stored in the soil
 * @param balanceError  storageChange - infiltration + drainage, the sum of
 *                      WHETGEO's {@code errorVolume} over the substeps
 * @param runoff        water the soil could not take, input - infiltration,
 *                      when the surface was held saturated; zero otherwise
 */
public record SoilWaterStepResult(double[] suctions, double[] faceWater, double infiltration, double drainage,
		double storageChange, double balanceError, double runoff) {
}
