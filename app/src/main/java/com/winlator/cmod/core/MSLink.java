package com.winlator.cmod.core;

import android.content.Context;
import com.winlator.cmod.xenvironment.ImageFs;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.commons.compress.archivers.tar.TarConstants;

/* loaded from: classes10.dex */
public abstract class MSLink {
    private static final int ForceNoLinkInfo = 256;
    private static final int HasArguments = 32;
    private static final int HasIconLocation = 64;
    private static final int HasLinkTargetIDList = 1;
    public static final byte SW_SHOWMAXIMIZED = 3;
    public static final byte SW_SHOWMINNOACTIVE = 7;
    public static final byte SW_SHOWNORMAL = 1;

    public static final class Options {
        public String cmdArgs;
        public int fileSize;
        public int iconIndex;
        public String iconLocation;
        public int showCommand = 1;
        public String targetPath;
    }

    private static int charToHexDigit(char chr) {
        return chr >= 'A' ? (chr - 'A') + 10 : chr - '0';
    }

    private static byte twoCharsToByte(char chr1, char chr2) {
        return (byte) ((charToHexDigit(Character.toUpperCase(chr1)) * 16) + charToHexDigit(Character.toUpperCase(chr2)));
    }

    private static byte[] convertCLSIDtoDATA(String str) {
        return new byte[]{twoCharsToByte(str.charAt(6), str.charAt(7)), twoCharsToByte(str.charAt(4), str.charAt(5)), twoCharsToByte(str.charAt(2), str.charAt(3)), twoCharsToByte(str.charAt(0), str.charAt(1)), twoCharsToByte(str.charAt(11), str.charAt(12)), twoCharsToByte(str.charAt(9), str.charAt(10)), twoCharsToByte(str.charAt(16), str.charAt(17)), twoCharsToByte(str.charAt(14), str.charAt(15)), twoCharsToByte(str.charAt(19), str.charAt(20)), twoCharsToByte(str.charAt(21), str.charAt(22)), twoCharsToByte(str.charAt(24), str.charAt(25)), twoCharsToByte(str.charAt(26), str.charAt(27)), twoCharsToByte(str.charAt(28), str.charAt(29)), twoCharsToByte(str.charAt(30), str.charAt(31)), twoCharsToByte(str.charAt(32), str.charAt(33)), twoCharsToByte(str.charAt(34), str.charAt(35))};
    }

