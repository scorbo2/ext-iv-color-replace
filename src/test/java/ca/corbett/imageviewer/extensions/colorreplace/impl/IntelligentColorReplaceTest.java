package ca.corbett.imageviewer.extensions.colorreplace.impl;

import ca.corbett.imageviewer.extensions.colorreplace.IColorReplace;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the HSB-based color replacement algorithm.
 * The "shades become shades" expectations were computed by hand from the
 * documented HSB math, not by running the code first.
 */
class IntelligentColorReplaceTest {

    // ------------------------------------------------------------------
    // Color conversion unit tests
    // ------------------------------------------------------------------

    @Test
    void rgbToHsb_knownColors_expectedHsvValues() {
        // GIVEN a set of well-known RGB values (alpha is always 255 here)
        // WHEN converted to HSB
        // THEN each matches its expected hue / saturation / brightness
        assertHsb(0xFFFF0000, 0f, 1f, 1f);          // red
        assertHsb(0xFF00FF00, 120f, 1f, 1f);        // green
        assertHsb(0xFF0000FF, 240f, 1f, 1f);        // blue
        assertHsb(0xFF00FFFF, 180f, 1f, 1f);        // cyan
        assertHsb(0xFFFFFFFF, 0f, 0f, 1f);          // white: achromatic, hue meaningless
        assertHsb(0xFF000000, 0f, 0f, 0f);          // black
        assertHsb(0xFF808080, 0f, 0f, 128f / 255f);  // mid-gray
        assertHsb(0xFF804040, 0f, 0.5f, 128f / 255f); // dark red: half chroma, brightness 128/255
    }

    @Test
    void hsbToRgb_roundTripsRgbToHsb_withinOnePerChannel() {
        // GIVEN several non-trivial RGB values
        int[] samples = {0xFFC8783C, 0xFF112233, 0xFFAABBCC, 0xFF0060C0, 0xFF787878};

        // WHEN we convert each one to HSB and back to RGB
        // THEN the result is indistinguishable (within one count per channel)
        for (int sample : samples) {
            float[] hsb = new float[3];
            IntelligentColorReplace.rgbToHsb(sample, hsb);
            int back = IntelligentColorReplace.hsbToRgb(hsb[0], hsb[1], hsb[2], sample & 0xFF000000);

            assertEquals((sample >> 16) & 0xFF, (back >> 16) & 0xFF, 1, "R channel of 0x" + Integer.toHexString(sample));
            assertEquals((sample >> 8) & 0xFF, (back >> 8) & 0xFF, 1, "G channel of 0x" + Integer.toHexString(sample));
            assertEquals(sample & 0xFF, back & 0xFF, 1, "B channel of 0x" + Integer.toHexString(sample));
            assertEquals(sample & 0xFF000000, back & 0xFF000000, "alpha of 0x" + Integer.toHexString(sample));
        }
    }

    // ------------------------------------------------------------------
    // EXACT strictness
    // ------------------------------------------------------------------

    @Test
    void replace_exactStrictness_onlyExactArGbPixelReplaced_othersUntouched() {
        // GIVEN a row of [exact source color, red off by one count, same RGB with different alpha]
        BufferedImage image = row(0xFFFF0000, 0xFFFE0000, 0x80FF0000);
        Color source = new Color(255, 0, 0);
        Color target = new Color(0, 255, 0);

        // WHEN we replace with strictness EXACT
        int[] out = replace(image, source, target, IColorReplace.Strictness.EXACT);

        // THEN only the pixel with the exact ARGB value of the source is repainted,
        // and even it keeps its own alpha channel
        assertEquals(0xFF00FF00, out[0], "exact match should be replaced by the target color");
        assertEquals(0xFFFE0000, out[1], "one count off in red is not an exact match");
        assertEquals(0x80FF0000, out[2], "a different alpha is not an exact match");
    }

    // ------------------------------------------------------------------
    // Shades of the source become shades of the target
    // ------------------------------------------------------------------

