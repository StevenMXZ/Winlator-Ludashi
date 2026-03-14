package com.winlator.cmod.contentdialog;

import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes4.dex */
public class DriverRepo {
    public String apiUrl;
    public String name;

    public DriverRepo(String name, String apiUrl) {
        this.name = name;
        this.apiUrl = apiUrl;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("name", this.name);
        obj.put("url", this.apiUrl);
        return obj;
    }

    public static DriverRepo fromJson(JSONObject obj) {
        return new DriverRepo(obj.optString("name", "Unknown Repo"), obj.optString("url", ""));
    }
}
