package com.winlator.cmod.xserver;

import android.util.SparseArray;
import com.winlator.cmod.xconnector.XInputStream;
import com.winlator.cmod.xserver.GraphicsContext;
import java.util.Iterator;

/* loaded from: classes11.dex */
public class GraphicsContextManager extends XResourceManager {
    private final SparseArray<GraphicsContext> graphicsContexts = new SparseArray<>();

    public GraphicsContext getGraphicsContext(int id) {
        return this.graphicsContexts.get(id);
    }

    public GraphicsContext createGraphicsContext(int id, Drawable drawable) {
        if (this.graphicsContexts.indexOfKey(id) >= 0) {
            return null;
        }
        GraphicsContext graphicsContext = new GraphicsContext(id, drawable);
        this.graphicsContexts.put(id, graphicsContext);
        triggerOnCreateResourceListener(graphicsContext);
        return graphicsContext;
    }

    public void copyGraphicsContext(GraphicsContext src, GraphicsContext dst, Bitmask valueMask) {
        updateGraphicsContext(dst, src, valueMask);
    }

    public void freeGraphicsContext(int id) {
        triggerOnFreeResourceListener(this.graphicsContexts.get(id));
        this.graphicsContexts.remove(id);
    }

    public void updateGraphicsContext(GraphicsContext dstContext, GraphicsContext srcContext, Bitmask valueMask) {
        Iterator<Integer> it = valueMask.iterator();
        while (it.hasNext()) {
            int index = it.next().intValue();
            switch (index) {
                case 1:
                    dstContext.setFunction(srcContext.getFunction());
                    break;
                case 2:
                    dstContext.setPlaneMask(srcContext.getPlaneMask());
                    break;
                case 4:
                    dstContext.setForeground(srcContext.getForeground());
                    break;
                case 8:
                    dstContext.setBackground(srcContext.getBackground());
                    break;
                case 16:
                    dstContext.setLineWidth(srcContext.getLineWidth());
                    break;
                case 32768:
                    dstContext.setSubwindowMode(srcContext.getSubwindowMode());
                    break;
            }
        }
    }

    public void updateGraphicsContext(GraphicsContext graphicsContext, Bitmask valueMask, XInputStream inputStream) {
        Iterator<Integer> it = valueMask.iterator();
        while (it.hasNext()) {
            int index = it.next().intValue();
            switch (index) {
                case 1:
                    graphicsContext.setFunction(GraphicsContext.Function.values()[inputStream.readInt()]);
                    break;
                case 2:
                    graphicsContext.setPlaneMask(inputStream.readInt());
                    break;
                case 4:
                    graphicsContext.setForeground(inputStream.readInt());
                    break;
                case 8:
                    graphicsContext.setBackground(inputStream.readInt());
                    break;
                case 16:
                    graphicsContext.setLineWidth(inputStream.readInt());
                    break;
                case 32:
                case 64:
                case 128:
                case 256:
                case 512:
                case 1024:
                case 2048:
                case 4096:
                case 8192:
                case 16384:
                case 65536:
                case 131072:
                case 262144:
                case 524288:
                case 1048576:
                case 2097152:
                case 4194304:
                    inputStream.skip(4);
                    break;
                case 32768:
                    graphicsContext.setSubwindowMode(GraphicsContext.SubwindowMode.values()[inputStream.readInt()]);
                    break;
            }
        }
    }
}
