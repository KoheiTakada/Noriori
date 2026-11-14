package jp.kohei.norisugoshi;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

public final class StationPickerDialog extends Dialog {
    public interface OnSelected { boolean onSelected(StationCatalog.Entry station); }
    private static final ExecutorService SEARCH = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "station-candidates"); thread.setDaemon(true); return thread;
    });
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final double latitude, longitude;
    private final OnSelected selected;
    private final Predicate<StationCatalog.Entry> allowed;
    private final List<StationCatalog.Entry> results = new ArrayList<>();
    private final EditText query;
    private final CandidateAdapter adapter = new CandidateAdapter();
    private final TextView empty;
    private final LinearLayout root;
    private int generation;
    private boolean closed;

    public StationPickerDialog(Context context, String hint, double latitude, double longitude, OnSelected selected) {
        this(context, hint, latitude, longitude, entry -> true, selected);
    }
    public StationPickerDialog(Context context, String hint, double latitude, double longitude,
                               Predicate<StationCatalog.Entry> allowed, OnSelected selected) {
        super(context);
        this.latitude = latitude; this.longitude = longitude; this.selected = selected;
        this.allowed = allowed;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.background(context, Color.WHITE, DesignTokens.Shape.LARGE));
        root.setPadding(Ui.dp(context, DesignTokens.Space.LG), Ui.dp(context, DesignTokens.Space.LG),
                Ui.dp(context, DesignTokens.Space.LG), Ui.dp(context, DesignTokens.Space.SM));
        LinearLayout search = new LinearLayout(context); search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(Ui.background(context, DesignTokens.Color.CONTAINER, DesignTokens.Shape.PILL));
        search.addView(Ui.iconButton(context, R.drawable.ic_close,
                "閉じる", this::dismiss), new LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 56)));
        query = new EditText(context); query.setSingleLine(true); query.setHint(hint);
        query.setTextSize(DesignTokens.Type.BODY); query.setBackgroundColor(Color.TRANSPARENT);
        query.setTextColor(Ui.INK); query.setHintTextColor(Ui.MUTED);
        query.setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 16), 0);
        query.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        query.setContentDescription(hint); query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.addView(query, new LinearLayout.LayoutParams(0, Ui.dp(context, 56), 1));
        root.addView(search);
        ListView list = new ListView(context); list.setAdapter(adapter); list.setDivider(null);
        list.setSelector(DesignSystem.ripple(context, Color.TRANSPARENT, DesignTokens.Shape.MEDIUM, Ui.BLUE));
        list.setPadding(0, Ui.dp(context, DesignTokens.Space.SM), 0, 0);
        list.setVerticalScrollBarEnabled(false);
        list.setContentDescription("駅の候補");
        list.setOnItemClickListener((parent, view, position, id) -> {
            StationCatalog.Entry entry = results.get(position);
            if (selected.onSelected(entry)) dismiss();
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = Ui.text(context, "", 14, false); empty.setGravity(Gravity.CENTER); empty.setTextColor(Ui.MUTED);
        root.addView(empty, new LinearLayout.LayoutParams(-1, Ui.dp(context, 40)));
        list.setEmptyView(empty);
        TextView credit = Ui.text(context, "© 国土交通省", DesignTokens.Type.CAPTION, false);
        credit.setTextColor(Ui.MUTED); credit.setGravity(Gravity.END);
        credit.setMinimumHeight(Ui.dp(context, DesignTokens.Size.TOUCH));
        credit.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        credit.setOnClickListener(view -> context.startActivity(new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2025.html"))));
        root.addView(credit);
        setContentView(root);
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams windowParams = window.getAttributes();
            windowParams.dimAmount = 0.32f;
            window.setAttributes(windowParams);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        query.setOnEditorActionListener((view, action, event) -> { refresh(); return true; });
    }
    /** Optional current selection for a single, immediately saved destination station. */
    void setCurrentStation(String name, String lines, Runnable remove) {
        Context context = getContext();
        LinearLayout current = new LinearLayout(context); current.setGravity(Gravity.CENTER_VERTICAL);
        current.setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8));
        current.setBackground(Ui.background(context, DesignTokens.Color.PRIMARY_CONTAINER, DesignTokens.Shape.MEDIUM));
        current.addView(DesignSystem.icon(context, R.drawable.ic_check, Ui.BLUE, 24));
        LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 8), 0);
        TextView selectedName = Ui.text(context, name, 16, true); selectedName.setSingleLine(true);
        selectedName.setEllipsize(TextUtils.TruncateAt.END); labels.addView(selectedName);
        if (!lines.isEmpty()) {
            TextView detail = Ui.text(context, lines, 12, false); detail.setTextColor(Ui.MUTED);
            detail.setSingleLine(true); detail.setEllipsize(TextUtils.TruncateAt.END); labels.addView(detail);
        }
        current.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Button delete = DesignSystem.button(context, "削除", DesignSystem.Action.QUIET, remove);
        delete.setTextColor(DesignTokens.Color.DANGER); delete.setContentDescription("家の最寄り駅を削除");
        current.addView(delete, new LinearLayout.LayoutParams(-2, Ui.dp(context, 48)));
        current.setContentDescription("登録済みの家の最寄り駅");
        root.addView(current, root.getChildCount() - 1);
    }
    @Override public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) {
            int screenHeight = getContext().getResources().getDisplayMetrics().heightPixels;
            int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(screenWidth, Ui.dp(getContext(), DesignTokens.Size.CONTENT_MAX)),
                    Math.min(Ui.dp(getContext(), DesignTokens.Size.SHEET_MAX), (int)(screenHeight * 0.7)));
        }
        refresh();
    }
    @Override public void dismiss() {
        closed = true; generation++; handler.removeCallbacksAndMessages(null); super.dismiss();
    }
    private void refresh() {
        int request = ++generation;
        String input = query.getText().toString();
        handler.postDelayed(() -> SEARCH.execute(() -> {
            try {
                List<StationCatalog.Entry> nearby = StationCatalog.suggest(getContext().getApplicationContext(),
                        input, latitude, longitude, 96);
                List<StationCatalog.Entry> found = new ArrayList<>();
                for (StationCatalog.Entry entry : nearby) {
                    if (allowed.test(entry)) found.add(entry);
                    if (found.size() == 24) break;
                }
                handler.post(() -> {
                    if (closed || request != generation) return;
                    results.clear(); results.addAll(found); adapter.notifyDataSetChanged();
                    empty.setText(!found.isEmpty() ? "" : nearby.isEmpty()
                            ? "駅が見つからない" : "通知する円と重なる");
                });
            } catch (IOException error) {
                handler.post(() -> { if (!closed && request == generation) empty.setText("駅を読み込めない"); });
            }
        }), input.isEmpty() ? 0 : 120);
    }
    private final class CandidateAdapter extends BaseAdapter {
        @Override public int getCount() { return results.size(); }
        @Override public StationCatalog.Entry getItem(int position) { return results.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            StationCatalog.Entry entry = getItem(position);
            Context context = getContext();
            LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Ui.dp(context, 72));
            row.setPadding(Ui.dp(context, DesignTokens.Space.MD), Ui.dp(context, DesignTokens.Space.MD),
                    Ui.dp(context, DesignTokens.Space.SM), Ui.dp(context, DesignTokens.Space.MD));
            ImageView icon = DesignSystem.icon(context, R.drawable.ic_train, Ui.MUTED, 40);
            icon.setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8));
            icon.setBackground(Ui.background(context, DesignTokens.Color.CONTAINER, DesignTokens.Shape.PILL));
            row.addView(icon);
            LinearLayout labels = new LinearLayout(context); labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(Ui.dp(context, 16), 0, 0, 0);
            TextView name = Ui.text(context, entry.name, DesignTokens.Type.BODY, true); name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END); labels.addView(name);
            TextView lines = Ui.text(context, entry.lines, DesignTokens.Type.CAPTION, false); lines.setTextColor(Ui.MUTED);
            lines.setPadding(0, Ui.dp(context, 4), 0, 0);
            lines.setSingleLine(true); lines.setEllipsize(TextUtils.TruncateAt.END); labels.addView(lines);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(DesignSystem.icon(context, R.drawable.ic_chevron, Ui.MUTED, 20));
            row.setContentDescription(entry.name + "を選ぶ");
            return row;
        }
    }
}
