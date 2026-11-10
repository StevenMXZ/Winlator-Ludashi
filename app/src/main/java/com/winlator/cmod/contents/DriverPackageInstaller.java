package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;

/** Shared dispatch for both local-package and local-driver pickers. */
public final class DriverPackageInstaller {
    public static final class Result {
        public final String id;
        public final boolean freedreno;
        Result(String id, boolean freedreno) { this.id = id; this.freedreno = freedreno; }
    }
    public static Result install(Context context, Uri uri) throws Exception {
        if (OpenGLDriverManager.isFreedreno(context, uri))
            return new Result(new OpenGLDriverManager(context).install(uri), true);
        String id = new AdrenotoolsManager(context).installDriver(uri);
        if (id == null || id.isEmpty()) throw new IOException("Invalid Vulkan driver ZIP or driver already installed");
        return new Result(id, false);
    }
    private DriverPackageInstaller() {}
}
