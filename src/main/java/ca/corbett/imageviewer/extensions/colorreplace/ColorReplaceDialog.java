package ca.corbett.imageviewer.extensions.colorreplace;

import ca.corbett.extras.MessageUtil;
import ca.corbett.extras.gradient.ColorSelectionType;
import ca.corbett.extras.image.ImagePanel;
import ca.corbett.extras.image.ImagePanelConfig;
import ca.corbett.extras.image.ImageUtil;
import ca.corbett.extras.io.KeyStrokeManager;
import ca.corbett.forms.FormPanel;
import ca.corbett.forms.fields.ColorField;
import ca.corbett.forms.fields.ComboField;
import ca.corbett.forms.fields.LabelField;
import ca.corbett.forms.fields.PanelField;
import ca.corbett.imageviewer.extensions.ImageViewerExtensionManager;
import ca.corbett.imageviewer.extensions.colorreplace.impl.IntelligentColorReplace;
import ca.corbett.imageviewer.ui.MainWindow;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides options for selecting a color in an image and replacing it with another color.
 * The replacement color can be selected from a color chooser or from any pixel in the image.
 * Color replacement is performed intelligently with a user-friendly "strictness" chooser,
 * that controls how closely the replacement color must match the original color.
 *
 * @author <a href="https://github.com/scorbo2">scorbo2</a>
 */
public class ColorReplaceDialog extends JDialog {

    /**
     * The directory last used when saving output via "Save As...".
     * Deliberately static and in-memory only: it must survive the dialog being
     * closed and re-opened (possibly for a different image), but it does not
     * need to survive an application restart, so we keep no disk persistence.
     */
    private static File lastSaveAsDirectory;

    private MessageUtil messageUtil;
    private final KeyStrokeManager keyStrokeManager;
    private final File srcFile;
    private BufferedImage originalImage; // keep the original image in memory
    private BufferedImage previewBuffer; // create a copy that we can modify
    private int imgWidth;
    private int imgHeight;
    private final ImagePanel imagePanel;

    private ColorField sourceColorField;
    private ColorField replacementColorField;
    private ComboField<IColorReplace.Strictness> strictnessField;