    @Test
    void replace_mediumStrictness_darkerShadeOfSource_becomesDarkerShadeOfTarget() {
        // GIVEN a red source (v=1.0) and a darker red pixel (v~=0.75); target is blue (v=1.0)
        BufferedImage image = row(0xFFC00000);
        Color source = new Color(255, 0, 0);
        Color target = new Color(0, 0, 255);

        // WHEN we replace with strictness MEDIUM
        int[] out = replace(image, source, target, IColorReplace.Strictness.MEDIUM);

        // THEN the pixel becomes a DARKER blue (v preserved as a ratio), not a flat target blue
        assertEquals(0x00, (out[0] >> 16) & 0xFF, 2, "R");
        assertEquals(0x00, (out[0] >> 8) & 0xFF, 2, "G");
        assertEquals(0xC0, out[0] & 0xFF, 2, "B");
    }

    @Test
    void replace_mediumStrictness_brighterShadeOfSource_becomesBrighterShadeOfTarget() {
        // GIVEN an orange source (v=0.8) and a brighter orange pixel (v~=0.902); target is cyan (v=0.8)
        BufferedImage image = row(0xFFE67300);
        Color source = new Color(204, 136, 0);
        Color target = new Color(0, 204, 204);

        // WHEN we replace with strictness MEDIUM
        int[] out = replace(image, source, target, IColorReplace.Strictness.MEDIUM);

        // THEN the result is a cyan shade BRIGHTER than the target itself
        // (target v=0.8, expected result v~=0.902 -> (0, 230, 192))
        assertEquals(0x00, (out[0] >> 16) & 0xFF, 2, "R");
        assertEquals(0xE6, (out[0] >> 8) & 0xFF, 2, "G");
        assertEquals(0xC0, out[0] & 0xFF, 2, "B");
    }

    @Test
    void replace_washedOutHighlight_looseStrictness_becomesPaleShadeOfTarget() {
        // GIVEN a saturated red source (s=1.0), a washed-out red highlight
        BufferedImage image = row(0xFFFFFFFF, 0xFFFF8080, 0xFF00FFFF, 0xFF808080);
        Color source = new Color(255, 0, 0);
        Color target = new Color(0, 0, 255);

        // WHEN we replace with strictness LOOSE
        int[] out = replace(image, source, target, IColorReplace.Strictness.LOOSE);

        // THEN the highlight becomes a bright, low-saturation blue: its
        // saturation relative to the source (0.498) is applied to the target's
        // chroma at full brightness -> (128, 128, 255). The achromatic
        // distractors differ by a whole saturation point and are left alone.
        assertEquals(0xFFFFFFFF, out[0], "white should not match a saturated source");
        assertEquals(0x80, (out[1] >> 16) & 0xFF, 2, "highlight R");
        assertEquals(0x80, (out[1] >> 8) & 0xFF, 2, "highlight G");
        assertEquals(0xFF, out[1] & 0xFF, 2, "highlight B");
        assertEquals(0xFF00FFFF, out[2], "cyan should not match a red source");
        assertEquals(0xFF808080, out[3], "mid-gray should not match a saturated source");
    }

    @Test
    void replace_deepShadeOfSource_strictAndMediumUntouched_looseReplacesIt() {
        // GIVEN a red source and a very dark red pixel (v~=0.5): a shade only
        // LOOSE strictness is wide enough to reach
        Color source = new Color(255, 0, 0);
        Color target = new Color(0, 0, 255);

        // WHEN we replace at each strictness level
        int[] strictOut = replace(row(0xFF800000), source, target, IColorReplace.Strictness.STRICT);
        int[] mediumOut = replace(row(0xFF800000), source, target, IColorReplace.Strictness.MEDIUM);
        int[] looseOut = replace(row(0xFF800000), source, target, IColorReplace.Strictness.LOOSE);

        // THEN STRICT and MEDIUM leave the deep shade alone, LOOSE replaces it
        // with a dark blue (v~=0.5 of the target)
        assertEquals(0xFF800000, strictOut[0], "STRICT should not reach this far from the source");
        assertEquals(0xFF800000, mediumOut[0], "MEDIUM should not reach this far from the source");
        assertEquals(0x00, (looseOut[0] >> 16) & 0xFF, 2, "loose R");
        assertEquals(0x00, (looseOut[0] >> 8) & 0xFF, 2, "loose G");
        assertEquals(0x80, looseOut[0] & 0xFF, 2, "loose B");
    }

