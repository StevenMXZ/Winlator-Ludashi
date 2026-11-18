package com.winlator.cmod.xserver.errors;

public class GLXBadContext extends XRequestError {
    public GLXBadContext(int id) {
        super(GLXError.BASE_ERROR_CODE, id);
    }
}
