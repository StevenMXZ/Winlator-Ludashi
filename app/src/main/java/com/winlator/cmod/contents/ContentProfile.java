package com.winlator.cmod.contents;

import com.winlator.cmod.container.Container;
import java.util.List;

/* loaded from: classes15.dex */
public class ContentProfile {
    public static final String MARK_DESC = "description";
    public static final String MARK_FILE_LIST = "files";
    public static final String MARK_FILE_SOURCE = "source";
    public static final String MARK_FILE_TARGET = "target";
    public static final String MARK_TYPE = "type";
    public static final String MARK_VERSION_CODE = "versionCode";
    public static final String MARK_VERSION_NAME = "versionName";
    public static final String MARK_WINE = "wine";
    public static final String MARK_WINE_BINPATH = "binPath";
    public static final String MARK_WINE_LIBPATH = "libPath";
    public static final String MARK_WINE_PREFIX_PACK = "prefixPack";
    public String desc;
    public List<ContentFile> fileList;
    public String remoteUrl;
    public ContentType type;
    public int verCode;
    public String verName;
    public String wineBinPath;
    public String wineLibPath;
    public String winePrefixPack;

    public static class ContentFile {
        public String source;
        public String target;
    }

    public enum ContentType {
        CONTENT_TYPE_WINE("Wine"),
        CONTENT_TYPE_PROTON("Proton"),
        CONTENT_TYPE_DXVK("DXVK"),
        CONTENT_TYPE_VKD3D("VKD3D"),
        CONTENT_TYPE_BOX64("Box64"),
        CONTENT_TYPE_WOWBOX64("WOWBox64"),
        CONTENT_TYPE_FEXCORE(Container.DEFAULT_EMULATOR);

        final String typeName;

        ContentType(String typeName) {
            this.typeName = typeName;
        }

        @Override // java.lang.Enum
        public String toString() {
            return this.typeName;
        }

        public static ContentType getTypeByName(String name) {
            for (ContentType type : values()) {
                if (type.typeName.toLowerCase().equals(name.toLowerCase())) {
                    return type;
                }
            }
            return null;
        }
    }
}
