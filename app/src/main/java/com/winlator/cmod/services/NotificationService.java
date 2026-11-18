package com.winlator.cmod.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

import androidx.core.content.ContextCompat;
import com.winlator.cmod.R;
import com.winlator.cmod.MainActivity;
import com.winlator.cmod.core.ProcessHelper;

public class NotificationService extends Service {
    private static PowerManager.WakeLock wakeLock = null;
    private static boolean isRunning = false;
    
    public static void acquireLock() {
        if (wakeLock == null || (wakeLock != null && wakeLock.isHeld())) return;
        
        wakeLock.acquire();
    }
    
    public static void releaseLock() {
        if (wakeLock == null || (wakeLock != null && !wakeLock.isHeld())) return;
        
        wakeLock.release();
    }
    
    public static boolean isRunning() {
        return isRunning;
    }
    
	@Override
	public void onCreate() {
		super.onCreate();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {	
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, MainActivity.NOTIFICATION_CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_stat_ab_gear_0011)
			.setContentTitle("Winlator")
			.setContentText("Winlator is running, do not kill or swipe this notification")
			.setPriority(NotificationCompat.PRIORITY_LOW)
        	.setContentIntent(pendingIntent)
		    .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
		    .setOngoing(true);
        
		Notification notification = builder.build();
		startForeground(MainActivity.NOTIFICATION_ID, notification);
        
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NotificationService::KeepAlive");
        
        isRunning = true;
        
		return START_NOT_STICKY;
	}

	@Override
	public void onTaskRemoved(Intent rootIntent) {
		stopForeground(STOP_FOREGROUND_REMOVE);
		stopSelf();
        releaseLock();
        ProcessHelper.killAllWineProcesses();
        isRunning = false;
	}
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        releaseLock();
        isRunning = false;
    }

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
