package com.appletv.launcher;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws a translucent glass band behind the dock row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
        this.density = density;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(0.75f * density);
        edge.setColor(0x35FFFFFF);
    }

    void setDockSize(int dockSize) {
        this.dockSize = dockSize;
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = child.findViewById(R.id.card);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft() + child.getTranslationX();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        float horizontalPad = 38 * density;
        float verticalPad = 13 * density;
        rect.set(horizontalPad, rect.top - verticalPad, parent.getWidth() - horizontalPad,
                rect.bottom + verticalPad);
        float radius = rect.height() / 2f;

        band.setShader(new LinearGradient(0, rect.top, 0, rect.bottom,
                new int[]{0x42172F35, 0x32132126, 0x28111A20}, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(rect, radius, radius, band);

        sheen.setShader(new LinearGradient(0, rect.top, 0, rect.top + rect.height() * 0.52f,
                0x17FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        int save = c.save();
        c.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.52f);
        c.drawRoundRect(rect, radius, radius, sheen);
        c.restoreToCount(save);

        c.drawRoundRect(rect, radius, radius, edge);
    }
}
