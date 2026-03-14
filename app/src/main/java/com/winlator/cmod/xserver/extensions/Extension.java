package com.winlator.cmod.xserver.extensions;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.XRequestError;
import java.io.IOException;

/* loaded from: classes11.dex */
public interface Extension {
    byte getFirstErrorId();

    byte getFirstEventId();

    byte getMajorOpcode();

    String getName();

    void handleRequest(XClient xClient, XInputStream xInputStream, XOutputStream xOutputStream) throws IOException, XRequestError;
}
