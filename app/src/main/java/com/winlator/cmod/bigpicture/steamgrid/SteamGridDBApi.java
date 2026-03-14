package com.winlator.cmod.bigpicture.steamgrid;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Header;
import retrofit2.http.Path;
import retrofit2.http.Query;

/* loaded from: classes14.dex */
public interface SteamGridDBApi {
    @GET("grids/game/{gameId}")
    Call<SteamGridGridsResponse> getGridsByGameId(@Header("Authorization") String str, @Path("gameId") int i, @Query("styles") String str2, @Query("dimensions") String str3, @Query("types") String str4);

    @GET("search/autocomplete/{term}")
    Call<SteamGridSearchResponse> searchGame(@Header("Authorization") String str, @Path("term") String str2);
}
