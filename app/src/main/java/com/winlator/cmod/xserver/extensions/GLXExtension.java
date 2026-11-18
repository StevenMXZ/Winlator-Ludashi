package com.winlator.cmod.xserver.extensions;

import android.animation.Animator;
import static com.winlator.cmod.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.util.SparseArray;
import com.winlator.cmod.xserver.Drawable;
import com.winlator.cmod.xserver.Pixmap;
import com.winlator.cmod.xserver.Window;
import android.util.Log;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xconnector.XOutputStream;
import com.winlator.cmod.xconnector.XStreamLock;
import com.winlator.cmod.xserver.XClient;
import com.winlator.cmod.xserver.XServer;
import com.winlator.cmod.xserver.errors.BadDrawable;
import com.winlator.cmod.xserver.errors.BadIdChoice;
import com.winlator.cmod.xserver.errors.BadImplementation;
import com.winlator.cmod.xserver.errors.BadMatch;
import com.winlator.cmod.xserver.errors.BadPixmap;
import com.winlator.cmod.xserver.errors.BadValue;
import com.winlator.cmod.xserver.errors.BadWindow;
import com.winlator.cmod.xserver.errors.GLXBadContext;
import com.winlator.cmod.xserver.errors.GLXBadDrawable;
import com.winlator.cmod.xserver.errors.GLXBadFBConfig;
import com.winlator.cmod.xserver.errors.GLXBadPixmap;
import com.winlator.cmod.xserver.errors.GLXBadProfileARB;
import com.winlator.cmod.xserver.errors.GLXBadWindow;
import com.winlator.cmod.xserver.errors.GLXError;
import com.winlator.cmod.xserver.errors.XRequestError;
import java.io.IOException;
import java.util.ArrayList;

public class GLXExtension implements Extension {
    public static final byte MAJOR_OPCODE = -105;
    
    private XServer xserver;
    private FBConfig defaultConfig;
    private FBConfig[] fbConfigs;
    private ClientInfo clientInfo;
    private SparseArray<GLXContext> glxContexts = new SparseArray<>();
    private SparseArray<GLXPixmap> glxPixmaps = new SparseArray<>();
    private SparseArray<GLXWindow> glxWindows = new SparseArray<>();
    
    private FBConfig findFBConfig(int id){
        for (FBConfig config : fbConfigs) {
            if (config.id == id) {
                return config;
            }
        }
        
        return null;
    }
   
    private static abstract class GLXConstants {
        private static final int GLX_VENDOR = 1;
        private static final int GLX_VERSION = 2;
        private static final int GLX_EXTENSIONS = 3;
        private static final int GLX_NONE = 0x8000;
    }
    
    private static abstract class GLXAttributes {
        private static final int GLX_X_VISUAL_ID = 0x800B;
        private static final int GLX_FBCONFIG_ID = 0x8013;
        private static final int GLX_X_RENDERABLE = 0x8012;
        private static final int GLX_WIDTH = 0x801D;
        private static final int GLX_HEIGHT = 0x801E;
        private static final int GLX_RGBA = 4;
        private static final int GLX_RENDER_TYPE = 0x8011;
        private static final int GLX_DOUBLEBUFFER = 5;
        private static final int GLX_STEREO = 6;
        private static final int GLX_BUFFER_SIZE = 2;
        private static final int GLX_LEVEL = 3;
        private static final int GLX_AUX_BUFFERS = 7;
        private static final int GLX_RED_SIZE = 8;
        private static final int GLX_GREEN_SIZE = 9;
        private static final int GLX_BLUE_SIZE = 10;
        private static final int GLX_ALPHA_SIZE = 11;
        private static final int GLX_DEPTH_SIZE = 12;
        private static final int GLX_STENCIL_SIZE = 13;
        private static final int GLX_ACCUM_RED_SIZE = 14;
        private static final int GLX_ACCUM_GREEN_SIZE = 15;
        private static final int GLX_ACCUM_BLUE_SIZE = 16;
        private static final int GLX_ACCUM_ALPHA_SIZE = 17;
        private static final int GLX_X_VISUAL_TYPE = 0x22;
        private static final int GLX_TRANSPARENT_TYPE = 0x23;
        private static final int GLX_TRANSPARENT_INDEX_VALUE = 0x24;
        private static final int GLX_TRANSPARENT_RED_VALUE = 0x25;
        private static final int GLX_TRANSPARENT_GREEN_VALUE = 0x26;
        private static final int GLX_TRANSPARENT_BLUE_VALUE = 0x27;
        private static final int GLX_TRANSPARENT_ALPHA_VALUE = 0x28;
        private static final int GLX_DRAWABLE_TYPE = 0x8010;
    }
    
