# ext-iv-color-replace

## What is this?

This is an extension for the [ImageViewer](https://github.com/scorbo2/imageviewer) application that allows you to
change colors in an image intelligently. Select a color in the image (or choose
one manually), then select a replacement color. Choose the match threshold
to use, and the selected color in the image will be replaced in a live preview panel.
You can then save the result or discard it and try again.

This extension works with JPEG and PNG images.

NOTE: Not yet functional! This is a work in progress.

## How do I get it?

### Option 1: automatic download and install

Visit the "Available" tab in the extension manager dialog. Select "Color replace" from the list on the
left and then hit the "Install" button in the top right.
If you decide later to remove the extension, come back to the extension manager dialog, select "Color replace"
from the list on the left, and hit the "Uninstall" button in the top right. The application will prompt to restart.
It's just that easy!

### Option 2: manual download and install

You can manually download the extension jar:
[ext-iv-color-replace-3.3.0.jar](https://www.corbett.ca/apps/ImageViewer/extensions/3.3/ext-iv-color-replace-3.3.0.jar)

Save it to your ~/.ImageViewer/extensions directory and restart the application.

### Option 3: build from source

You can clone this repo and build the extension jar with Maven (Java 25 or higher required):

```shell
git clone https://github.com/scorbo2/ext-iv-color-replace.git
cd ext-iv-color-replace

# Note: you must have built ImageViewer and
# installed it in your local Maven repo in order
# for this step to work. See the ImageViewer 
# README for instructions.
mvn clean package

# Copy the result to extensions dir:
cp target/ext-iv-color-replace-3.3.0.jar ~/.ImageViewer/extensions/
```

## Okay, it's installed, now how do I use it?

TODO usage notes here.

## Notes

Color replacement is currently only supported for jpeg and png images.

## Requirements

Compatible with any ImageViewer 3.x release.

## License

ImageViewer and this extension are made available under the MIT license: https://opensource.org/license/mit
