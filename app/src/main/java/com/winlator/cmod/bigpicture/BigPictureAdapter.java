package com.winlator.cmod.bigpicture;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.recyclerview.widget.RecyclerView;
import com.ludashi.benchmark.R;
import com.winlator.cmod.BigPictureActivity;
import com.winlator.cmod.container.Shortcut;
import java.util.List;

/* loaded from: classes12.dex */
public class BigPictureAdapter extends RecyclerView.Adapter<ViewHolder> {
    private final RecyclerView recyclerView;
    private final List<Shortcut> shortcuts;

    public BigPictureAdapter(List<Shortcut> shortcuts, RecyclerView recyclerView) {
        this.shortcuts = shortcuts;
        this.recyclerView = recyclerView;
    }

    public Shortcut getItem(int position) {
        return this.shortcuts.get(position);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final ImageView iconView;

        public ViewHolder(View itemView) {
            super(itemView);
            this.iconView = (ImageView) itemView.findViewById(R.id.IVCoverArt);
        }
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.big_picture_list_item, parent, false);
        return new ViewHolder(view);
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public void onBindViewHolder(ViewHolder holder, final int position) {
        final Shortcut shortcut = this.shortcuts.get(position);
        if (shortcut.icon != null) {
            holder.iconView.setImageBitmap(shortcut.icon);
        } else {
            holder.iconView.setImageResource(R.mipmap.ic_launcher_foreground);
        }
        holder.itemView.setFocusable(true);
        holder.itemView.setFocusableInTouchMode(true);
        holder.itemView.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.bigpicture.BigPictureAdapter$$ExternalSyntheticLambda0
            @Override // android.view.View.OnClickListener
            public final void onClick(View view) {
                BigPictureAdapter.this.lambda$onBindViewHolder$0(position, shortcut, view);
            }
        });
        holder.itemView.setOnFocusChangeListener(new View.OnFocusChangeListener() { // from class: com.winlator.cmod.bigpicture.BigPictureAdapter$$ExternalSyntheticLambda1
            @Override // android.view.View.OnFocusChangeListener
            public final void onFocusChange(View view, boolean z) {
                BigPictureAdapter.this.lambda$onBindViewHolder$1(position, shortcut, view, z);
            }
        });
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onBindViewHolder$0(int position, Shortcut shortcut, View v) {
        this.recyclerView.smoothScrollToPosition(position);
        ((BigPictureActivity) this.recyclerView.getContext()).loadShortcutData(shortcut);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$onBindViewHolder$1(int position, Shortcut shortcut, View v, boolean hasFocus) {
        if (hasFocus) {
            this.recyclerView.smoothScrollToPosition(position);
            ((BigPictureActivity) this.recyclerView.getContext()).loadShortcutData(shortcut);
        }
    }

    @Override // androidx.recyclerview.widget.RecyclerView.Adapter
    public int getItemCount() {
        return this.shortcuts.size();
    }
}
