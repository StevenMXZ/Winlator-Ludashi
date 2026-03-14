package com.winlator.cmod.renderer;

import android.opengl.GLES20;
import com.winlator.cmod.renderer.effects.Effect;
import com.winlator.cmod.renderer.effects.FrameGenerationEffect;
import com.winlator.cmod.renderer.effects.ToonEffect;
import com.winlator.cmod.renderer.material.ShaderMaterial;
import java.util.ArrayList;
import java.util.List;

/* loaded from: classes12.dex */
public class EffectComposer {
    private static final String TAG = "EffectComposer";
    public static final boolean logEnabled = false;
    private FrameGenerationEffect frameGenerationEffect;
    private RenderTarget readBuffer;
    private final GLRenderer renderer;
    private RenderTarget writeBuffer;
    private boolean isRendering = false;
    private final List<Effect> effects = new ArrayList();
    private int lastWidth = 0;
    private int lastHeight = 0;
    private long lastFpsTime = 0;
    private int frameCount = 0;

    public EffectComposer(GLRenderer renderer) {
        this.renderer = renderer;
    }

    private void LogString(String message) {
    }

    private void initBuffers(int width, int height) {
        if (this.readBuffer == null || width != this.lastWidth || height != this.lastHeight) {
            if (this.readBuffer != null) {
                GLES20.glDeleteFramebuffers(1, new int[]{this.readBuffer.getFramebuffer()}, 0);
            }
            if (this.writeBuffer != null) {
                GLES20.glDeleteFramebuffers(1, new int[]{this.writeBuffer.getFramebuffer()}, 0);
            }
            this.readBuffer = new RenderTarget();
            this.readBuffer.allocateFramebuffer(width, height);
            this.writeBuffer = new RenderTarget();
            this.writeBuffer.allocateFramebuffer(width, height);
            this.lastWidth = width;
            this.lastHeight = height;
        }
    }

    public synchronized void addEffect(Effect effect) {
        if (this.frameGenerationEffect == null || (effect instanceof FrameGenerationEffect)) {
            if (!this.effects.contains(effect)) {
                this.effects.add(effect);
                if (effect instanceof FrameGenerationEffect) {
                    this.frameGenerationEffect = (FrameGenerationEffect) effect;
                }
            }
            this.renderer.xServerView.requestRender();
        }
    }

    public synchronized <T extends Effect> T getEffect(Class<T> effectClass) {
        for (Effect effect : this.effects) {
            if (effect.getClass() == effectClass) {
                return effectClass.cast(effect);
            }
        }
        return null;
    }

    public synchronized boolean hasEffects() {
        return !this.effects.isEmpty();
    }

    public synchronized void removeEffect(Effect effect) {
        if (this.effects.remove(effect) && effect == this.frameGenerationEffect) {
            this.frameGenerationEffect = null;
        }
        this.renderer.xServerView.requestRender();
    }

    private int determineFrameSequence() {
        if (this.frameGenerationEffect == null || !this.frameGenerationEffect.isEnabled()) {
            return 0;
        }
        int frameType = this.frameGenerationEffect.getFrameToDisplay();
        if (frameType != 1 || this.frameGenerationEffect.isReadyForGeneration()) {
            return frameType;
        }
        return 0;
    }

    private void updateFPS() {
        long now = System.nanoTime();
        if (this.lastFpsTime == 0) {
            this.lastFpsTime = now;
            return;
        }
        this.frameCount++;
        if (now - this.lastFpsTime >= 500000000) {
            float fps = (this.frameCount * 1.0E9f) / (now - this.lastFpsTime);
            if (this.frameGenerationEffect != null && this.frameGenerationEffect.isAutoDetectFPS()) {
                this.frameGenerationEffect.updateFPS((int) fps);
            }
            this.lastFpsTime = now;
            this.frameCount = 0;
        }
    }

