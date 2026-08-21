package ca.corbett.imageviewer.extensions.colorreplace;

import ca.corbett.extras.EnhancedAction;
import ca.corbett.imageviewer.ui.ImageInstance;
import ca.corbett.imageviewer.ui.MainWindow;

import java.awt.event.ActionEvent;
import java.io.File;
import java.util.Locale;

public class ColorReplaceAction extends EnhancedAction {
    private static ColorReplaceAction instance;
    private static final String NAME = "Color replace...";

    private ColorReplaceAction() {
        super(NAME); // no icon for this action
    }

    public static ColorReplaceAction getInstance() {
        if (instance == null) {
            instance = new ColorReplaceAction();
        }
        return instance;
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        ImageInstance currentImage = MainWindow.getInstance().getSelectedImage();
        if (currentImage.isEmpty()) {
            MainWindow.getInstance().showMessageDialog("Color replace", "Nothing selected.");
            return;
        }

        // Ensure correct file format:
        File file = currentImage.getImageFile();
        String filename = file.getName().toLowerCase(Locale.ROOT);
        if (!filename.endsWith("jpg")
                && !filename.endsWith("jpeg")
                && !filename.endsWith("png")) {
            MainWindow.getInstance().showMessageDialog("Color replace",
                                                       "Color replace can currently only be performed on jpeg or png images.");
            return;
        }

        new ColorReplaceDialog(file).setVisible(true);
    }
}
