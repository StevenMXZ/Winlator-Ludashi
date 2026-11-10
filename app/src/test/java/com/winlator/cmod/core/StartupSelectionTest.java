package com.winlator.cmod.core;

import com.winlator.cmod.container.Container;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = com.winlator.cmod.container.AudioDriverMigrationTest.GpuShadow.class)
public class StartupSelectionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String[] SETS = {"CurrentControlSet", "ControlSet001", "ControlSet002"};

    private Container container() throws Exception {
        Container container = new Container(1);
        container.setRootDir(temporary.newFolder());
        File prefix = new File(container.getRootDir(), ".wine");
        assertTrue(prefix.mkdir());
        writeRegistry(container, 2);
        return container;
    }

    private File registry(Container container) {
        return new File(container.getRootDir(), ".wine/system.reg");
    }

    private void writeRegistry(Container container, int eventlogStart) throws Exception {
        StringBuilder text = new StringBuilder("WINE REGISTRY Version 2\n\n");
        for (String set : SETS) {
            for (String service : new String[]{"Eventlog", "PlugPlay", "UnrelatedService"}) {
                text.append("[System\\\\").append(set).append("\\\\Services\\\\")
                    .append(service).append("]\n\"Start\"=dword:")
                    .append(String.format("%08x", service.equals("Eventlog") ? eventlogStart : 3))
                    .append("\n\n");
            }
        }
        Files.write(registry(container).toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void assertStart(Container container, String service, int expected) {
        try (WineRegistryEditor editor = new WineRegistryEditor(registry(container))) {
            for (String set : SETS)
                assertEquals(Integer.valueOf(expected), editor.getDwordValue(
                    "System\\" + set + "\\Services\\" + service, "Start", -1));
        }
    }

    @Test public void cachedAggressiveSelectionRepairsDriftedRegistry() throws Exception {
        Container container = container();
        container.putExtra("startupSelection", "2");
        assertFalse(WineUtils.applyStartupSelection(container, "2"));
        assertStart(container, "Eventlog", 4);
        assertStart(container, "PlugPlay", 2);
        assertStart(container, "UnrelatedService", 3);
    }

    @Test public void regeneratedPrefixIsReconciledWithoutTogglingSelection() throws Exception {
        Container container = container();
        assertTrue(WineUtils.applyStartupSelection(container, "2"));
        writeRegistry(container, 2);
        assertFalse(WineUtils.applyStartupSelection(container, "2"));
        assertStart(container, "Eventlog", 4);
    }

    @Test public void effectiveShortcutSelectionCanSwitchBackToContainerSelection() throws Exception {
        Container container = container();
        container.setStartupSelection(Container.STARTUP_SELECTION_AGGRESSIVE);
        WineUtils.applyStartupSelection(container, "2");
        WineUtils.applyStartupSelection(container, "0");
        assertStart(container, "Eventlog", 2);
        assertEquals(Container.STARTUP_SELECTION_AGGRESSIVE, container.getStartupSelection());
        WineUtils.applyStartupSelection(container, String.valueOf(container.getStartupSelection()));
        assertStart(container, "Eventlog", 4);
    }

    @Test public void matchingRegistryIsNotRewrittenAndAbsentServicesStayAbsent() throws Exception {
        Container container = container();
        WineUtils.applyStartupSelection(container, "2");
        File file = registry(container);
        assertTrue(file.setLastModified(1000000000000L));
        long timestamp = file.lastModified();
        WineUtils.applyStartupSelection(container, "2");
        assertEquals(timestamp, file.lastModified());
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertFalse(text.contains("Services\\\\BITS"));
    }
}
