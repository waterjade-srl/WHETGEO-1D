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
package org.geoframe.whetgeo1d.io.snowsoil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SHA-256 of the input files, for the attributes of a {@link RunHeader}
 * (VAL-7): {@code sha256sum} reproduces the same hex string.
 */
public final class InputHashes {

	private InputHashes() {
	}

	/** Hex SHA-256 of the file. */
	public static String sha256(Path file) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
		try (InputStream in = Files.newInputStream(file)) {
			byte[] buffer = new byte[1 << 16];
			for (int n; (n = in.read(buffer)) > 0;) {
				digest.update(buffer, 0, n);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	/**
	 * One attribute per input, in the order given: {@code input.<name>} = the
	 * path, {@code sha256.<name>} = its hash.
	 */
	public static Map<String, String> attributes(Map<String, Path> inputs) throws IOException {
		Map<String, String> result = new LinkedHashMap<>();
		for (Map.Entry<String, Path> e : inputs.entrySet()) {
			result.put("input." + e.getKey(), e.getValue().toString());
			result.put("sha256." + e.getKey(), sha256(e.getValue()));
		}
		return result;
	}
}