    private static byte[] stringToByteArray(String str) {
        byte[] bytes = new byte[str.length()];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) str.charAt(i);
        }
        return bytes;
    }

    private static byte[] intToByteArray(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private static byte[] stringSizePaddedToByteArray(String str) {
        ByteBuffer buffer = ByteBuffer.allocate(str.length() + 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) str.length());
        for (int i = 0; i < str.length(); i++) {
            buffer.put((byte) str.charAt(i));
        }
        return buffer.array();
    }

    private static byte[] generateIDLIST(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (bytes.length + 2));
        return ArrayUtils.concat(buffer.array(), bytes);
    }

    public static void createFile(String targetPath, File outputFile) {
        Options options = new Options();
        options.targetPath = targetPath;
        createFile(options, outputFile);
    }

    public static void createFile(Options options, File outputFile) {
        byte[] FileAttributes;
        byte[] prefixOfTarget;
        byte[] CLSIDNetwork;
        byte[] targetLeaf;
        byte[] prefixRoot;
        byte[] targetRoot;
        Throwable th;
        byte[] targetRoot2;
        byte[] targetLeaf2;
        byte[] HeaderSize = {TarConstants.LF_GNUTYPE_LONGNAME, 0, 0, 0};
        byte[] LinkCLSID = convertCLSIDtoDATA("00021401-0000-0000-c000-000000000046");
        int linkFlags = 257;
        if (options.cmdArgs != null && !options.cmdArgs.isEmpty()) {
            linkFlags = 257 | 32;
        }
        if (options.iconLocation != null && !options.iconLocation.isEmpty()) {
            linkFlags |= 64;
        }
        byte[] LinkFlags = intToByteArray(linkFlags);
        options.targetPath = options.targetPath.replaceAll("/+", "\\\\");
        if (options.targetPath.endsWith("\\")) {
            FileAttributes = new byte[]{16, 0, 0, 0};
            prefixOfTarget = new byte[]{49, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            options.targetPath = options.targetPath.replaceAll("\\\\+$", "");
        } else {
            FileAttributes = new byte[]{32, 0, 0, 0};
            prefixOfTarget = new byte[]{TarConstants.LF_SYMLINK, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        }
        byte[] CreationTime = {0, 0, 0, 0, 0, 0, 0, 0};
        byte[] FileSize = intToByteArray(options.fileSize);
        byte[] IconIndex = intToByteArray(options.iconIndex);
        byte[] ShowCommand = intToByteArray(options.showCommand);
        byte[] Hotkey = {0, 0};
        byte[] Reserved1 = {0, 0};
        byte[] Reserved2 = {0, 0, 0, 0};
        byte[] Reserved3 = {0, 0, 0, 0};
        byte[] CLSIDComputer = convertCLSIDtoDATA("20d04fe0-3aea-1069-a2d8-08002b30309d");
        byte[] CLSIDNetwork2 = convertCLSIDtoDATA("208d2c60-3aea-1069-a2d7-08002b30309d");
        if (options.targetPath.startsWith("\\")) {
            byte[] prefixRoot2 = {-61, 1, -127};
            byte[] targetRoot3 = stringToByteArray(options.targetPath);
            if (options.targetPath.endsWith("\\")) {
                targetRoot2 = targetRoot3;
                targetLeaf2 = null;
            } else {
                targetRoot2 = targetRoot3;
                targetLeaf2 = stringToByteArray(options.targetPath.substring(options.targetPath.lastIndexOf("\\") + 1));
            }
            targetRoot = targetLeaf2;
            targetLeaf = targetRoot2;
            CLSIDNetwork = ArrayUtils.concat(new byte[]{31, TarConstants.LF_PAX_EXTENDED_HEADER_UC}, CLSIDNetwork2);
            prefixRoot = prefixRoot2;
        } else {
            byte[] prefixRoot3 = {47};
            int index = options.targetPath.indexOf("\\");
            byte[] targetRoot4 = stringToByteArray(options.targetPath.substring(0, index + 1));
            byte[] targetLeaf3 = stringToByteArray(options.targetPath.substring(index + 1));
            byte[] targetLeaf4 = {31, 80};
            CLSIDNetwork = ArrayUtils.concat(targetLeaf4, CLSIDComputer);
            targetLeaf = targetRoot4;
            prefixRoot = prefixRoot3;
            targetRoot = targetLeaf3;
        }
        byte[] targetRoot5 = ArrayUtils.concat(targetLeaf, new byte[21]);
        byte[] endOfString = {0};
        byte[] IDListItems = ArrayUtils.concat(generateIDLIST(CLSIDNetwork), generateIDLIST(ArrayUtils.concat(prefixRoot, targetRoot5, endOfString)));
        if (targetRoot != null) {
            IDListItems = ArrayUtils.concat(IDListItems, generateIDLIST(ArrayUtils.concat(prefixOfTarget, targetRoot, endOfString)));
        }
        byte[] IDList = generateIDLIST(IDListItems);
        byte[] TerminalID = {0, 0};
        byte[] StringData = new byte[0];
        if ((linkFlags & 32) != 0) {
            StringData = ArrayUtils.concat(StringData, stringSizePaddedToByteArray(options.cmdArgs));
        }
        if ((linkFlags & 64) != 0) {
            StringData = ArrayUtils.concat(StringData, stringSizePaddedToByteArray(options.iconLocation));
        }
        byte[] StringData2 = StringData;
        try {
            FileOutputStream os = new FileOutputStream(outputFile);
            try {
                os.write(HeaderSize);
                os.write(LinkCLSID);
                os.write(LinkFlags);
                os.write(FileAttributes);
                os.write(CreationTime);
                os.write(CreationTime);
                os.write(CreationTime);
                os.write(FileSize);
                try {
                    os.write(IconIndex);
                    try {
                        os.write(ShowCommand);
                        try {
                            os.write(Hotkey);
                            try {
                                os.write(Reserved1);
                                try {
                                    os.write(Reserved2);
                                    try {
                                        os.write(Reserved3);
                                        os.write(IDList);
                                        try {
                                            os.write(TerminalID);
                                            if (StringData2.length > 0) {
                                                os.write(StringData2);
                                            }
                                            try {
                                                os.close();
                                            } catch (IOException e) {
                                                e = e;
                                                e.printStackTrace();
                                            }
                                        } catch (Throwable th2) {
                                            th = th2;
                                            try {
                                                try {
                                                    os.close();
                                                    throw th;
                                                } catch (IOException e2) {
                                                    e = e2;
                                                    e.printStackTrace();
                                                }
                                            } catch (Throwable th3) {
                                                th.addSuppressed(th3);
                                                throw th;
                                            }
                                        }
                                    } catch (Throwable th4) {
                                        th = th4;
                                    }
                                } catch (Throwable th5) {
                                    th = th5;
                                }
                            } catch (Throwable th6) {
                                th = th6;
                            }
                        } catch (Throwable th7) {
                            th = th7;
                        }
                    } catch (Throwable th8) {
                        th = th8;
                    }
                } catch (Throwable th9) {
                    th = th9;
                }
            } catch (Throwable th10) {
                th = th10;
            }
        } catch (IOException e3) {
            e = e3;
        }
    }

    public static String parseFilePath(File lnkFile) {
        int linkInfoStart;
        String filePath = "";
        try {
            FileInputStream fis = new FileInputStream(lnkFile);
            byte[] bytes = new byte[(int) lnkFile.length()];
            DataInputStream dis = new DataInputStream(fis);
            dis.readFully(bytes);
            ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int linkFlags = data.getInt(20);
            if ((linkFlags & 1) != 0) {
                short linkInfoTargetIdListSize = data.getShort(76);
                linkInfoStart = linkInfoTargetIdListSize + 78;
            } else {
                int linkInfoStart2 = linkFlags & 2;
                if (linkInfoStart2 == 0) {
                    return "";
                }
                linkInfoStart = 76;
            }
            int localBasePathOffset = data.getInt(linkInfoStart + 16);
            if (localBasePathOffset > 0) {
                filePath = StringUtils.fromANSIString(data, linkInfoStart + localBasePathOffset);
            }
            dis.close();
            fis.close();
        } catch (IOException e) {
        }
        return filePath;
    }

    public static void createDesktopFile(File lnkFile, Context context) {
        String lnkFilePath = lnkFile.getPath();
        String filePath = StringUtils.escapeFileDOSPath(parseFilePath(lnkFile));
        ImageFs imageFs = ImageFs.find(context);
        File desktopFile = new File(lnkFilePath.substring(0, lnkFilePath.lastIndexOf(".")) + ".desktop");
        try {
            FileOutputStream fos = new FileOutputStream(desktopFile);
            PrintWriter pw = new PrintWriter(fos);
            pw.write("[Desktop Entry]\n");
            pw.write("Name=" + lnkFile.getName().substring(0, lnkFile.getName().lastIndexOf(".")) + "\n");
            pw.write("Exec=env WINEPREFIX=\"" + imageFs.wineprefix + "\" wine " + filePath + "\n");
            pw.write("Type=Application\n");
            pw.write("StartupNotify=True\n");
            pw.close();
            fos.close();
        } catch (IOException e) {
        }
    }
}