    private static abstract class GLXDrawableType {
        private static final int GLX_WINDOW_BIT = 0x00000001;
        private static final int GLX_PIXMAP_BIT = 0x00000002;
    }
    
    private static abstract class GLXRenderType {
        private static final int GLX_RGBA_BIT = 0x00000001;
        private static final int GLX_RGBA_TYPE = 0x8014;
        private static final int GLX_COLOR_INDEX_TYPE = 0x8015;
    }
    
    private static abstract class GLXVisualClass {
        private static final int GLX_TRUE_COLOR = 0x8002;
    }
    
    private static abstract class GLXContextAttribs {
        private static final int GLX_CONTEXT_MAJOR_VERSION_ARB = 0x2091;
        private static final int GLX_CONTEXT_MINOR_VERSION_ARB = 0x2092;
        private static final int GLX_CONTEXT_FLAGS_ARB = 0x2094;
        private static final int GLX_RENDER_TYPE = 0x8011;
        private static final int GLX_CONTEXT_PROFILE_MASK_ARB = 0x9126;
    }
    
    private static abstract class GLXContextFlags {
        private static final int GLX_CONTEXT_DEBUG_BIT_ARB = 0x0001;
        private static final int GLX_CONTEXT_FORWARD_COMPATIBLE_BIT_ARB = 0x0002;
    }
    
    private static abstract class GLXContextProfile {
        private static final int GLX_CONTEXT_CORE_PROFILE_BIT_ARB = 0x00000001;
        private static final int GLX_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB = 0x00000002;
    }
    
    private static abstract class ClientOpcodes {
        private static final byte CREATE_CONTEXT = 3;
        private static final byte DESTROY_CONTEXT = 4;
        private static final byte IS_DIRECT = 6;
        private static final byte QUERY_VERSION = 7;
        private static final byte GET_VISUAL_CONFIGS = 14;
        private static final byte QUERY_SERVER_STRING = 19;
        private static final byte CLIENT_INFO = 20;
        private static final byte GET_FB_CONFIGS = 21;
        private static final byte CREATE_PIXMAP = 22;
        private static final byte DESTROY_PIXMAP = 23;
        private static final byte CREATE_NEW_CONTEXT = 24;
        private static final byte GET_DRAWABLE_ATTRIBUTES = 29;
        private static final byte CREATE_WINDOW = 31;
        private static final byte DESTROY_WINDOW = 32;
        private static final byte CLIENT_INFO_ARB = 33;
        private static final byte CREATE_CONTEXT_ATTRIBS_ARB = 34;
        private static final byte CLIENT_INFO_2_ARB = 35;
    }
    
    private class GLXContext {
        private int id;
        private int visualId;
        private FBConfig fbconfig;
        private int screen;
        private int renderType;
        private int shareList;
        private boolean isDirect;
        private int majorGL;
        private int minorGL;
        private int flags;
        private int profileMask;
        
        GLXContext(int id, int visualId, FBConfig fbconfig, int screen, int renderType, int shareList, boolean isDirect) {
            this.id = id;
            this.visualId = visualId;
            this.fbconfig = fbconfig;
            this.screen = screen;
            this.renderType = renderType;
            this.shareList = shareList;
            this.isDirect = isDirect;
            this.majorGL = 1;
            this.minorGL = 0;
            this.flags = 0;
            this.profileMask = GLXContextProfile.GLX_CONTEXT_CORE_PROFILE_BIT_ARB;
        }
        
        GLXContext(int id, int visualId, FBConfig fbconfig, int screen, int renderType, int shareList, boolean isDirect, int majorGL, int minorGL, int flags, int profileMask) {
            this.id = id;
            this.visualId = visualId;
            this.fbconfig = fbconfig;
            this.screen = screen;
            this.renderType = renderType;
            this.shareList = shareList;
            this.isDirect = isDirect;
            this.majorGL = majorGL;
            this.minorGL = minorGL;
            this.flags = flags;
            this.profileMask = profileMask;
        }
    }
    
