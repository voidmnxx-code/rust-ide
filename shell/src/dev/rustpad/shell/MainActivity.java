package dev.rustpad.shell;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** قشرة ثابتة: تعرض نصاً من Rust وزراً يستدعي Rust. لا تتغير بين المشاريع. */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        final TextView text = new TextView(this);
        text.setTextSize(20f);
        text.setGravity(Gravity.CENTER);
        text.setText(Native.hello());

        Button button = new Button(this);
        button.setText("Call Rust");
        button.setOnClickListener(v -> text.setText(Native.click()));

        root.addView(text);
        root.addView(button);
        setContentView(root);
    }
}
