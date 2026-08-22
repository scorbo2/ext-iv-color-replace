package ca.corbett.imageviewer.extensions.colorreplace.impl;

import ca.corbett.extras.logging.Stopwatch;
import ca.corbett.imageviewer.extensions.colorreplace.IColorReplace;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Intelligent implementation of {@link IColorReplace} that does all of its
 * color math in HSB (hue / saturation / brightness) space, because both
 * "is this pixel a shade of the source color?" and "what shade is it, exactly?"
 * are questions about hue and brightness relationships that plain RGB value
 * comparison simply cannot answer (see {@link NaiveColorReplace} for the RGB
 * baseline this one corrects).
 *
 * <h2>Matching</h2>
 * A pixel is a match when the sum of its squared, normalized deviations in
 * hue, saturation and brightness from the source color is {@code <= 1}, i.e.
 * the deviations jointly fit inside an ellipsoid whose radii are set by the
 * requested {@link Strictness}:
 *
 * <table>
 * <tr><th>Strictness</th><th>Hue radius</th><th>Saturation radius</th><th>Brightness radius</th></tr>
 * <tr><td>STRICT</td><td>12&deg;</td><td>&plusmn;0.25</td><td>&plusmn;0.25</td></tr>
 * <tr><td>MEDIUM</td><td>25&deg;</td><td>&plusmn;0.40</td><td>&plusmn;0.40</td></tr>
 * <tr><td>LOOSE</td><td>45&deg;</td><td>&plusmn;0.55</td><td>&plusmn;0.65</td></tr>
 * </table>
 *
 * The hue deviation is weighted by {@code min(pixelSaturation, sourceSaturation)}.
 * Hue is effectively meaningless for near-achromatic (grayish) colors, so the
 * less chromatic either color is, the less its hue deviation counts. Matching a
 * gray source therefore depends on brightness proximity alone.
 *
 * A {@code score <= 1} test instead of three independent caps means a pixel may
 * trade a little hue deviation for a bit of brightness deviation (and vice
 * versa) - gradients at object edges stay coherent instead of shattering into
 * "hue ok but brightness not" noise.
 *
 * <h2>Recoloring (shades become shades)</h2>
 * For each matching pixel, its saturation and brightness <em>relative to the
 * source color</em> are transferred onto the target color:
 * <ul>
 * <li>a dark red next to a red source becomes a dark <em>blue</em> (not a flat
 * blue) when the target color is blue;</li>
 * <li>a washed-out highlight becomes a bright, low-saturation version of the
 * target;</li>
 * <li>the pixel's small hue deviation from the source is transferred onto the
 * target hue (damped by the same saturation factor), preserving local hue
 * texture inside the replaced region.</li>
 * </ul>
 *
 * The pixel's alpha channel is always preserved - replacement changes color,
 * not transparency.
 *
 * <h2>Known limitations</h2>
 * <ul>
 * <li>Replacing achromatic sources (gray, black, white) can only discriminate
 * by brightness, so large regions of similar-brightness pixels of <em>any</em>
 * hue will be replaced. That is inherent to gray - hue carries no information
 * there - so expect LOOSE gray replacements to be aggressive.</li>
 * <li>Hue is noisy in the presence of jpeg compression artifacts, especially
 * near achromatic colors; some jitter along match boundaries is normal.</li>
 * <li>The replacement is a pure per-pixel function; no spatial smoothing is
 * applied, so hard color edges in the original image will remain hard.</li>
 * </ul>
 */
public class IntelligentColorReplace implements IColorReplace {

    private static final String TIMER_NAME = "IntelligentColorReplace.replace";
    /**
     * Below this saturation/brightness a color is treated as achromatic,
     * because hue (or a brightness ratio) becomes numerically meaningless.
     */
    private static final float ACHROMATIC_EPSILON = 0.02f;

    private static final int KEEP_ALPHA_MASK = 0xFF000000;
    private static final int RGB_ONLY_MASK = 0x00FFFFFF;

    /**
     * Per-strictness match ellipsoid radii: maximum hue deviation in degrees,
     * and saturation/brightness deviation ranges in 0..1. These are heuristics
     * tuned for typical photographs; adjust here if taste changes.
     */
    private record Tolerances(float hueTolDeg, float satTol, float valTol) {
    }

    private static final Tolerances STRICT_TOLERANCES = new Tolerances(12f, 0.25f, 0.25f);
    private static final Tolerances MEDIUM_TOLERANCES = new Tolerances(25f, 0.40f, 0.40f);
    private static final Tolerances LOOSE_TOLERANCES  = new Tolerances(45f, 0.55f, 0.65f);

