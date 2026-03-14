package com.winlator.cmod.container;

import android.os.Environment;
import com.winlator.cmod.core.DefaultVersion;
import com.winlator.cmod.core.EnvVars;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.KeyValueSet;
import com.winlator.cmod.core.WineInfo;
import com.winlator.cmod.core.WineThemeManager;
import java.io.File;
import java.util.Iterator;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes14.dex */
public class Container {
    public static final String DEFAULT_AUDIO_DRIVER = "alsa";
    public static final String DEFAULT_DXWRAPPER = "dxvk+vkd3d";
    public static final String DEFAULT_EMULATOR = "FEXCore";
    public static final String DEFAULT_ENV_VARS = "WRAPPER_MAX_IMAGE_COUNT=0 VKD3D_SHADER_MODEL=6_6 ZINK_DESCRIPTORS=lazy ZINK_DEBUG=compact MESA_SHADER_CACHE_DISABLE=false MESA_SHADER_CACHE_MAX_SIZE=512MB mesa_glthread=true WINEESYNC=1 TU_DEBUG=noconform,sysmem DXVK_HUD=0";
    public static final String DEFAULT_GRAPHICSDRIVERCONFIG = "vulkanVersion=1.3;version=;blacklistedExtensions=;maxDeviceMemory=0;presentMode=mailbox;syncFrame=0;disablePresentWait=0;resourceType=auto;bcnEmulation=auto;bcnEmulationType=compute;bcnEmulationCache=0;gpuName=Device";
    public static final String DEFAULT_GRAPHICS_DRIVER = "wrapper";
    public static final String DEFAULT_SCREEN_SIZE = "1280x720";
    public static final String DEFAULT_WINCOMPONENTS = "direct3d=1,directsound=0,directmusic=0,directshow=0,directplay=0,xaudio=0,vcrun2010=1";
    public static final String FALLBACK_WINCOMPONENTS = "direct3d=1,directsound=1,directmusic=1,directshow=1,directplay=1,xaudio=1,vcrun2010=1";
    public static final byte MAX_DRIVE_LETTERS = 26;
    public static final byte STARTUP_SELECTION_AGGRESSIVE = 2;
    public static final byte STARTUP_SELECTION_ESSENTIAL = 1;
    public static final byte STARTUP_SELECTION_NORMAL = 0;
    private String audioDriver;
    private String box64Preset;
    private String box64Version;
    private ContainerManager containerManager;
    private String controllerMapping;
    private String cpuList;
    private String cpuListWoW64;
    private String desktopTheme;
    private String drives;
    private String dxwrapper;
    private String dxwrapperConfig;
    private String emulator;
    private String envVars;
    private boolean exclusiveXInput;
    private JSONObject extraData;
    private String fexcorePreset;
    private String fexcoreVersion;
    private boolean fullscreenStretched;
    private String graphicsDriver;
    private String graphicsDriverConfig;
    public final int id;
    private int inputType;
    private String lc_all;
    private String midiSoundFont;
    private String name;
    private int primaryController;
    private File rootDir;
    private String screenSize;
    private boolean showFPS;
    private byte startupSelection;
    private String wincomponents;
    private String wineVersion;
    public static final String DEFAULT_DDRAWRAPPER = "none";
    public static final String DEFAULT_DXWRAPPERCONFIG = "version=" + DefaultVersion.DXVK + ",framerate=0,async=0,asyncCache=0,vkd3dVersion=" + DefaultVersion.VKD3D + ",vkd3dLevel=12_1,ddrawrapper=" + DEFAULT_DDRAWRAPPER + ",csmt=3,gpuName=NVIDIA GeForce GTX 480,videoMemorySize=2048,strict_shader_math=1,OffscreenRenderingMode=fbo,renderer=gl";
    public static final String DEFAULT_DRIVES = "F:" + Environment.getExternalStorageDirectory().getAbsolutePath() + "D:" + Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

