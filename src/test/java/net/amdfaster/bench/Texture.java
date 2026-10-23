package net.amdfaster.bench;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * A decoded PNG, held as packed ARGB.
 *
 * <p>The files are the ones {@code bench/tools/make_assets.py} writes, decoded through
 * {@link ImageIO}, which is the same decoder a real texture loader ends up calling. Nothing here is
 * procedural: if the file is absent the load throws, so the benchmark cannot silently degrade into
 * drawing solid colours and calling it fast.
 */
final class Texture {

    final String name;
    final int width;
    final int height;
    /** Row-major, top-left origin, packed 0xAARRGGBB. */
    final int[] argb;

    private Texture(String name, int width, int height, int[] argb) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    static Texture load(File file, String name) throws IOException {
        if (!file.isFile()) {
            throw new IOException("missing asset, run bench/tools/make_assets.py first: " + file);
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("no PNG reader accepted " + file);
        }
        int w = image.getWidth();
        int h = image.getHeight();
        int[] pixels = new int[w * h];
        image.getRGB(0, 0, w, h, pixels, 0, w);
        if ((w & (w - 1)) != 0 || (h & (h - 1)) != 0) {
            // Wrap sampling below uses a mask, which is only a modulo when the extent is a power of two.
            throw new IOException("benchmark textures must be powers of two, " + file + " is " + w + "x" + h);
        }
        return new Texture(name, w, h, pixels);
    }

    /** Nearest-neighbour sample with wrap on both axes, which is how a repeating block texture is read. */
    int sample(float u, float v) {
        int x = ((int) Math.floor(u * this.width)) & (this.width - 1);
        int y = ((int) Math.floor(v * this.height)) & (this.height - 1);
        return this.argb[y * this.width + x];
    }

    /** Fraction of the texture that is not fully transparent, so a report can prove it is real content. */
    int coveragePercent() {
        int visible = 0;
        for (int pixel : this.argb) {
            if ((pixel >>> 24) != 0) {
                visible++;
            }
        }
        return (int) (100L * visible / this.argb.length);
    }
}