    /**
     * Replaces matching pixels in-place. Runs synchronously and always invokes
     * {@code onComplete} (unless null) once finished, even on early return.
     */
    @Override
    public void replace(BufferedImage srcImage, Color srcColor, Color targetColor, Strictness strictness, OnComplete onComplete) {
        if (srcImage == null) {
            throw new IllegalArgumentException("srcImage must not be null");
        }
        if (srcColor == null) {
            throw new IllegalArgumentException("srcColor must not be null");
        }
        if (targetColor == null) {
            throw new IllegalArgumentException("targetColor must not be null");
        }
        if (strictness == null) {
            throw new IllegalArgumentException("strictness must not be null");
        }

        Stopwatch.start(TIMER_NAME);
        try {
            if (srcColor.equals(targetColor)) {
                // Replacing a color with itself must be a guaranteed no-op. The
                // shade mapping below is mathematically the identity in this case,
                // but floating-point round-trips through HSB could still nudge
                // channels by one count, mutating an image that should not change.
                return;
            }
            if (srcImage.getWidth() <= 0 || srcImage.getHeight() <= 0) {
                return; // nothing to replace, but we still report completion
            }
            switch (strictness) {
                case EXACT -> replaceExact(srcImage, srcColor.getRGB(), targetColor.getRGB());
                case STRICT -> replaceHsb(srcImage, srcColor, targetColor, STRICT_TOLERANCES);
                case MEDIUM -> replaceHsb(srcImage, srcColor, targetColor, MEDIUM_TOLERANCES);
                case LOOSE -> replaceHsb(srcImage, srcColor, targetColor, LOOSE_TOLERANCES);
            }
        }
        finally {
            if (onComplete != null) {
                onComplete.onComplete(Stopwatch.stop(TIMER_NAME));
            }
        }
    }

    // ------------------------------------------------------------------
    // Replacement strategies
    // ------------------------------------------------------------------

    /**
     * EXACT semantics: full 32-bit ARGB value comparison, the same rule
     * {@link NaiveColorReplace} uses. The replaced pixel keeps its own alpha.
     */
    private static void replaceExact(BufferedImage image, int sourceRgb, int targetRgb) {
        int width = image.getWidth();
        int[] pixels = image.getRGB(0, 0, width, image.getHeight(), new int[width * image.getHeight()], 0, width);
        int targetWithoutAlpha = targetRgb & RGB_ONLY_MASK;
        for (int i = 0; i < pixels.length; i++) {
            if (pixels[i] == sourceRgb) {
                pixels[i] = targetWithoutAlpha | (pixels[i] & KEEP_ALPHA_MASK);
            }
        }
        image.setRGB(0, 0, width, image.getHeight(), pixels, 0, width);
    }

