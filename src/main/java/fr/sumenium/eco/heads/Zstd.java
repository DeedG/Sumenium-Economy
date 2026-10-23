package fr.sumenium.eco.heads;

import io.airlift.compress.zstd.ZstdDecompressor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Décompression zstd en Java pur (aircompressor, inclus dans le jar). */
final class Zstd {

	private Zstd() {}

	static byte[] decompress(byte[] in) throws Exception {
		long size = ZstdDecompressor.getDecompressedSize(in, 0, in.length);
		if (size >= 0 && size < Integer.MAX_VALUE) {
			byte[] out = new byte[(int) size];
			int n = new ZstdDecompressor().decompress(in, 0, in.length, out, 0, out.length);
			if (n == out.length) return out;
			byte[] cut = new byte[n];
			System.arraycopy(out, 0, cut, 0, n);
			return cut;
		}
		// taille absente de l'en-tête : décompression en flux
		Class<?> c = Class.forName("io.airlift.compress.zstd.ZstdInputStream");
		try (InputStream z = (InputStream) c.getConstructor(InputStream.class).newInstance(new ByteArrayInputStream(in))) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			z.transferTo(out);
			return out.toByteArray();
		}
	}
}
