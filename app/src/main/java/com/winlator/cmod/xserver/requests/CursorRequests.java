package com.winlator.cmod.xserver.requests;

import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.Cursor;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Pointer;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.XRequestError;

import java.io.IOException;

public abstract class CursorRequests {
    public static void createCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        int cursorId = inputStream.readInt();
        int sourcePixmapId = inputStream.readInt();
        int maskPixmapId = inputStream.readInt();

        if (!client.isValidResourceId(cursorId)) throw new BadIdChoice(cursorId);

        Pixmap sourcePixmap = client.xServer.pixmapManager.getPixmap(sourcePixmapId);
        if (sourcePixmap == null) throw new BadPixmap(sourcePixmapId);

        Pixmap maskPixmap = client.xServer.pixmapManager.getPixmap(maskPixmapId);
        if (maskPixmap != null && (
            maskPixmap.drawable.visual.depth != 1 ||
            maskPixmap.drawable.width != sourcePixmap.drawable.width ||
            maskPixmap.drawable.height != sourcePixmap.drawable.height)) {
            throw new BadMatch();
        }

        byte foreRed = (byte)inputStream.readShort();
        byte foreGreen = (byte)inputStream.readShort();
        byte foreBlue = (byte)inputStream.readShort();
        byte backRed = (byte)inputStream.readShort();
        byte backGreen = (byte)inputStream.readShort();
        byte backBlue = (byte)inputStream.readShort();
        short x = inputStream.readShort();
        short y = inputStream.readShort();

        Cursor cursor = client.xServer.cursorManager.createCursor(cursorId, x, y, sourcePixmap, maskPixmap);
        if (cursor == null) throw new BadIdChoice(cursorId);
        client.xServer.cursorManager.recolorCursor(cursor, foreRed, foreGreen, foreBlue, backRed, backGreen, backBlue);
        client.registerAsOwnerOfResource(cursor);
    }

    public static void freeCursor(XClient client, XInputStream inputStream, XOutputStream outputStream) throws XRequestError {
        client.xServer.cursorManager.freeCursor(inputStream.readInt());
    }

    // Identity map for every button (wheel included): Wine maps XI raw buttons through this table
    // and reads past its end if it is shorter than the button number
    public static void getPointerMaping(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        try (XStreamLock lock = outputStream.lock()) {
            byte n = Pointer.MAX_BUTTONS;
            int padLen = -n & 3;

            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte(n);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((n + padLen) / 4);
            outputStream.writePad(24);

            for (int i = 1; i <= n; i++) outputStream.writeByte((byte)i);
            outputStream.writePad(padLen);
        }
    }
}