    /**
     * HSB-space replacement: every pixel that matches the source color within
     * the given tolerances is recolor-mapped onto a shade of the target color.
     */
    private static void replaceHsb(BufferedImage image, Color source, Color target, Tolerances tolerances) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, new int[width * height], 0, width);

        float[] sourceHsb = new float[3];
        float[] targetHsb = new float[3];
        rgbToHsb(source.getRGB(), sourceHsb);
        rgbToHsb(target.getRGB(), targetHsb);

        float[] pixelHsb = new float[3]; // reused across the whole scan; we're single-threaded
        for (int i = 0; i < pixels.length; i++) {
            rgbToHsb(pixels[i], pixelHsb);
            if (isMatch(pixelHsb, sourceHsb, tolerances)) {
                pixels[i] = recolor(pixels[i], pixelHsb, sourceHsb, targetHsb);
            }
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
    }

    // ------------------------------------------------------------------
    // Matching
    // ------------------------------------------------------------------

    /**
     * @param pixel      HSB of the candidate pixel
     * @param source     HSB of the source color
     * @param tolerances strictness-dependent radii of the match ellipsoid
     * @return whether the pixel counts as a shade of the source color
     */
    static boolean isMatch(float[] pixel, float[] source, Tolerances tolerances) {
        float dSat = Math.abs(pixel[1] - source[1]);
        float dVal = Math.abs(pixel[2] - source[2]);
        // Either deviation alone would already push the squared sum past 1:
        if (dSat > tolerances.satTol() || dVal > tolerances.valTol()) {
            return false;
        }

        float hueWeight = Math.min(pixel[1], source[1]);
        if (hueWeight <= ACHROMATIC_EPSILON) {
            // At least one color is effectively achromatic, so hue carries no
            // information; match is based on saturation/brightness only.
            float score = squared(dSat / tolerances.satTol())
                    + squared(dVal / tolerances.valTol());
            return score <= 1.0f;
        }

        float weightedHueDiff = circularHueDiff(pixel[0], source[0]) * hueWeight;
        float score = squared(weightedHueDiff / tolerances.hueTolDeg())
                + squared(dSat / tolerances.satTol())
                + squared(dVal / tolerances.valTol());
        return score <= 1.0f;
    }

    // ------------------------------------------------------------------
    // Recoloring
    // ------------------------------------------------------------------

    /**
     * Maps a matching pixel onto the target color while preserving the pixel's
     * saturation and brightness <em>relative to the source</em>. That is what
     * makes shades of the source become shades of the target instead of a
     * uniform splotch.
     *
     * @return new 32-bit ARGB value with the pixel's original alpha preserved
     */
    static int recolor(int pixel, float[] pixelHsb, float[] sourceHsb, float[] targetHsb) {
        // The pixel's local hue deviation from the source is carried over onto the
        // target hue, damped by both colors' saturation so that near-achromatic
        // pixels don't drag the hue randomly (their hue is just noise).
        float hueWeight = Math.min(pixelHsb[1], sourceHsb[1]);
        float deltaHue = ((pixelHsb[0] - sourceHsb[0] + 540f) % 360f) - 180f; // signed shortest (-180..180)
        float newHue = targetHsb[0] + deltaHue * hueWeight;

        float newSat;
        if (sourceHsb[1] <= ACHROMATIC_EPSILON) {
            // Achromatic source: brightness is the only shading channel, so the
            // whole region takes on the target's full chroma.
            newSat = targetHsb[1];
        }
        else {
            // Scale the target's chroma by how saturated this pixel is,
            // relative to the source.
            newSat = targetHsb[1] * (pixelHsb[1] / sourceHsb[1]);
        }

        float newVal;
        if (sourceHsb[2] <= ACHROMATIC_EPSILON) {
            // A black source has no brightness ratio to speak of; scale the
            // target by the pixel's own brightness instead.
            newVal = targetHsb[2] * pixelHsb[2];
        }
        else {
            // Scale the target's brightness by how bright this pixel is,
            // relative to the source.
            newVal = targetHsb[2] * (pixelHsb[2] / sourceHsb[2]);
        }

        return hsbToRgb(newHue, newSat, newVal, pixel & KEEP_ALPHA_MASK);
    }

    // ------------------------------------------------------------------
    // Color conversions (allocation-free, reusable scratch array)
    // ------------------------------------------------------------------

    /**
     * Converts a 32-bit ARGB value to HSB (alpha is ignored).
     *
     * @param rgb pixel value
     * @param out scratch array, receives [0] hue in degrees 0..360, [1] saturation 0..1,
     *            [2] brightness 0..1. Hue is 0 for achromatic colors (meaningless).
     */
    static void rgbToHsb(int rgb, float[] out) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        int delta = max - min;

        out[2] = max / 255f;                              // brightness
        out[1] = max == 0 ? 0f : delta / (float) max;     // saturation

        if (delta == 0) {
            out[0] = 0f;                                  // achromatic: hue meaningless
            return;
        }

        float hue;
        if (max == r) {
            hue = 60f * ((g - b) / (float) delta % 6f);
        }
        else if (max == g) {
            hue = 60f * ((b - r) / (float) delta + 2f);
        }
        else {
            hue = 60f * ((r - g) / (float) delta + 4f);
        }
        out[0] = hue < 0f ? hue + 360f : hue;
    }

    /**
     * Converts HSB to a 32-bit ARGB value. Inputs are clamped into their valid
     * ranges, so callers need not be careful about overshoot.
     */
    static int hsbToRgb(float hue, float sat, float val, int alpha) {
        float s = clamp01(sat);
        float v = clamp01(val);
        float h = ((hue % 360f) + 360f) % 360f;

        float chroma = v * s;
        float sector = h / 60f;
        float secondary = chroma * (1f - Math.abs(sector % 2f - 1f));
        float lightShift = v - chroma;

        float r;
        float g;
        float b;
        if (sector < 1f) {
            r = chroma; g = secondary; b = 0f;
        }
        else if (sector < 2f) {
            r = secondary; g = chroma; b = 0f;
        }
        else if (sector < 3f) {
            r = 0f; g = chroma; b = secondary;
        }
        else if (sector < 4f) {
            r = 0f; g = secondary; b = chroma;
        }
        else if (sector < 5f) {
            r = secondary; g = 0f; b = chroma;
        }
        else {
            r = chroma; g = 0f; b = secondary;
        }

        return (alpha & KEEP_ALPHA_MASK)
                | ((int) Math.round((r + lightShift) * 255f) << 16)
                | ((int) Math.round((g + lightShift) * 255f) << 8)
                | ((int) Math.round((b + lightShift) * 255f));
    }

    // ------------------------------------------------------------------
    // Small math helpers
    // ------------------------------------------------------------------

    /**
     * Shortest angular distance between two hues, in degrees (0..180).
     */
    static float circularHueDiff(float h1, float h2) {
        float d = Math.abs(h1 - h2) % 360f;
        return d > 180f ? 360f - d : d;
    }

    private static float squared(float v) {
        return v * v;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