    public synchronized void render() {
        boolean renderToScreen;
        if (this.isRendering) {
            return;
        }
        this.isRendering = true;
        try {
            updateFPS();
            int width = this.renderer.surfaceWidth;
            int height = this.renderer.surfaceHeight;
            initBuffers(width, height);
            int currentSequence = determineFrameSequence();
            if (hasEffects()) {
                try {
                    GLES20.glBindFramebuffer(36160, this.readBuffer.getFramebuffer());
                } catch (Throwable th) {
                    th = th;
                    this.isRendering = false;
                    throw th;
                }
            } else {
                GLES20.glBindFramebuffer(36160, 0);
            }
            GLES20.glClear(16384);
            this.renderer.drawFrame();
            GLES20.glDisable(3089);
            for (int i = 0; i < this.effects.size(); i++) {
                Effect effect = this.effects.get(i);
                if (i == this.effects.size() - 1) {
                    renderToScreen = true;
                } else {
                    renderToScreen = false;
                }
                int targetFramebuffer = renderToScreen ? 0 : this.writeBuffer.getFramebuffer();
                if (effect == this.frameGenerationEffect && this.frameGenerationEffect != null) {
                    GLES20.glBindFramebuffer(36160, this.readBuffer.getFramebuffer());
                    this.frameGenerationEffect.prepareFrame(width, height, currentSequence);
                    GLES20.glBindFramebuffer(36160, targetFramebuffer);
                    GLES20.glViewport(0, 0, width, height);
                    this.renderer.setViewportNeedsUpdate(true);
                    GLES20.glClear(16384);
                    effect.getMaterial().use();
                    this.frameGenerationEffect.setupShaderUniforms();
                    renderEffect(effect);
                    if (!renderToScreen) {
                        swapBuffers();
                    }
                } else {
                    GLES20.glBindFramebuffer(36160, targetFramebuffer);
                    GLES20.glViewport(0, 0, width, height);
                    this.renderer.setViewportNeedsUpdate(true);
                    GLES20.glClear(16384);
                    renderEffect(effect);
                    swapBuffers();
                }
            }
            this.renderer.xServerView.requestRender();
            this.isRendering = false;
        } catch (Throwable th2) {
            th = th2;
        }
    }

    private void renderEffect(Effect effect) {
        ShaderMaterial material = effect.getMaterial();
        if (material == null) {
            return;
        }
        material.use();
        if (effect instanceof FrameGenerationEffect) {
            FrameGenerationEffect interpEffect = (FrameGenerationEffect) effect;
            interpEffect.setupShaderUniforms();
            GLES20.glActiveTexture(33984);
        } else {
            material.setUniformVec2("resolution", this.renderer.surfaceWidth, this.renderer.surfaceHeight);
            GLES20.glActiveTexture(33984);
            GLES20.glBindTexture(3553, this.readBuffer.getTextureId());
            material.setUniformInt("screenTexture", 0);
        }
        this.renderer.getQuadVertices().bind(material.programId);
        GLES20.glDrawArrays(5, 0, this.renderer.quadVertices.count());
        GLES20.glBindTexture(3553, 0);
    }

    private void swapBuffers() {
        RenderTarget tmp = this.writeBuffer;
        this.writeBuffer = this.readBuffer;
        this.readBuffer = tmp;
    }

    public synchronized void toggleToonEffect() {
        ToonEffect toonEffect = (ToonEffect) getEffect(ToonEffect.class);
        if (toonEffect != null) {
            removeEffect(toonEffect);
        } else {
            addEffect(new ToonEffect());
        }
        this.renderer.xServerView.requestRender();
    }

    public synchronized void configureFrameGeneration(int targetFPS, int mode) {
        if (this.frameGenerationEffect != null) {
            this.frameGenerationEffect.setTargetFPS(targetFPS);
            this.frameGenerationEffect.setGenerationMode(mode);
        }
        this.renderer.xServerView.requestRender();
    }

    public void setDisplayRefreshRate(int refreshRate) {
        if (this.frameGenerationEffect != null) {
            this.frameGenerationEffect.setDisplayRefreshRate(refreshRate);
        }
    }

    public void setGenerationMode(int mode) {
        if (this.frameGenerationEffect != null) {
            this.frameGenerationEffect.setGenerationMode(mode);
        }
    }

    public synchronized FrameGenerationSettings getFrameGenerationSettings() {
        if (this.frameGenerationEffect == null) {
            return null;
        }
        return new FrameGenerationSettings(this.frameGenerationEffect.getTargetFPS(), this.frameGenerationEffect.isAutoDetectFPS(), this.frameGenerationEffect.getCurrentRealFrameInterval(), this.frameGenerationEffect.getCurrentTargetFrameInterval());
    }

    public static class FrameGenerationSettings {
        public final boolean autoDetect;
        public final long realInterval;
        public final int targetFPS;
        public final long targetInterval;

        public FrameGenerationSettings(int targetFPS, boolean autoDetect, long realInterval, long targetInterval) {
            this.targetFPS = targetFPS;
            this.autoDetect = autoDetect;
            this.realInterval = realInterval;
            this.targetInterval = targetInterval;
        }
    }
}
