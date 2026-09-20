package ca.corbett.imageviewer.extensions.colorreplace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the pure (UI-free) helpers in ColorReplaceDialog:
 * the "Save As..." starting-directory resolution, the default-extension
 * handling, and the format dispatch.
 */
class ColorReplaceDialogTest {

    // ------------------------------------------------------------------
    // resolveInitialSaveAsDirectory
    // ------------------------------------------------------------------

    @Test
    void resolveInitialSaveAsDirectory_existingLastDirectory_returnsLastDirectory(@TempDir Path tempDir) throws IOException {
        // GIVEN a last-used directory that still exists, and a source image in a different directory:
        File lastDirectory = tempDir.resolve("last-used").toFile();
        assertTrue(lastDirectory.mkdirs());
        File sourceFile = tempDir.resolve("source").resolve("image.png").toFile();
        assertTrue(sourceFile.getParentFile().mkdirs());
        assertTrue(sourceFile.createNewFile());

        // WHEN resolving the initial directory for the "Save As..." chooser
        File resolved = ColorReplaceDialog.resolveInitialSaveAsDirectory(lastDirectory, sourceFile);

        // THEN the last-used directory wins, even though the source has its own directory
        assertEquals(lastDirectory.getAbsoluteFile(), resolved);
    }

    @Test
    void resolveInitialSaveAsDirectory_deletedLastDirectory_fallsBackToSourceDirectory(@TempDir Path tempDir) throws IOException {
        // GIVEN a last-used directory that has since been deleted:
        File lastDirectory = tempDir.resolve("last-used").toFile();
        assertTrue(lastDirectory.mkdirs());
        lastDirectory.delete();
        // AND a source image whose directory does exist:
        File sourceFile = tempDir.resolve("source").resolve("image.png").toFile();
        assertTrue(sourceFile.getParentFile().mkdirs());
        assertTrue(sourceFile.createNewFile());

        // WHEN resolving the initial directory for the "Save As..." chooser
        File resolved = ColorReplaceDialog.resolveInitialSaveAsDirectory(lastDirectory, sourceFile);

        // THEN we fall back to the source image's directory rather than the stale one
        assertEquals(sourceFile.getParentFile().getAbsoluteFile(), resolved);
    }

    @Test
    void resolveInitialSaveAsDirectory_nullLastDirectory_returnsSourceDirectory(@TempDir Path tempDir) throws IOException {
        // GIVEN no last-used directory yet (first "Save As..." in this JVM),
        // AND a source image whose directory exists:
        File sourceFile = tempDir.resolve("source").resolve("image.png").toFile();
        assertTrue(sourceFile.getParentFile().mkdirs());
        assertTrue(sourceFile.createNewFile());

        // WHEN resolving the initial directory for the "Save As..." chooser
        File resolved = ColorReplaceDialog.resolveInitialSaveAsDirectory(null, sourceFile);

        // THEN the source image's directory is used as the natural default
        assertEquals(sourceFile.getParentFile().getAbsoluteFile(), resolved);
    }

    // ------------------------------------------------------------------
    // saveImageTo
    // ------------------------------------------------------------------

