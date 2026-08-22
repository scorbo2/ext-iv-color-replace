package ca.corbett.imageviewer.extensions.colorreplace.impl;

import ca.corbett.extras.logging.Stopwatch;
import ca.corbett.imageviewer.extensions.colorreplace.IColorReplace;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * An intelligent implementation of {@link IColorReplace} that replaces a source color in an image with a
 * target color while preserving the perceptual "shades" of the original.
 *
 * <p>Matching works in HSB (hue, saturation, brightness) space. For each pixel the deviation from the source
 * color is measured along each HSB axis and normalized by an axis-aligned <em>ellipsoid</em> whose radii are
 * chosen according to the requested {@link IColorReplace.Strictness}. A pixel is replaced when the sum of the
 * squared, normalized deviations is at most 1 — i.e. it lies inside the ellipsoid. This lets the many shades of
 * the source color (pixels that differ only slightly in hue, saturation or brightness) be replaced too, rather
 * than requiring an exact RGB match, which is what fails on photographs.</p>
 *
 * <p>Recoloring preserves the shade relationship: a pixel's saturation and brightness are expressed as an offset
 * from the source color's, and that same offset is applied to the target color. As a result, dark red becomes
 * dark blue (not flat blue) when replacing red with blue, and a highlight of red becomes a highlight of blue. The
 * pixel's original alpha channel is preserved.</p>
 *
 * <p>HSB arrays produced by {@link Color#RGBtoHSB(int, int, int, float[])} are indexed {@code [hue, saturation,
 * brightness]}, with hue in the range {@code [0, 1]} and saturation/brightness in {@code [0, 1]}.</p>
 *
 * @author <a href="https://github.com/scorbo2">scorbo2</a>
 */
public class IntelligentColorReplace implements IColorReplace {

    /**
     * Saturation below which a color is treated as gray. For near-gray colors the hue channel is meaningless
     * ({@link Color#RGBtoHSB(int, int, int, float[])} returns an arbitrary hue), so we stop penalizing hue
     * differences below this threshold to avoid rejecting otherwise-similar pixels.
     */
    private static final double SATURATION_GRAY_THRESHOLD = 0.02;

    @Override
    public void replace(BufferedImage srcImage, Color srcColor, Color targetColor, Strictness strictness,
                        OnComplete onComplete) {
        Stopwatch.start("IntelligentColorReplace.replace");

        // Nothing to do when the colors are identical; the offset math would leave every pixel unchanged anyway.
        if (srcColor.getRGB() == targetColor.getRGB()) {
            reportCompletion(onComplete);
            return;
        }

        // Precompute the source/target HSB components once, outside the per-pixel loop.
        float[] srcHsb = new float[3];
        float[] tgtHsb = new float[3];
        Color.RGBtoHSB(srcColor.getRed(), srcColor.getGreen(), srcColor.getBlue(), srcHsb);
        Color.RGBtoHSB(targetColor.getRed(), targetColor.getGreen(), targetColor.getBlue(), tgtHsb);

        // Map the requested strictness to ellipsoid radii (hue in degrees, saturation/brightness in 0..1).
        double rHue;
        double rSat;
        double rBright;
        boolean exact;
        switch (strictness) {
            case EXACT:
                // No tolerance: only pixels identical to the source are replaced, and they become the flat target.
                rHue = 0;
                rSat = 0;
                rBright = 0;
                exact = true;
                break;
            case STRICT:
                rHue = 8;
                rSat = 0.10;
                rBright = 0.10;
                exact = false;
                break;
            case MEDIUM:
                rHue = 20;
                rSat = 0.25;
                rBright = 0.25;
                exact = false;
                break;
            default: // LOOSE
                rHue = 40;
                rSat = 0.55;
                rBright = 0.55;
                exact = false;
                break;
        }

        int width = srcImage.getWidth();
        int height = srcImage.getHeight();
        int[] line = new int[width];
        int srcRGB = srcColor.getRGB() & 0x00FFFFFF; // match on RGB only; each pixel's alpha is preserved
        int targetRGB = targetColor.getRGB() & 0x00FFFFFF;
        float[] pixelHsb = new float[3]; // reused scratch buffer to avoid per-pixel allocation

        for (int y = 0; y < height; y++) {
            // Bulk-read the row into a single int array, then bulk-write it back. This is dramatically faster
            // than calling getRGB/setRGB once per pixel, which matters for large photographic images.
            srcImage.getRGB(0, y, width, 1, line, 0, width);

            for (int x = 0; x < width; x++) {
                int argb = line[x];

                boolean match = exact
                        ? (argb & 0x00FFFFFF) == srcRGB
                        : isInEllipsoid(argb & 0x00FFFFFF, srcHsb, pixelHsb, rHue, rSat, rBright);
                if (!match) {
                    continue;
                }

                int alpha = argb >>> 24;
                int rgb = argb & 0x00FFFFFF;
                int newRgb = exact ? targetRGB : recolor(rgb, srcHsb, tgtHsb, pixelHsb);
                line[x] = (alpha << 24) | newRgb;
            }

            srcImage.setRGB(0, y, width, 1, line, 0, width);
        }

        reportCompletion(onComplete);
    }