    public enum XrControllerMapping {
        BUTTON_A,
        BUTTON_B,
        BUTTON_X,
        BUTTON_Y,
        BUTTON_GRIP,
        BUTTON_TRIGGER,
        THUMBSTICK_UP,
        THUMBSTICK_DOWN,
        THUMBSTICK_LEFT,
        THUMBSTICK_RIGHT
    }

    public Container(int id) {
        this.screenSize = DEFAULT_SCREEN_SIZE;
        this.envVars = DEFAULT_ENV_VARS;
        this.graphicsDriver = DEFAULT_GRAPHICS_DRIVER;
        this.graphicsDriverConfig = DEFAULT_GRAPHICSDRIVERCONFIG;
        this.dxwrapper = DEFAULT_DXWRAPPER;
        this.dxwrapperConfig = "";
        this.wincomponents = DEFAULT_WINCOMPONENTS;
        this.audioDriver = DEFAULT_AUDIO_DRIVER;
        this.drives = DEFAULT_DRIVES;
        this.wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        this.startupSelection = (byte) 1;
        this.desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
        this.fexcorePreset = "INTERMEDIATE";
        this.box64Preset = "COMPATIBILITY";
        this.midiSoundFont = "";
        this.inputType = 4;
        this.lc_all = "";
        this.primaryController = 1;
        this.controllerMapping = new String(new char[XrControllerMapping.values().length]);
        this.exclusiveXInput = true;
        this.id = id;
        this.name = "Container-" + id;
    }

    public Container(int id, ContainerManager containerManager) {
        this.screenSize = DEFAULT_SCREEN_SIZE;
        this.envVars = DEFAULT_ENV_VARS;
        this.graphicsDriver = DEFAULT_GRAPHICS_DRIVER;
        this.graphicsDriverConfig = DEFAULT_GRAPHICSDRIVERCONFIG;
        this.dxwrapper = DEFAULT_DXWRAPPER;
        this.dxwrapperConfig = "";
        this.wincomponents = DEFAULT_WINCOMPONENTS;
        this.audioDriver = DEFAULT_AUDIO_DRIVER;
        this.drives = DEFAULT_DRIVES;
        this.wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        this.startupSelection = (byte) 1;
        this.desktopTheme = WineThemeManager.DEFAULT_DESKTOP_THEME;
        this.fexcorePreset = "INTERMEDIATE";
        this.box64Preset = "COMPATIBILITY";
        this.midiSoundFont = "";
        this.inputType = 4;
        this.lc_all = "";
        this.primaryController = 1;
        this.controllerMapping = new String(new char[XrControllerMapping.values().length]);
        this.exclusiveXInput = true;
        this.id = id;
        this.name = "Container-" + id;
        this.containerManager = containerManager;
    }

