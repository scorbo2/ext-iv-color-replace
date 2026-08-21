package ca.corbett.imageviewer.extensions.colorreplace.impl;

import ca.corbett.extras.logging.Stopwatch;
import ca.corbett.imageviewer.extensions.colorreplace.IColorReplace;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * This is a deliberately naive implementation of the IColorReplace interface,
 * showing a very simplistic approach that ignores the Strictness parameter and
 * only does an exact color value match. This will actually work in low-color
 * images, like 8-bit pixel graphics, but will fail spectacularly in high-color photographs.
 *
 * @author <a href="https://github.com/scorbo2">scorbo2</a>
 */
public class NaiveColorReplace implements IColorReplace {
    @Override
    public void replace(BufferedImage srcImage, Color srcColor, Color targetColor, Strictness strictness, OnComplete onComplete) {
        Stopwatch.start("NaiveColorReplace.replace");
        int srcRGB = srcColor.getRGB();
        int targetRGB = targetColor.getRGB();

        for (int y = 0; y < srcImage.getHeight(); y++) {
            for (int x = 0; x < srcImage.getWidth(); x++) {
                if (srcImage.getRGB(x, y) == srcRGB) {
                    srcImage.setRGB(x, y, targetRGB);
                }
            }
        }

        if (onComplete != null) {
            onComplete.onComplete(Stopwatch.stop("NaiveColorReplace.replace"));
        }
    }
}
