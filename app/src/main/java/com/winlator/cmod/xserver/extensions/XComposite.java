package com.winlator.cmod.xserver.extensions;

import android.util.Log;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Window;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadAccess;
import com.winlator.cmod.xserver.errors.BadAlloc;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.XRequestError;
import java.io.IOException;

public class XComposite implements Extension {
    public static final byte MAJOR_OPCODE = -106;
    private XServer xserver;
    
    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte REDIRECT_WINDOW = 1;
        private static final byte UNREDIRECT_WINDOW = 3;
    }
    
    private static abstract class UpdateType {
        private static final int AUTOMATIC = 0;
        private static final int MANUAL = 1;
    }
    
    public XComposite(XServer xserver) {
        this.xserver = xserver;
    }
    
    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);
        
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writePad(16);
        }
    }
    
    private void redirectWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int updateType = inputStream.readByte() & 0xff;
        inputStream.skip(3);
        
        Window window = xserver.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        
        if (window == xserver.windowManager.rootWindow) throw new BadMatch();
        if (window.isCompositeRedirected() && updateType == UpdateType.MANUAL) throw new BadAccess();
        
        window.setCompositeRedirected(true);
        window.getContent().setOffscreen(true);
        xserver.getXServerView().nativeSetCompositeRedirected(window.id, true);
    }
    
    private void unredirectWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int updateType = inputStream.readByte() & 0xff;
        inputStream.skip(3);
        
        Window window = xserver.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        
        if (!window.isCompositeRedirected()) throw new BadValue(windowId);
        
        window.setCompositeRedirected(false);
        window.getContent().setOffscreen(false);
        xserver.getXServerView().nativeSetCompositeRedirected(window.id, false);
    }
    
    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.REDIRECT_WINDOW:
                redirectWindow(client, inputStream, outputStream);
                break;
            case ClientOpcodes.UNREDIRECT_WINDOW:
                unredirectWindow(client, inputStream, outputStream);
                break;
            default:
                throw new BadImplementation();
        }
    }
    
    @Override
    public String getName() {
        return "Composite";
    }
    
    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }
}