    // ------------------------------------------------------------------
    // Hue family handling
    // ------------------------------------------------------------------

    @Test
    void replace_slightlyOffHue_strictUntouched_looseMovesOntoTargetHue() {
        // GIVEN a red source and a pixel 20 degrees off-hue (fully saturated orange-red)
        BufferedImage sourceImage = row(0xFFFFAA00);
        Color source = new Color(255, 0, 0);
        Color target = new Color(0, 0, 255);

        // WHEN we replace with strictness STRICT and LOOSE
        int[] strictOut = replace(sourceImage, source, target, IColorReplace.Strictness.STRICT);
        int[] looseOut = replace(row(0xFFFFAA00), source, target, IColorReplace.Strictness.LOOSE);

        // THEN STRICT judges it too far in hue, while LOOSE accepts it and
        // carries its hue deviation over onto the target hue (240 + 20 = 260)
        assertEquals(0xFFFFAA00, strictOut[0], "20 degrees off hue is outside STRICT's 12 degree radius");
        assertEquals(0xAA, (looseOut[0] >> 16) & 0xFF, 2, "deviated hue should be carried onto the target");
        assertEquals(0x00, (looseOut[0] >> 8) & 0xFF, 2, "G");
        assertEquals(0xFF, looseOut[0] & 0xFF, 2, "B");
    }

    @Test
    void replace_fullyDifferentHue_evenLoose_notReplaced() {
        // GIVEN a red source and a fully saturated green pixel (120 degrees away)
        BufferedImage image = row(0xFFFF0000, 0xFF00FF00);
        Color source = new Color(255, 0, 0);
        Color target = new Color(0, 0, 255);

        // WHEN we replace with strictness LOOSE
        int[] out = replace(image, source, target, IColorReplace.Strictness.LOOSE);

        // THEN the matching pixel is replaced while the green is untouched
        assertEquals(0xFF0000FF, out[0]);
        assertEquals(0xFF00FF00, out[1], "a 120 degree hue difference is outside even LOOSE's 45 degree radius");
    }

    // ------------------------------------------------------------------
    // Achromatic (gray) sources
    // ------------------------------------------------------------------

    @Test
    void replace_achromaticGraySource_looseStrictness_graysBecomeShadesOfTarget() {
        // GIVEN a light gray source (v~=0.941), a darker gray pixel (v~=0.471)
        // and a fully saturated red distractor; target is yellow
        BufferedImage image = row(0xFFF0F0F0, 0xFF787878, 0xFFFF0000);
        Color source = new Color(240, 240, 240);
        Color target = new Color(255, 255, 0);

        // WHEN we replace with strictness LOOSE
        int[] out = replace(image, source, target, IColorReplace.Strictness.LOOSE);

        // THEN the gray row becomes shades of yellow with brightness ratios
        // preserved (darker gray -> dark yellow (128,128,0)), while the red,
        // which differs by a full saturation point, is left alone
        assertEquals(0xFF, (out[0] >> 16) & 0xFF, 2, "source-gray R");
        assertEquals(0xFF, (out[0] >> 8) & 0xFF, 2, "source-gray G");
        assertEquals(0x00, out[0] & 0xFF, 2, "source-gray B");
        assertEquals(0x80, (out[1] >> 16) & 0xFF, 2, "dark-gray R");
        assertEquals(0x80, (out[1] >> 8) & 0xFF, 2, "dark-gray G");
        assertEquals(0x00, out[1] & 0xFF, 2, "dark-gray B");
        assertEquals(0xFFFF0000, out[2], "fully saturated red should not match an achromatic source");
    }

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------

    @Test
    void replace_matchingPixelWithZeroAlpha_rgbReplaced_alphaPreserved() {
        // GIVEN a fully transparent red pixel (color matches the red source)
        BufferedImage image = row(0x00FF0000);
        Color source = new Color(255, 0, 0);
        Color target = new Color(0, 255, 0);

        // WHEN we replace with strictness MEDIUM
        int[] out = replace(image, source, target, IColorReplace.Strictness.MEDIUM);

        // THEN the color is replaced but transparency is preserved -
        // replacement changes color, not alpha
        assertEquals(0x0000FF00, out[0]);
    }

