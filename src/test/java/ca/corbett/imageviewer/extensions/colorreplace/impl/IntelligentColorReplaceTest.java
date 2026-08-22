package ca.corbett.imageviewer.extensions.colorreplace.impl;

import ca.corbett.imageviewer.extensions.colorreplace.IColorReplace;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link IntelligentColorReplace}. The tests exercise the public {@code replace()} entry point on
 * small in-memory images, since that is exactly how the production dialog drives the implementation.
 */
class IntelligentColorReplaceTest {

    private static final Color RED = new Color(255, 0, 0);
    private static final Color BLUE = new Color(0, 0, 255);
    private static final Color GREEN = new Color(0, 255, 0);

    private final IntelligentColorReplace replacer = new IntelligentColorReplace();

    /**
     * Renders a single-pixel image, runs the replacement, and returns the resulting pixel as a {@link Color}.
     */
    private Color replaceOnePixel(Color src, Color target, IColorReplace.Strictness strictness, Color pixel) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixel.getRGB());
        replacer.replace(image, src, target, strictness, null);
        return new Color(image.getRGB(0, 0), true);
    }

    /**
     * A pixel that exactly equals the source color must map exactly onto the target color, at any strictness.
     */
    @Test
    void replace_withExactSourcePixel_becomesTargetColor() {
        // WHEN a pixel exactly matching the source (red) is replaced with blue at MEDIUM strictness:
        Color result = replaceOnePixel(RED, BLUE, IColorReplace.Strictness.MEDIUM, RED);

        // THEN it becomes exactly blue:
        assertEquals(BLUE.getRGB(), result.getRGB());
    }

    /**
     * A shade of the source color should translate into a corresponding shade of the target color, rather than
     * collapsing onto the flat target color.
     */
    @Test
    void replace_withShadedSourcePixel_translatesToShadeOfTarget() {
        // WHEN a dark-red pixel (a shade of red) is replaced with blue under LOOSE strictness:
        Color darkRed = new Color(128, 0, 0);
        Color result = replaceOnePixel(RED, BLUE, IColorReplace.Strictness.LOOSE, darkRed);

        // THEN the result is a dark shade of blue: it is bluish (hue near blue's 240 deg), it is not flat blue
        // (its brightness is well below pure blue's), and it is plainly different from the original red.
        double resultHueDeg = hueDegrees(result);
        double blueHueDeg = hueDegrees(BLUE);
        assertTrue(Math.abs(resultHueDeg - blueHueDeg) < 30.0,
                "Expected a bluish hue (near " + blueHueDeg + " deg), got " + resultHueDeg + " deg");
        assertTrue(Math.max(result.getRed(), Math.max(result.getGreen(), result.getBlue())) < 255,
                "Expected a shaded (darker) result, got " + result);
        assertNotEquals(darkRed.getRGB(), result.getRGB());
    }

    /**
     * A color far from the source must be left untouched, even at the loosest strictness that is being tested.
     */
    @Test
    void replace_withColorBeyondTolerance_remainsUnchanged() {
        // WHEN a green pixel (far from red) is processed under STRICT strictness:
        Color result = replaceOnePixel(RED, BLUE, IColorReplace.Strictness.STRICT, GREEN);

        // THEN it is unchanged:
        assertEquals(GREEN.getRGB(), result.getRGB());
    }

    /**
     * The strictness parameter must control the tolerance: a moderately close color should be replaced under LOOSE
     * but left untouched under STRICT.
     */
    @Test
    void replace_withModeratelyCloseColor_replacedUnderLoose_butNotUnderStrict() {
        // A slightly-off red (200, 30, 30) sits at a normalized distance the LOOSE band accepts but the STRICT band
        // rejects.
        Color close = new Color(200, 30, 30);

        Color underStrict = replaceOnePixel(RED, BLUE, IColorReplace.Strictness.STRICT, close);
        Color underLoose = replaceOnePixel(RED, BLUE, IColorReplace.Strictness.LOOSE, close);

        // THEN STRICT leaves it untouched, while LOOSE translates it toward blue:
        assertEquals(close.getRGB(), underStrict.getRGB());
        assertNotEquals(close.getRGB(), underLoose.getRGB());
    }

    /**
     * Under EXACT strictness only a fully matching pixel is replaced; anything short of an exact match survives.
     */
    @Test
    void replace_withExactStrictness_onlyReplacesExactMatch() {
        Color exact = replaceOnePixel(RED, BLUE, IColorReplace.Strictness.EXACT, RED);
        Color almost = replaceOnePixel(RED, BLUE, IColorReplace.Strictness.EXACT, new Color(254, 0, 0));

        // THEN the exact match becomes blue, but the near-match is left alone:
        assertEquals(BLUE.getRGB(), exact.getRGB());
        assertEquals(new Color(254, 0, 0).getRGB(), almost.getRGB());
    }

    /**
     * Returns the hue of the given color in degrees (0-360), or -1 for grayscale inputs.
     */
    private static double hueDegrees(Color color) {
        float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
        return hsb[0] * 360.0;
    }
}
