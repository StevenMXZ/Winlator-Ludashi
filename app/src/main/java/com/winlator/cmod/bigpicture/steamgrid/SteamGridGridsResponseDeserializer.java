package com.winlator.cmod.bigpicture.steamgrid;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.winlator.cmod.bigpicture.steamgrid.SteamGridGridsResponse;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;

/* loaded from: classes14.dex */
public class SteamGridGridsResponseDeserializer implements JsonDeserializer<SteamGridGridsResponse> {
    /* JADX WARN: Can't rename method to resolve collision */
    @Override // com.google.gson.JsonDeserializer
    public SteamGridGridsResponse deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        SteamGridGridsResponse response = new SteamGridGridsResponse();
        JsonObject jsonObject = json.getAsJsonObject();
        if (jsonObject.has("success") && !jsonObject.get("success").isJsonNull()) {
            response.success = jsonObject.get("success").getAsBoolean();
        } else {
            response.success = false;
        }
        if (jsonObject.has("page") && !jsonObject.get("page").isJsonNull()) {
            response.page = jsonObject.get("page").getAsInt();
        } else {
            response.page = 0;
        }
        if (jsonObject.has("total") && !jsonObject.get("total").isJsonNull()) {
            response.total = jsonObject.get("total").getAsInt();
        } else {
            response.total = 0;
        }
        if (jsonObject.has("limit") && !jsonObject.get("limit").isJsonNull()) {
            response.limit = jsonObject.get("limit").getAsInt();
        } else {
            response.limit = 0;
        }
        JsonElement dataElement = jsonObject.get("data");
        if (dataElement != null && dataElement.isJsonArray()) {
            response.data = new ArrayList();
            JsonArray gridsArray = dataElement.getAsJsonArray();
            Iterator<JsonElement> it = gridsArray.iterator();
            while (it.hasNext()) {
                JsonElement element = it.next();
                SteamGridGridsResponse.Grid grid = (SteamGridGridsResponse.Grid) context.deserialize(element, SteamGridGridsResponse.Grid.class);
                response.data.add(grid);
            }
        } else if (dataElement != null && dataElement.isJsonObject()) {
            response.data = new ArrayList();
            SteamGridGridsResponse.Grid grid2 = (SteamGridGridsResponse.Grid) context.deserialize(dataElement, SteamGridGridsResponse.Grid.class);
            response.data.add(grid2);
        }
        return response;
    }
}