    /**
     * Creates a new ColorReplaceDialog based on the image represented by the given file.
     * We rely on our launching action to ensure that we only ever receive a JPEG or PNG file.
     *
     * @param file The File containing the image to be edited.
     */
    public ColorReplaceDialog(File file) {
        super(MainWindow.getInstance(), "Color replace", true);
        this.srcFile = file;
        this.keyStrokeManager = new KeyStrokeManager(this);
        addWindowListener(new WindowCleanupListener());
        setMinimumSize(new Dimension(500, 500));
        // Start maximized (or effectively maximized... can't use setExtendedState( MAXIMIZED_BOTH ) on a dialog)
        setSize(new Dimension(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().width,
                              GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds().height));
        setResizable(true);
        setLocationRelativeTo(MainWindow.getInstance());
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());
        imagePanel = new ImagePanel(ImagePanelConfig.createSimpleReadOnlyProperties());
        add(buildControlPanel(), BorderLayout.WEST);
        add(imagePanel, BorderLayout.CENTER);
        imagePanel.addMouseListener(new MouseInputListener());
        configureKeyboardShortcuts();
        loadImage();
    }

    /**
     * Invoked internally to reset back to the original supplied image, so we can start over.
     */
    private void resetAll() {
        // Completely arbitrary default values:
        sourceColorField.setColor(Color.BLACK); // Solid black
        replacementColorField.setColor(Color.WHITE); // Solid white
        updatePreview();
    }

    /**
     * Prompts the user for confirmation, and if confirmed,
     * saves the current edit (if needed) and then closes the dialog.
     * If the user does not confirm, the dialog does NOT close.
     */
    private void promptToSaveAndExit() {
        if (getMessageUtil().askYesNo("Confirm",
                                      "Save current changes and close?\nThis will overwrite the original image.")
                == MessageUtil.YES) {
            saveChanges(); // will dispose() on success
        }
    }

    /**
     * Saves the current edit and then closes the dialog.
     * Changes are saved in-place to the source file, so as soon as the dialog closes, the main image panel
     * in the MainWindow should already be updated to show the result.
     */
    private void saveChanges() {
        // if we're in a wonky state, I guess we're done here:
        if (previewBuffer == null || originalImage == null) {
            getMessageUtil().info("Nothing to save.");
            dispose();
            return;
        }

        // Start with the original image:
        Graphics2D graphics = previewBuffer.createGraphics();
        Graphics2D targetGraphics = originalImage.createGraphics();
        try {
            targetGraphics.drawImage(previewBuffer, 0, 0, null);
            targetGraphics.dispose();
            targetGraphics = null;

            getMessageUtil().getLogger().log(Level.INFO, "Color replace: saving image {0}", srcFile.getAbsolutePath());
            saveImageTo(originalImage, srcFile);

            // Force thumbnail regeneration for this image:
            ImageViewerExtensionManager.getInstance().removeThumbnail(srcFile);

            // Force reload of current image in MainWindow:
            MainWindow.getInstance().reloadCurrentImage();

            // We're done here:
            dispose();
        }
        catch (IOException ioe) {
            getMessageUtil().error("Problem saving image: " + ioe.getMessage(), ioe);
        }
        finally {
            if (graphics != null) {
                graphics.dispose();
            }
            if (targetGraphics != null) {
                targetGraphics.dispose();
            }
        }
    }

    /**
     * Prompts the user for a target file and saves the current color replacement
     * result there. Unlike saveChanges(), the source image on disk is NOT modified
     * and the dialog does NOT close, so the user can keep tweaking the colors and
     * "Save As..." as often as they like.
     *
     * The directory of the chosen target file is remembered in lastSaveAsDirectory,
     * which is static, so the next "Save As..." (even from a fresh dialog instance)
     * starts in that same directory.
     */
    private void saveAs() {
        // if we're in a wonky state, I guess we're done here:
        if (previewBuffer == null || originalImage == null) {
            getMessageUtil().info("Nothing to save.");
            return;
        }

        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Save As...");
        fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fileChooser.setApproveButtonText("Save");
        fileChooser.setFileFilter(new FileNameExtensionFilter("PNG or JPEG image", "png", "jpg", "jpeg"));

        File startingDirectory = resolveInitialSaveAsDirectory(lastSaveAsDirectory, srcFile);
        fileChooser.setCurrentDirectory(startingDirectory);
        // Pre-select the source image's name: a sensible default if the user
        // merely picks a new directory and hits "Save" without renaming:
        fileChooser.setSelectedFile(new File(startingDirectory, srcFile.getName()));

        if (fileChooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return; // user canceled the file chooser
        }

        File targetFile = withDefaultExtension(fileChooser.getSelectedFile().getAbsoluteFile());
        boolean overwriteExisting = targetFile.exists();

        if (overwriteExisting) {
            if (getMessageUtil().askYesNo("Confirm",
                                          "\"" + targetFile.getName() + "\" already exists.\nOverwrite it?")
                    != MessageUtil.YES) {
                return;
            }
        }

        try {
            saveImageTo(previewBuffer, targetFile);
            getMessageUtil().getLogger().log(Level.INFO, "Color replace: saved image {0}", targetFile.getAbsolutePath());

            // Remember where we saved, so the next "Save As..." starts there:
            lastSaveAsDirectory = targetFile.getParentFile();

            // If we overwrote an existing file, drop its now-stale thumbnail cache entry:
            if (overwriteExisting) {
                ImageViewerExtensionManager.getInstance().removeThumbnail(targetFile);
            }

            // Edge case: the user picked the source file itself as the target.
            // The main window is currently displaying it, so refresh it exactly
            // as an in-place save would:
            if (targetFile.equals(srcFile.getAbsoluteFile())) {
                MainWindow.getInstance().reloadCurrentImage();
            }

            getMessageUtil().info("Image saved to " + targetFile.getAbsolutePath());
        }
        catch (IOException ioe) {
            getMessageUtil().error("Problem saving image: " + ioe.getMessage(), ioe);
        }
    }

    /**
     * Determines the starting directory for the "Save As..." file chooser:
     * the last directory used for "Save As...", if it still exists; otherwise,
     * the directory containing the source image (the natural default on first use).
     *
     * @param lastDirectory The last directory used for "Save As...", or null if there isn't one yet.
     * @param sourceFile    The image currently being edited.
     * @return A directory to start the file chooser in. Never null.
     */
    static File resolveInitialSaveAsDirectory(File lastDirectory, File sourceFile) {
        if (lastDirectory != null && lastDirectory.exists()) {
            return lastDirectory;
        }
        File sourceDirectory = sourceFile.getAbsoluteFile().getParentFile();
        if (sourceDirectory != null && sourceDirectory.exists()) {
            return sourceDirectory;
        }
        // Last resort: the user's home directory. We shouldn't normally get here,
        // but the file chooser must have a valid starting directory:
        return new File(System.getProperty("user.home"));
    }

    /**
     * Saves the given image to the given file, choosing the writer based on the
     * file's extension. Only PNG and JPEG files are supported, which are the same
     * formats this dialog can be launched on.
     *
     * @param image      The image to save.
     * @param targetFile The file to save it to.
     * @throws IOException If the file extension is not a supported image format,
     *                     or if the image cannot be written.
     */
    static void saveImageTo(BufferedImage image, File targetFile) throws IOException {
        String fileName = targetFile.getName().toLowerCase(Locale.ROOT);
        if (fileName.endsWith("png")) {
            ImageUtil.savePngImage(image, targetFile);
        }
        else if (fileName.endsWith("jpg") || fileName.endsWith("jpeg")) {
            saveJpegImage(image, targetFile);
        }
        else {
            throw new IOException("Unsupported image format; must be png or jpeg image.");
        }
    }

    /**
     * Saves the given image to the given file in JPEG format. Since JPEG has no
     * alpha channel, an image with alpha is first flattened over a white
     * background. This matters in practice: our preview buffer is always
     * TYPE_INT_ARGB, and the JPEG writer flatly refuses to encode it otherwise.
     *
     * @param image      The image to save.
     * @param targetFile The file to save it to.
     * @throws IOException If the image cannot be written.
     */
    static void saveJpegImage(BufferedImage image, File targetFile) throws IOException {
        BufferedImage imageToSave = image;
        if (image.getColorModel().hasAlpha()) {
            imageToSave = flattenToRgb(image);
        }
        ImageUtil.saveImage(imageToSave, targetFile);
    }

    /**
     * Flattens the given image onto a new opaque RGB buffer, compositing any
     * alpha over a white background. White is the conventional flattening
     * background (it's what GIMP, ImageMagick, and friends use by default).
     *
     * @param source The image to flatten. May or may not have an alpha channel.
     * @return A new TYPE_3BYTE_BGR image with no alpha channel.
     */
    private static BufferedImage flattenToRgb(BufferedImage source) {
        BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(source, 0, 0, null);
        }
        finally {
            g.dispose();
        }
        return rgb;
    }

    /**
     * Returns a File with ".png" appended if the given file's name has no extension,
     * otherwise returns the file unchanged. JFileChooser does not append an extension
     * for us when the user types a bare name into the file name field, and
     * saveImageTo() would reject such a name, so we default to PNG (the first
     * extension in the file chooser's filter). A name with an unrecognized
     * extension is left alone; saveImageTo() will report it as unsupported.
     *
     * @param file The file chosen by the user.
     * @return The file, possibly with a ".png" extension appended. Never null.
     */
    static File withDefaultExtension(File file) {
        String name = file.getName();
        // A dot at index 0 (e.g. ".hidden") or no dot at all means "no extension":
        if (name.lastIndexOf('.') > 0) {
            return file;
        }
        String parent = file.getParent();
        return parent == null ? new File(name + ".png") : new File(parent, name + ".png");
    }

    private FormPanel buildControlPanel() {
        final int MAX = Integer.MAX_VALUE; // image dimensions aren't available at form build time, so...
        FormPanel formPanel = new FormPanel();
        formPanel.setBorderMargin(10);
        formPanel.setBorder(BorderFactory.createLoweredBevelBorder());

        final int headerSize = 16;
        formPanel.add(LabelField.createBoldHeaderLabel("Color Replace:", headerSize));

        sourceColorField = new ColorField("Source color:", ColorSelectionType.SOLID);
        sourceColorField.setColor(Color.BLACK);
        sourceColorField.addValueChangedListener(_ -> onFieldValueChanged());
        sourceColorField.setHelpText("Left click on the image to select, or choose from the color chooser popup.");
        formPanel.add(sourceColorField);

        replacementColorField = new ColorField("Replacement color:", ColorSelectionType.SOLID);
        replacementColorField.setColor(new Color(0, 0, 0, 0));
        replacementColorField.addValueChangedListener(_ -> onFieldValueChanged());
        replacementColorField.setHelpText(
                "Right click on the image to select, or choose from the color chooser popup.");
        formPanel.add(replacementColorField);

        strictnessField = new ComboField<>("Strictness:", Arrays.asList(IColorReplace.Strictness.values()), 0, false);
        strictnessField.setHelpText("Controls how closely the replacement color must match the original color.");
        strictnessField.addValueChangedListener(_ -> onFieldValueChanged());
        formPanel.add(strictnessField);

        PanelField wrapper = new PanelField(new GridBagLayout());
        wrapper.setShouldExpand(true);
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(4, 0, 4, 0);
        gbc.anchor = GridBagConstraints.CENTER;
        JPanel panel = wrapper.getPanel();
        JButton button = new JButton("Reset");
        button.addActionListener(e -> resetAll());
        button.setPreferredSize(new Dimension(150, 24));
        panel.add(button, gbc);

        gbc.gridy++;
        button = new JButton("Save and close");
        button.addActionListener(e -> saveChanges());
        button.setPreferredSize(new Dimension(150, 24));
        panel.add(button, gbc);

        gbc.gridy++;
        button = new JButton("Save As...");
        button.addActionListener(e -> saveAs());
        button.setToolTipText("Save the result to a different file, leaving the original image untouched.");
        button.setPreferredSize(new Dimension(150, 24));
        panel.add(button, gbc);

        gbc.gridy++;
        button = new JButton("Cancel");
        button.addActionListener(e -> dispose());
        button.setPreferredSize(new Dimension(150, 24));
        panel.add(button, gbc);

        panel.setBorder(BorderFactory.createLoweredBevelBorder());
        wrapper.getMargins().setTop(45);
        formPanel.add(wrapper);

        return formPanel;
    }

    private void loadImage() {
        try {
            originalImage = ImageUtil.loadImage(srcFile);
            imgWidth = originalImage.getWidth();
            imgHeight = originalImage.getHeight();
            // We create our dBuffer with an alpha channel even if the source image didn't have one:
            previewBuffer = new BufferedImage(imgWidth, imgHeight, BufferedImage.TYPE_INT_ARGB);
            resetAll();
            updatePreview();
        }
        catch (IOException | ArrayIndexOutOfBoundsException ioe) {
            getMessageUtil().error("Error loading image: " + ioe.getMessage(), ioe);
            dispose();
        }
    }

    /**
     * Invoked internally to do the color replacement on the original image and show
     * the results in our image panel. The results are NOT saved to disk as a result
     * of this method, and the original image is NOT overwritten.
     */
    private void updatePreview() {
        Graphics2D g = previewBuffer.createGraphics();
        try {
            g.drawImage(originalImage, 0, 0, null);
            Color srcColor = sourceColorField.getColor();
            Color destColor = replacementColorField.getColor();
            IColorReplace.Strictness strictness = strictnessField.getSelectedItem();

            // The intelligent implementation handles all strictness levels,
            // EXACT included (it does the same full value match, but also
            // preserves the pixel's alpha) - see its javadoc for details.
            new IntelligentColorReplace().replace(previewBuffer, srcColor, destColor, strictness, this::replaceComplete);
        }
        finally {
            g.dispose();
        }
    }

    private void replaceComplete(long runTimeMillis) {
        getMessageUtil().getLogger().log(Level.INFO, "Color replace completed in {0} ms", runTimeMillis);
        imagePanel.setImage(previewBuffer);
    }

    /**
     * Invoked internally when any of our input fields changes.
     * Re-computes the image based on the new settings and updates our preview.
     */
    private void onFieldValueChanged() {
        updatePreview();
    }

    /**
     * As usual for dialogs, ESC will cancel the dialog, and Enter
     * will prompt to save the current edit and close the dialog.
     */
    private void configureKeyboardShortcuts() {
        keyStrokeManager.clear();
        keyStrokeManager.registerHandler("esc", e -> dispose());
        keyStrokeManager.registerHandler("enter", e -> promptToSaveAndExit());
    }

    /**
     * Returns a MessageUtil instance for this dialog, creating it if needed.
     * Never use JOptionPane directly! The MessageUtil ensures that messages
     * also get logged as well as shown in a dialog. MessageUtil also contains
     * utility methods for asking questions or getting user input.
     */
    private MessageUtil getMessageUtil() {
        if (messageUtil == null) {
            messageUtil = new MessageUtil(this, Logger.getLogger(ColorReplaceDialog.class.getName()));
        }
        return messageUtil;
    }

    /**
     * Idempotent cleanup method.
     */
    private void cleanup() {
        if (imagePanel != null) {
            imagePanel.dispose();
        }
        if (keyStrokeManager != null) {
            keyStrokeManager.dispose();
        }
        if (originalImage != null) {
            originalImage.flush();
            originalImage = null;
        }
        if (previewBuffer != null) {
            previewBuffer.flush();
            previewBuffer = null;
        }
    }

    /**
     * Simple cleanup class to ensure that our cleanup is invoked no matter how the window
     * is closed - either by our own close button, or by the user clicking the "X" button on
     * the window frame.
     */
    private class WindowCleanupListener extends WindowAdapter {
        @Override
        public void windowClosing(WindowEvent e) {
            cleanup();
        }

        @Override
        public void windowClosed(WindowEvent e) {
            cleanup();
        }
    }

    /**
     * Listens for mouse clicks to allow the user to select the source color (by left
     * clicking anywhere on the image) or the replacement color (by right clicking
     * anywhere on the image). Our color input fields are updated accordingly.
     * The user also has the option of using the color input fields to select
     * any arbitrary color, even colors that don't exist in the current image.
     * <p>
     * <b>Implementation note:</b> clicking a single pixel in a typical image
     * may have problems related to jpeg artifacting or such. We might want to
     * sample the surrounding pixels and return an average color instead of the
     * exact pixel's color that was clicked. That can be a future enhancement.
     * </p>
     */
    private class MouseInputListener extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            Point p = e.getPoint();
            Point translatedPoint = imagePanel.getTranslatedPoint(p); // translate to image coordinates
            if (SwingUtilities.isLeftMouseButton(e)) {
                if (translatedPoint.x >= 0 && translatedPoint.x < imgWidth && translatedPoint.y >= 0 && translatedPoint.y < imgHeight) {
                    Color c = new Color(originalImage.getRGB(translatedPoint.x, translatedPoint.y), true);
                    sourceColorField.setColor(c);
                }
            }
            else if (SwingUtilities.isRightMouseButton(e)) {
                if (translatedPoint.x >= 0 && translatedPoint.x < imgWidth && translatedPoint.y >= 0 && translatedPoint.y < imgHeight) {
                    Color c = new Color(originalImage.getRGB(translatedPoint.x, translatedPoint.y), true);
                    replacementColorField.setColor(c);
                }
            }
        }
    }
}
