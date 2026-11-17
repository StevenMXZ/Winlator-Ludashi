package com.winlator.cmod.stream.moonlight;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.widget.EditText;

/**
 * Makes the phone a Moonlight (GameStream) host while a game runs in stream mode:
 * nvhttp server, PIN pairing dialog on the phone, and mDNS announcement so Moonlight
 * lists the phone automatically.
 */
public final class MoonlightHost {
    private static final String TAG = "LudashiMoonlight";

    private final Activity activity;
    private NvHttpServer server;
    private NsdManager nsd;
    private NsdManager.RegistrationListener nsdListener;

    public MoonlightHost(Activity activity) {
        this.activity = activity;
    }

    public void start(NvHttpServer.LaunchHandler launchHandler) throws Exception {
        HostIdentity identity = HostIdentity.loadOrCreate(activity);
        server = new NvHttpServer(identity, this::askPin);
        server.setLaunchHandler(launchHandler);
        server.start();
        advertise(server.getHostName());
    }

    public void stop() {
        if (nsd != null && nsdListener != null) {
            try { nsd.unregisterService(nsdListener); } catch (RuntimeException ignored) {}
        }
        nsdListener = null;
        if (server != null) { server.stop(); server = null; }
    }

    private void advertise(String name) {
        nsd = (NsdManager) activity.getSystemService(Context.NSD_SERVICE);
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType("_nvstream._tcp");
        info.setPort(NvHttpServer.PORT_HTTP);
        nsdListener = new NsdManager.RegistrationListener() {
            @Override public void onServiceRegistered(NsdServiceInfo s) { Log.i(TAG, "mDNS registered: " + s.getServiceName()); }
            @Override public void onRegistrationFailed(NsdServiceInfo s, int err) { Log.w(TAG, "mDNS registration failed: " + err); }
            @Override public void onServiceUnregistered(NsdServiceInfo s) {}
            @Override public void onUnregistrationFailed(NsdServiceInfo s, int err) {}
        };
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener);
        } catch (RuntimeException e) {
            Log.w(TAG, "mDNS unavailable", e);
            nsdListener = null;
        }
    }

    /** Shows "enter the PIN from Moonlight" on the phone. */
    private void askPin(String deviceName, NvHttpServer.PinCallback callback) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) { callback.onPin(null); return; }
            EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_NUMBER);
            input.setGravity(Gravity.CENTER);
            input.setTextSize(28);
            input.setHint("0000");
            final boolean[] answered = {false};
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(activity.getString(com.winlator.cmod.R.string.stream_pin_title, deviceName))
                    .setMessage(com.winlator.cmod.R.string.stream_pin_message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok, (d, w) -> { answered[0] = true; callback.onPin(input.getText().toString().trim()); })
                    .setNegativeButton(com.winlator.cmod.R.string.stream_pin_cancel, (d, w) -> { answered[0] = true; callback.onPin(null); })
                    .setOnDismissListener(d -> { if (!answered[0]) callback.onPin(null); })
                    .create();
            dialog.show();
            input.requestFocus();
        });
    }
}
