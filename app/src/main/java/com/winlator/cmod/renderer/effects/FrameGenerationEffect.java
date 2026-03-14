package com.winlator.cmod.renderer.effects;

import android.opengl.GLES20;
import com.winlator.cmod.renderer.material.ScreenMaterial;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import org.apache.commons.compress.archivers.tar.TarConstants;

/* loaded from: classes9.dex */
public class FrameGenerationEffect extends Effect {
    public static final int FPS_120 = 120;
    public static final int FPS_15 = 15;
    public static final int FPS_20 = 20;
    public static final int FPS_25 = 25;
    public static final int FPS_30 = 30;
    public static final int FPS_45 = 45;
    public static final int FPS_60 = 60;
    public static final int FPS_90 = 90;
    public static final int FPS_AUTO = 0;
    public static final int MODE_FRAME_GEN = 1;
    public static final int MODE_NATIVE_PLUS = 0;
    private static final long NANOS_PER_SECOND = 1000000000;
    private boolean autoDetectFPS;
    private int targetFPS;
    private int currentMode = 1;
    private long lastRealFrameTimeNs = 0;
    private long currentRealFrameIntervalNs = 33333333;
    private long currentTargetFrameIntervalNs = 16666666;
    private boolean isEnabled = false;
    private int[] texturePool = new int[3];
    private boolean texturesInitialized = false;
    private int poolIndex = 0;
    private int texturePrev = -1;
    private int textureCurr = -1;
    private boolean hasFirstFrame = false;
    private boolean hasSecondFrame = false;
    private boolean waitingForSecondFrame = true;
    private int displayRefreshRate = 60;
    private int realFrameDisplayCount = 1;
    private int generatedFrameDisplayCount = 1;
    private int currentDisplayFrameType = 0;
    private int currentFrameDisplayCount = 0;
    private int currentSequence = 0;
    private int nextCycleFrameId = -1;
    private boolean hasCapturedFrame = false;
    private boolean skipFirstRealDisplay = false;
    private int currentWidth = 0;
    private int currentHeight = 0;
    public int uIsEnabledLoc = -1;
    private int uBlendFactorLoc = -1;
    private int uTexturePrevLoc = -1;
    private int uTextureCurrLoc = -1;
    private int uResolutionLoc = -1;

    public FrameGenerationEffect() {
        this.targetFPS = 0;
        this.autoDetectFPS = true;
        this.targetFPS = 0;
        this.autoDetectFPS = true;
        updateFrameIntervals();
        calculateDisplayCounts();
    }

    public long getCurrentRealFrameInterval() {
        return this.currentRealFrameIntervalNs;
    }

    public long getCurrentTargetFrameInterval() {
        return this.currentTargetFrameIntervalNs;
    }

    public int getCurrentMode() {
        return this.currentMode;
    }

    public int getTargetFPS() {
        return this.targetFPS;
    }

    public boolean isAutoDetectFPS() {
        return this.autoDetectFPS;
    }

    public boolean isEnabled() {
        return this.isEnabled;
    }

    @Override // com.winlator.cmod.renderer.effects.Effect
    protected ShaderMaterial createMaterial() {
        return new MotionAwareMaterial();
    }

    public void setGenerationMode(int mode) {
        if (this.currentMode != mode) {
            this.currentMode = mode;
            cleanup();
            resetState();
        }
    }

    public void toggleGeneration() {
        this.isEnabled = !this.isEnabled;
        if (this.isEnabled) {
            resetState();
            long now = System.nanoTime();
            this.lastRealFrameTimeNs = now;
        }
    }

    public synchronized void updateFPS(int fps) {
        if (fps < 5) {
            return;
        }
        long newInterval = NANOS_PER_SECOND / fps;
        if (Math.abs(newInterval - this.currentRealFrameIntervalNs) > 2000000) {
            this.currentRealFrameIntervalNs = newInterval;
            this.currentTargetFrameIntervalNs = this.currentRealFrameIntervalNs / 2;
            calculateDisplayCounts();
        }
    }

    public void setDisplayRefreshRate(int refreshRate) {
        if (refreshRate > 0 && refreshRate != this.displayRefreshRate) {
            this.displayRefreshRate = refreshRate;
            calculateDisplayCounts();
        }
    }