    @Test
    void saveImageTo_pngExtension_writesValidPng(@TempDir Path tempDir) throws IOException {
        // GIVEN a simple test image and a target file with a .png extension:
        BufferedImage image = new BufferedImage(10, 8, BufferedImage.TYPE_INT_ARGB);
        File targetFile = tempDir.resolve("out.png").toFile();

        // WHEN saving the image via the format dispatch
        ColorReplaceDialog.saveImageTo(image, targetFile);

        // THEN the file exists, starts with the PNG magic number,
        // and can be read back with the original dimensions:
        assertTrue(targetFile.exists());
        assertFileStartsWith(targetFile, (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47); // "PNG"
        BufferedImage readBack = ImageIO.read(targetFile);
        assertNotNull(readBack);
        assertEquals(10, readBack.getWidth());
        assertEquals(8, readBack.getHeight());
    }

    @Test
    void saveImageTo_jpegExtension_writesValidJpeg(@TempDir Path tempDir) throws IOException {
        // GIVEN a simple test image and a target file with a .jpeg extension:
        BufferedImage image = new BufferedImage(10, 8, BufferedImage.TYPE_INT_RGB);
        File targetFile = tempDir.resolve("out.jpeg").toFile();

        // WHEN saving the image via the format dispatch
        ColorReplaceDialog.saveImageTo(image, targetFile);

        // THEN the file exists, starts with the JPEG magic number,
        // and can be read back with the original dimensions:
        assertTrue(targetFile.exists());
        assertFileStartsWith(targetFile, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        BufferedImage readBack = ImageIO.read(targetFile);
        assertNotNull(readBack);
        assertEquals(10, readBack.getWidth());
        assertEquals(8, readBack.getHeight());
    }

    @Test
    void saveImageTo_argbImage_jpegExtension_flattensAlphaOverWhite(@TempDir Path tempDir) throws IOException {
        // GIVEN a fully transparent image of TYPE_INT_ARGB, the same type our
        // preview buffer always is (JPEG cannot encode alpha, so this used to
        // fail with "Bogus input colorspace"):
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        File targetFile = tempDir.resolve("out.jpg").toFile();

        // WHEN saving via the format dispatch
        ColorReplaceDialog.saveImageTo(image, targetFile);

        // THEN a valid JPEG is written, and the transparent pixels were
        // composited over white (JPEG is lossy, so allow a little rounding error):
        assertFileStartsWith(targetFile, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF);
        BufferedImage readBack = ImageIO.read(targetFile);
        assertNotNull(readBack);
        int rgb = readBack.getRGB(0, 0);
        assertEquals(255, (rgb >> 16) & 0xFF, 4, "red channel");
        assertEquals(255, (rgb >> 8) & 0xFF, 4, "green channel");
        assertEquals(255, rgb & 0xFF, 4, "blue channel");
    }

    @Test
    void saveImageTo_unsupportedExtension_throwsIOException(@TempDir Path tempDir) {
        // GIVEN a simple test image and a target file with an extension we don't support:
        BufferedImage image = new BufferedImage(10, 8, BufferedImage.TYPE_INT_RGB);
        File targetFile = tempDir.resolve("out.gif").toFile();

        // WHEN saving the image via the format dispatch
        // THEN we get an IOException, because only png and jpeg are supported:
        assertThrows(IOException.class, () -> ColorReplaceDialog.saveImageTo(image, targetFile));
    }

    @Test
    void saveImageTo_upperCaseExtension_isCaseInsensitive(@TempDir Path tempDir) throws IOException {
        // GIVEN a simple test image and a target file with an upper-case extension:
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        File targetFile = tempDir.resolve("OUT.PNG").toFile();

        // WHEN saving the image via the format dispatch
        ColorReplaceDialog.saveImageTo(image, targetFile);

        // THEN the extension matches case-insensitively and a valid PNG is written:
        assertFileStartsWith(targetFile, (byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
    }

    // ------------------------------------------------------------------
    // withDefaultExtension
    // ------------------------------------------------------------------

    @Test
    void withDefaultExtension_noExtension_appendsPng() {
        // GIVEN a file name with no extension, as a user might type into the file chooser:
        File file = new File("/some/dir", "photo");

        // WHEN applying the default extension
        File result = ColorReplaceDialog.withDefaultExtension(file);

        // THEN ".png" is appended to the name:
        assertEquals("/some/dir/photo.png", result.getPath());
    }

    @Test
    void withDefaultExtension_supportedExtension_unchanged() {
        // GIVEN a file name with a supported extension:
        File file = new File("/some/dir", "photo.png");

        // WHEN applying the default extension
        File result = ColorReplaceDialog.withDefaultExtension(file);

        // THEN the file is returned unchanged:
        assertSame(file, result);
    }

    @Test
    void withDefaultExtension_unsupportedExtension_unchanged() {
        // GIVEN a file name with an extension we don't support:
        File file = new File("/some/dir", "photo.gif");

        // WHEN applying the default extension
        File result = ColorReplaceDialog.withDefaultExtension(file);

        // THEN the file is returned unchanged, so saveImageTo() gets the
        // chance to report it as an unsupported format:
        assertSame(file, result);
    }

    @Test
    void withDefaultExtension_onlyLeadingDot_appendsPng() {
        // GIVEN a file name whose only dot is at index 0 (a hidden file with no extension):
        File file = new File("/some/dir", ".hidden");

        // WHEN applying the default extension
        File result = ColorReplaceDialog.withDefaultExtension(file);

        // THEN the leading dot does not count as an extension:
        assertEquals("/some/dir/.hidden.png", result.getPath());
    }

    @Test
    void withDefaultExtension_parentlessFileName_appendsPng() {
        // GIVEN a bare file name with no parent directory:
        File file = new File("photo");

        // WHEN applying the default extension
        File result = ColorReplaceDialog.withDefaultExtension(file);

        // THEN a bare name with ".png" appended is returned:
        assertEquals("photo.png", result.getPath());
    }

    /**
     * Asserts that the first bytes of the given file match the given magic number.
     */
    private static void assertFileStartsWith(File file, byte... magicBytes) throws IOException {
        byte[] actual = new byte[magicBytes.length];
        try (var in = java.nio.file.Files.newInputStream(file.toPath())) {
            int bytesRead = in.readNBytes(actual, 0, actual.length);
            assertEquals(magicBytes.length, bytesRead, "expected file to be at least " + magicBytes.length + " bytes long");
        }
        for (int i = 0; i < magicBytes.length; i++) {
            assertEquals(magicBytes[i], actual[i], "magic byte " + i + " of " + file.getName());
        }
    }
}
