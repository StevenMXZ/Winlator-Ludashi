package com.winlator.cmod.contentdialog;

import android.content.Context;
import android.widget.TextView;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.ludashi.benchmark.R;
import com.winlator.cmod.contentdialog.ContentInfoDialog;
import com.winlator.cmod.contents.ContentProfile;
import java.util.List;

/* loaded from: classes4.dex */
public class ContentUntrustedDialog extends ContentDialog {
    public ContentUntrustedDialog(Context context, List<ContentProfile.ContentFile> contentFiles) {
        super(context, R.layout.content_untrusted_dialog);
        setIcon(R.drawable.icon_info);
        setTitle(R.string.warning);
        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.recyclerView);
        recyclerView.setAdapter(new ContentInfoDialog.ContentInfoFileAdapter(contentFiles));
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), 1));
        ((TextView) findViewById(R.id.BTConfirm)).setText(R.string._continue);
    }
}