    private class GLXPixmap {
        private int id;
        private int screen;
        private Pixmap pixmap;
        private FBConfig fbconfig;
        
        
        GLXPixmap(int id, int screen, Pixmap pixmap, FBConfig fbconfig) {
            this.id = id;
            this.screen = screen;
            this.pixmap = pixmap;
            this.fbconfig = fbconfig;
        }
    }
    
    private class GLXWindow {
        private int id;
        private int screen;
        private FBConfig fbconfig;
        private Window window;
        
        GLXWindow(int id, int screen, FBConfig fbconfig, Window window) {
            this.id = id;
            this.screen = screen;
            this.fbconfig = fbconfig;
            this.window = window;
        }
    }
    
    private class ContextVersion {
        int major;
        int minor;
        int flags;
        
        ContextVersion(int major, int minor, int flags) {
            this.major = major;
            this.minor = minor;
            this.flags = flags;
        }
    }
    
    private class ClientInfo {
        private int majorGLXVersion;
        private int minorGLXVersion;
        private String clientGLXExtensions;
        private String clientGLExtensions;
        private ArrayList<ContextVersion> contextVersions;
        
        ClientInfo(int major, int minor, String clientGLXExtensions, String clientGLExtensions, ArrayList<ContextVersion> contextVersions) {
            this.majorGLXVersion = major;
            this.minorGLXVersion = minor;
            this.clientGLXExtensions = clientGLXExtensions;
            this.clientGLExtensions = clientGLExtensions;
            this.contextVersions = contextVersions;
        }
    }
    
    private class FBConfig {
        private int id;
        private int depth;
        private int stencil;
        private boolean doubleBuffered;
        private int bufferSize;
        
        FBConfig(int id, int depth, int stencil, boolean doubleBuffered, int bufferSize) {
            this.id = id;
            this.depth = depth;
            this.stencil = stencil;
            this.doubleBuffered = doubleBuffered;
            this.bufferSize = bufferSize;
        }
    }
    
    public GLXExtension(XServer xserver) {
        this.xserver = xserver;
        this.defaultConfig = new FBConfig(1, 0, 0, true, 32);
        this.fbConfigs = new FBConfig[] { 
            defaultConfig,
            new FBConfig(2, 0, 0, false, 32),
            new FBConfig(3, 24, 0, false, 32),
            new FBConfig(4, 24, 8, false, 32),
            new FBConfig(5, 24, 0, true, 32),
            new FBConfig(6, 24, 8, true, 32)
        };
    }
    
    private void writeFBConfig(XOutputStream outputStream, FBConfig fbConfig) {
        outputStream.writeIntPair(GLXAttributes.GLX_X_VISUAL_ID, xserver.drawableManager.getVisual().id);
        outputStream.writeIntPair(GLXAttributes.GLX_FBCONFIG_ID, fbConfig.id);
        outputStream.writeIntPair(GLXAttributes.GLX_X_RENDERABLE, 1);
        outputStream.writeIntPair(GLXAttributes.GLX_RGBA, 1);
        outputStream.writeIntPair(GLXAttributes.GLX_RENDER_TYPE, GLXRenderType.GLX_RGBA_BIT);
        outputStream.writeIntPair(GLXAttributes.GLX_DOUBLEBUFFER, fbConfig.doubleBuffered ? 1 : 0);
        outputStream.writeIntPair(GLXAttributes.GLX_STEREO, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_BUFFER_SIZE, fbConfig.bufferSize);
        outputStream.writeIntPair(GLXAttributes.GLX_LEVEL, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_AUX_BUFFERS, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_RED_SIZE, 8);
        outputStream.writeIntPair(GLXAttributes.GLX_BLUE_SIZE, 8);
        outputStream.writeIntPair(GLXAttributes.GLX_GREEN_SIZE, 8);
        outputStream.writeIntPair(GLXAttributes.GLX_ALPHA_SIZE, 8);
        outputStream.writeIntPair(GLXAttributes.GLX_ACCUM_RED_SIZE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_ACCUM_BLUE_SIZE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_ACCUM_GREEN_SIZE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_ACCUM_ALPHA_SIZE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_DEPTH_SIZE, fbConfig.depth);
        outputStream.writeIntPair(GLXAttributes.GLX_STENCIL_SIZE, fbConfig.stencil);
        outputStream.writeIntPair(GLXAttributes.GLX_X_VISUAL_TYPE, GLXVisualClass.GLX_TRUE_COLOR);
        outputStream.writeIntPair(GLXAttributes.GLX_TRANSPARENT_TYPE, GLXConstants.GLX_NONE);
        outputStream.writeIntPair(GLXAttributes.GLX_TRANSPARENT_INDEX_VALUE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_TRANSPARENT_RED_VALUE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_TRANSPARENT_BLUE_VALUE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_TRANSPARENT_GREEN_VALUE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_TRANSPARENT_ALPHA_VALUE, 0);
        outputStream.writeIntPair(GLXAttributes.GLX_DRAWABLE_TYPE, GLXDrawableType.GLX_WINDOW_BIT | GLXDrawableType.GLX_PIXMAP_BIT);
        for (int i = 0; i < 16; i++) {
            outputStream.writeIntPair(0, 0);
        }
    }
   
