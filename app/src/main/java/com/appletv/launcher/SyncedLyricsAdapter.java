package com.appletv.launcher;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

final class SyncedLyricsAdapter extends RecyclerView.Adapter<SyncedLyricsAdapter.Holder> {
    private final float density;
    private final List<String> lines = new ArrayList<>();
    private List<String> sourceLines;
    private int currentLine = -1;
    private float gapProgress;
    private long gapRemainingMs;
    private boolean animateGap;

    SyncedLyricsAdapter(float density) {
        this.density = density;
        setHasStableIds(false);
    }

    void setLyrics(List<String> newLines, int activeLine) {
        setLyrics(newLines, activeLine, 0f, 0L, false);
    }

    void setLyrics(List<String> newLines, int activeLine, float waitProgress,
                   long waitRemainingMs, boolean shouldAnimateGap) {
        int nextCurrentLine = newLines.isEmpty() ? -1
                : Math.max(0, Math.min(activeLine, newLines.size() - 1));
        boolean lineChanged = currentLine != nextCurrentLine;
        if (sourceLines == newLines) {
            updateGap(waitProgress, waitRemainingMs, shouldAnimateGap);
            if (!lineChanged) {
                if (hasActiveGap()) notifyItemChanged(1);
                return;
            }
            currentLine = nextCurrentLine;
            if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
            return;
        }
        List<String> replacement = new ArrayList<>(newLines);
        if (lines.equals(replacement)) {
            sourceLines = newLines;
            updateGap(waitProgress, waitRemainingMs, shouldAnimateGap);
            if (!lineChanged) {
                if (hasActiveGap()) notifyItemChanged(1);
                return;
            }
            currentLine = nextCurrentLine;
            if (getItemCount() > 0) notifyItemRangeChanged(0, getItemCount());
            return;
        }
        lines.clear();
        lines.addAll(replacement);
        sourceLines = newLines;
        currentLine = nextCurrentLine;
        updateGap(waitProgress, waitRemainingMs, shouldAnimateGap);
        notifyDataSetChanged();
    }

    static int nextNonEmptyLineIndex(List<String> lines, int startIndex) {
        if (lines == null) return -1;
        for (int i = Math.max(0, startIndex); i < lines.size(); i++) {
            if (lines.get(i) != null && !lines.get(i).trim().isEmpty()) return i;
        }
        return -1;
    }

    static float gapProgressAt(long gapStartMs, long nextLineMs, long positionMs) {
        if (nextLineMs <= gapStartMs) return 1f;
        float progress = (positionMs - gapStartMs) / (float) (nextLineMs - gapStartMs);
        return Math.max(0f, Math.min(1f, progress));
    }

    private void updateGap(float progress, long remainingMs, boolean shouldAnimate) {
        gapProgress = Math.max(0f, Math.min(1f, progress));
        gapRemainingMs = Math.max(0L, remainingMs);
        animateGap = shouldAnimate;
    }

    private boolean hasActiveGap() {
        return currentLine >= 0 && currentLine < lines.size()
                && lines.get(currentLine).trim().isEmpty()
                && nextNonEmptyLineIndex(lines, currentLine + 1) >= 0;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout item = new FrameLayout(parent.getContext());
        RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4);
        params.bottomMargin = dp(4);
        item.setLayoutParams(params);

        TextView line = new TextView(parent.getContext());
        line.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        line.setGravity(Gravity.CENTER);
        line.setIncludeFontPadding(false);
        line.setMaxLines(Integer.MAX_VALUE);
        line.setEllipsize(null);
        line.setPadding(dp(8), dp(6), dp(8), dp(6));
        line.setMinHeight(dp(42));
        line.setFocusable(false);
        line.setClickable(false);
        line.setLineSpacing(dp(2), 1f);

        LyricsGapIndicatorView gapIndicator = new LyricsGapIndicatorView(parent.getContext());
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(
                dp(56), dp(24), Gravity.CENTER);
        item.addView(line);
        item.addView(gapIndicator, indicatorParams);
        return new Holder(item, line, gapIndicator);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        TextView line = holder.line;
        int lineIndex = currentLine + position - 1;
        if (lineIndex < 0 || lineIndex >= lines.size()) {
            line.setText("");
            line.setAlpha(0f);
            line.setMinHeight(dp(38));
            holder.gapIndicator.setVisibility(android.view.View.GONE);
            holder.gapIndicator.setWaitProgress(0f, 0L, false);
            return;
        }
        String text = lines.get(lineIndex);
        if (lineIndex == currentLine && text.trim().isEmpty()
                && nextNonEmptyLineIndex(lines, lineIndex + 1) >= 0) {
            line.setText("");
            line.setAlpha(0f);
            line.setMinHeight(position == 1 ? dp(58) : dp(48));
            holder.gapIndicator.setVisibility(android.view.View.VISIBLE);
            holder.gapIndicator.setWaitProgress(gapProgress, gapRemainingMs,
                    animateGap && position == 1);
            return;
        }
        holder.gapIndicator.setVisibility(android.view.View.GONE);
        holder.gapIndicator.setWaitProgress(0f, 0L, false);
        line.setText(text);
        line.setAlpha(1f);
        if (position == 1) {
            line.setTextColor(Color.WHITE);
            line.setTextSize(27);
            line.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            line.setAlpha(1f);
            line.setMinHeight(dp(58));
        } else {
            line.setTextColor(0xD9FFFFFF);
            line.setTextSize(21);
            line.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            line.setAlpha(0.38f);
            line.setMinHeight(dp(48));
        }
    }

    @Override
    public int getItemCount() {
        return lines.isEmpty() ? 0 : 3;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView line;
        final LyricsGapIndicatorView gapIndicator;

        Holder(@NonNull FrameLayout item, TextView line, LyricsGapIndicatorView gapIndicator) {
            super(item);
            this.line = line;
            this.gapIndicator = gapIndicator;
        }
    }
}
