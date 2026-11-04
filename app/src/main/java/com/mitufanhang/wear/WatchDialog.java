package com.mitufanhang.wear;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small, thumb friendly confirmation dialog for a watch screen. */
public final class WatchDialog {
    public interface OnChoice {
        void onPositive();

        default void onNegative() {
        }
    }

    private WatchDialog() {
    }

    public static Dialog show(Context context, String title, String message,
                              String positive, String negative, OnChoice choice) {
        final Dialog dialog = new Dialog(context, R.style.WatchDialog);
        float density = context.getResources().getDisplayMetrics().density;
        int pad = dp(density, 12f);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(density, 11f), pad, dp(density, 11f));
        box.setBackgroundResource(R.drawable.bg_dialog);

        TextView heading = new TextView(context);
        heading.setText(title);
        heading.setTextColor(context.getColor(R.color.text_primary));
        heading.setTextSize(13.5f);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setGravity(Gravity.CENTER);
        heading.setIncludeFontPadding(false);
        box.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (message != null && message.length() > 0) {
            TextView body = new TextView(context);
            body.setText(message);
            body.setTextColor(context.getColor(R.color.text_secondary));
            body.setTextSize(11f);
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(dp(density, 1.5f), 1f);
            body.setIncludeFontPadding(false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(density, 5f);
            lp.bottomMargin = dp(density, 2f);
            box.addView(body, lp);
        }

        Button positiveButton = makeButton(context, positive, true);
        positiveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (choice != null) {
                    choice.onPositive();
                }
            }
        });

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams buttonRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(density, 38f));
        buttonRowLp.topMargin = dp(density, 6f);
        box.addView(buttons, buttonRowLp);

        LinearLayout.LayoutParams positiveLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        buttons.addView(positiveButton, positiveLp);

        if (negative != null && negative.length() > 0) {
            Button negativeButton = makeButton(context, negative, false);
            negativeButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (choice != null) {
                        choice.onNegative();
                    }
                }
            });
            LinearLayout.LayoutParams negativeLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f);
            negativeLp.leftMargin = dp(density, 7f);
            buttons.addView(negativeButton, negativeLp);
        }

        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(android.content.DialogInterface d) {
                if (choice != null) {
                    choice.onNegative();
                }
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.68f;
            params.gravity = Gravity.CENTER;
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            params.width = (int) (Math.min(metrics.widthPixels, metrics.heightPixels) * 0.80f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        dialog.show();
        return dialog;
    }

    private static Button makeButton(Context context, String text, boolean primary) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(13f);
        button.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        if (primary) {
            button.setTextColor(0xFF062018);
            button.setBackgroundResource(R.drawable.bg_primary);
        } else {
            button.setTextColor(context.getColor(R.color.text_primary));
            button.setBackgroundResource(R.drawable.bg_secondary);
        }
        return button;
    }

    private static int dp(float density, float value) {
        return (int) (value * density + 0.5f);
    }
}
