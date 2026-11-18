package com.winlator.cmod.xserver.errors;

public class XIBadDevice extends XRequestError {
    public XIBadDevice(int id) {
        super(XInputError.BASE_ERROR_CODE, id);
    }
}
