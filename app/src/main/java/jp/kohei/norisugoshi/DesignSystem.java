package jp.kohei.norisugoshi;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Shared product components. Geometry and notification state belong to their existing models. */
final class DesignSystem {
    enum Text { TITLE, STATION, BODY, LABEL, CAPTION }
    enum Action { FILLED, TONAL, QUIET, DANGER }
    private static final int[] DISABLED = {-android.R.attr.state_enabled};
    private static final int[] CHECKED = {android.R.attr.state_checked};
    private static final int[] NORMAL = {};

    private DesignSystem() { }

    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, String value, Text role) {
        int size = role == Text.TITLE ? DesignTokens.Type.TITLE : role == Text.STATION
                ? DesignTokens.Type.STATION : role == Text.BODY ? DesignTokens.Type.BODY
                : role == Text.LABEL ? DesignTokens.Type.LABEL : DesignTokens.Type.CAPTION;
        return text(c, value, size, role != Text.BODY && role != Text.CAPTION);
    }

    static TextView text(Context c, String value, int size, boolean emphasized) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(DesignTokens.Color.INK);
        view.setTypeface(Typeface.create(emphasized ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(c, 2), 1f);
        return view;
    }

    static GradientDrawable shape(Context c, int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(c, radius));
        return result;
    }

    static Drawable ripple(Context c, int fill, int radius, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(alpha(rippleColor, 24)),
                shape(c, fill, radius), shape(c, Color.WHITE, radius));
    }

    static Button button(Context c, String label, Action action, Runnable clicked) {
        Button button = new Button(c);
        button.setText(label);
        button.setTextSize(DesignTokens.Type.LABEL);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setIncludeFontPadding(false);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(c, DesignTokens.Size.CONTROL));
        button.setMinWidth(dp(c, DesignTokens.Size.TOUCH));
        button.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), dp(c, 8));
        button.setStateListAnimator(null);
        styleButton(button, action);
        button.setOnClickListener(view -> clicked.run());
        return button;
    }

    static void styleButton(Button button, Action action) {
        Context c = button.getContext();
        int fill = action == Action.FILLED ? DesignTokens.Color.PRIMARY
                : action == Action.DANGER ? DesignTokens.Color.DANGER
                : action == Action.TONAL ? DesignTokens.Color.PRIMARY_CONTAINER : Color.TRANSPARENT;
        int ink = action == Action.FILLED || action == Action.DANGER ? DesignTokens.Color.ON_PRIMARY
                : DesignTokens.Color.ON_PRIMARY_CONTAINER;
        StateListDrawable backgrounds = new StateListDrawable();
        backgrounds.addState(DISABLED, shape(c, DesignTokens.Color.DISABLED, DesignTokens.Shape.PILL));
        backgrounds.addState(NORMAL, shape(c, fill, DesignTokens.Shape.PILL));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(alpha(ink, 28)), backgrounds,
                shape(c, Color.WHITE, DesignTokens.Shape.PILL)));
        button.setTextColor(new ColorStateList(new int[][]{DISABLED, NORMAL},
                new int[]{DesignTokens.Color.DISABLED_INK, ink}));
    }

    static ImageButton iconButton(Context c, int icon, String description, Runnable clicked) {
        ImageButton button = new ImageButton(c);
        button.setImageResource(icon);
        button.setImageTintList(ColorStateList.valueOf(DesignTokens.Color.INK));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setContentDescription(description);
        button.setBackground(ripple(c, Color.TRANSPARENT, DesignTokens.Shape.PILL, DesignTokens.Color.INK));
        button.setMinimumWidth(dp(c, DesignTokens.Size.TOUCH));
        button.setMinimumHeight(dp(c, DesignTokens.Size.TOUCH));
        button.setOnClickListener(view -> clicked.run());
        return button;
    }

    static ImageView icon(Context c, int icon, int tint, int size) {
        ImageView view = new ImageView(c);
        view.setImageResource(icon);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(c, size), dp(c, size)));
        return view;
    }

    static void selection(Button button, boolean selected) {
        Context c = button.getContext();
        button.setSelected(selected);
        button.setTextColor(selected ? DesignTokens.Color.ON_PRIMARY_CONTAINER : DesignTokens.Color.MUTED);
        button.setBackground(ripple(c, selected ? DesignTokens.Color.PRIMARY_CONTAINER : DesignTokens.Color.CONTAINER,
                DesignTokens.Shape.PILL, DesignTokens.Color.PRIMARY));
        button.setCompoundDrawablePadding(dp(c, 6));
        button.setCompoundDrawablesRelative(null, null, null, null);
        if (android.os.Build.VERSION.SDK_INT >= 30) button.setStateDescription(selected ? "選択中" : null);
    }

    static Switch stateSwitch(Context c, boolean checked, String description) {
        Switch view = new Switch(c);
        view.setShowText(false);
        view.setSplitTrack(false);
        view.setSwitchMinWidth(dp(c, 52));
        view.setMinimumHeight(dp(c, DesignTokens.Size.TOUCH));
        view.setGravity(Gravity.CENTER);
        view.setThumbTintList(null);
        view.setTrackTintList(null);
        StateListDrawable tracks = new StateListDrawable();
        GradientDrawable on = shape(c, DesignTokens.Color.PRIMARY, DesignTokens.Shape.PILL);
        on.setSize(dp(c, 52), dp(c, 32));
        GradientDrawable off = shape(c, DesignTokens.Color.DISABLED, DesignTokens.Shape.PILL);
        off.setStroke(dp(c, 2), DesignTokens.Color.MUTED);
        off.setSize(dp(c, 52), dp(c, 32));
        tracks.addState(CHECKED, on); tracks.addState(NORMAL, off);
        view.setTrackDrawable(tracks); view.setThumbDrawable(new SwitchThumb(c, tracks));
        view.setChecked(checked);
        view.setContentDescription(description);
        return view;
    }

    static void slider(SeekBar slider) {
        slider.setProgressTintList(ColorStateList.valueOf(DesignTokens.Color.PRIMARY));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(DesignTokens.Color.OUTLINE));
        slider.setThumbTintList(ColorStateList.valueOf(DesignTokens.Color.PRIMARY));
        slider.setMinimumHeight(dp(slider.getContext(), DesignTokens.Size.TOUCH));
        slider.setPadding(dp(slider.getContext(), 8), 0, dp(slider.getContext(), 8), 0);
    }

    static void styleDialog(AlertDialog dialog, boolean destructive) {
        dialog.getWindow().setBackgroundDrawable(shape(dialog.getContext(), DesignTokens.Color.SURFACE,
                DesignTokens.Shape.LARGE));
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            Button button = dialog.getButton(which);
            if (button != null) {
                button.setAllCaps(false);
                button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                button.setTextColor(which == AlertDialog.BUTTON_POSITIVE && destructive
                        ? DesignTokens.Color.DANGER : DesignTokens.Color.PRIMARY);
                button.setMinHeight(dp(dialog.getContext(), DesignTokens.Size.TOUCH));
            }
        }
    }

    @SuppressWarnings("deprecation")
    static void window(Activity activity) {
        activity.getWindow().setStatusBarColor(DesignTokens.Color.CANVAS);
        activity.getWindow().setNavigationBarColor(DesignTokens.Color.SURFACE);
        activity.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    static boolean wide(Context c) { return c.getResources().getConfiguration().screenWidthDp >= 600; }
    static boolean animationsEnabled() { return ValueAnimator.areAnimatorsEnabled(); }
    static int alpha(int color, int alpha) { return (color & 0xFFFFFF) | (alpha << 24); }

    /** Map the native thumb motion into the track's rounded ends without widening the switch. */
    private static final class SwitchThumb extends Drawable {
        private final Context context;
        private final Drawable track;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        SwitchThumb(Context context, Drawable track) { this.context = context; this.track = track; }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] states) {
            boolean next = false;
            for (int state : states) if (state == android.R.attr.state_checked) next = true;
            if (next == checked) return false;
            checked = next; invalidateSelf(); return true;
        }
        @Override public void draw(Canvas canvas) {
            paint.setColor(checked ? Color.WHITE : DesignTokens.Color.MUTED);
            float center = getBounds().exactCenterX();
            int width = track.getBounds().width(), height = track.getBounds().height();
            if (width > getIntrinsicWidth()) {
                float progress = (center - track.getBounds().left - getIntrinsicWidth() / 2f)
                        / (width - getIntrinsicWidth());
                progress = Math.max(0, Math.min(1, progress));
                center = track.getBounds().left + height / 2f + progress * (width - height);
            }
            canvas.drawCircle(center, getBounds().exactCenterY(),
                    dp(context, checked ? 12 : 8), paint);
        }
        @Override public int getIntrinsicWidth() { return dp(context, 26); }
        @Override public int getIntrinsicHeight() { return dp(context, 32); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Keeps lists readable on an unfolded phone without hardcoding a device width. */
    static final class ContentColumn extends LinearLayout {
        ContentColumn(Context context) { super(context); setOrientation(VERTICAL); }
        @Override protected void onMeasure(int width, int height) {
            int max = dp(getContext(), DesignTokens.Size.CONTENT_MAX);
            if (MeasureSpec.getSize(width) > max) width = MeasureSpec.makeMeasureSpec(max, MeasureSpec.EXACTLY);
            super.onMeasure(width, height);
        }
    }
}
