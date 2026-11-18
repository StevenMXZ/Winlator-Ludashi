package com.winlator.cmod.xserver.errors;

public class GLXBadWindow extends XRequestError {
    public GLXBadWindow(int id) {
        super(GLXError.BASE_ERROR_CODE + 12, id);
    }
}
