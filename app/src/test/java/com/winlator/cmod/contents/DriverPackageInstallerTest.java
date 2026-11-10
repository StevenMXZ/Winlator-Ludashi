package com.winlator.cmod.contents;

import android.content.Context;
import android.net.Uri;
import com.winlator.cmod.core.FileUtils;
import java.io.*;
import java.nio.*;
import java.util.UUID;
import java.util.zip.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DriverPackageInstallerTest {
    private byte[] library(String dependency) {
        ByteBuffer b=ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x464c457f); b.put(4,(byte)2); b.put(5,(byte)1);
        b.putShort(16,(short)3); b.putShort(18,(short)183);
        b.putLong(32,64); b.putShort(54,(short)56); b.putShort(56,(short)2);
        b.putInt(64,1); b.putLong(96,512);
        b.putInt(120,2); b.putLong(128,200); b.putLong(152,48);
        b.putLong(200,1); b.putLong(216,5); b.putLong(224,300);
        b.position(300); b.put((dependency+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return b.array();
    }
    private void entry(ZipOutputStream zip,String name,byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    @Test public void sharedPickerDispatchesFreedrenoToItsOwnComponentCategory() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String name="OpenGL-test-"+UUID.randomUUID();
        File archive=File.createTempFile("freedreno", ".zip",context.getCacheDir());
        File installed=null;
        try {
            JSONObject meta=new JSONObject().put("schemaVersion",1).put("type","freedreno")
                    .put("name",name).put("packageVersion","1").put("driverVersion","Mesa-27.0.0-devel")
                    .put("libraryName","libGL.so.1");
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(archive))) {
                entry(zip,"meta.json",meta.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                entry(zip,"libGL.so.1",library("libgallium-test.so"));
                entry(zip,"libgallium-test.so",library("libc.so"));
            }
            DriverPackageInstaller.Result result=DriverPackageInstaller.install(context,Uri.fromFile(archive));
            assertTrue(result.freedreno);
            OpenGLDriverManager driverManager = new OpenGLDriverManager(context);
            assertEquals("26.3.0", driverManager.versionLabel(OpenGLDriverManager.BUNDLED));
            assertEquals("27.0.0", driverManager.versionLabel(result.id));
            assertEquals("27.0.0", driverManager.versionLabels(result.id).get(result.id));
            installed=new OpenGLDriverManager(context).resolve("freedreno",result.id);
            assertEquals(ContentsManager.getContentTypeDir(context,ContentProfile.ContentType.CONTENT_TYPE_FREEDRENO), installed.getParentFile());
            assertTrue(new File(installed,"libGL.so").isFile());
            JSONObject profile=new JSONObject(FileUtils.readString(new File(installed,"profile.json")));
            assertEquals("Freedreno",profile.getString("type"));
        } finally { archive.delete(); if(installed!=null)FileUtils.delete(installed); }
    }
}
