package com.winlator.cmod.core;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.assertEquals;

public class RuntimeBackendProbeTest {
    @Test public void identifiesUnixlibsAndDllModes() {
        assertEquals(RuntimeBackendProbe.FexMode.UNIXLIB, RuntimeBackendProbe.inspectMaps(Arrays.asList(
                "7abc /container/opt/fex/libarm64ecfex.so")));
        assertEquals(RuntimeBackendProbe.FexMode.DLL, RuntimeBackendProbe.inspectMaps(Arrays.asList(
                "7abc /container/.wine/drive_c/windows/system32/libarm64ecfex.dll")));
    }

    @Test public void unixlibsWinsWhenBothKindsAreMapped() {
        assertEquals(RuntimeBackendProbe.FexMode.UNIXLIB, RuntimeBackendProbe.inspectMaps(Arrays.asList(
                "7abc /libwow64fex.dll", "7def /libwow64fex.so")));
    }

    @Test public void doesNotReportFexForUnrelatedMappings() {
        assertEquals(RuntimeBackendProbe.FexMode.NA, RuntimeBackendProbe.inspectMaps(Arrays.asList(
                "7abc /system/lib64/libvulkan.so", "7def /libwine.so")));
    }
}
