#!/usr/bin/env python3
"""Exercise production LSFG Java configuration methods without Android or a GPU."""
from pathlib import Path
import subprocess
import tempfile
import sys


def method(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


def main():
    path = (Path(sys.argv[1]) if len(sys.argv) > 1 else
            Path(__file__).resolve().parents[6] /
            'java/com/winlator/cmod/widget/VulkanXServerView.java')
    source = path.read_text()
    methods = '\n'.join(method(source, signature) for signature in [
        'public void setFrameGenerationConfig(',
        'public boolean setFrameGenerationEnabled(',
        'private void applyPendingFrameGeneration('])
    harness = r'''
import java.io.File;
class LiveEnableTest {
    final Object lock = new Object(); long nativeHandle=1;
    boolean pendingFrameGenEnabled, effectiveFrameGenEnabled, supported=true;
    String pendingFrameGenCache; String pendingFrameGenEngine="lsfg";
    void nativeSetFrameGenerationEngine(long handle, int engine) {}
    int pendingFrameGenMultiplier, pendingFrameGenTargetRate, pendingFrameGenFlowScale;
    float pendingFrameGenRefreshRate;
    java.util.concurrent.atomic.AtomicLong sourceFrames = new java.util.concurrent.atomic.AtomicLong();
    int configCalls, notifications; String nativeCache; boolean failNative;
    Object getContext() {return this;}
    boolean nativeIsFrameGenerationSupported(long h) {return supported;}
    boolean nativeConfigureFrameGeneration(long h, boolean enabled, String cache,
             int multiplier, int targetRate, int scale, float refresh) {
        ++configCalls; nativeCache=cache;
        return enabled && !failNative && ("wfg".equals(pendingFrameGenEngine) ||
            cache!=null && new File(cache).isFile());
    }
    void nativeSetFrameGenerationSourceCount(long h,long count) {}
    void notifyFrameGenerationStateChanged() {++notifications;}
    static void check(boolean result,String label) {
        if(!result) throw new AssertionError(label);
    }
''' + methods + r'''
    public static void main(String[] args) throws Exception {
        File cache=File.createTempFile("lsfg-live-enable-", ".cache");
        try {
            LosslessScaling.cache=cache;
            LiveEnableTest view=new LiveEnableTest();
            view.setFrameGenerationConfig(false,null,2,-1,70,120);
            check(LosslessScaling.resolutions==0,"disabled start must not resolve cache");
            check(view.setFrameGenerationEnabled(true),"sidebar enable after disabled start");
            check(view.effectiveFrameGenEnabled && view.nativeCache.equals(cache.getAbsolutePath()),
                  "resolved cache must reach native configure");
            check(LosslessScaling.resolutions==1,"cache must resolve once on first enable");
            check(view.setFrameGenerationEnabled(false),"disable after enable");
            check(!view.effectiveFrameGenEnabled,"disable state");
            check(view.setFrameGenerationEnabled(true),"second enable reuses valid path");
            check(LosslessScaling.resolutions==1,"valid path must not be resolved again");
            view=new LiveEnableTest();
            view.setFrameGenerationConfig(true,cache.getAbsolutePath(),2,-1,70,120);
            check(view.effectiveFrameGenEnabled,"enabled startup must still work");
            view=new LiveEnableTest(); view.pendingFrameGenCache="/missing-lsfg-cache";
            check(view.setFrameGenerationEnabled(true),"stale cache path must be resolved again");
            view=new LiveEnableTest(); LosslessScaling.cache=null;
            check(!view.setFrameGenerationEnabled(true),"missing shaders must reject enable");
            check(!view.pendingFrameGenEnabled && view.configCalls==0,"missing cache must not arm native");
            int resolutions=LosslessScaling.resolutions;
            check(view.setFrameGenerationEnabled(false),"disable with missing cache");
            check(LosslessScaling.resolutions==resolutions,"disable must not resolve cache");
            view=new LiveEnableTest(); view.supported=false; LosslessScaling.cache=cache;
            check(!view.setFrameGenerationEnabled(true) && !view.effectiveFrameGenEnabled,
                  "unsupported driver must remain disabled");
            view=new LiveEnableTest(); view.failNative=true;
            check(!view.setFrameGenerationEnabled(true) && !view.pendingFrameGenEnabled,
                  "native initialization failure must disarm");
            view=new LiveEnableTest(); view.nativeHandle=0;
            check(view.setFrameGenerationEnabled(true) && view.pendingFrameGenEnabled &&
                  !view.effectiveFrameGenEnabled,"enable before surface must remain pending");
            view.nativeHandle=1; view.applyPendingFrameGeneration();
            check(view.effectiveFrameGenEnabled,"surface creation must apply pending enable");
            view=new LiveEnableTest(); view.pendingFrameGenEngine="wfg";
            int beforeWfg=LosslessScaling.resolutions;
            view.setFrameGenerationConfig(true,null,2,-1,50,120);
            check(view.effectiveFrameGenEnabled,"WFG startup without DLL");
            check(view.setFrameGenerationEnabled(false),"WFG disable");
            check(view.setFrameGenerationEnabled(true),"WFG reenable without DLL");
            check(LosslessScaling.resolutions==beforeWfg,"WFG must not resolve LSFG cache");
            view.supported=false;
            check(!view.setFrameGenerationEnabled(true),"WFG unsupported driver fallback");
        } finally {cache.delete();}
        System.out.println("PASS: LSFG off/on, cache resolution, native failure and surface lifecycle");
    }
}
class LosslessScaling {
    static File cache; static int resolutions;
    static File resolveCacheFile(Object context, boolean preferFp16) {
        ++resolutions; return cache;
    }
}

'''
    with tempfile.TemporaryDirectory() as directory:
        java=Path(directory)/'LiveEnableTest.java'
        java.write_text(harness)
        # Java 11+ source-file mode also works when javac is not on PATH.
        subprocess.run(['java',str(java)],check=True,timeout=60)

if __name__=='__main__':
    main()
