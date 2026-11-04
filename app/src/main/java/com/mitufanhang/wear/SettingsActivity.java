package com.mitufanhang.wear;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Small, scrollable settings screen. Every change is saved immediately. */
public final class SettingsActivity extends Activity {

    private static final int[] INTERVALS = {1000, 2000, 5000};

    private AppSettings settings;
    private WatchFrameLayout root;
    private TextView title;
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout shapeGroup;
    private LinearLayout headingGroup;
    private LinearLayout intervalGroup;
    private TextView shapeNote;
    private TextView keepValue;
    private TextView vibrateValue;
    private LinearLayout keepRow;
    private LinearLayout vibrateRow;
    private android.app.Dialog dialog;

    private interface Picker {
        void onPick(int index);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new AppSettings(this);
        buildUi();
        setContentView(root);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    applyGeometry();
                }
            }
        });
        applyGeometry();
        refreshUi();
    }

    private void buildUi() {
        root = new WatchFrameLayout(this);
        root.setBackgroundColor(getColor(R.color.bg));

        title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(13f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setIncludeFontPadding(false);
        root.addView(title, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(22f),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(2f), dp(2f), dp(2f), dp(4f));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));

        addLabel(R.string.setting_shape);
        shapeGroup = segment(new int[]{
                R.string.setting_auto, R.string.setting_shape_round,
                R.string.setting_shape_square}, new Picker() {
            @Override
            public void onPick(int index) {
                settings.setShape(index == 0 ? AppSettings.SHAPE_AUTO
                        : index == 1 ? AppSettings.SHAPE_ROUND : AppSettings.SHAPE_SQUARE);
                applyGeometry();
                refreshUi();
            }
        });
        content.addView(shapeGroup, rowParams(dp(34f), dp(4f)));

        shapeNote = new TextView(this);
        shapeNote.setTextColor(getColor(R.color.text_secondary));
        shapeNote.setTextSize(9f);
        shapeNote.setGravity(Gravity.CENTER);
        shapeNote.setIncludeFontPadding(false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(16f));
        noteParams.topMargin = dp(3f);
        content.addView(shapeNote, noteParams);

        keepRow = toggleRow(R.string.setting_keep_awake, new Runnable() {
            @Override
            public void run() {
                settings.setKeepAwake(!settings.keepAwake());
                refreshUi();
            }
        });
        addRow(keepRow, dp(10f));

        vibrateRow = toggleRow(R.string.setting_vibrate, new Runnable() {
            @Override
            public void run() {
                settings.setVibrate(!settings.vibrate());
                refreshUi();
            }
        });
        addRow(vibrateRow, dp(6f));

        addLabel(R.string.setting_heading_up);
        headingGroup = segment(new int[]{
                R.string.setting_heading_up_on, R.string.setting_heading_up_off}, new Picker() {
            @Override
            public void onPick(int index) {
                settings.setHeadingUp(index == 0);
                refreshUi();
            }
        });
        content.addView(headingGroup, rowParams(dp(34f), dp(4f)));

        addLabel(R.string.setting_interval);
        intervalGroup = segment(new int[]{
                R.string.setting_seconds_1, R.string.setting_seconds_2,
                R.string.setting_seconds_5}, new Picker() {
            @Override
            public void onPick(int index) {
                settings.setIntervalMs(INTERVALS[index]);
                refreshUi();
            }
        });
        content.addView(intervalGroup, rowParams(dp(34f), dp(4f)));

        TextView reset = navRow(R.string.setting_reset);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmReset();
            }
        });
        addRow(reset, dp(12f));

        TextView about = navRow(R.string.setting_about);
        about.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAbout();
            }
        });
        addRow(about, dp(6f));

        TextView selfTest = navRow(R.string.setting_self_test);
        selfTest.setTextColor(getColor(R.color.mint));
        selfTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, SelfTestActivity.class));
            }
        });
        addRow(selfTest, dp(6f));

        TextView terminate = navRow(R.string.setting_terminate);
        terminate.setTextColor(getColor(R.color.danger));
        terminate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmTerminate();
            }
        });
        addRow(terminate, dp(12f));

        Button done = new Button(this);
        done.setText(R.string.finish_ok);
        done.setTextSize(13f);
        done.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        done.setAllCaps(false);
        done.setIncludeFontPadding(false);
        done.setBackgroundResource(R.drawable.bg_primary);
        done.setTextColor(getColor(R.color.bg));
        done.setMinHeight(0);
        done.setMinimumHeight(0);
        done.setPadding(0, 0, 0, 0);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finishWithResult();
            }
        });
        LinearLayout.LayoutParams doneParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38f));
        doneParams.topMargin = dp(12f);
        content.addView(done, doneParams);
    }

    private void addLabel(int text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(getColor(R.color.text_secondary));
        label.setTextSize(10f);
        label.setIncludeFontPadding(false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(18f));
        params.topMargin = dp(9f);
        params.leftMargin = dp(4f);
        content.addView(label, params);
    }

    private void addRow(View row, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42f));
        params.topMargin = topMargin;
        content.addView(row, params);
    }

    private LinearLayout.LayoutParams rowParams(int height, int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height);
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout toggleRow(int labelText, final Runnable onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_row);
        row.setPadding(dp(10f), 0, dp(7f), 0);
        row.setClickable(true);
        row.setFocusable(true);

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(getColor(R.color.text_primary));
        label.setTextSize(12f);
        label.setIncludeFontPadding(false);
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setTextColor(getColor(R.color.mint));
        value.setTextSize(10f);
        value.setGravity(Gravity.CENTER);
        value.setIncludeFontPadding(false);
        value.setBackgroundResource(R.drawable.bg_chip);
        value.setPadding(dp(9f), dp(3f), dp(9f), dp(3f));
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToggle.run();
            }
        });
        // Keep references so refreshUi can update them.
        if (labelText == R.string.setting_keep_awake) {
            keepValue = value;
        } else {
            vibrateValue = value;
        }
        return row;
    }

    private LinearLayout segment(int[] labels, final Picker picker) {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(10.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setIncludeFontPadding(false);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    picker.onPick(index);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            params.setMargins(dp(2f), 0, dp(2f), 0);
            group.addView(chip, params);
        }
        return group;
    }

    private void styleChips(LinearLayout group, int selected) {
        for (int i = 0; i < group.getChildCount(); i++) {
            TextView chip = (TextView) group.getChildAt(i);
            boolean on = i == selected;
            chip.setBackgroundResource(on ? R.drawable.bg_primary : R.drawable.bg_secondary);
            chip.setTextColor(on ? getColor(R.color.bg) : getColor(R.color.text_primary));
            chip.setTypeface(Typeface.create("sans-serif-medium",
                    on ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    private void refreshUi() {
        styleChips(shapeGroup, settings.shape() == AppSettings.SHAPE_AUTO ? 0
                : settings.shape() == AppSettings.SHAPE_ROUND ? 1 : 2);
        styleChips(headingGroup, settings.headingUp() ? 0 : 1);
        int interval = 0;
        for (int i = 0; i < INTERVALS.length; i++) {
            if (INTERVALS[i] == settings.intervalMs()) {
                interval = i;
                break;
            }
        }
        styleChips(intervalGroup, interval);

        boolean round = settings.resolveRound(getResources());
        if (settings.shape() == AppSettings.SHAPE_AUTO) {
            shapeNote.setText(getString(R.string.setting_shape_auto,
                    getString(round ? R.string.setting_shape_round : R.string.setting_shape_square)));
        } else {
            shapeNote.setText(getString(R.string.setting_shape_fixed,
                    getString(round ? R.string.setting_shape_round : R.string.setting_shape_square)));
        }

        keepValue.setText(settings.keepAwake() ? R.string.setting_on : R.string.setting_off);
        vibrateValue.setText(settings.vibrate() ? R.string.setting_on : R.string.setting_off);
        keepRow.setContentDescription(getString(R.string.setting_keep_awake) + " "
                + getString(settings.keepAwake() ? R.string.setting_on : R.string.setting_off));
        vibrateRow.setContentDescription(getString(R.string.setting_vibrate) + " "
                + getString(settings.vibrate() ? R.string.setting_on : R.string.setting_off));
    }

    private TextView navRow(int text) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(getColor(R.color.text_primary));
        row.setTextSize(12f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setIncludeFontPadding(false);
        row.setBackgroundResource(R.drawable.bg_row);
        row.setPadding(dp(10f), 0, dp(10f), 0);
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    private void confirmReset() {
        showDialog(getString(R.string.setting_reset),
                getString(R.string.setting_reset_confirm),
                getString(R.string.confirm), getString(R.string.cancel),
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        settings.reset();
                        applyGeometry();
                        refreshUi();
                        Toast.makeText(SettingsActivity.this,
                                R.string.setting_reset_done, Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void showAbout() {
        String release = Build.VERSION.RELEASE;
        if (release == null || release.trim().isEmpty()) {
            release = Integer.toString(Build.VERSION.SDK_INT);
        }
        String message = getString(R.string.setting_about_text,
                BuildConfig.VERSION_NAME, release, Build.VERSION.SDK_INT, Build.MODEL);
        showDialog(getString(R.string.app_name), message,
                getString(R.string.ok), null, null);
    }

    private void confirmTerminate() {
        showDialog(getString(R.string.setting_terminate),
                getString(R.string.setting_terminate_confirm),
                getString(R.string.terminate), getString(R.string.cancel),
                new WatchDialog.OnChoice() {
                    @Override
                    public void onPositive() {
                        terminateApp();
                    }
                });
    }

    /**
     * Stops the foreground service (releasing GPS, wake locks and the compass),
     * then closes the whole task. Unfinished tracks are saved by the service's
     * onDestroy, so the next launch can offer to resume them.
     */
    private void terminateApp() {
        stopService(new Intent(this, TrackingService.class));
        finishAffinity();
    }

    private void showDialog(String titleText, String message, String positive,
                            String negative, WatchDialog.OnChoice choice) {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
        dialog = WatchDialog.show(this, titleText, message, positive, negative, choice);
    }

    private void finishWithResult() {
        setResult(RESULT_OK);
        finish();
    }

    @Override
    protected void onPause() {
        setResult(RESULT_OK);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyGeometry();
        refreshUi();
    }

    private void applyGeometry() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        boolean round = settings.resolveRound(getResources());
        root.setRound(round);
        int side = Math.min(w, h);

        FrameLayout.LayoutParams titleParams = (FrameLayout.LayoutParams) title.getLayoutParams();
        titleParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        titleParams.height = dp(22f);
        titleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        titleParams.topMargin = round ? Math.round(side * 0.075f) : dp(10f);
        title.setLayoutParams(titleParams);

        int top = round ? Math.round(side * 0.21f) : dp(42f);
        int bottom = round ? Math.round(side * 0.20f) : dp(10f);
        int width = round ? Math.round(side * 0.64f) : w - dp(20f);
        FrameLayout.LayoutParams scrollParams =
                (FrameLayout.LayoutParams) scroll.getLayoutParams();
        scrollParams.width = width;
        scrollParams.height = Math.max(dp(80f), h - top - bottom);
        scrollParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        scrollParams.topMargin = top;
        scrollParams.bottomMargin = 0;
        scroll.setLayoutParams(scrollParams);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
