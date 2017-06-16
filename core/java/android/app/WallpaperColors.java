/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Size;

import com.android.internal.graphics.palette.Palette;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Provides information about the colors of a wallpaper.
 * <p>
 * This class contains two main components:
 * <ul>
 * <li>Named colors: Most visually representative colors of a wallpaper. Can be either
 * {@link WallpaperColors#getPrimaryColor()}, {@link WallpaperColors#getSecondaryColor()}
 * or {@link WallpaperColors#getTertiaryColor()}.
 * </li>
 * <li>Hints: How colors may affect other system components. Currently the only supported hint is
 * {@link WallpaperColors#HINT_SUPPORTS_DARK_TEXT}, which specifies if dark text is preferred
 * over the wallpaper.</li>
 * </ul>
 */
public final class WallpaperColors implements Parcelable {

    /**
     * Specifies that dark text is preferred over the current wallpaper for best presentation.
     * <p>
     * eg. A launcher may set its text color to black if this flag is specified.
     */
    public static final int HINT_SUPPORTS_DARK_TEXT = 0x1;

    // Maximum size that a bitmap can have to keep our calculations sane
    private static final int MAX_BITMAP_SIZE = 112;

    // Even though we have a maximum size, we'll mainly match bitmap sizes
    // using the area instead. This way our comparisons are aspect ratio independent.
    private static final int MAX_WALLPAPER_EXTRACTION_AREA = MAX_BITMAP_SIZE * MAX_BITMAP_SIZE;

    // When extracting the main colors, only consider colors
    // present in at least MIN_COLOR_OCCURRENCE of the image
    private static final float MIN_COLOR_OCCURRENCE = 0.05f;

    // Minimum mean luminosity that an image needs to have to support dark text
    private static final float BRIGHT_IMAGE_MEAN_LUMINANCE = 0.9f;
    // We also check if the image has dark pixels in it,
    // to avoid bright images with some dark spots.
    private static final float DARK_PIXEL_LUMINANCE = 0.45f;
    private static final float MAX_DARK_AREA = 0.05f;

    private final ArrayList<Color> mMainColors;
    private int mColorHints;

    public WallpaperColors(Parcel parcel) {
        mMainColors = new ArrayList<>();
        final int count = parcel.readInt();
        for (int i = 0; i < count; i++) {
            final int colorInt = parcel.readInt();
            Color color = Color.valueOf(colorInt);
            mMainColors.add(color);
        }
        mColorHints = parcel.readInt();
    }

    /**
     * Constructs {@link WallpaperColors} from a drawable.
     * <p>
     * Main colors will be extracted from the drawable and hints will be calculated.
     *
     * @see WallpaperColors#HINT_SUPPORTS_DARK_TEXT
     * @param drawable Source where to extract from.
     */
    public static WallpaperColors fromDrawable(Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();

        // Some drawables do not have intrinsic dimensions
        if (width <= 0 || height <= 0) {
            width = MAX_BITMAP_SIZE;
            height = MAX_BITMAP_SIZE;
        }

        Size optimalSize = calculateOptimalSize(width, height);
        Bitmap bitmap = Bitmap.createBitmap(optimalSize.getWidth(), optimalSize.getHeight(),
                Bitmap.Config.ARGB_8888);
        final Canvas bmpCanvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, bitmap.getWidth(), bitmap.getHeight());
        drawable.draw(bmpCanvas);

        final WallpaperColors colors = WallpaperColors.fromBitmap(bitmap);
        bitmap.recycle();

        return colors;
    }

    /**
     * Constructs {@link WallpaperColors} from a bitmap.
     * <p>
     * Main colors will be extracted from the bitmap and hints will be calculated.
     *
     * @see WallpaperColors#HINT_SUPPORTS_DARK_TEXT
     * @param bitmap Source where to extract from.
     */
    public static WallpaperColors fromBitmap(@NonNull Bitmap bitmap) {
        if (bitmap == null) {
            throw new IllegalArgumentException("Bitmap can't be null");
        }

        final int bitmapArea = bitmap.getWidth() * bitmap.getHeight();
        if (bitmapArea > MAX_WALLPAPER_EXTRACTION_AREA) {
            Size optimalSize = calculateOptimalSize(bitmap.getWidth(), bitmap.getHeight());
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, optimalSize.getWidth(),
                    optimalSize.getHeight(), true /* filter */);
            bitmap.recycle();
            bitmap = scaledBitmap;
        }

        final Palette palette = Palette
                .from(bitmap)
                .clearFilters()
                .resizeBitmapArea(MAX_WALLPAPER_EXTRACTION_AREA)
                .generate();

        // Remove insignificant colors and sort swatches by population
        final ArrayList<Palette.Swatch> swatches = new ArrayList<>(palette.getSwatches());
        final float minColorArea = bitmap.getWidth() * bitmap.getHeight() * MIN_COLOR_OCCURRENCE;
        swatches.removeIf(s -> s.getPopulation() < minColorArea);
        swatches.sort((a, b) -> b.getPopulation() - a.getPopulation());

        final int swatchesSize = swatches.size();
        Color primary = null, secondary = null, tertiary = null;

        swatchLoop:
        for (int i = 0; i < swatchesSize; i++) {
            Color color = Color.valueOf(swatches.get(i).getRgb());
            switch (i) {
                case 0:
                    primary = color;
                    break;
                case 1:
                    secondary = color;
                    break;
                case 2:
                    tertiary = color;
                    break;
                default:
                    // out of bounds
                    break swatchLoop;
            }
        }

        int hints = 0;
        if (calculateDarkTextSupport(bitmap)) {
            hints |= HINT_SUPPORTS_DARK_TEXT;
        }
        return new WallpaperColors(primary, secondary, tertiary, hints);
    }

    /**
     * Constructs a new object from three colors, where hints can be specified.
     *
     * @param primaryColor Primary color.
     * @param secondaryColor Secondary color.
     * @param tertiaryColor Tertiary color.
     * @param colorHints A combination of WallpaperColor hints.
     * @see WallpaperColors#HINT_SUPPORTS_DARK_TEXT
     * @see WallpaperColors#fromBitmap(Bitmap)
     * @see WallpaperColors#fromDrawable(Drawable)
     */
    public WallpaperColors(@NonNull Color primaryColor, @Nullable Color secondaryColor,
            @Nullable Color tertiaryColor, int colorHints) {

        if (primaryColor == null) {
            throw new IllegalArgumentException("Primary color should never be null.");
        }

        mMainColors = new ArrayList<>(3);
        mMainColors.add(primaryColor);
        if (secondaryColor != null) {
            mMainColors.add(secondaryColor);
        }
        if (tertiaryColor != null) {
            if (secondaryColor == null) {
                throw new IllegalArgumentException("tertiaryColor can't be specified when "
                        + "secondaryColor is null");
            }
            mMainColors.add(tertiaryColor);
        }

        mColorHints = colorHints;
    }

    public static final Creator<WallpaperColors> CREATOR = new Creator<WallpaperColors>() {
        @Override
        public WallpaperColors createFromParcel(Parcel in) {
            return new WallpaperColors(in);
        }

        @Override
        public WallpaperColors[] newArray(int size) {
            return new WallpaperColors[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        List<Color> mainColors = getMainColors();
        int count = mainColors.size();
        dest.writeInt(count);
        for (int i = 0; i < count; i++) {
            Color color = mainColors.get(i);
            dest.writeInt(color.toArgb());
        }
        dest.writeInt(mColorHints);
    }

    /**
     * Gets the most visually representative color of the wallpaper.
     * "Visually representative" means easily noticeable in the image,
     * probably happening at high frequency.
     *
     * @return A color.
     */
    public @NonNull Color getPrimaryColor() {
        return mMainColors.get(0);
    }

    /**
     * Gets the second most preeminent color of the wallpaper. Can be null.
     *
     * @return A color, may be null.
     */
    public @Nullable Color getSecondaryColor() {
        return mMainColors.size() < 2 ? null : mMainColors.get(1);
    }

    /**
     * Gets the third most preeminent color of the wallpaper. Can be null.
     *
     * @return A color, may be null.
     */
    public @Nullable Color getTertiaryColor() {
        return mMainColors.size() < 3 ? null : mMainColors.get(2);
    }

    /**
     * List of most preeminent colors, sorted by importance.
     *
     * @return List of colors.
     * @hide
     */
    public @NonNull List<Color> getMainColors() {
        return Collections.unmodifiableList(mMainColors);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        WallpaperColors other = (WallpaperColors) o;
        return mMainColors.equals(other.mMainColors)
                && mColorHints == other.mColorHints;
    }

    @Override
    public int hashCode() {
        return 31 * mMainColors.hashCode() + mColorHints;
    }

    /**
     * Combination of WallpaperColor hints.
     *
     * @see WallpaperColors#HINT_SUPPORTS_DARK_TEXT
     * @return True if dark text is supported.
     */
    public int getColorHints() {
        return mColorHints;
    }

    /**
     * @param colorHints Combination of WallpaperColors hints.
     * @see WallpaperColors#HINT_SUPPORTS_DARK_TEXT
     * @hide
     */
    public void setColorHints(int colorHints) {
        mColorHints = colorHints;
    }

    /**
     * Checks if image is bright and clean enough to support light text.
     *
     * @param source What to read.
     * @return Whether image supports dark text or not.
     */
    private static boolean calculateDarkTextSupport(Bitmap source) {
        if (source == null) {
            return false;
        }

        int[] pixels = new int[source.getWidth() * source.getHeight()];
        double totalLuminance = 0;
        final int maxDarkPixels = (int) (pixels.length * MAX_DARK_AREA);
        int darkPixels = 0;
        source.getPixels(pixels, 0 /* offset */, source.getWidth(), 0 /* x */, 0 /* y */,
                source.getWidth(), source.getHeight());

        // This bitmap was already resized to fit the maximum allowed area.
        // Let's just loop through the pixels, no sweat!
        for (int i = 0; i < pixels.length; i++) {
            final float luminance = Color.luminance(pixels[i]);
            final int alpha = Color.alpha(pixels[i]);

            // Make sure we don't have a dark pixel mass that will
            // make text illegible.
            if (luminance < DARK_PIXEL_LUMINANCE && alpha != 0) {
                darkPixels++;
                if (darkPixels > maxDarkPixels) {
                    return false;
                }
            }

            totalLuminance += luminance;
        }
        return totalLuminance / pixels.length > BRIGHT_IMAGE_MEAN_LUMINANCE;
    }

    private static Size calculateOptimalSize(int width, int height) {
        // Calculate how big the bitmap needs to be.
        // This avoids unnecessary processing and allocation inside Palette.
        final int requestedArea = width * height;
        double scale = 1;
        if (requestedArea > MAX_WALLPAPER_EXTRACTION_AREA) {
            scale = Math.sqrt(MAX_WALLPAPER_EXTRACTION_AREA / (double) requestedArea);
        }
        int newWidth = (int) (width * scale);
        int newHeight = (int) (height * scale);
        // Dealing with edge cases of the drawable being too wide or too tall.
        // Width or height would end up being 0, in this case we'll set it to 1.
        if (newWidth == 0) {
            newWidth = 1;
        }
        if (newHeight == 0) {
            newHeight = 1;
        }

        return new Size(newWidth, newHeight);
    }
}
