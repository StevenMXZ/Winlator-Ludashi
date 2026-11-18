package com.winlator.cmod.xserver.errors;

public class GLXBadFBConfig extends XRequestError {
    public GLXBadFBConfig(int id) {
        super(GLXError.BASE_ERROR_CODE + 9, id);
    }
}
