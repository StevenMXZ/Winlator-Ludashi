package com.winlator.cmod.xserver.errors;

public class GLXBadDrawable extends XRequestError {
    public GLXBadDrawable(int id) {
        super(GLXError.BASE_ERROR_CODE + 2, id);
    }
}
