package dev.rustpad.shell;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * قشرة عامة: ترسم واجهة يصفها Rust بـ JSON وترسل إليه الأحداث (Elm-style).
 * العناصر: col, row, text, button, input, checkbox, list, progress, spacer.
 */
public class MainActivity extends Activity {
    private final Map<String, EditText> inputs = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private int tickMs = 0;
    private final Runnable tickTask = new Runnable() {
        @Override public void run() {
            if (tickMs <= 0) return;
            send("tick", "");
            if (tickMs > 0) { handler.removeCallbacks(this); handler.postDelayed(this, tickMs); }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        setContentView(scroll);
        try {
            apply(Native.init());
        } catch (UnsatisfiedLinkError e) {
            legacyUi(e);
        } catch (Throwable e) {
            showError("Failed to start Rust UI: " + e);
        }
    }

    @Override protected void onPause() { handler.removeCallbacks(tickTask); super.onPause(); }

    @Override protected void onResume() {
        super.onResume();
        if (tickMs > 0) { handler.removeCallbacks(tickTask); handler.postDelayed(tickTask, tickMs); }
    }

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    // ---------- تطبيق JSON ----------
    private void apply(String json) {
        if (json == null || json.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("title")) setTitle(o.getString("title"));
            if (o.has("root")) {
                final int y = scroll.getScrollY();
                inputs.clear();
                View v = build(o.getJSONObject("root"));
                scroll.removeAllViews();
                scroll.addView(v);
                scroll.post(() -> scroll.scrollTo(0, y));
            }
            String t = o.optString("toast", "");
            if (!t.isEmpty()) Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
            setTick(o.optInt("tick_ms", 0));
        } catch (Exception e) {
            showError("UI error: " + e);
        }
    }

    private void setTick(int ms) {
        if (ms == tickMs) return;
        handler.removeCallbacks(tickTask);
        tickMs = ms;
        if (ms > 0) handler.postDelayed(tickTask, ms);
    }

    private void send(String id, String value) {
        try {
            apply(Native.event(id, value, encodeInputs()));
        } catch (Throwable e) {
            showError("Rust error: " + e);
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n");
    }

    private String encodeInputs() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, EditText> e : inputs.entrySet()) {
            sb.append(esc(e.getKey())).append('\t').append(esc(e.getValue().getText().toString())).append('\n');
        }
        return sb.toString();
    }

    private void showError(String msg) {
        TextView tv = new TextView(this);
        tv.setText(msg);
        tv.setTextColor(Color.RED);
        tv.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.removeAllViews();
        scroll.addView(tv);
    }

    // ---------- بناء العناصر ----------
    private View build(JSONObject n) throws JSONException {
        String t = n.optString("t");
        switch (t) {
            case "col":
            case "row": return buildBox(n, t.equals("row"));
            case "text": {
                TextView tv = new TextView(this);
                tv.setText(n.optString("text"));
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 16));
                if (n.optBoolean("bold")) tv.setTypeface(null, Typeface.BOLD);
                color(tv, n);
                if ("center".equals(n.optString("align"))) tv.setGravity(Gravity.CENTER_HORIZONTAL);
                return tv;
            }
            case "button": {
                Button b = new Button(this);
                b.setText(n.optString("text"));
                b.setAllCaps(false);
                final String id = n.optString("id");
                b.setOnClickListener(v -> send(id, ""));
                return b;
            }
            case "input": {
                final EditText et = new EditText(this);
                final String id = n.optString("id");
                et.setText(n.optString("text"));
                et.setHint(n.optString("hint"));
                boolean multi = n.optBoolean("multiline");
                String kind = n.optString("kind");
                if ("number".equals(kind)) {
                    et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                } else if ("password".equals(kind)) {
                    et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                } else {
                    et.setInputType(InputType.TYPE_CLASS_TEXT | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
                }
                et.setSingleLine(!multi);
                if (!multi) {
                    et.setImeOptions(EditorInfo.IME_ACTION_DONE);
                    et.setOnEditorActionListener((v, a, e) -> { send(id, et.getText().toString()); return true; });
                }
                inputs.put(id, et);
                return et;
            }
            case "checkbox": {
                CheckBox cb = new CheckBox(this);
                cb.setText(n.optString("text"));
                cb.setChecked(n.optBoolean("checked"));
                final String id = n.optString("id");
                cb.setOnCheckedChangeListener((v, checked) -> send(id, checked ? "true" : "false"));
                return cb;
            }
            case "list": {
                LinearLayout l = new LinearLayout(this);
                l.setOrientation(LinearLayout.VERTICAL);
                final String id = n.optString("id");
                JSONArray items = n.optJSONArray("items");
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    TextView tv = new TextView(this);
                    tv.setText(items.getString(i));
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    tv.setPadding(dp(8), dp(12), dp(8), dp(12));
                    tv.setBackgroundResource(android.R.drawable.list_selector_background);
                    final int idx = i;
                    tv.setOnClickListener(v -> send(id, String.valueOf(idx)));
                    l.addView(tv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                return l;
            }
            case "progress": {
                ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                p.setMax(1000);
                p.setProgress((int) (n.optDouble("value", 0) * 1000));
                return p;
            }
            case "spacer": {
                View v = new View(this);
                int s = dp(n.optInt("size", 8));
                v.setMinimumHeight(s);
                v.setMinimumWidth(s);
                return v;
            }
            default: {
                TextView tv = new TextView(this);
                tv.setText("[unknown widget: " + t + "]");
                tv.setTextColor(Color.RED);
                return tv;
            }
        }
    }

    private View buildBox(JSONObject n, boolean row) throws JSONException {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (row) l.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(n.optInt("pad", 0));
        l.setPadding(pad, pad, pad, pad);
        int gap = dp(n.optInt("gap", 0));
        JSONArray ch = n.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) {
            JSONObject c = ch.getJSONObject(i);
            View v = build(c);
            float w = (float) c.optDouble("weight", 0);
            LinearLayout.LayoutParams lp = row
                ? new LinearLayout.LayoutParams(w > 0 ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, w)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w > 0 ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, w);
            if (i > 0) { if (row) lp.leftMargin = gap; else lp.topMargin = gap; }
            l.addView(v, lp);
        }
        return l;
    }

    private void color(TextView tv, JSONObject n) {
        String c = n.optString("color", "");
        if (c.isEmpty()) return;
        try { tv.setTextColor(Color.parseColor(c)); } catch (IllegalArgumentException ignored) { }
    }

    // ---------- توافق مع مشاريع قديمة (hello/click) ----------
    private void legacyUi(Throwable cause) {
        try {
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER);
            final TextView text = new TextView(this);
            text.setTextSize(20f);
            text.setGravity(Gravity.CENTER);
            text.setText(Native.hello());
            Button button = new Button(this);
            button.setText("Call Rust");
            button.setOnClickListener(v -> text.setText(Native.click()));
            root.addView(text);
            root.addView(button);
            scroll.removeAllViews();
            scroll.addView(root);
        } catch (Throwable t) {
            showError("Native library problem: " + cause + "\n" + t);
        }
    }
}
