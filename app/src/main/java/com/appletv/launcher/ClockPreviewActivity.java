package com.appletv.launcher;

import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Full-screen clock preview launched from Control Center. */
public final class ClockPreviewActivity extends LauncherActivity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.dream_clock);

        LinearLayout content = findViewById(R.id.dreamContent);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) content.getLayoutParams();
        params.gravity = Gravity.CENTER;
        content.setLayoutParams(params);
    }
}
