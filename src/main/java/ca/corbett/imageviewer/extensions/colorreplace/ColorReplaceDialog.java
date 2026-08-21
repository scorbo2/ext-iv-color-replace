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
import ca.corbett.imageviewer.extensions.colorreplace.impl.NaiveColorReplace;
import ca.corbett.imageviewer.ui.MainWindow;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
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
            if (srcFile.getName().toLowerCase(Locale.ROOT).endsWith("png")) {
                ImageUtil.savePngImage(originalImage, srcFile);
            }
            else if (srcFile.getName().toLowerCase(Locale.ROOT).endsWith("jpg") ||
                    srcFile.getName().toLowerCase(Locale.ROOT).endsWith("jpeg")) {
                ImageUtil.saveImage(originalImage, srcFile);
            }
            else {
                throw new IOException("Unsupported image format; must be png or jpeg image.");
            }

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

    private FormPanel buildControlPanel() {
        final int MAX = Integer.MAX_VALUE; // image dimensions aren't available at form build time, so...
        FormPanel formPanel = new FormPanel();
        formPanel.setBorderMargin(10);
        formPanel.setBorder(BorderFactory.createLoweredBevelBorder());

        final int headerSize = 16;
        formPanel.add(LabelField.createBoldHeaderLabel("Gradient:", headerSize));

        sourceColorField = new ColorField("Start color:", ColorSelectionType.SOLID);
        sourceColorField.setColor(Color.BLACK);
        sourceColorField.addValueChangedListener(_ -> onFieldValueChanged());
        sourceColorField.setHelpText("Left click on the image to select, or choose from the color chooser popup.");
        formPanel.add(sourceColorField);

        replacementColorField = new ColorField("End color:", ColorSelectionType.SOLID);
        replacementColorField.setColor(new Color(0, 0, 0, 0));
        replacementColorField.addValueChangedListener(_ -> onFieldValueChanged());
        replacementColorField.setHelpText(
                "Right click on the image to select, or choose from the color chooser popup.");
        formPanel.add(replacementColorField);

        strictnessField = new ComboField<>("Strictness:", Arrays.asList(IColorReplace.Strictness.values()), 0, false);
        strictnessField.setHelpText("Controls how closely the replacement color must match the original color.");
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

            // Special case "exact" strictness and hand it to our naive implementation:
            if (strictness == IColorReplace.Strictness.EXACT) {
                new NaiveColorReplace().replace(previewBuffer, srcColor, destColor, strictness, this::replaceComplete);
            }
            else {
                // TODO create an instance of the actual color replacement handler
                // TODO invoke it here:
                // replacer.replace(previewBuffer, srcColor, destColor, strictness, this::replaceComplete);

                // TODO remove this placeholder log:
                getMessageUtil().getLogger().info(strictness + " color replacement is not yet implemented.");
            }
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
