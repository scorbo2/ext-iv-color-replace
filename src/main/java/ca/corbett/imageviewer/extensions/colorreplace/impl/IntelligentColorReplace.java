package ca.corbett.imageviewer.extensions.colorreplace.impl;

import ca.corbett.extras.logging.Stopwatch;
import ca.corbett.imageviewer.extensions.colorreplace.IColorReplace;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Intelligent implementation of {@link IColorReplace} that replaces a source color with a target color while
 * preserving the relative shade (brightness and saturation) of each matched pixel.
 * <p>
 * The naive approach simply swaps every exact-match pixel for the target color, which looks flat and ugly in a
 * photograph: a smooth gradient of reds collapses into one solid blue. Instead, this implementation blends each
 * matched pixel from its original color toward the target color by an amount proportional to how closely it matches
 * the source color. A pixel that exactly matches the source becomes exactly the target; a slightly-off shade becomes
 * a shade that is mostly the target color but still carries a hint of its original tint. This is what lets shades of
 * the source translate into shades of the target, producing smooth, natural-looking transitions.
 * <p>
 * Matching uses a perceptually-weighted Euclidean distance in RGB space. Channels are weighted by human luminance
 * sensitivity (green matters more than blue), and the raw distance is normalized to {@code [0, 1]}. The
 * {@link Strictness} parameter maps to a maximum accepted distance (the tolerance): the stricter the match, the
 * smaller the tolerance, and the fewer pixels are considered "close enough" to be replaced.
 *
 * @author <a href="https://github.com/scorbo2">scorbo2</a>
 */
public class IntelligentColorReplace implements IColorReplace {

    /**
     * Perceived-luminance weights for the RGB channels (ITU-R BT.601). Green is weighted highest because the human
     * eye is most sensitive to it.
     */
    private static final double R_WEIGHT = 0.299D;
    private static final double G_WEIGHT = 0.587D;
    private static final double B_WEIGHT = 0.114D;

    /**
     * Largest possible weighted distance between two colors, used to normalize a raw distance to {@code [0, 1]}.
     */
    private static final double MAX_NORMALIZED_DISTANCE =
            Math.sqrt(R_WEIGHT * R_WEIGHT + G_WEIGHT * G_WEIGHT + B_WEIGHT * B_WEIGHT) * 255D;

    /**
     * Maximum normalized distance (tolerance) accepted for each strictness level.
     */
    private static final double STRICT_TOLERANCE = 0.10D;
    private static final double MEDIUM_TOLERANCE = 0.30D;
    private static final double LOOSE_TOLERANCE = 0.60D;

    @Override
    public void replace(BufferedImage srcImage, Color srcColor, Color targetColor,
                        Strictness strictness, OnComplete onComplete) {
        Stopwatch.start("IntelligentColorReplace.replace");

        final int width = srcImage.getWidth();
        final int height = srcImage.getHeight();

        // Pull every pixel out in one shot: getRGB() transparently handles any image type and is far faster than
        // looping getRGB(x, y) calls. The modified array is written back in one setRGB() at the end.
        int[] pixels = srcImage.getRGB(0, 0, width, height, null, 0, width);

        final int srcRGB = srcColor.getRGB();
        final int targetRGB = targetColor.getRGB();
        final int srcR = srcColor.getRed();
        final int srcG = srcColor.getGreen();
        final int srcB = srcColor.getBlue();
        final int tgtR = targetColor.getRed();
        final int tgtG = targetColor.getGreen();
        final int tgtB = targetColor.getBlue();
        final int tgtA = targetColor.getAlpha();

        final double tolerance = toleranceFor(strictness);

        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];

            // Fast path: an exact source match (including alpha) becomes an exact target color. This also handles
            // the EXACT strictness case, where tolerance is 0 and the blend below is skipped.
            if (rgb == srcRGB) {
                pixels[i] = targetRGB;
                continue;
            }

            pixels[i] = replacePixel(rgb, srcR, srcG, srcB, tgtR, tgtG, tgtB, tgtA, tolerance);
        }

        srcImage.setRGB(0, 0, width, height, pixels, 0, width);

        if (onComplete != null) {
            onComplete.onComplete(Stopwatch.stop("IntelligentColorReplace.replace"));
        }
    }

    /**
     * Blends the given pixel toward the target color if it is within the accepted tolerance of the source color.
     *
     * @param rgb       The source pixel, as an ARGB int.
     * @param srcR      The source color's red channel.
     * @param srcG      The source color's green channel.
     * @param srcB      The source color's blue channel.
     * @param tgtR      The target color's red channel.
     * @param tgtG      The target color's green channel.
     * @param tgtB      The target color's blue channel.
     * @param tgtA      The target color's alpha channel.
     * @param tolerance The maximum normalized distance accepted (see {@link Strictness}).
     * @return The replacement pixel, or the original {@code rgb} if it is too far from the source color to replace.
     */
    private static int replacePixel(int rgb, int srcR, int srcG, int srcB,
                                    int tgtR, int tgtG, int tgtB, int tgtA, double tolerance) {
        int pr = (rgb >> 16) & 0xFF;
        int pg = (rgb >> 8) & 0xFF;
        int pb = rgb & 0xFF;

        double distance = weightedDistance(pr, pg, pb, srcR, srcG, srcB) / MAX_NORMALIZED_DISTANCE;
        // tolerance is never negative, but skip blending entirely when it is 0 (EXACT) to avoid a divide-by-zero
        // in the blend formula below; exact matches are handled by the fast path in replace().
        if (distance > tolerance || tolerance <= 0.0) {
            return rgb;
        }

        // Blend factor: 1.0 for an exact source match (maps fully onto the target), tapering to 0.0 at the
        // tolerance edge (leaves the pixel essentially unchanged). This taper is what preserves shade.
        double blend = (tolerance - distance) / tolerance;

        int srcA = (rgb >> 24) & 0xFF;
        int na = clamp((int) Math.round(srcA + blend * (tgtA - srcA)));
        int nr = clamp((int) Math.round(pr + blend * (tgtR - pr)));
        int ng = clamp((int) Math.round(pg + blend * (tgtG - pg)));
        int nb = clamp((int) Math.round(pb + blend * (tgtB - pb)));

        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    /**
     * Returns the normalized Euclidean distance (in {@code [0, 1]}) between two colors, weighted by human
     * luminance sensitivity.
     */
    private static double weightedDistance(int r1, int g1, int b1, int r2, int g2, int b2) {
        double dr = R_WEIGHT * (r1 - r2);
        double dg = G_WEIGHT * (g1 - g2);
        double db = B_WEIGHT * (b1 - b2);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /**
     * Maps a strictness level to its maximum accepted (normalized) distance.
     */
    private static double toleranceFor(Strictness strictness) {
        return switch (strictness) {
            case EXACT -> 0.0D;
            case STRICT -> STRICT_TOLERANCE;
            case MEDIUM -> MEDIUM_TOLERANCE;
            case LOOSE -> LOOSE_TOLERANCE;
        };
    }

    /**
     * Clamps a channel value to the valid 0-255 range, in case rounding nudges it out of bounds.
     */
    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
