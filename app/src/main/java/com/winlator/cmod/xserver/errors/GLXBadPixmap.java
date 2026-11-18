package com.winlator.cmod.xserver.errors;

public class GLXBadPixmap extends XRequestError {
    public GLXBadPixmap(int id) {
        super(GLXError.BASE_ERROR_CODE + 3, id);
    }
}
