package com.winlator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Keep;
import android.view.Surface;
import android.view.View;
import android.view.MotionEvent;

// Imports necessários para o Input Bridge
import com.winlator.input.TouchInputHandler;
import com.winlator.input.InputStub; 

@Keep
public class X11Activity extends AppCompatActivity {
    
    // --- VARIÁVEIS ESTÁTICAS (Ponte para o LorieView) ---
    // O LorieView original usa métodos estáticos para se comunicar com a Activity
    private static TouchInputHandler mInputHandler;
    private static boolean mInputHandlerRunning = false;
    private static LorieView mLorieView; // Se precisar referenciar a view

    // --- MÉTODOS NATIVOS (JNI) ---
    // A libXlorie.so chama estes métodos. Não altere os nomes!
    public native void setMinKeycode(int minKeycode);
    public native void connect(int fd);
    public native void start(String path);
    public native void poll();

    // --- CALLBACKS DO NATIVO PARA O JAVA ---
    
    @Keep
    public void onWindowCreated(Surface surface) {
        // Se o LorieView precisar saber que a janela foi criada
        if (mLorieView != null) mLorieView.onWindowCreated(surface);
    }
    
    @Keep
    public void onWindowChanged(Surface surface) {
        if (mLorieView != null) mLorieView.onWindowChanged(surface);
    }
    
    @Keep
    public void onWindowDestroyed() {
        if (mLorieView != null) mLorieView.onWindowDestroyed();
    }
    
    @Keep
    public void setCursorType(int type) {
        // Implementação básica para evitar crash
    }

    @Keep
    public void setCursorType(int type, int[] pixels, int width, int height, int stride, int hotspotX, int hotspotY) {
        // Sobrecarga necessária para cursores customizados
    }

    // --- MÉTODOS DE AJUDA (INPUT BRIDGE) ---
    // Estes métodos são chamados pelo LorieView para enviar input

    public static void setInputHandler(TouchInputHandler handler) {
        mInputHandler = handler;
        mInputHandlerRunning = (handler != null);
    }
    
    public static void setLorieView(LorieView view) {
        mLorieView = view;
    }

    public static boolean handleTouchEventX11(View view, MotionEvent event) {
        if (mInputHandlerRunning && mInputHandler != null) {
            // Redireciona o toque para o TouchInputHandler do Winlator
            mInputHandler.handleTouchEvent(view, view, event);
            return true;
        }
        return false;
    }

    public static boolean handleInputMouseEventX11(int button, boolean isActionDown) {
        if (mInputHandlerRunning && mInputHandler != null) {
            // Tradução simplificada de botões
            // Você pode precisar ajustar a classe Binding se ela for usada aqui
            // Mas por enquanto, vamos passar o int direto se possível, ou mapear.
            
            // Nota: O código original usava um objeto Binding. 
            // Para simplificar e evitar erros de importação, vamos assumir que o LorieView
            // foi adaptado para passar os IDs de botão do InputStub.
            
            // Exemplo de injeção direta:
            // mInputHandler.sendMouseEvent(button, isActionDown, true);
            return true;
        }
        return false;
    }
}