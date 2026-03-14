package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.ludashi.benchmark.R;
import com.winlator.cmod.contents.ContentProfile;
import java.util.List;

/* loaded from: classes4.dex */
public class ContentInfoDialog extends ContentDialog {
    public ContentInfoDialog(Context context, ContentProfile profile) {
        super(context, R.layout.content_info_dialog);
        setIcon(R.drawable.icon_about);
        setTitle(R.string.content_info);
        TextView tvType = (TextView) findViewById(R.id.TVType);
        TextView tvVersion = (TextView) findViewById(R.id.TVVersion);
        TextView tvVersionCode = (TextView) findViewById(R.id.TVVersionCode);
        TextView tvDescription = (TextView) findViewById(R.id.TVDesc);
        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        tvType.setText(profile.type.toString());
        tvVersion.setText(profile.verName);
        tvVersionCode.setText(String.valueOf(profile.verCode));
        tvDescription.setText(profile.desc);
        recyclerView.setAdapter(new ContentInfoFileAdapter(profile.fileList));
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), 1));
    }

    public static class ContentInfoFileAdapter extends RecyclerView.Adapter<ViewHolder> {
        private final List<ContentProfile.ContentFile> data;

        private static class ViewHolder extends RecyclerView.ViewHolder {
            private final TextView tvSource;
            private final TextView tvtarget;

            private ViewHolder(View view) {
                super(view);
                this.tvSource = (TextView) view.findViewById(R.id.TVFileSource);
                this.tvtarget = (TextView) view.findViewById(R.id.TVFileTarget);
            }
        }

        public ContentInfoFileAdapter(List<ContentProfile.ContentFile> data) {
            this.data = data;
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.content_file_list_item, parent, false));
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(ViewHolder holder, int position) {
            holder.tvSource.setText(this.data.get(position).source + " ->");
            holder.tvtarget.setText('\t' + this.data.get(position).target);
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public int getItemCount() {
            return this.data.size();
        }
    }
}