    @Test
    void replace_sourceSameAsTarget_imageStaysByteIdentical() {
        // GIVEN an image containing a matching pixel and one that would survive
        // a float HSB round trip only by luck
        BufferedImage image = row(0xFFFF0000, 0xFFFFAA00);
        Color source = new Color(255, 0, 0);

        // WHEN we replace with the SAME color as the target
        int[] out = replace(image, source, source, IColorReplace.Strictness.LOOSE);

        // THEN not a single count changes anywhere
        assertArrayEquals(new int[]{0xFFFF0000, 0xFFFFAA00}, out);
    }

    @Test
    void replace_noMatchingPixels_callbackStillInvoked_imageUnchanged() {
        // GIVEN an image containing only colors far from the source
        BufferedImage image = row(0xFF00FF00, 0xFFFFFFFF, 0xFF00FFFF);
        AtomicLong runTime = new AtomicLong(-1);

        // WHEN we replace on it (note: BufferedImage cannot have zero
        // dimensions, so "empty" input is not constructible in practice)
        new IntelligentColorReplace()
                .replace(image, new Color(255, 0, 0), new Color(255, 255, 0), IColorReplace.Strictness.LOOSE, runTime::set);

        // THEN nothing changes and the completion callback still fires
        assertTrue(runTime.get() >= 0, "onComplete must be invoked even when nothing matches");
        int[] out = image.getRGB(0, 0, image.getWidth(), image.getHeight(), new int[3], 0, image.getWidth());
        assertArrayEquals(new int[]{0xFF00FF00, 0xFFFFFFFF, 0xFF00FFFF}, out);
    }

    @Test
    void replace_anyNullArgument_throwsIllegalArgumentException() {
        // GIVEN a valid image, colors and strictness
        BufferedImage image = row(0xFFFF0000);
        IntelligentColorReplace replacer = new IntelligentColorReplace();
        Color red = new Color(255, 0, 0);
        Color blue = new Color(0, 0, 255);
        IColorReplace.Strictness medium = IColorReplace.Strictness.MEDIUM;

        // WHEN any single argument is null
        // THEN the call fails fast with a descriptive exception instead of NPE-ing mid-scan
        assertThrows(IllegalArgumentException.class, () -> replacer.replace(null, red, blue, medium, null));
        assertThrows(IllegalArgumentException.class, () -> replacer.replace(image, null, blue, medium, null));
        assertThrows(IllegalArgumentException.class, () -> replacer.replace(image, red, null, medium, null));
        assertThrows(IllegalArgumentException.class, () -> replacer.replace(image, red, blue, null, null));
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    /**
     * Runs a replacement on the given image and returns a snapshot of the
     * resulting pixels, asserting that the completion callback fired.
     */
    private static int[] replace(BufferedImage image, Color source, Color target, IColorReplace.Strictness strictness) {
        AtomicLong runTime = new AtomicLong(-1);
        new IntelligentColorReplace().replace(image, source, target, strictness, runTime::set);
        assertTrue(runTime.get() >= 0, "onComplete must be invoked with the run time");
        BufferedImage snapshot = image;
        int width = snapshot.getWidth();
        int height = snapshot.getHeight();
        return snapshot.getRGB(0, 0, width, height, new int[width * height], 0, width);
    }

    /**
     * Builds a 1-pixel-tall image from the given ARGB values.
     */
    private static BufferedImage row(int... pixels) {
        BufferedImage image = new BufferedImage(pixels.length, 1, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < pixels.length; i++) {
            image.setRGB(i, 0, pixels[i]);
        }
        return image;
    }

    private static void assertHsb(int rgb, float expectedHue, float expectedSat, float expectedVal) {
        float[] hsb = new float[3];
        IntelligentColorReplace.rgbToHsb(rgb, hsb);
        assertEquals(expectedHue, hsb[0], 0.01f, "hue of 0x" + Integer.toHexString(rgb));
        assertEquals(expectedSat, hsb[1], 0.001f, "saturation of 0x" + Integer.toHexString(rgb));
        assertEquals(expectedVal, hsb[2], 0.001f, "brightness of 0x" + Integer.toHexString(rgb));
    }
}
