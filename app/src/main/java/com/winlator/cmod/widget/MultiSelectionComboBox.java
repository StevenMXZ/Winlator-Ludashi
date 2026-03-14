package com.winlator.cmod.widget;

import android.R;
import android.content.Context;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckedTextView;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.ListPopupWindow;
import com.winlator.cmod.core.UnitUtils;
import java.util.Collections;

/* loaded from: classes14.dex */
public class MultiSelectionComboBox extends AppCompatTextView {
    private String[] items;
    private final ArraySet<String> selectedItemSet;
    private String text;

    public MultiSelectionComboBox(Context context) {
        this(context, null);
    }

    public MultiSelectionComboBox(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MultiSelectionComboBox(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.selectedItemSet = new ArraySet<>();
        this.text = "";
    }

    public String[] getItems() {
        return this.items;
    }

    public void setItems(String[] items) {
        this.items = items;
        setText(getSelectedItemsAsString());
    }

    public void setItems(String[] items, String text) {
        this.items = items;
        this.text = text;
        if (!text.isEmpty()) {
            setText(items.length + " " + text);
        } else {
            setText(getSelectedItemsAsString());
        }
    }

    public void setSelectedItems(String[] selectedItems) {
        this.selectedItemSet.clear();
        Collections.addAll(this.selectedItemSet, selectedItems);
        if (!this.text.isEmpty()) {
            setText(this.selectedItemSet.size() + " " + this.text);
        } else {
            setText(getSelectedItemsAsString());
        }
    }

    public void setSelectedItem(String item) {
        if (this.selectedItemSet.contains(item)) {
            this.selectedItemSet.add(item);
        }
        if (!this.text.isEmpty()) {
            setText(this.selectedItemSet.size() + " " + this.text);
        } else {
            setText(getSelectedItemsAsString());
        }
    }

    public void unsetSelectedItem(String item) {
        if (this.selectedItemSet.contains(item)) {
            this.selectedItemSet.remove(item);
        }
        if (!this.text.isEmpty()) {
            setText(this.selectedItemSet.size() + " " + this.text);
        } else {
            setText(getSelectedItemsAsString());
        }
    }

    public String getSelectedItemsAsString() {
        String result = "";
        for (String item : this.items) {
            if (this.selectedItemSet.contains(item)) {
                result = result + (!result.isEmpty() ? "," : "") + item;
            }
        }
        return result;
    }

    public String getUnSelectedItemsAsString() {
        String result = "";
        for (String item : this.items) {
            if (!this.selectedItemSet.contains(item)) {
                result = result + (!result.isEmpty() ? "," : "") + item;
            }
        }
        return result;
    }

    @Override // android.view.View
    public boolean performClick() {
        if (this.items == null || this.items.length == 0) {
            return true;
        }
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(getContext(), R.layout.simple_list_item_multiple_choice, this.items) { // from class: com.winlator.cmod.widget.MultiSelectionComboBox.1
            @Override // android.widget.ArrayAdapter, android.widget.Adapter
            public View getView(int position, View convertView, ViewGroup parent) {
                CheckedTextView checkedTextView = (CheckedTextView) super.getView(position, convertView, parent);
                checkedTextView.setChecked(MultiSelectionComboBox.this.selectedItemSet.contains(MultiSelectionComboBox.this.items[position]));
                if (!MultiSelectionComboBox.this.text.isEmpty()) {
                    MultiSelectionComboBox.this.setText(MultiSelectionComboBox.this.selectedItemSet.size() + " " + MultiSelectionComboBox.this.text);
                } else {
                    MultiSelectionComboBox.this.setText(MultiSelectionComboBox.this.getSelectedItemsAsString());
                }
                return checkedTextView;
            }
        };
        ListPopupWindow popupWindow = new ListPopupWindow(getContext());
        popupWindow.setAdapter(adapter);
        popupWindow.setAnchorView(this);
        popupWindow.setWidth((int) UnitUtils.dpToPx(260.0f));
        popupWindow.setOnItemClickListener(new AdapterView.OnItemClickListener() { // from class: com.winlator.cmod.widget.MultiSelectionComboBox$$ExternalSyntheticLambda0
            @Override // android.widget.AdapterView.OnItemClickListener
            public final void onItemClick(AdapterView adapterView, View view, int i, long j) {
                MultiSelectionComboBox.this.lambda$performClick$0(adapter, adapterView, view, i, j);
            }
        });
        popupWindow.show();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$performClick$0(ArrayAdapter adapter, AdapterView parent, View view, int position, long id) {
        String item = this.items[position];
        if (this.selectedItemSet.contains(item)) {
            this.selectedItemSet.remove(item);
        } else {
            this.selectedItemSet.add(item);
        }
        adapter.notifyDataSetChanged();
    }
}