    private void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);
        
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(4);
            outputStream.writePad(16);
        }
    }
    
    private void getVisualConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(18 * fbConfigs.length);
            outputStream.writeInt(fbConfigs.length);
            outputStream.writeInt(18);
            outputStream.writePad(16);
            
            for (FBConfig fbconfig : fbConfigs) {
                outputStream.writeInt(xserver.drawableManager.getVisual().id);
                outputStream.writeInt(xserver.drawableManager.getVisual().visualClass);
                outputStream.writeInt(1);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(8);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
                outputStream.writeInt(fbconfig.doubleBuffered ? 1 : 0);
                outputStream.writeInt(0);
                outputStream.writeInt(fbconfig.bufferSize);
                outputStream.writeInt(fbconfig.depth);
                outputStream.writeInt(fbconfig.stencil);
                outputStream.writeInt(0);
                outputStream.writeInt(0);
            }
        }
    }
    
    private void queryServerString(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        int name = inputStream.readInt();
        
        String returnedString;
        switch(name) {
            case GLXConstants.GLX_VENDOR:
                returnedString = "Winlator ";
                break;
            case GLXConstants.GLX_VERSION:
                returnedString = "1.4 ";
                break;
            case GLXConstants.GLX_EXTENSIONS:
                returnedString = "GLX_ARB_create_context GLX_ARB_create_context_profile ";
                break;
            default:
                returnedString = "";
                break;
        }
        
        int stringLength = returnedString.length();
        
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt((stringLength + 3) / 4);
            outputStream.writePad(4);
            outputStream.writeInt(stringLength);
            outputStream.writePad(16);
            outputStream.writeString8(returnedString);
        }
    }
    
    private void getFBConfigs(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(4);
        
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(88 * fbConfigs.length);
            outputStream.writeInt(fbConfigs.length);
            outputStream.writeInt(44);
            outputStream.writePad(16);
            
            for (FBConfig config : fbConfigs) {
                writeFBConfig(outputStream, config);
            }
        }  
    }
    
    private void setClientInfo(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int majorVersion = inputStream.readInt();
        int minorVersion = inputStream.readInt();
        int clientStringLength = inputStream.readInt();
        String clientExtensions = inputStream.readString8(clientStringLength);
        
        clientInfo = new ClientInfo(majorVersion, minorVersion, clientExtensions, "", null);
    }
    
    private void setClientInfoARB(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int majorVersion = inputStream.readInt();
        int minorVersion = inputStream.readInt();
        int numContextVersions = inputStream.readInt();
        int clientGLStringLength = inputStream.readInt();
        int clientGLXStringLength = inputStream.readInt();
        
        ArrayList<ContextVersion> contextVersions = new ArrayList<>();
        for (int i = 0; i < numContextVersions; i++) {
            int major = inputStream.readInt();
            int minor = inputStream.readInt();
            ContextVersion contextVersion = new ContextVersion(major, minor, 0);
            contextVersions.add(contextVersion);
        }
        
        String clientGLExtensions = inputStream.readString8(clientGLStringLength);
        String clientGLXExtensions = inputStream.readString8(clientGLXStringLength);
        
        clientInfo = new ClientInfo(majorVersion, minorVersion, clientGLExtensions, clientGLXExtensions, contextVersions);
    }
    
    private void setClientInfo2ARB(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int majorVersion = inputStream.readInt();
        int minorVersion = inputStream.readInt();
        int numContextVersions = inputStream.readInt();
        int clientGLStringLength = inputStream.readInt();
        int clientGLXStringLength = inputStream.readInt();
        
        ArrayList<ContextVersion> contextVersions = new ArrayList<>();
        for (int i = 0; i < numContextVersions; i++) {
            int major = inputStream.readInt();
            int minor = inputStream.readInt();
            int flags = inputStream.readInt();
            ContextVersion contextVersion = new ContextVersion(major, minor, flags);
            contextVersions.add(contextVersion);
        }
        
        String clientGLExtensions = inputStream.readString8(clientGLStringLength);
        String clientGLXExtensions = inputStream.readString8(clientGLXStringLength);
        
        clientInfo = new ClientInfo(majorVersion, minorVersion, clientGLExtensions, clientGLXExtensions, contextVersions);
    }
    
    private void createContextAttribsARB(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        int fbconfigId = inputStream.readInt();
        int screen = inputStream.readInt();
        int shareList = inputStream.readInt();
        
        boolean isDirect = inputStream.readByte() != 0;
        inputStream.skip(3);
        
        int numAttribs = inputStream.readInt();
        
        int majorGL = 1;
        int minorGL = 0;
        int flags = 0;
        int renderType = GLXRenderType.GLX_RGBA_TYPE;
        int profileMask = GLXContextProfile.GLX_CONTEXT_CORE_PROFILE_BIT_ARB;
        for (int i = 0; i < numAttribs; i++) {
            int attr = inputStream.readInt();
            int value = inputStream.readInt();
            
            switch(attr) {
                case GLXContextAttribs.GLX_CONTEXT_MAJOR_VERSION_ARB:
                    majorGL = value;
                    break;
                case GLXContextAttribs.GLX_CONTEXT_MINOR_VERSION_ARB:
                    minorGL = value;
                    break;
                case GLXContextAttribs.GLX_CONTEXT_FLAGS_ARB:
                    flags = value;
                    break;
                case GLXContextAttribs.GLX_RENDER_TYPE:
                    if (value != GLXRenderType.GLX_COLOR_INDEX_TYPE && value != GLXRenderType.GLX_RGBA_TYPE) throw new BadValue(value);
                    renderType = value;
                    break;
                case GLXContextAttribs.GLX_CONTEXT_PROFILE_MASK_ARB:
                    if (value == 0) throw new GLXBadProfileARB(value);
                    if ((value & ~(GLXContextProfile.GLX_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB | GLXContextProfile.GLX_CONTEXT_CORE_PROFILE_BIT_ARB)) != 0) throw new GLXBadProfileARB(value);
                    if (value != GLXContextProfile.GLX_CONTEXT_CORE_PROFILE_BIT_ARB && value != GLXContextProfile.GLX_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB) throw new GLXBadProfileARB(value);
                    
                    profileMask = value;
                    break;
                default:
                     throw new BadValue(attr);
            }
        }
        
        if (renderType != GLXRenderType.GLX_RGBA_TYPE) throw new BadValue(renderType);
        
        FBConfig fbconfig = findFBConfig(fbconfigId);
        if (fbconfig == null) throw new GLXBadFBConfig(fbconfigId);
        
        GLXContext sharedContext = glxContexts.get(shareList);
        if (sharedContext == null && shareList != 0) throw new GLXBadContext(shareList);
        
        if (majorGL > 4 || majorGL < 1) throw new BadMatch();
        if (majorGL == 1 && (minorGL < 0 || minorGL > 5)) throw new BadMatch();
        if (majorGL == 2 && (minorGL < 0 || minorGL > 1)) throw new BadMatch();
        if (majorGL == 3 && (minorGL < 0 || minorGL  > 3)) throw new BadMatch();
        if (majorGL >= 3 && renderType == GLXRenderType.GLX_COLOR_INDEX_TYPE) throw new BadMatch();
        if (majorGL < 3 && (flags & GLXContextFlags.GLX_CONTEXT_FORWARD_COMPATIBLE_BIT_ARB) != 0) throw new BadMatch();
        
        GLXContext context = new GLXContext(contextId, xserver.drawableManager.getVisual().id, fbconfig, screen, renderType, shareList, isDirect, majorGL, minorGL, flags, profileMask);
        glxContexts.put(contextId, context);
    }
    
    private void createNewContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        int fbconfigId = inputStream.readInt();
        int screen = inputStream.readInt();
        int renderType = inputStream.readInt();
        int shareList = inputStream.readInt();

        boolean isDirect = inputStream.readByte() != 0;
        inputStream.skip(3);
        
        if (renderType != GLXRenderType.GLX_RGBA_TYPE) throw new BadValue(renderType);
        
        FBConfig fbconfig = findFBConfig(fbconfigId);
        if (fbconfig == null) throw new GLXBadFBConfig(fbconfigId);
        
        GLXContext sharedContext = glxContexts.get(shareList);
        if (sharedContext == null && shareList != 0) throw new GLXBadContext(shareList);
        
        GLXContext context = new GLXContext(contextId, xserver.drawableManager.getVisual().id, fbconfig, screen, renderType, shareList, isDirect);
        glxContexts.put(contextId, context);
    }
    
    private void createContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        int visualId = inputStream.readInt();
        int screen = inputStream.readInt();
        int shareList = inputStream.readInt();
        boolean isDirect = inputStream.readByte() != 0;

        inputStream.skip(3);
        
        if (visualId != xserver.drawableManager.getVisual().id) throw new BadValue(visualId);
        
        GLXContext sharedContext = glxContexts.get(shareList);
        if (sharedContext == null && shareList != 0) throw new GLXBadContext(shareList);
        
        GLXContext context = new GLXContext(contextId, visualId, null, screen, -1, shareList, isDirect);
        glxContexts.put(contextId, context);
    }
    
    private void isDirect(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        
        GLXContext context = glxContexts.get(contextId);
        if (context == null) throw new GLXBadContext(contextId);
        
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeByte(context.isDirect ? (byte)1 : (byte)0);
            outputStream.writePad(23);
        }
    }
    
    private void getDrawableAttributes(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int drawableId = inputStream.readInt();
        
        Window window;
        GLXWindow glxWindow;
        GLXPixmap glxPixmap;
        
        glxWindow = glxWindows.get(drawableId);
        if (glxWindow == null) 
            window = xserver.windowManager.getWindow(drawableId);
        else 
            window = glxWindow.window;
                    
        glxPixmap = glxPixmaps.get(drawableId);
        
        if (glxWindow == null && window == null && glxPixmap == null) throw new GLXBadDrawable(drawableId);
        
        FBConfig fbconfig = glxWindow != null ? glxWindow.fbconfig : ((glxPixmap != null) ? glxPixmap.fbconfig : defaultConfig);
        int drawableType = glxPixmap != null ? GLXDrawableType.GLX_PIXMAP_BIT : GLXDrawableType.GLX_WINDOW_BIT;
        int width = window != null ? window.getWidth() : glxPixmap.pixmap.drawable.width;
        int height = window != null ? window.getHeight() : glxPixmap.pixmap.drawable.height;
        
        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(10);
            outputStream.writeInt(5);
            outputStream.writePad(20);
            
            outputStream.writeIntPair(GLXAttributes.GLX_FBCONFIG_ID, fbconfig.id);
            outputStream.writeIntPair(GLXAttributes.GLX_DRAWABLE_TYPE, drawableType);
            outputStream.writeIntPair(GLXAttributes.GLX_RENDER_TYPE, 1);
            outputStream.writeIntPair(GLXAttributes.GLX_WIDTH, width);
            outputStream.writeIntPair(GLXAttributes.GLX_HEIGHT, height);
        }
    }
    
    private void createPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int screen = inputStream.readInt();
        int fbConfigId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int glxPixmapId = inputStream.readInt();
        int numAttrs = inputStream.readInt();
        
        for (int i = 0; i < numAttrs; i++) {
            inputStream.readInt();
            inputStream.readInt();
        }
        
        Pixmap pixmap = xserver.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);
        
        FBConfig fbconfig = findFBConfig(fbConfigId);
        if (fbconfig == null) throw new GLXBadFBConfig(fbConfigId);
        
        if (pixmap.drawable.visual.depth != fbconfig.bufferSize) throw new BadMatch();
        
        GLXPixmap glxPixmap = new GLXPixmap(glxPixmapId, screen, pixmap, fbconfig);
        glxPixmaps.put(glxPixmapId, glxPixmap);
    }
    
    private void destroyPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int glxPixmapId = inputStream.readInt();
        
        GLXPixmap glxPixmap = glxPixmaps.get(glxPixmapId);
        if (glxPixmap == null) throw new GLXBadPixmap(glxPixmapId);
        
        glxPixmaps.remove(glxPixmapId);
    }
    
    private void createWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int screen = inputStream.readInt();
        int fbConfigId = inputStream.readInt();
        int windowId = inputStream.readInt();
        int glxWindowId = inputStream.readInt();
        int numAttrs = inputStream.readInt();
        
        for (int i = 0; i < numAttrs; i++) {
            inputStream.readInt();
            inputStream.readInt();
        }
        
        Window window = xserver.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);
        
        FBConfig fbConfig = findFBConfig(fbConfigId);
        if (fbConfig == null) throw new GLXBadFBConfig(fbConfigId);
        
        if (window.getContent().visual.depth != fbConfig.bufferSize) throw new BadMatch();
        
        GLXWindow glxWindow = new GLXWindow(glxWindowId, screen, fbConfig, window);
        glxWindows.put(glxWindowId, glxWindow);
    }
    
    private void destroyWindow(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int glxWindowId = inputStream.readInt();
        
        GLXWindow window = glxWindows.get(glxWindowId);
        if (window == null) throw new GLXBadWindow(glxWindowId);
        
        glxWindows.delete(glxWindowId);
    }
    
    private void destroyContext(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int contextId = inputStream.readInt();
        
        GLXContext context = glxContexts.get(contextId);
        if (context == null) throw new GLXBadContext(contextId);
        
        glxContexts.delete(contextId);
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION:
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.QUERY_SERVER_STRING:
                queryServerString(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_VISUAL_CONFIGS:
                getVisualConfigs(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_FB_CONFIGS:
                getFBConfigs(client, inputStream, outputStream);
                break;        
            case ClientOpcodes.CLIENT_INFO:
                setClientInfo(client, inputStream, outputStream);
                break;     
            case ClientOpcodes.CLIENT_INFO_ARB:
                setClientInfoARB(client, inputStream, outputStream);
                break;
            case ClientOpcodes.CLIENT_INFO_2_ARB:
                setClientInfo2ARB(client, inputStream, outputStream);
                break;       
            case ClientOpcodes.CREATE_NEW_CONTEXT:
                createNewContext(client, inputStream, outputStream);
                break;    
            case ClientOpcodes.IS_DIRECT:
                isDirect(client, inputStream, outputStream);
                break;  
            case ClientOpcodes.CREATE_CONTEXT:
                createContext(client, inputStream, outputStream);
                break;
            case ClientOpcodes.GET_DRAWABLE_ATTRIBUTES:
                getDrawableAttributes(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_CONTEXT:
                destroyContext(client, inputStream, outputStream);
                break;  
            case ClientOpcodes.CREATE_WINDOW:
                createWindow(client, inputStream, outputStream);
                break;
            case ClientOpcodes.DESTROY_WINDOW:
                destroyWindow(client, inputStream, outputStream);
                break; 
            case ClientOpcodes.CREATE_PIXMAP:
                createPixmap(client, inputStream, outputStream);
                break; 
            case ClientOpcodes.DESTROY_PIXMAP:
                destroyPixmap(client, inputStream, outputStream);
                break;
            case ClientOpcodes.CREATE_CONTEXT_ATTRIBS_ARB:
                createContextAttribsARB(client, inputStream, outputStream);
                break;
            default:
                Log.d("GLXExtension", "Unimpemented opcode " + opcode);
                throw new BadImplementation();
        }
    }
    
    @Override
    public String getName() {
        return "GLX";
    }
    
    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return GLXError.BASE_ERROR_CODE;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }
}
