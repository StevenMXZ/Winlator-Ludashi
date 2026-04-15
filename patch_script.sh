#!/bin/bash
set -e

# 1. Add RECORD_AUDIO permission
MANIFEST="app/src/main/AndroidManifest.xml"
if ! grep -q "RECORD_AUDIO" "$MANIFEST"; then
  sed -i '/<uses-permission android:name="android.permission.INTERNET"\/>/a\
    <uses-permission android:name="android.permission.RECORD_AUDIO" />' "$MANIFEST"
fi

# 2. Add microphone menu item
MENU_FILE="app/src/main/res/menu/xserver_menu.xml"
if ! grep -q "main_menu_microphone" "$MENU_FILE"; then
  sed -i '/<\/group>/i\
        <item\
            android:id="@+id/main_menu_microphone"\
            android:icon="@drawable/ic_mic"\
            android:title="Microphone" />' "$MENU_FILE"
fi

# 3. Patch XServerDisplayActivity.java (add imports, fields, methods, menu handling)
ACTIVITY_FILE="app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java"
if ! grep -q "nativeEnableMicrophone" "$ACTIVITY_FILE"; then
  # Add imports
  sed -i 's/import androidx.core.view.GravityCompat;/import androidx.core.view.GravityCompat;\nimport androidx.core.app.ActivityCompat;\nimport androidx.core.content.ContextCompat;\nimport android.Manifest;\nimport android.widget.Toast;/' "$ACTIVITY_FILE"
  # Add fields and native method
  sed -i '/private boolean isRelativeMouseMovement = false;/a\
    private boolean isMicEnabled = false;\
    private static final int REQUEST_RECORD_AUDIO = 100;\
    private native void nativeEnableMicrophone(boolean enable);\
    static {\
        System.loadLibrary("microphone");\
    }' "$ACTIVITY_FILE"
  # Add permission methods
  sed -i '/^}/i\
    private void checkAndRequestMicrophonePermission() {\
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {\
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);\
        }\
    }\
    \
    @Override\
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {\
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);\
        if (requestCode == REQUEST_RECORD_AUDIO) {\
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {\
                nativeEnableMicrophone(true);\
                isMicEnabled = true;\
                updateMicMenuItem();\
                Toast.makeText(this, "Microphone enabled", Toast.LENGTH_SHORT).show();\
            } else {\
                Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show();\
            }\
        }\
    }\
    \
    private void updateMicMenuItem() {\
        MenuItem item = findViewById(R.id.NavigationView).getMenu().findItem(R.id.main_menu_microphone);\
        if (item != null) {\
            if (isMicEnabled) {\
                item.setTitle("Microphone ON");\
                item.getIcon().setTint(android.graphics.Color.BLUE);\
            } else {\
                item.setTitle("Microphone");\
                item.getIcon().setTint(android.graphics.Color.WHITE);\
            }\
        }\
    }' "$ACTIVITY_FILE"
  # Add menu handling
  sed -i '/case R.id.main_menu_exit:/i\
            case R.id.main_menu_microphone:\
                if (!isMicEnabled) {\
                    checkAndRequestMicrophonePermission();\
                } else {\
                    isMicEnabled = false;\
                    nativeEnableMicrophone(false);\
                    updateMicMenuItem();\
                }\
                return true;' "$ACTIVITY_FILE"
fi

# 4. Create native capture source
mkdir -p app/src/main/cpp
cat > app/src/main/cpp/MicrophoneCapture.cpp << 'CPP_EOF'
#include <jni.h>
#include <android/log.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <pthread.h>
#include <unistd.h>
#include <cstring>
#include <cstdio>

#define LOG_TAG "MicrophoneCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static volatile int isCapturing = 0;
static pthread_t captureThread;

static void* captureLoop(void* arg) {
    const char* pipePath = "/data/data/com.winlator.vortex/cache/mic_pipe";
    FILE* pipe = fopen(pipePath, "wb");
    if (!pipe) {
        LOGI("Failed to open pipe");
        return nullptr;
    }
    const int bufferSize = 48000 * 2;
    short* buffer = new short[bufferSize];
    while (isCapturing) {
        memset(buffer, 0, bufferSize * sizeof(short));
        fwrite(buffer, sizeof(short), bufferSize, pipe);
        usleep(10000);
    }
    delete[] buffer;
    fclose(pipe);
    return nullptr;
}

extern "C" {
JNIEXPORT void JNICALL
Java_com_winlator_cmod_XServerDisplayActivity_nativeEnableMicrophone(JNIEnv* env, jobject thiz, jboolean enable) {
    if (enable && !isCapturing) {
        isCapturing = 1;
        pthread_create(&captureThread, nullptr, captureLoop, nullptr);
        LOGI("Mic start");
    } else if (!enable && isCapturing) {
        isCapturing = 0;
        pthread_join(captureThread, nullptr);
        LOGI("Mic stop");
    }
}
}
CPP_EOF

# 5. Update CMakeLists.txt
CMAKE_FILE="app/src/main/cpp/CMakeLists.txt"
if ! grep -q "microphone" "$CMAKE_FILE"; then
  echo 'add_library(microphone SHARED MicrophoneCapture.cpp)' >> "$CMAKE_FILE"
  echo 'target_link_libraries(microphone log OpenSLES)' >> "$CMAKE_FILE"
fi

# 6. Patch startup script (optional)
START_SCRIPT="assets/scripts/start.sh"
if [ -f "$START_SCRIPT" ] && ! grep -q "mic_pipe" "$START_SCRIPT"; then
  sed -i '/pulseaudio --start/a\
    PIPE_PATH="/data/data/com.winlator.vortex/cache/mic_pipe"\
    mkfifo $PIPE_PATH 2>/dev/null || true\
    pactl load-module module-pipe-source source_name=vortex_mic file=$PIPE_PATH format=s16le rate=48000 channels=2\
    pactl set-default-source vortex_mic' "$START_SCRIPT"
fi

echo "All patches applied"
