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
 * Matching measures distance in <em>HSB</em> space rather than RGB. Because a "shade" of a color shares its hue,
 * grouping by hue (with wraparound, so red sits between violet and orange) keeps all the shades of the source
 * together while pushing unrelated hues far away. Saturation and value differences then capture how far a shade is
 * from the pure source color. This separates "shades of the source" from "background" far more cleanly than an RGB
 * distance can, which keeps the strictness settings from having to thread a fragile narrow window. The raw distance
 * is weighted (hue dominates; saturation/value describe the shade) and normalized to {@code [0, 1]}. The
 * {@link Strictness} parameter maps to a maximum accepted distance (the tolerance): the stricter the match, the
 * smaller the tolerance, and the fewer pixels are considered "close enough" to be replaced.
 *
 * @author <a href="https://github.com/scorbo2">scorbo2</a>
 */
public class IntelligentColorReplace implements IColorReplace {

    /**
     * Relative importance of each HSB component when measuring how far a pixel is from the source color. Hue
     * dominates because it defines the color family; saturation and value (which describe the shade) matter less,
     * so lighter/darker versions of the source still match.
     */
    private static final double HUE_WEIGHT = 1.0D;
    private static final double SATURATION_WEIGHT = 0.75D;
    private static final double VALUE_WEIGHT = 0.75D;

    /**
     * Largest possible weighted HSB distance, used to normalize a raw distance to {@code [0, 1]}.
     */
    private static final double MAX_NORMALIZED_DISTANCE =
            Math.sqrt(HUE_WEIGHT * HUE_WEIGHT + SATURATION_WEIGHT * SATURATION_WEIGHT + VALUE_WEIGHT * VALUE_WEIGHT);

    /**
     * Maximum normalized distance (tolerance) accepted for each strictness level. These were calibrated so that
     * STRICT captures the core shades of a source, MEDIUM also picks up nearby hues (e.g. pink and orange for a red
     * source) without reaching unrelated background colors, and LOOSE casts a wide net.
     */
    private static final double STRICT_TOLERANCE = 0.20D;
    private static final double MEDIUM_TOLERANCE = 0.30D;
    private static final double LOOSE_TOLERANCE = 0.50D;

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

        // Source color in HSB, computed once. Hue is compared with wraparound, so achromatic sources (gray/black/
        // white, which report hue 0) still work: they differ from a colored source in saturation/value instead.
        float[] srcHsb = Color.RGBtoHSB(srcColor.getRed(), srcColor.getGreen(), srcColor.getBlue(), null);
        final float sh = srcHsb[0];
        final float ss = srcHsb[1];
        final float sv = srcHsb[2];

        final int tgtR = targetColor.getRed();
        final int tgtG = targetColor.getGreen();
        final int tgtB = targetColor.getBlue();
        final int tgtA = targetColor.getAlpha();

        final double tolerance = toleranceFor(strictness);

        // Reused so we don't allocate a fresh array per pixel (this runs on every preview update).
        float[] pixelHsb = new float[3];

        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];

            // Fast path: an exact source match (including alpha) becomes an exact target color. This also handles
            // the EXACT strictness case, where tolerance is 0 and the blend below is skipped.
            if (rgb == srcRGB) {
                pixels[i] = targetRGB;
                continue;
            }

            pixels[i] = replacePixel(rgb, sh, ss, sv, tgtR, tgtG, tgtB, tgtA, tolerance, pixelHsb);
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
     * @param sh        The source color's hue.
     * @param ss        The source color's saturation.
     * @param sv        The source color's value (brightness).
     * @param tgtR      The target color's red channel.
     * @param tgtG      The target color's green channel.
     * @param tgtB      The target color's blue channel.
     * @param tgtA      The target color's alpha channel.
     * @param tolerance The maximum normalized distance accepted (see {@link Strictness}).
     * @param hsb       Reusable scratch array for the pixel's HSB values.
     * @return The replacement pixel, or the original {@code rgb} if it is too far from the source color to replace.
     */
    private static int replacePixel(int rgb, float sh, float ss, float sv,
                                    int tgtR, int tgtG, int tgtB, int tgtA,
                                    double tolerance, float[] hsb) {
        int pr = (rgb >> 16) & 0xFF;
        int pg = (rgb >> 8) & 0xFF;
        int pb = rgb & 0xFF;

        double distance = hsbDistance(pr, pg, pb, sh, ss, sv, hsb);
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
     * Returns the normalized {@code [0, 1]} distance between a pixel and the source color in HSB space. Hue is
     * compared with wraparound (red sits between violet and orange); saturation and value differences capture how
     * different a shade is. Achromatic pixels (saturation 0) have an undefined hue, so they are distinguished by
     * saturation/value instead, which keeps gray/black/white from being mistaken for a colored source. The scratch
     * array receives the pixel's HSB values to avoid per-pixel allocation.
     */
    private static double hsbDistance(int r, int g, int b, float sh, float ss, float sv, float[] hsb) {
        Color.RGBtoHSB(r, g, b, hsb);
        float hd = Math.abs(hsb[0] - sh);
        hd = Math.min(hd, 1 - hd);   // wraparound: hue is a circle, so the shortest way around wins
        hd /= 0.5f;                  // normalize wrapped hue diff from [0, 0.5] to [0, 1]
        float sd = Math.abs(hsb[1] - ss);
        float vd = Math.abs(hsb[2] - sv);

        double raw = Math.sqrt(
                (HUE_WEIGHT * hd) * (HUE_WEIGHT * hd)
                + (SATURATION_WEIGHT * sd) * (SATURATION_WEIGHT * sd)
                + (VALUE_WEIGHT * vd) * (VALUE_WEIGHT * vd));
        return raw / MAX_NORMALIZED_DISTANCE;
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
