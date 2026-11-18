package com.winlator.cmod.xserver.errors;

public class GLXBadProfileARB extends XRequestError {
    public GLXBadProfileARB(int id) {
        super(GLXError.BASE_ERROR_CODE + 13, id);
    }
}
