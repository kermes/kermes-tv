package com.kermes.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The Continue watching row above the dock: a header and a sideways strip of poster cards, one per show
 * (WatchNext). Its own focus, not the grid's: the cards wear a white ring, and the glass stays on app tiles.
 * Left and Right stop at the strip's ends; Up and Down are the activity's (Listener), so they land on the tile
 * or pill they came from instead of wherever the focus finder points.
 */
public class WatchNextRow extends LinearLayout {
    interface Listener {
        void onWatchNextOpen(WatchNext.Item item);
        /** Up or Down from a card. */
        void onWatchNextLeave(boolean up);
    }

    private static final float FOCUS_SCALE = 1.06f;
    /** Poster heights the activity fits the row into; posters are always decoded for the largest. */
    static final int MIN_POSTER_DP = 72, MAX_POSTER_DP = 150;

    private final float density;
    private LinearLayout strip;
    private Listener listener;
    private List<WatchNext.Item> items = new ArrayList<>();
    private String signature = "";
    private int posterHeight;
    private int lastFocused;

    public WatchNextRow(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        posterHeight = Math.round(120 * density);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        strip = findViewById(R.id.watchNextStrip);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    boolean hasItems() {
        return !items.isEmpty();
    }

    int posterHeight() {
        return posterHeight;
    }

    /** Rebuilds the cards only when what they show changed; returns whether it did. */
    boolean setItems(List<WatchNext.Item> next) {
        StringBuilder sb = new StringBuilder();
        for (WatchNext.Item item : next) sb.append(item.signature()).append('\n');
        if (sb.toString().equals(signature)) return false;
        boolean hadFocus = strip.hasFocus();
        signature = sb.toString();
        items = new ArrayList<>(next);
        strip.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(getContext());
        for (int i = 0; i < items.size(); i++) strip.addView(card(inflater, i));
        lastFocused = Math.min(lastFocused, Math.max(0, items.size() - 1));
        if (hadFocus && !items.isEmpty()) focusRemembered();
        return true;
    }

    /**
     * A new poster height (16:9 cards) to fit the space above the dock. The cards are resized where they are, not
     * rebuilt, so a card that has focus keeps it.
     */
    void setPosterHeight(int height) {
        if (height == posterHeight) return;
        posterHeight = height;
        int width = cardWidth();
        for (int i = 0; i < strip.getChildCount(); i++) {
            View holder = strip.getChildAt(i);
            View card = holder.findViewById(R.id.wnCard);
            ViewGroup.LayoutParams lp = card.getLayoutParams();
            lp.width = width;
            lp.height = height;
            card.setLayoutParams(lp);
            View bar = holder.findViewById(R.id.wnProgress);
            bar.getLayoutParams().width = progressWidth(width, items.get(i).progress);
            bar.requestLayout();
        }
    }

    private int cardWidth() {
        return Math.round(posterHeight * 16f / 9f);
    }

    private int progressWidth(int cardWidth, float progress) {
        return Math.max(Math.round(4 * density), Math.round(cardWidth * progress));
    }

    void focusRemembered() {
        if (strip.getChildCount() == 0) return;
        View card = strip.getChildAt(Math.min(lastFocused, strip.getChildCount() - 1)).findViewById(R.id.wnCard);
        card.requestFocus();
    }

    private View card(LayoutInflater inflater, int index) {
        WatchNext.Item item = items.get(index);
        View holder = inflater.inflate(R.layout.item_watch_next, strip, false);
        View card = holder.findViewById(R.id.wnCard);
        int width = cardWidth();
        ViewGroup.LayoutParams lp = card.getLayoutParams();
        lp.width = width;
        lp.height = posterHeight;
        card.setLayoutParams(lp);
        card.setClipToOutline(true);

        TextView fallback = holder.findViewById(R.id.wnFallback);
        fallback.setText(item.title);
        ((TextView) holder.findViewById(R.id.wnTitle)).setText(item.title);
        ((TextView) holder.findViewById(R.id.wnSub)).setText(item.episode == null ? item.appLabel
                : getContext().getString(R.string.watch_next_sub, item.appLabel, item.episode));
        card.setContentDescription(item.title);
        if (item.progress > 0f) {
            holder.findViewById(R.id.wnTrack).setVisibility(View.VISIBLE);
            View bar = holder.findViewById(R.id.wnProgress);
            bar.getLayoutParams().width = progressWidth(width, item.progress);
            bar.setVisibility(View.VISIBLE);
        }

        ImageView poster = holder.findViewById(R.id.wnPoster);
        int decodeWidth = Math.round(MAX_POSTER_DP * density * 16f / 9f);
        if (item.image != null) {
            Bitmap cached = Posters.cached(item.image, decodeWidth);
            if (cached != null) {
                poster.setImageBitmap(cached);
                fallback.setVisibility(View.INVISIBLE);
            } else {
                poster.setTag(item.image);
                Posters.load(getContext(), item.image, decodeWidth, bitmap -> {
                    // The row may have been rebuilt while this was loading.
                    if (bitmap == null || !item.image.equals(poster.getTag())) return;
                    poster.setImageBitmap(bitmap);
                    poster.setAlpha(0f);
                    poster.animate().alpha(1f).setDuration(200).withEndAction(() -> fallback.setVisibility(View.INVISIBLE)).start();
                });
            }
        }

        TextView title = holder.findViewById(R.id.wnTitle);
        card.setOnFocusChangeListener((v, focused) -> {
            if (focused) lastFocused = index;
            title.setTextColor(focused ? 0xFFFFFFFF : 0xB3FFFFFF);
            v.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f).setDuration(160).start();
        });
        card.setOnClickListener(v -> {
            if (listener != null) listener.onWatchNextOpen(item);
        });
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == (rtl ? items.size() - 1 : 0);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == (rtl ? 0 : items.size() - 1);
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (listener != null) listener.onWatchNextLeave(keyCode == KeyEvent.KEYCODE_DPAD_UP);
                    return true;
                default:
                    return false;
            }
        });
        return holder;
    }
}