    /**
     * Returns true if the given RGB pixel lies inside the HSB ellipsoid centered on the source color.
     *
     * @param rgb    The pixel's RGB channels (alpha bits ignored).
     * @param srcHsb The source color's HSB components.
     * @param pixelHsb Reusable scratch buffer filled with the pixel's HSB components.
     * @param rHue   The ellipsoid radius along the hue axis (degrees).
     * @param rSat   The ellipsoid radius along the saturation axis (0..1).
     * @param rBright The ellipsoid radius along the brightness axis (0..1).
     */
    private static boolean isInEllipsoid(int rgb, float[] srcHsb, float[] pixelHsb, double rHue, double rSat,
                                         double rBright) {
        Color.RGBtoHSB((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, pixelHsb);

        // Hue is only meaningful for saturated colors; for near-gray pixels its value is arbitrary, so don't
        // let it disqualify a pixel that otherwise matches on saturation and brightness.
        double hueDeviation;
        if (srcHsb[1] > SATURATION_GRAY_THRESHOLD && pixelHsb[1] > SATURATION_GRAY_THRESHOLD) {
            hueDeviation = hueDifferenceDegrees(srcHsb[0], pixelHsb[0]) / rHue;
        }
        else {
            hueDeviation = 0.0;
        }

        double saturationDeviation = Math.abs(pixelHsb[1] - srcHsb[1]) / rSat;
        double brightnessDeviation = Math.abs(pixelHsb[2] - srcHsb[2]) / rBright;

        // Inside the unit ellipsoid when the sum of squared, normalized deviations is at most 1.
        return (hueDeviation * hueDeviation)
             + (saturationDeviation * saturationDeviation)
             + (brightnessDeviation * brightnessDeviation) <= 1.0;
    }

    /**
     * Translates a matched pixel into the target color's shade: the target's hue is used, while the pixel's
     * saturation and brightness are offset from the source color and applied to the target. This is what turns
     * "dark red" into "dark blue" rather than a flat target blue.
     *
     * @param rgb    The pixel's RGB channels (alpha bits ignored).
     * @param srcHsb The source color's HSB components.
     * @param tgtHsb The target color's HSB components.
     * @param pixelHsb Reusable scratch buffer filled with the pixel's HSB components.
     */
    private static int recolor(int rgb, float[] srcHsb, float[] tgtHsb, float[] pixelHsb) {
        Color.RGBtoHSB((rgb >>> 16) & 0xFF, (rgb >>> 8) & 0xFF, rgb & 0xFF, pixelHsb);

        float newSaturation = clamp(tgtHsb[1] + (pixelHsb[1] - srcHsb[1]));
        float newBrightness = clamp(tgtHsb[2] + (pixelHsb[2] - srcHsb[2]));

        return Color.getHSBColor(tgtHsb[0], newSaturation, newBrightness).getRGB() & 0x00FFFFFF;
    }

    /**
     * Returns the smaller of the two angular differences between two hues (in degrees), correctly handling the
     * 0°/360° wrap-around at the ends of the hue wheel.
     */
    private static double hueDifferenceDegrees(double h1, double h2) {
        double diff = Math.abs(h1 - h2) * 360.0; // HSB hue is normalized to 0..1; convert to degrees
        return diff > 180.0 ? 360.0 - diff : diff;
    }

    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    /**
     * Invokes the completion callback (if any) with the elapsed time reported by the shared stopwatch.
     */
    private void reportCompletion(OnComplete onComplete) {
        if (onComplete != null) {
            onComplete.onComplete(Stopwatch.stop("IntelligentColorReplace.replace"));
        }
    }
}
