package com.winlator.cmod.bigpicture.steamgrid;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/* loaded from: classes14.dex */
public class SteamGridSearchResponse {

    @SerializedName("data")
    public List<GameData> data;

    @SerializedName("success")
    public boolean success;

    public class GameData {

        @SerializedName("id")
        public int id;

        @SerializedName("name")
        public String name;

        @SerializedName("url")
        public String url;

        public GameData() {
        }
    }
}