    public void setTargetFPS(int fps) {
        if (fps == 0) {
            this.autoDetectFPS = true;
        } else {
            this.autoDetectFPS = false;
            this.targetFPS = fps;
        }
        updateFrameIntervals();
        resetState();
    }

    private void updateFrameIntervals() {
        if (!this.autoDetectFPS && this.targetFPS > 0) {
            this.currentRealFrameIntervalNs = NANOS_PER_SECOND / this.targetFPS;
            this.currentTargetFrameIntervalNs = this.currentRealFrameIntervalNs / 2;
            calculateDisplayCounts();
        }
    }

    private void calculateDisplayCounts() {
        this.realFrameDisplayCount = 1;
        this.generatedFrameDisplayCount = 1;
    }

    private void ensureTextures(int width, int height) {
        if (!this.texturesInitialized || this.currentWidth != width || this.currentHeight != height) {
            if (this.texturesInitialized) {
                GLES20.glDeleteTextures(3, this.texturePool, 0);
            }
            GLES20.glGenTextures(3, this.texturePool, 0);
            for (int i = 0; i < 3; i++) {
                GLES20.glBindTexture(3553, this.texturePool[i]);
                GLES20.glTexImage2D(3553, 0, 6408, width, height, 0, 6408, 5121, null);
                GLES20.glTexParameteri(3553, 10241, 9729);
                GLES20.glTexParameteri(3553, TarConstants.DEFAULT_BLKSIZE, 9729);
                GLES20.glTexParameteri(3553, 10242, 33071);
                GLES20.glTexParameteri(3553, 10243, 33071);
            }
            this.texturesInitialized = true;
            this.poolIndex = 0;
            this.currentWidth = width;
            this.currentHeight = height;
            resetState();
        }
    }

    public void prepareFrame(int width, int height, int sequence) {
        this.currentSequence = sequence;
        if (this.isEnabled) {
            ensureTextures(width, height);
            if (sequence == 0) {
                this.lastRealFrameTimeNs = System.nanoTime();
                int targetTexId = this.texturePool[this.poolIndex];
                this.poolIndex = (this.poolIndex + 1) % 3;
                GLES20.glBindTexture(3553, targetTexId);
                GLES20.glCopyTexSubImage2D(3553, 0, 0, 0, 0, 0, width, height);
                GLES20.glBindTexture(3553, 0);
                if (!this.hasFirstFrame) {
                    this.textureCurr = targetTexId;
                    this.hasFirstFrame = true;
                    this.waitingForSecondFrame = true;
                } else {
                    if (this.waitingForSecondFrame) {
                        this.texturePrev = this.textureCurr;
                        this.textureCurr = targetTexId;
                        this.hasSecondFrame = true;
                        this.waitingForSecondFrame = false;
                        return;
                    }
                    this.nextCycleFrameId = targetTexId;
                    this.hasCapturedFrame = true;
                    this.skipFirstRealDisplay = true;
                }
            }
        }
    }

    public int getFrameToDisplay() {
        if (!this.isEnabled) {
            return 0;
        }
        int requiredCount = this.currentDisplayFrameType == 0 ? this.realFrameDisplayCount : this.generatedFrameDisplayCount;
        if (this.currentFrameDisplayCount < requiredCount) {
            this.currentFrameDisplayCount++;
            if (this.currentDisplayFrameType == 0 && this.currentFrameDisplayCount == 1 && this.skipFirstRealDisplay) {
                this.skipFirstRealDisplay = false;
                this.currentDisplayFrameType = 1;
                this.currentFrameDisplayCount = 0;
                return getFrameToDisplay();
            }
            return this.currentDisplayFrameType;
        }
        this.currentFrameDisplayCount = 1;
        if (this.currentDisplayFrameType == 0) {
            this.currentDisplayFrameType = 1;
        } else {
            this.currentDisplayFrameType = 0;
            if (this.hasCapturedFrame && this.nextCycleFrameId != -1) {
                this.texturePrev = this.textureCurr;
                this.textureCurr = this.nextCycleFrameId;
                this.nextCycleFrameId = -1;
                this.hasCapturedFrame = false;
            }
        }
        return this.currentDisplayFrameType;
    }

