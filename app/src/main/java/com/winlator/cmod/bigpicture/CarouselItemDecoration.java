package com.winlator.cmod.bigpicture;

import android.graphics.Rect;
import android.view.View;
import androidx.recyclerview.widget.RecyclerView;

/* loaded from: classes12.dex */
public class CarouselItemDecoration extends RecyclerView.ItemDecoration {
    private final int spacing;

    public CarouselItemDecoration(int spacing) {
        this.spacing = spacing;
    }

    @Override // androidx.recyclerview.widget.RecyclerView.ItemDecoration
    public void getItemOffsets(Rect outRect, View view, RecyclerView parent, RecyclerView.State state) {
        outRect.left = this.spacing / 2;
        outRect.right = this.spacing / 2;
    }
}