    public ContainerManager getManager() {
        return this.containerManager;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getScreenSize() {
        return this.screenSize;
    }

    public void setScreenSize(String screenSize) {
        this.screenSize = screenSize;
    }

    public String getEnvVars() {
        return this.envVars;
    }

    public void setEnvVars(String envVars) {
        this.envVars = envVars != null ? envVars : "";
    }

    public String getGraphicsDriver() {
        return this.graphicsDriver;
    }

    public void setGraphicsDriver(String graphicsDriver) {
        this.graphicsDriver = graphicsDriver;
    }

    public String getGraphicsDriverConfig() {
        return this.graphicsDriverConfig;
    }

    public void setGraphicsDriverConfig(String graphicsDriverConfig) {
        this.graphicsDriverConfig = graphicsDriverConfig;
    }

    public String getDXWrapper() {
        return this.dxwrapper;
    }

    public void setDXWrapper(String dxwrapper) {
        this.dxwrapper = dxwrapper;
    }

    public String getDXWrapperConfig() {
        return this.dxwrapperConfig;
    }

    public void setDXWrapperConfig(String dxwrapperConfig) {
        this.dxwrapperConfig = dxwrapperConfig != null ? dxwrapperConfig : "";
    }

    public String getAudioDriver() {
        return this.audioDriver;
    }

    public void setAudioDriver(String audioDriver) {
        this.audioDriver = audioDriver;
    }

    public String getWinComponents() {
        return this.wincomponents;
    }

    public void setWinComponents(String wincomponents) {
        this.wincomponents = wincomponents;
    }

    public String getDrives() {
        return this.drives;
    }

    public void setDrives(String drives) {
        this.drives = drives;
    }

    public String getLC_ALL() {
        return this.lc_all;
    }

    public void setLC_ALL(String lc_all) {
        this.lc_all = lc_all;
    }

    public int getPrimaryController() {
        return this.primaryController;
    }

    public void setPrimaryController(int primaryController) {
        this.primaryController = primaryController;
    }

    public byte getControllerMapping(XrControllerMapping input) {
        return (byte) this.controllerMapping.charAt(input.ordinal());
    }

    public void setControllerMapping(String controllerMapping) {
        this.controllerMapping = controllerMapping;
    }

    public boolean isFullscreenStretched() {
        return this.fullscreenStretched;
    }

    public boolean isShowFPS() {
        return this.showFPS;
    }

    public void setFullscreenStretched(boolean fullscreenStretched) {
        this.fullscreenStretched = fullscreenStretched;
    }

    public void setShowFPS(boolean showFPS) {
        this.showFPS = showFPS;
    }

    public byte getStartupSelection() {
        return this.startupSelection;
    }

    public void setStartupSelection(byte startupSelection) {
        this.startupSelection = startupSelection;
    }

    public String getCPUList() {
        return getCPUList(false);
    }

    public String getCPUList(boolean allowFallback) {
        if (this.cpuList != null) {
            return this.cpuList;
        }
        if (allowFallback) {
            return getFallbackCPUList();
        }
        return null;
    }

    public void setCPUList(String cpuList) {
        this.cpuList = (cpuList == null || cpuList.isEmpty()) ? null : cpuList;
    }

    public String getCPUListWoW64() {
        return getCPUListWoW64(false);
    }

    public String getCPUListWoW64(boolean allowFallback) {
        if (this.cpuListWoW64 != null) {
            return this.cpuListWoW64;
        }
        if (allowFallback) {
            return getFallbackCPUListWoW64();
        }
        return null;
    }

    public void setCPUListWoW64(String cpuListWoW64) {
        this.cpuListWoW64 = (cpuListWoW64 == null || cpuListWoW64.isEmpty()) ? null : cpuListWoW64;
    }

    public void setFEXCoreVersion(String version) {
        this.fexcoreVersion = version;
    }

    public String getFEXCoreVersion() {
        return this.fexcoreVersion;
    }

    public void setFEXCorePreset(String preset) {
        this.fexcorePreset = preset;
    }

    public String getFEXCorePreset() {
        return this.fexcorePreset;
    }

    public String getBox64Preset() {
        return this.box64Preset;
    }

    public void setBox64Preset(String box64Preset) {
        this.box64Preset = box64Preset;
    }

    public String getBox64Version() {
        return this.box64Version;
    }

    public void setBox64Version(String version) {
        this.box64Version = version;
    }

    public void setEmulator(String emulator) {
        this.emulator = emulator;
    }

    public String getEmulator() {
        return this.emulator;
    }

    public File getRootDir() {
        return this.rootDir;
    }

    public void setRootDir(File rootDir) {
        this.rootDir = rootDir;
    }

    public void setExtraData(JSONObject extraData) {
        this.extraData = extraData;
    }

    public String getExtra(String name) {
        return getExtra(name, "");
    }

    public String getExtra(String name, String fallback) {
        try {
            return (this.extraData == null || !this.extraData.has(name)) ? fallback : this.extraData.getString(name);
        } catch (JSONException e) {
            return fallback;
        }
    }

    public void putExtra(String name, Object value) {
        if (this.extraData == null) {
            this.extraData = new JSONObject();
        }
        try {
            if (value != null) {
                this.extraData.put(name, value);
            } else {
                this.extraData.remove(name);
            }
        } catch (JSONException e) {
        }
    }

    public String getWineVersion() {
        return this.wineVersion;
    }

    public void setWineVersion(String wineVersion) {
        this.wineVersion = wineVersion;
    }

    public File getConfigFile() {
        return new File(this.rootDir, ".container");
    }

    public File getDesktopDir() {
        return new File(this.rootDir, ".wine/drive_c/users/xuser/Desktop/");
    }

    public File getStartMenuDir() {
        return new File(this.rootDir, ".wine/drive_c/ProgramData/Microsoft/Windows/Start Menu/");
    }

    public File getIconsDir(int size) {
        return new File(this.rootDir, ".local/share/icons/hicolor/" + size + "x" + size + "/apps/");
    }

    public String getDesktopTheme() {
        return this.desktopTheme;
    }

    public void setDesktopTheme(String desktopTheme) {
        this.desktopTheme = desktopTheme;
    }

    public String getMIDISoundFont() {
        return this.midiSoundFont;
    }

    public void setMidiSoundFont(String fileName) {
        this.midiSoundFont = fileName;
    }

    public int getInputType() {
        return this.inputType;
    }

    public void setInputType(int inputType) {
        this.inputType = inputType;
    }

    public boolean isExclusiveXInput() {
        return this.exclusiveXInput;
    }

    public void setExclusiveXInput(boolean exclusiveXInput) {
        this.exclusiveXInput = exclusiveXInput;
    }

    public Iterable<String[]> drivesIterator() {
        return drivesIterator(this.drives);
    }

    public static Iterable<String[]> drivesIterator(final String drives) {
        final int[] index = {drives.indexOf(":")};
        final String[] item = new String[2];
        return new Iterable() { // from class: com.winlator.cmod.container.Container$$ExternalSyntheticLambda0
            @Override // java.lang.Iterable
            public final Iterator iterator() {
                return Container.lambda$drivesIterator$0(index, item, drives);
            }
        };
    }

    static /* synthetic */ Iterator lambda$drivesIterator$0(final int[] index, final String[] item, final String drives) {
        return new Iterator<String[]>() { // from class: com.winlator.cmod.container.Container.1
            @Override // java.util.Iterator
            public boolean hasNext() {
                return index[0] != -1;
            }

            @Override // java.util.Iterator
            public String[] next() {
                item[0] = String.valueOf(drives.charAt(index[0] - 1));
                int nextIndex = drives.indexOf(":", index[0] + 1);
                item[1] = drives.substring(index[0] + 1, nextIndex != -1 ? nextIndex - 1 : drives.length());
                index[0] = nextIndex;
                return item;
            }
        };
    }

    public void saveData() {
        try {
            JSONObject data = new JSONObject();
            data.put("id", this.id);
            data.put("name", this.name);
            data.put("screenSize", this.screenSize);
            data.put("envVars", this.envVars);
            data.put("cpuList", this.cpuList);
            data.put("cpuListWoW64", this.cpuListWoW64);
            data.put("graphicsDriver", this.graphicsDriver);
            data.put("graphicsDriverConfig", this.graphicsDriverConfig);
            data.put("emulator", this.emulator);
            data.put("dxwrapper", this.dxwrapper);
            if (!this.dxwrapperConfig.isEmpty()) {
                data.put("dxwrapperConfig", this.dxwrapperConfig);
            }
            data.put("audioDriver", this.audioDriver);
            data.put("wincomponents", this.wincomponents);
            data.put("drives", this.drives);
            data.put("showFPS", this.showFPS);
            data.put("fullscreenStretched", this.fullscreenStretched);
            data.put("inputType", this.inputType);
            data.put("startupSelection", (int) this.startupSelection);
            data.put("box64Version", this.box64Version);
            data.put("fexcorePreset", this.fexcorePreset);
            data.put("fexcoreVersion", this.fexcoreVersion);
            data.put("box64Preset", this.box64Preset);
            data.put("desktopTheme", this.desktopTheme);
            data.put("extraData", this.extraData);
            data.put("midiSoundFont", this.midiSoundFont);
            data.put("lc_all", this.lc_all);
            data.put("primaryController", this.primaryController);
            data.put("controllerMapping", this.controllerMapping);
            data.put("exclusiveXInput", this.exclusiveXInput);
            if (!WineInfo.isMainWineVersion(this.wineVersion)) {
                data.put("wineVersion", this.wineVersion);
            }
            FileUtils.writeString(getConfigFile(), data.toString());
        } catch (JSONException e) {
        }
    }

    /* JADX WARN: Can't fix incorrect switch cases order, some code will duplicate */
    public void loadData(JSONObject data) throws JSONException {
        char c;
        this.wineVersion = WineInfo.MAIN_WINE_VERSION.identifier();
        this.dxwrapperConfig = "";
        checkObsoleteOrMissingProperties(data);
        Iterator<String> it = data.keys();
        while (it.hasNext()) {
            String key = it.next();
            switch (key.hashCode()) {
                case -1923271874:
                    if (key.equals("primaryController")) {
                        c = 26;
                        break;
                    }
                    c = 65535;
                    break;
                case -1847945073:
                    if (key.equals("wineVersion")) {
                        c = 17;
                        break;
                    }
                    c = 65535;
                    break;
                case -1663247725:
                    if (key.equals("graphicsDriver")) {
                        c = 5;
                        break;
                    }
                    c = 65535;
                    break;
                case -1590567751:
                    if (key.equals("envVars")) {
                        c = 2;
                        break;
                    }
                    c = 65535;
                    break;
                case -1565142520:
                    if (key.equals("box64Preset")) {
                        c = 21;
                        break;
                    }
                    c = 65535;
                    break;
                case -1373868288:
                    if (key.equals("fexcoreVersion")) {
                        c = 19;
                        break;
                    }
                    c = 65535;
                    break;
                case -1323526103:
                    if (key.equals("drives")) {
                        c = 11;
                        break;
                    }
                    c = 65535;
                    break;
                case -1225243246:
                    if (key.equals("controllerMapping")) {
                        c = 27;
                        break;
                    }
                    c = 65535;
                    break;
                case -1108663591:
                    if (key.equals("lc_all")) {
                        c = 25;
                        break;
                    }
                    c = 65535;
                    break;
                case -1011044752:
                    if (key.equals("exclusiveXInput")) {
                        c = 28;
                        break;
                    }
                    c = 65535;
                    break;
                case -604892465:
                    if (key.equals("box64Version")) {
                        c = 18;
                        break;
                    }
                    c = 65535;
                    break;
                case -430035982:
                    if (key.equals("wincomponents")) {
                        c = '\b';
                        break;
                    }
                    c = 65535;
                    break;
                case -417399155:
                    if (key.equals("screenSize")) {
                        c = 1;
                        break;
                    }
                    c = 65535;
                    break;
                case -253792294:
                    if (key.equals("extraData")) {
                        c = 16;
                        break;
                    }
                    c = 65535;
                    break;
                case -245711689:
                    if (key.equals("cpuListWoW64")) {
                        c = 4;
                        break;
                    }
                    c = 65535;
                    break;
                case -77469313:
                    if (key.equals("dxwrapper")) {
                        c = '\t';
                        break;
                    }
                    c = 65535;
                    break;
                case 3373707:
                    if (key.equals("name")) {
                        c = 0;
                        break;
                    }
                    c = 65535;
                    break;
                case 10774430:
                    if (key.equals("audioDriver")) {
                        c = 22;
                        break;
                    }
                    c = 65535;
                    break;
                case 184151273:
                    if (key.equals("fullscreenStretched")) {
                        c = '\r';
                        break;
                    }
                    c = 65535;
                    break;
                case 478092253:
                    if (key.equals("midiSoundFont")) {
                        c = 24;
                        break;
                    }
                    c = 65535;
                    break;
                case 985436774:
                    if (key.equals("cpuList")) {
                        c = 3;
                        break;
                    }
                    c = 65535;
                    break;
                case 990029581:
                    if (key.equals("desktopTheme")) {
                        c = 23;
                        break;
                    }
                    c = 65535;
                    break;
                case 1319545783:
                    if (key.equals("fexcorePreset")) {
                        c = 20;
                        break;
                    }
                    c = 65535;
                    break;
                case 1336193813:
                    if (key.equals("emulator")) {
                        c = 7;
                        break;
                    }
                    c = 65535;
                    break;
                case 1369820047:
                    if (key.equals("startupSelection")) {
                        c = 15;
                        break;
                    }
                    c = 65535;
                    break;
                case 1706976804:
                    if (key.equals("inputType")) {
                        c = 14;
                        break;
                    }
                    c = 65535;
                    break;
                case 1865011125:
                    if (key.equals("graphicsDriverConfig")) {
                        c = 6;
                        break;
                    }
                    c = 65535;
                    break;
                case 1902837153:
                    if (key.equals("dxwrapperConfig")) {
                        c = '\n';
                        break;
                    }
                    c = 65535;
                    break;
                case 2067265708:
                    if (key.equals("showFPS")) {
                        c = '\f';
                        break;
                    }
                    c = 65535;
                    break;
                default:
                    c = 65535;
                    break;
            }
            switch (c) {
                case 0:
                    setName(data.getString(key));
                    break;
                case 1:
                    setScreenSize(data.getString(key));
                    break;
                case 2:
                    setEnvVars(data.getString(key));
                    break;
                case 3:
                    setCPUList(data.getString(key));
                    break;
                case 4:
                    setCPUListWoW64(data.getString(key));
                    break;
                case 5:
                    setGraphicsDriver(data.getString(key));
                    break;
                case 6:
                    setGraphicsDriverConfig(data.getString(key));
                    break;
                case 7:
                    setEmulator(data.getString(key));
                    break;
                case '\b':
                    setWinComponents(data.getString(key));
                    break;
                case '\t':
                    setDXWrapper(data.getString(key));
                    break;
                case '\n':
                    setDXWrapperConfig(data.getString(key));
                    break;
                case 11:
                    setDrives(data.getString(key));
                    break;
                case '\f':
                    setShowFPS(data.getBoolean(key));
                    break;
                case '\r':
                    setFullscreenStretched(data.getBoolean(key));
                    break;
                case 14:
                    setInputType(data.getInt(key));
                    break;
                case 15:
                    setStartupSelection((byte) data.getInt(key));
                    break;
                case 16:
                    JSONObject extraData = data.getJSONObject(key);
                    checkObsoleteOrMissingProperties(extraData);
                    setExtraData(extraData);
                    break;
                case 17:
                    setWineVersion(data.getString(key));
                    break;
                case 18:
                    setBox64Version(data.getString(key));
                    break;
                case 19:
                    setFEXCoreVersion(data.getString(key));
                    break;
                case 20:
                    setFEXCorePreset(data.getString(key));
                    break;
                case 21:
                    setBox64Preset(data.getString(key));
                    break;
                case 22:
                    setAudioDriver(data.getString(key));
                    break;
                case 23:
                    setDesktopTheme(data.getString(key));
                    break;
                case 24:
                    setMidiSoundFont(data.getString(key));
                    break;
                case 25:
                    setLC_ALL(data.getString(key));
                    break;
                case 26:
                    setPrimaryController(data.getInt(key));
                    break;
                case 27:
                    this.controllerMapping = data.getString(key);
                    break;
                case 28:
                    setExclusiveXInput(data.getBoolean(key));
                    break;
            }
        }
    }

    public static void checkObsoleteOrMissingProperties(JSONObject data) {
        try {
            if (data.has("dxcomponents")) {
                data.put("wincomponents", data.getString("dxcomponents"));
                data.remove("dxcomponents");
            }
            if (data.has("dxwrapper")) {
                String dxwrapper = data.getString("dxwrapper");
                if (dxwrapper.equals("original-wined3d")) {
                    data.put("dxwrapper", DEFAULT_DXWRAPPER);
                } else if (dxwrapper.startsWith("d8vk-") || dxwrapper.startsWith("dxvk-")) {
                    data.put("dxwrapper", dxwrapper);
                }
            }
            if (data.has("graphicsDriver")) {
                String graphicsDriver = data.getString("graphicsDriver");
                if (!graphicsDriver.equals("turnip-zink") && !graphicsDriver.equals("turnip")) {
                    if (graphicsDriver.equals("llvmpipe")) {
                        data.put("graphicsDriver", DEFAULT_GRAPHICS_DRIVER);
                    }
                }
                data.put("graphicsDriver", DEFAULT_GRAPHICS_DRIVER);
            }
            if (data.has("envVars") && data.has("extraData")) {
                JSONObject extraData = data.getJSONObject("extraData");
                int appVersion = Integer.parseInt(extraData.optString("appVersion", "0"));
                if (appVersion < 16) {
                    EnvVars defaultEnvVars = new EnvVars(DEFAULT_ENV_VARS);
                    EnvVars envVars = new EnvVars(data.getString("envVars"));
                    Iterator<String> it = defaultEnvVars.iterator();
                    while (it.hasNext()) {
                        String name = it.next();
                        if (!envVars.has(name)) {
                            envVars.put(name, defaultEnvVars.get(name));
                        }
                    }
                    data.put("envVars", envVars.toString());
                }
            }
            KeyValueSet wincomponents1 = new KeyValueSet(DEFAULT_WINCOMPONENTS);
            KeyValueSet wincomponents2 = new KeyValueSet(data.getString("wincomponents"));
            String result = "";
            Iterator<String[]> it2 = wincomponents1.iterator();
            while (it2.hasNext()) {
                String[] wincomponent1 = it2.next();
                String value = wincomponent1[1];
                Iterator<String[]> it3 = wincomponents2.iterator();
                while (true) {
                    if (!it3.hasNext()) {
                        break;
                    }
                    String[] wincomponent2 = it3.next();
                    if (wincomponent1[0].equals(wincomponent2[0])) {
                        value = wincomponent2[1];
                        break;
                    }
                }
                result = result + (!result.isEmpty() ? "," : "") + wincomponent1[0] + "=" + value;
            }
            data.put("wincomponents", result);
        } catch (JSONException e) {
        }
    }

    public static String getFallbackCPUList() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = 0; i < numProcessors; i++) {
            cpuList = cpuList + (!cpuList.isEmpty() ? "," : "") + i;
        }
        return cpuList;
    }

    public static String getFallbackCPUListWoW64() {
        String cpuList = "";
        int numProcessors = Runtime.getRuntime().availableProcessors();
        for (int i = numProcessors / 2; i < numProcessors; i++) {
            cpuList = cpuList + (!cpuList.isEmpty() ? "," : "") + i;
        }
        return cpuList;
    }

    public boolean hasEnvVar(String keyValue) {
        if (this.envVars == null || this.envVars.isEmpty()) {
            return false;
        }
        String[] vars = this.envVars.split(",");
        for (String var : vars) {
            if (var.trim().equalsIgnoreCase(keyValue.trim())) {
                return true;
            }
        }
        return false;
    }
}
