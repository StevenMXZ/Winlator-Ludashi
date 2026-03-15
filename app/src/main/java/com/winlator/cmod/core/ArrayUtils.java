package com.winlator.cmod.core;

import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONException;

/* loaded from: classes10.dex */
public abstract class ArrayUtils {
    public static byte[] concat(byte[]... elements) {
        byte[] result = Arrays.copyOf(elements[0], elements[0].length);
        for (int i = 1; i < elements.length; i++) {
            byte[] newArray = Arrays.copyOf(result, result.length + elements[i].length);
            System.arraycopy(elements[i], 0, newArray, result.length, elements[i].length);
            result = newArray;
        }
        return result;
    }

    @SafeVarargs
    public static <T> T[] concat(T[]... tArr) {
        Object[] objArr = (T[]) Arrays.copyOf(tArr[0], tArr[0].length);
        for (int i = 1; i < tArr.length; i++) {
            Object[] copyOf = Arrays.copyOf(objArr, objArr.length + tArr[i].length);
            System.arraycopy(tArr[i], 0, copyOf, objArr.length, tArr[i].length);
            objArr = (T[]) copyOf;
        }
        return (T[]) objArr;
    }

    public static String[] toStringArray(JSONArray data) {
        String[] stringArray = new String[data.length()];
        for (int i = 0; i < data.length(); i++) {
            try {
                stringArray[i] = data.getString(i);
            } catch (JSONException e) {
            }
        }
        return stringArray;
    }
}
