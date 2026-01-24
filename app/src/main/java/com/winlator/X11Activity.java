package com.winlator;

import androidx.appcompat.app.AppCompatActivity;
import androidx.annotation.Keep;

@Keep
public class X11Activity extends AppCompatActivity {
    // Esta classe existe apenas para satisfazer a exigência da libXlorie.so
    // que busca por "com/winlator/X11Activity" via JNI.
}
