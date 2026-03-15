package com.winlator.cmod;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.winlator.cmod.ExternalControllerBindingsActivity;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.inputcontrols.Binding;
import com.winlator.cmod.inputcontrols.ControlsProfile;
import com.winlator.cmod.inputcontrols.ExternalController;
import com.winlator.cmod.inputcontrols.ExternalControllerBinding;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.math.Mathf;

/* loaded from: classes8.dex */
public class ExternalControllerBindingsActivity extends AppCompatActivity {
    private ControllerBindingsAdapter adapter;
    private ExternalController controller;
    private TextView emptyTextView;
    private ControlsProfile profile;
    private RecyclerView recyclerView;
    private boolean l2WasPressed = false;
    private boolean r2WasPressed = false;

    @Override // androidx.fragment.app.FragmentActivity, androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.ludashi.benchmark.R.layout.external_controller_bindings_activity);
        Intent intent = getIntent();
        int profileId = intent.getIntExtra("profile_id", 0);
        this.profile = InputControlsManager.loadProfile(this, ControlsProfile.getProfileFile(this, profileId));
        String controllerId = intent.getStringExtra("controller_id");
        this.controller = this.profile.getController(controllerId);
        if (this.controller == null) {
            this.controller = this.profile.addController(controllerId);
            this.profile.save();
        }
        Toolbar toolbar = (Toolbar) findViewById(com.ludashi.benchmark.R.id.Toolbar);
        toolbar.setTitle(this.controller.getName());
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);
        actionBar.setHomeAsUpIndicator(com.ludashi.benchmark.R.drawable.icon_action_bar_back);
        this.emptyTextView = (TextView) findViewById(com.ludashi.benchmark.R.id.TVEmptyText);
        this.recyclerView = (RecyclerView) findViewById(com.ludashi.benchmark.R.id.RecyclerView);
        this.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        this.recyclerView.addItemDecoration(new DividerItemDecoration(this, 1));
        RecyclerView recyclerView = this.recyclerView;
        ControllerBindingsAdapter controllerBindingsAdapter = new ControllerBindingsAdapter();
        this.adapter = controllerBindingsAdapter;
        recyclerView.setAdapter(controllerBindingsAdapter);
        updateEmptyTextView();
    }

    private void updateControllerBinding(int keyCode, Binding binding) {
        int position;
        if (keyCode == 0) {
            return;
        }
        ExternalControllerBinding controllerBinding = this.controller.getControllerBinding(keyCode);
        if (controllerBinding == null) {
            ExternalControllerBinding controllerBinding2 = new ExternalControllerBinding();
            controllerBinding2.setKeyCode(keyCode);
            controllerBinding2.setBinding(binding);
            this.controller.addControllerBinding(controllerBinding2);
            this.profile.save();
            this.adapter.notifyDataSetChanged();
            updateEmptyTextView();
            position = this.controller.getPosition(controllerBinding2);
        } else {
            position = this.controller.getPosition(controllerBinding);
            animateItemView(position);
        }
        this.recyclerView.scrollToPosition(position);
    }

    private void processJoystickInput() {
        int[] axes = {0, 1, 11, 14, 15, 16};
        float[] values = {this.controller.state.thumbLX, this.controller.state.thumbLY, this.controller.state.thumbRX, this.controller.state.thumbRY, this.controller.state.getDPadX(), this.controller.state.getDPadY()};
        for (int i = 0; i < axes.length; i++) {
            float value = values[i];
            byte sign = Mathf.sign(value);
            if (sign != 0) {
                int keyCode = ExternalControllerBinding.getKeyCodeForAxis(axes[i], sign);
                updateControllerBinding(keyCode, Binding.NONE);
            }
        }
    }

    @Override // android.app.Activity, android.view.Window.Callback
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        InputDevice device = event.getDevice();
        if (device != null && ExternalController.isGameController(device) && this.controller.updateStateFromMotionEvent(event)) {
            float l2Value = Math.max(event.getAxisValue(17), event.getAxisValue(23));
            float r2Value = Math.max(event.getAxisValue(18), event.getAxisValue(22));
            boolean l2Pressed = l2Value > 0.8f;
            if (l2Pressed && !this.l2WasPressed) {
                updateControllerBinding(104, Binding.NONE);
            }
            this.l2WasPressed = l2Pressed;
            boolean r2Pressed = r2Value > 0.8f;
            if (r2Pressed && !this.r2WasPressed) {
                updateControllerBinding(105, Binding.NONE);
            }
            this.r2WasPressed = r2Pressed;
            processJoystickInput();
            return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }

    @Override // androidx.appcompat.app.AppCompatActivity, android.app.Activity, android.view.KeyEvent.Callback
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isGamepadKeyCode(keyCode)) {
            updateControllerBinding(keyCode, Binding.NONE);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override // android.app.Activity, android.view.KeyEvent.Callback
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isGamepadKeyCode(keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean isGamepadKeyCode(int keyCode) {
        return keyCode == 96 || keyCode == 97 || keyCode == 99 || keyCode == 100 || keyCode == 102 || keyCode == 103 || keyCode == 104 || keyCode == 105 || keyCode == 106 || keyCode == 107 || keyCode == 108 || keyCode == 109 || keyCode == 110 || keyCode == 19 || keyCode == 20 || keyCode == 21 || keyCode == 22 || keyCode == 23;
    }

    @Override // android.app.Activity
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        finish();
        return true;
    }

    /* JADX INFO: Access modifiers changed from: private */
    class ControllerBindingsAdapter extends RecyclerView.Adapter<ViewHolder> {
        private ControllerBindingsAdapter() {
        }

        /* JADX INFO: Access modifiers changed from: private */
        class ViewHolder extends RecyclerView.ViewHolder {
            private final Spinner binding;
            private final Spinner bindingType;
            private final ImageButton removeButton;
            private final TextView title;

            private ViewHolder(View view) {
                super(view);
                this.title = (TextView) view.findViewById(com.ludashi.benchmark.R.id.TVTitle);
                this.bindingType = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBindingType);
                this.binding = (Spinner) view.findViewById(com.ludashi.benchmark.R.id.SBinding);
                this.removeButton = (ImageButton) view.findViewById(com.ludashi.benchmark.R.id.BTRemove);
            }
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(com.ludashi.benchmark.R.layout.external_controller_binding_list_item, parent, false));
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public void onBindViewHolder(ViewHolder holder, int position) {
            final ExternalControllerBinding item = ExternalControllerBindingsActivity.this.controller.getControllerBindingAt(position);
            holder.title.setText(item.toString());
            loadBindingSpinner(holder, item);
            holder.removeButton.setOnClickListener(new View.OnClickListener() { // from class: com.winlator.cmod.ExternalControllerBindingsActivity$ControllerBindingsAdapter$$ExternalSyntheticLambda1
                @Override // android.view.View.OnClickListener
                public final void onClick(View view) {
                    ExternalControllerBindingsActivity.ControllerBindingsAdapter.this.lambda$onBindViewHolder$0(item, view);
                }
            });
        }

        /* JADX INFO: Access modifiers changed from: private */
        public /* synthetic */ void lambda$onBindViewHolder$0(ExternalControllerBinding item, View view) {
            ExternalControllerBindingsActivity.this.controller.removeControllerBinding(item);
            ExternalControllerBindingsActivity.this.profile.save();
            notifyDataSetChanged();
            ExternalControllerBindingsActivity.this.updateEmptyTextView();
        }

        @Override // androidx.recyclerview.widget.RecyclerView.Adapter
        public final int getItemCount() {
            return ExternalControllerBindingsActivity.this.controller.getControllerBindingCount();
        }

        private void loadBindingSpinner(final ViewHolder holder, final ExternalControllerBinding item) {
            final Context $this = ExternalControllerBindingsActivity.this;
            final Runnable update = new Runnable() { // from class: com.winlator.cmod.ExternalControllerBindingsActivity$ControllerBindingsAdapter$$ExternalSyntheticLambda0
                @Override // java.lang.Runnable
                public final void run() {
                    ExternalControllerBindingsActivity.ControllerBindingsAdapter.lambda$loadBindingSpinner$1(ExternalControllerBindingsActivity.ControllerBindingsAdapter.ViewHolder.this, $this, item);
                }
            };
            holder.bindingType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ExternalControllerBindingsActivity.ControllerBindingsAdapter.1
                @Override // android.widget.AdapterView.OnItemSelectedListener
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    update.run();
                }

                @Override // android.widget.AdapterView.OnItemSelectedListener
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            Binding selectedBinding = item.getBinding();
            if (selectedBinding.isKeyboard()) {
                holder.bindingType.setSelection(0, false);
            } else if (selectedBinding.isMouse()) {
                holder.bindingType.setSelection(1, false);
            } else if (selectedBinding.isGamepad()) {
                holder.bindingType.setSelection(2, false);
            }
            holder.binding.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() { // from class: com.winlator.cmod.ExternalControllerBindingsActivity.ControllerBindingsAdapter.2
                @Override // android.widget.AdapterView.OnItemSelectedListener
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    Binding binding = Binding.NONE;
                    switch (holder.bindingType.getSelectedItemPosition()) {
                        case 0:
                            binding = Binding.keyboardBindingValues()[position];
                            break;
                        case 1:
                            binding = Binding.mouseBindingValues()[position];
                            break;
                        case 2:
                            binding = Binding.gamepadBindingValues()[position];
                            break;
                    }
                    if (binding != item.getBinding()) {
                        item.setBinding(binding);
                        ExternalControllerBindingsActivity.this.profile.save();
                    }
                }

                @Override // android.widget.AdapterView.OnItemSelectedListener
                public void onNothingSelected(AdapterView<?> parent) {
                }
            });
            update.run();
        }

        static /* synthetic */ void lambda$loadBindingSpinner$1(ViewHolder holder, Context $this, ExternalControllerBinding item) {
            String[] bindingEntries = null;
            switch (holder.bindingType.getSelectedItemPosition()) {
                case 0:
                    bindingEntries = Binding.keyboardBindingLabels();
                    break;
                case 1:
                    bindingEntries = Binding.mouseBindingLabels();
                    break;
                case 2:
                    bindingEntries = Binding.gamepadBindingLabels();
                    break;
            }
            holder.binding.setAdapter((SpinnerAdapter) new ArrayAdapter($this, android.R.layout.simple_spinner_dropdown_item, bindingEntries));
            AppUtils.setSpinnerSelectionFromValue(holder.binding, item.getBinding().toString());
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void updateEmptyTextView() {
        this.emptyTextView.setVisibility(this.adapter.getItemCount() == 0 ? 0 : 8);
    }

    private void animateItemView(int position) {
        final ControllerBindingsAdapter.ViewHolder holder = (ControllerBindingsAdapter.ViewHolder) this.recyclerView.findViewHolderForAdapterPosition(position);
        if (holder != null) {
            final int color = ContextCompat.getColor(this, com.ludashi.benchmark.R.color.colorAccent);
            ValueAnimator animator = ValueAnimator.ofFloat(0.4f, 0.0f);
            animator.setDuration(200L);
            animator.setInterpolator(new AccelerateDecelerateInterpolator());
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() { // from class: com.winlator.cmod.ExternalControllerBindingsActivity$$ExternalSyntheticLambda0
                @Override // android.animation.ValueAnimator.AnimatorUpdateListener
                public final void onAnimationUpdate(ValueAnimator valueAnimator) {
                    ExternalControllerBindingsActivity.lambda$animateItemView$0(ExternalControllerBindingsActivity.ControllerBindingsAdapter.ViewHolder.this, color, valueAnimator);
                }
            });
            animator.start();
        }
    }

    static /* synthetic */ void lambda$animateItemView$0(ControllerBindingsAdapter.ViewHolder holder, int color, ValueAnimator animation) {
        float alpha = ((Float) animation.getAnimatedValue()).floatValue();
        holder.itemView.setBackgroundColor(Color.argb((int) (255.0f * alpha), Color.red(color), Color.green(color), Color.blue(color)));
    }
}
