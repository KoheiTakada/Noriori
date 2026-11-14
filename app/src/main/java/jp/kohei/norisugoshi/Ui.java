package jp.kohei.norisugoshi;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int INK = DesignTokens.Color.INK;
    static final int MUTED = DesignTokens.Color.MUTED;
    static final int BLUE = DesignTokens.Color.PRIMARY;
    static final int PALE = DesignTokens.Color.PRIMARY_CONTAINER;

    private Ui() {
    }

    static int dp(Context context, int value) {
        return DesignSystem.dp(context, value);
    }

    static TextView text(Context context, String value, int size, boolean bold) {
        return DesignSystem.text(context, value, size, bold);
    }

    static GradientDrawable background(Context context, int color, int radius) {
        return DesignSystem.shape(context, color, radius);
    }

    static Button button(Context context, String label, boolean primary, Runnable action) {
        return DesignSystem.button(context, label, primary ? DesignSystem.Action.FILLED
                : DesignSystem.Action.TONAL, action);
    }

    static ImageButton iconButton(Context context, int icon, String label, Runnable action) {
        return DesignSystem.iconButton(context, icon, label, action);
    }

    static LinearLayout.LayoutParams margins(Context context, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(context, top), 0, dp(context, bottom));
        return params;
    }

    static void applyInsets(View view) {
        int left = view.getPaddingLeft();
        int top = view.getPaddingTop();
        int right = view.getPaddingRight();
        int bottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener((target, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout()
                );
                target.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            } else {
                applyLegacyInsets(target, insets, left, top, right, bottom);
            }
            return insets;
        });
    }

    @SuppressWarnings("deprecation")
    private static void applyLegacyInsets(
            View target,
            android.view.WindowInsets insets,
            int left,
            int top,
            int right,
            int bottom
    ) {
        target.setPadding(
                left + insets.getSystemWindowInsetLeft(),
                top + insets.getSystemWindowInsetTop(),
                right + insets.getSystemWindowInsetRight(),
                bottom + insets.getSystemWindowInsetBottom()
        );
    }
}
