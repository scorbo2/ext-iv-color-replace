package ca.corbett.imageviewer.extensions.colorreplace;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Interface for color replacement functionality in images.
 */
public interface IColorReplace {

    /**
     * Controls how the implementing algorithm should approach color replacement.
     * The exact implementation details are left up to the implementing class, but generally:
     * <ul>
     * <li>EXACT: Only replace pixels that match the source color exactly.</li>
     * <li>STRICT: Replace pixels that are very close to the source color.</li>
     * <li>MEDIUM: Replace pixels that are moderately close to the source color.</li>
     * <li>LOOSE: Replace pixels that are somewhat close to the source color.</li>
     * </ul>
     */
    enum Strictness {
        EXACT, STRICT, MEDIUM, LOOSE
    }

    /**
     * Will be invoked when the color replacement operation is complete.
     */
    @FunctionalInterface
    interface OnComplete {
        void onComplete();
    }

    /**
     * Replaces pixels in-place in the given source image according to the given Strictness.
     * Note that depending on source and target color values, this may have no effect on the
     * given image.
     *
     * @param srcImage    The image to be modified.
     * @param srcColor    The color to be replaced.
     * @param targetColor The color to replace with.
     * @param strictness  How strictly to match the source color.
     * @param onComplete  Callback to be invoked when the operation is complete.
     */
    void replace(BufferedImage srcImage, Color srcColor, Color targetColor, Strictness strictness, OnComplete onComplete);
}
