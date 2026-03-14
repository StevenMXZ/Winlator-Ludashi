package com.winlator.cmod.bigpicture.steamgrid;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/* loaded from: classes14.dex */
public class SteamGridGridsResponse {

    @SerializedName("data")
    public List<Grid> data;

    @SerializedName("limit")
    public int limit;

    @SerializedName("page")
    public int page;

    @SerializedName("success")
    public boolean success;

    @SerializedName("total")
    public int total;

    public class Grid {

        @SerializedName("author")
        public Author author;

        @SerializedName("id")
        public int id;

        @SerializedName("score")
        public int score;

        @SerializedName("style")
        public String style;

        @SerializedName("tags")
        public List<String> tags;

        @SerializedName("thumb")
        public String thumb;

        @SerializedName("url")
        public String url;

        public Grid() {
        }
    }

    public class Author {

        @SerializedName("avatar")
        public String avatar;

        @SerializedName("name")
        public String name;

        @SerializedName("steam64")
        public String steam64;

        public Author() {
        }
    }
}