    public void setupShaderUniforms() {
        ShaderMaterial material = getMaterial();
        if (material == null || material.programId == 0) {
            return;
        }
        int program = material.programId;
        if (this.uIsEnabledLoc == -1) {
            this.uIsEnabledLoc = GLES20.glGetUniformLocation(program, "uIsEnabled");
            this.uBlendFactorLoc = GLES20.glGetUniformLocation(program, "uBlendFactor");
            this.uTexturePrevLoc = GLES20.glGetUniformLocation(program, "uTexturePrev");
            this.uTextureCurrLoc = GLES20.glGetUniformLocation(program, "uTextureCurr");
            this.uResolutionLoc = GLES20.glGetUniformLocation(program, "resolution");
        }
        GLES20.glActiveTexture(33985);
        GLES20.glBindTexture(3553, this.texturePrev != -1 ? this.texturePrev : 0);
        GLES20.glUniform1i(this.uTexturePrevLoc, 1);
        GLES20.glActiveTexture(33986);
        GLES20.glBindTexture(3553, this.textureCurr != -1 ? this.textureCurr : 0);
        GLES20.glUniform1i(this.uTextureCurrLoc, 2);
        if (this.uResolutionLoc != -1 && this.currentWidth > 0 && this.currentHeight > 0) {
            GLES20.glUniform2f(this.uResolutionLoc, this.currentWidth, this.currentHeight);
        }
        boolean showGenerated = this.isEnabled && this.currentSequence == 1 && isReadyForGeneration();
        float blendFactor = 0.5f;
        if (showGenerated) {
            long now = System.nanoTime();
            long elapsed = now - this.lastRealFrameTimeNs;
            float alpha = elapsed / this.currentRealFrameIntervalNs;
            blendFactor = Math.max(0.3f, Math.min(alpha, 0.7f));
        }
        GLES20.glUniform1i(this.uIsEnabledLoc, showGenerated ? 1 : 0);
        GLES20.glUniform1f(this.uBlendFactorLoc, blendFactor);
        GLES20.glActiveTexture(33984);
    }

    public void cleanup() {
        if (this.texturesInitialized) {
            GLES20.glDeleteTextures(3, this.texturePool, 0);
            this.texturesInitialized = false;
        }
        resetState();
    }

    public void resetState() {
        this.texturePrev = -1;
        this.textureCurr = -1;
        this.nextCycleFrameId = -1;
        this.hasCapturedFrame = false;
        this.hasFirstFrame = false;
        this.hasSecondFrame = false;
        this.waitingForSecondFrame = true;
        this.currentDisplayFrameType = 0;
        this.currentFrameDisplayCount = 0;
        this.skipFirstRealDisplay = false;
    }

    public boolean isReadyForGeneration() {
        return this.hasFirstFrame && this.hasSecondFrame && this.texturePrev != -1 && this.textureCurr != -1;
    }

    private class MotionAwareMaterial extends ScreenMaterial {
        public MotionAwareMaterial() {
        }

        @Override // com.winlator.cmod.renderer.material.ShaderMaterial
        protected String getFragmentShader() {
            return String.join("\n", "precision mediump float;", "varying vec2 vUV;", "uniform sampler2D uTexturePrev;", "uniform sampler2D uTextureCurr;", "uniform int uIsEnabled;", "uniform float uBlendFactor;", "float getLuma(vec3 color) {", "    return dot(color, vec3(0.299, 0.587, 0.114));", "}", "void main() {", "    if (uIsEnabled == 1) {", "        vec4 p = texture2D(uTexturePrev, vUV);", "        vec4 c = texture2D(uTextureCurr, vUV);", "        float lumaP = getLuma(p.rgb);", "        float lumaC = getLuma(c.rgb);", "        float diff = abs(lumaP - lumaC);", "        float motion = smoothstep(0.02, 0.15, diff);", "        float adaptiveAlpha = mix(uBlendFactor, 1.0, motion * 0.5);", "        gl_FragColor = mix(p, c, adaptiveAlpha);", "    } else {", "        gl_FragColor = texture2D(uTexturePrev, vUV);", "    }", "}");
        }
    }
}
