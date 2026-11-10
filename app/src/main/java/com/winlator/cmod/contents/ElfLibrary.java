package com.winlator.cmod.contents;

import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.util.*;

/** Read ELF64 ARM64 DT_NEEDED names without executing an imported library. */
public final class ElfLibrary {
    public static List<String> dependencies(File file) throws IOException {
        byte[] bytes = Files.readAllBytes(file.toPath());
        try {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (bytes.length < 64 || b.getInt(0) != 0x464c457f || bytes[4] != 2 || bytes[5] != 1
                    || b.getShort(18) != 183 || b.getShort(16) != 3) throw new IOException("Expected ARM64 ELF shared library");
            long ph = b.getLong(32); int size = b.getShort(54) & 65535, count = b.getShort(56) & 65535;
            if (size < 56 || count > 4096 || ph < 0 || ph + (long)size*count > bytes.length) throw new IOException("Invalid ELF headers");
            long dynamic = -1, dynamicSize = 0, strings = -1;
            List<Long> needed = new ArrayList<>();
            for (int i=0;i<count;i++) {
                int p = (int)(ph+(long)i*size);
                if (b.getInt(p)==2) { dynamic=b.getLong(p+8); dynamicSize=b.getLong(p+32); }
            }
            if (dynamic < 0 || dynamicSize < 0 || dynamic+dynamicSize > bytes.length) throw new IOException("Invalid ELF dynamic section");
            for (long p=dynamic;p+16<=dynamic+dynamicSize;p+=16) {
                long tag=b.getLong((int)p), value=b.getLong((int)p+8);
                if (tag==0) break;
                if (tag==1) needed.add(value);
                if (tag==5) strings=value;
            }
            long offset=-1;
            for(int i=0;i<count;i++) {
                int p=(int)(ph+(long)i*size); long addr=b.getLong(p+16), len=b.getLong(p+32);
                if (b.getInt(p)==1 && strings>=addr && strings-addr<len) offset=b.getLong(p+8)+strings-addr;
            }
            if(offset<0) throw new IOException("ELF string table missing");
            List<String> result=new ArrayList<>();
            for(long index:needed) {
                long start=offset+index;
                if(index<0 || start<0 || start>=bytes.length) throw new IOException("Invalid ELF dependency");
                int end=(int)start; while(end<bytes.length && bytes[end]!=0 && end-start<512) end++;
                if(end==bytes.length || end-start>=512) throw new IOException("Invalid ELF dependency name");
                result.add(new String(bytes,(int)start,end-(int)start, java.nio.charset.StandardCharsets.UTF_8));
            }
            return result;
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) { throw new IOException("Malformed ELF",e); }
    }
    private ElfLibrary() {}
}
