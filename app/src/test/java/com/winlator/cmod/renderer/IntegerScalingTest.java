package com.winlator.cmod.renderer;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class IntegerScalingTest {
    @Test public void exactMultipleFillsOutput() {
        ViewTransformation view = new ViewTransformation();
        view.update(1920, 1080, 640, 360, true);
        assertEquals(3f, view.aspect, 0f);
        assertEquals(1920, view.viewWidth);
        assertEquals(1080, view.viewHeight);
        assertEquals(0, view.viewOffsetX);
        assertEquals(0, view.viewOffsetY);
    }

    @Test public void fractionalFitRoundsDownAndCenters() {
        ViewTransformation view = new ViewTransformation();
        view.update(1921, 1081, 800, 450, true);
        assertEquals(2f, view.aspect, 0f);
        assertEquals(1600, view.viewWidth);
        assertEquals(900, view.viewHeight);
        assertEquals(160, view.viewOffsetX);
        assertEquals(90, view.viewOffsetY);
        assertEquals(160f, view.sceneOffsetX * 1921 / 800, 0.001f);
        assertEquals(90f, view.sceneOffsetY * 1081 / 450, 0.001f);
    }

    @Test public void oversizedSourceFitsWithoutCropping() {
        ViewTransformation view = new ViewTransformation();
        view.update(1280, 720, 1920, 1080, true);
        assertEquals(2f / 3f, view.aspect, 0.00001f);
        assertEquals(1280, view.viewWidth);
        assertEquals(720, view.viewHeight);
    }

    @Test public void switchingBackRestoresFractionalFit() {
        ViewTransformation view = new ViewTransformation();
        view.update(2000, 1125, 800, 450, true);
        view.update(2000, 1125, 800, 450);
        assertEquals(2.5f, view.aspect, 0.00001f);
        assertEquals(2000, view.viewWidth);
        assertEquals(1125, view.viewHeight);
    }
}
