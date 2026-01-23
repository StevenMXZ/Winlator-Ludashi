package com.winlator.cmod;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;

import com.google.android.material.navigation.NavigationView;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.inputcontrols.InputControlsManager;
import com.winlator.cmod.widget.InputControlsView;
import com.winlator.cmod.winhandler.WinHandler;

import com.winlator.xserver.ScreenInfo;
import com.winlator.xserver.XServer;
import com.winlator.widget.XServerView;

import com.winlator.LorieView;
import com.winlator.input.TouchInputHandler;

public class XServerDisplayActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {
    private DrawerLayout drawerLayout;
    private ContainerManager containerManager;
    private Container container;
    
    private boolean useXlorie = false;
    private LorieView lorieView;
    private XServerView xServerView;
    private XServer xServer;

    private InputControlsView inputControlsView;
    private InputControlsManager inputControlsManager;
    private WinHandler winHandler;
    private boolean isPaused = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        containerManager = new ContainerManager(this);
        int containerId = getIntent().getIntExtra("container_id", 0);
        container = containerManager.getContainerById(containerId);

        if (container == null) {
            finish();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        useXlorie = prefs.getBoolean("use_xlorie", false);

        if (useXlorie) {
            lorieView = new LorieView(this);
            setContentView(lorieView);
        } else {
            xServer = new XServer(new ScreenInfo(getWindowManager().getDefaultDisplay()));
            xServerView = new XServerView(this, xServer);
            setContentView(xServerView);
        }

        inputControlsView = new InputControlsView(this);
        inputControlsView.setOverlay(true);

        if (useXlorie) {
            inputControlsView.setTouchInputHandler(lorieView.inputHandler);
        } else {
            inputControlsView.setXServer(xServer);
        }
        
        addContentView(inputControlsView, new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, 
                WindowManager.LayoutParams.MATCH_PARENT
        ));

        inputControlsManager = new InputControlsManager(this, inputControlsView);
        
        winHandler = new WinHandler(this, container);
        if (!useXlorie && xServer != null) {
            xServer.setWinHandler(winHandler);
        }
        winHandler.start();

        container.start(this);
        inputControlsManager.updateVisibility();

        drawerLayout = new DrawerLayout(this);
        setupSystemUI();
        
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    if (inputControlsView != null) inputControlsView.setVisibility(View.GONE);
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            }
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        isPaused = true;
        if (winHandler != null) winHandler.stop();
        
        if (useXlorie) {
            if (LorieView.connected()) LorieView.requestConnection();
        } else {
            if (xServerView != null) xServerView.onPause();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        isPaused = false;
        if (winHandler != null) winHandler.start();
        
        if (!useXlorie && xServerView != null) xServerView.onResume();
        setupSystemUI();
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (winHandler != null) winHandler.stop();
    }

    private void setupSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }
    
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setupSystemUI();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (inputControlsManager != null && inputControlsManager.onKeyEvent(event)) return true;
        if (useXlorie && lorieView != null) return lorieView.sendKeyEvent(0, event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN);
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (inputControlsManager != null && inputControlsManager.onGenericMotionEvent(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    public WinHandler getWinHandler() { return winHandler; }
    
    public View getXServerView() { 
        return useXlorie ? lorieView : xServerView; 
    }
    
    public XServer getXServer() { 
        return xServer; 
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        drawerLayout.closeDrawer(GravityCompat.START);
        return true;
    }
}