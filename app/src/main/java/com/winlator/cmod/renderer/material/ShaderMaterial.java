package com.winlator.cmod.renderer.material;

import android.graphics.Color;
import android.opengl.GLES20;
import android.util.Log;
import androidx.collection.ArrayMap;

/* loaded from: classes15.dex */
public class ShaderMaterial {
    public int programId;
    private final ArrayMap<String, Integer> uniforms = new ArrayMap<>();

    public void setUniformNames(String... names) {
        this.uniforms.clear();
        for (String name : names) {
            this.uniforms.put(name, -1);
        }
    }

    protected static int compileShaders(String vertexShader, String fragmentShader) {
        int beginIndex = vertexShader.indexOf("void main() {");
        String vertexShader2 = vertexShader.substring(0, beginIndex) + "vec2 applyXForm(vec2 p, float xform[6]) {\nreturn vec2(xform[0] * p.x + xform[2] * p.y + xform[4], xform[1] * p.x + xform[3] * p.y + xform[5]);\n}\n" + vertexShader.substring(beginIndex);
        int programId = GLES20.glCreateProgram();
        int[] compiled = new int[1];
        int vertexShaderId = GLES20.glCreateShader(35633);
        GLES20.glShaderSource(vertexShaderId, vertexShader2);
        GLES20.glCompileShader(vertexShaderId);
        GLES20.glGetShaderiv(vertexShaderId, 35713, compiled, 0);
        if (compiled[0] == 0) {
            throw new RuntimeException("Could not compile vertex shader: \n" + GLES20.glGetShaderInfoLog(vertexShaderId));
        }
        GLES20.glAttachShader(programId, vertexShaderId);
        int fragmentShaderId = GLES20.glCreateShader(35632);
        GLES20.glShaderSource(fragmentShaderId, fragmentShader);
        GLES20.glCompileShader(fragmentShaderId);
        GLES20.glGetShaderiv(fragmentShaderId, 35713, compiled, 0);
        if (compiled[0] == 0) {
            throw new RuntimeException("Could not compile fragment shader: \n" + GLES20.glGetShaderInfoLog(fragmentShaderId));
        }
        GLES20.glAttachShader(programId, fragmentShaderId);
        GLES20.glLinkProgram(programId);
        GLES20.glDeleteShader(vertexShaderId);
        GLES20.glDeleteShader(fragmentShaderId);
        return programId;
    }

    protected String getVertexShader() {
        return "";
    }

    protected String getFragmentShader() {
        return "";
    }

    public void use() {
        if (this.programId == 0) {
            this.programId = compileShaders(getVertexShader(), getFragmentShader());
        }
        GLES20.glUseProgram(this.programId);
        for (int i = 0; i < this.uniforms.size(); i++) {
            int location = this.uniforms.valueAt(i).intValue();
            if (location == -1) {
                String name = this.uniforms.keyAt(i);
                this.uniforms.put(name, Integer.valueOf(GLES20.glGetUniformLocation(this.programId, name)));
            }
        }
    }

    public int getUniformLocation(String name) {
        Integer location = this.uniforms.get(name);
        if (location == null) {
            Log.e("ShaderMaterial", "Uniform " + name + " is not registered in setUniformNames().");
            return -1;
        }
        if (location.intValue() == -1) {
            Log.e("ShaderMaterial", "Uniform " + name + " location not found in shader program.");
        }
        return location.intValue();
    }

    public void destroy() {
        GLES20.glDeleteProgram(this.programId);
        this.programId = 0;
    }

    public void setUniformVec2(String uniformName, float x, float y) {
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform2f(location, x, y);
        }
    }

    public void setUniformInt(String uniformName, int value) {
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform1i(location, value);
        }
    }

    public void setUniformFloat(String name, float value) {
        int location = getUniformLocation(name);
        if (location >= 0) {
            GLES20.glUniform1f(location, value);
        } else {
            Log.e("ScreenMaterial", "Uniform location for " + name + " not found!");
        }
    }

    public void setUniformFloatArray(String uniformName, float[] values) {
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform1fv(location, values.length, values, 0);
        }
    }

    public void setUniformColor(String uniformName, int color) {
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            float red = Color.red(color) / 255.0f;
            float green = Color.green(color) / 255.0f;
            float blue = Color.blue(color) / 255.0f;
            GLES20.glUniform3f(location, red, green, blue);
        }
    }

    public void setUniformVec3(String uniformName, float x, float y, float z) {
        int location = getUniformLocation(uniformName);
        if (location != -1) {
            GLES20.glUniform3f(location, x, y, z);
        } else {
            Log.e("ShaderMaterial", "Uniform location for " + uniformName + " not found!");
        }
    }
}
