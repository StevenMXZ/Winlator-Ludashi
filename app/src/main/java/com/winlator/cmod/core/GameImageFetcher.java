package com.winlator.cmod.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bouncycastle.i18n.MessageBundle;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/* loaded from: classes10.dex */
public class GameImageFetcher {
    private static final int COVER_HEIGHT = 900;
    private static final int COVER_WIDTH = 600;
    private static final int ICON_SIZE = 256;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Map<String, Integer> STEAM_IDS = new HashMap();
    private static final Map<String, String> CUSTOM_COVERS = new HashMap();

    static {
        STEAM_IDS.put("Grand Theft Auto IV", 12210);
        STEAM_IDS.put("Grand Theft Auto V", 271590);
        STEAM_IDS.put("Grand Theft Auto San Andreas", 12120);
        STEAM_IDS.put("Resident Evil 4", 2050650);
        STEAM_IDS.put("The Elder Scrolls V Skyrim", 489830);
        STEAM_IDS.put("The Witcher 3 Wild Hunt", 292030);
        STEAM_IDS.put("DOOM 2016", 379720);
        STEAM_IDS.put("Hades", 1145360);
        STEAM_IDS.put("God of War", 1593500);
        STEAM_IDS.put("Devil May Cry 4", 329050);
        CUSTOM_COVERS.put("Grand Theft Auto IV", "https://images.igdb.com/igdb/image/upload/t_720p/co2l1q.jpg");
        CUSTOM_COVERS.put("Devil May Cry 4", "https://images.igdb.com/igdb/image/upload/t_720p/co1ywv.jpg");
        CUSTOM_COVERS.put("Need for Speed Most Wanted", "https://images.igdb.com/igdb/image/upload/t_720p/co2240.jpg");
        CUSTOM_COVERS.put("Need for Speed Underground 2", "https://images.igdb.com/igdb/image/upload/t_720p/co2243.jpg");
        CUSTOM_COVERS.put("Need for Speed Underground", "https://images.igdb.com/igdb/image/upload/t_720p/co2244.jpg");
        CUSTOM_COVERS.put("Need for Speed Carbon", "https://images.igdb.com/igdb/image/upload/t_720p/co223z.jpg");
    }

    public static void fetchImage(final String rawName, final File destinationFile, final boolean isCover, final Runnable onComplete) {
        executor.submit(new Runnable() { // from class: com.winlator.cmod.core.GameImageFetcher$$ExternalSyntheticLambda0
            @Override // java.lang.Runnable
            public final void run() {
                GameImageFetcher.lambda$fetchImage$0(rawName, isCover, destinationFile, onComplete);
            }
        });
    }

    static /* synthetic */ void lambda$fetchImage$0(String rawName, boolean isCover, File destinationFile, Runnable onComplete) {
        try {
            String gameName = cleanGameName(rawName);
            if (isCover && CUSTOM_COVERS.containsKey(gameName)) {
                try {
                    downloadAndProcessImage(CUSTOM_COVERS.get(gameName), destinationFile, true);
                    if (onComplete != null) {
                        onComplete.run();
                        return;
                    }
                    return;
                } catch (Exception e) {
                }
            }
            boolean success = fetchFromSteamAPI(gameName, destinationFile, isCover);
            if (!success) {
                success = fetchFromGogAPI(gameName, destinationFile, isCover);
            }
            if (!success) {
                success = fetchFromWikipedia(gameName, destinationFile, isCover);
            }
            if (success && onComplete != null) {
                onComplete.run();
            }
        } catch (Exception e2) {
        }
    }

    private static String cleanGameName(String name) {
        if (name == null) {
            return "";
        }
        String raw = name.toLowerCase().replaceAll("[^a-z0-9]", "");
        if (raw.contains("gtaiv") || raw.contains("gta4") || raw.contains("grandtheftautoiv") || raw.contains("grandtheftauto4")) {
            return "Grand Theft Auto IV";
        }
        if (raw.contains("gtav") || raw.contains("gta5") || raw.contains("grandtheftautov") || raw.contains("grandtheftauto5")) {
            return "Grand Theft Auto V";
        }
        if (raw.contains("gtasa") || raw.contains("sanandreas")) {
            return "Grand Theft Auto San Andreas";
        }
        if (raw.contains("devilmaycry4") || raw.contains("dmc4")) {
            return "Devil May Cry 4";
        }
        if (raw.contains("nfsmw") || raw.contains("mostwanted")) {
            return "Need for Speed Most Wanted";
        }
        if (raw.contains("nfsu2") || raw.contains("underground2")) {
            return "Need for Speed Underground 2";
        }
        if (raw.contains("re4") || raw.contains("residentevil4")) {
            return "Resident Evil 4";
        }
        if (raw.contains("witcher3") || raw.contains("thewitcher3")) {
            return "The Witcher 3 Wild Hunt";
        }
        if (raw.contains("skyrim")) {
            return "The Elder Scrolls V Skyrim";
        }
        if (raw.contains("doom2016")) {
            return "DOOM 2016";
        }
        if (raw.contains("doom") && !raw.contains("doom3") && !raw.contains("doom2") && !raw.contains("doom64")) {
            return "DOOM 2016";
        }
        if (raw.contains("godofwar")) {
            return "God of War";
        }
        if (raw.contains("hades")) {
            return "Hades";
        }
        if (name.toLowerCase().endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.replace("_", " ").replace(".", " ").replaceAll("([a-z])([A-Z])", "$1 $2").replaceAll("([a-zA-Z])([0-9])", "$1 $2").replaceAll("\\(.*?\\)", "").replaceAll("\\[.*?\\]", "").replaceAll("(?i)\\b(dx\\d+|d3d\\d+|x64|x86|hd|remastered|remake|goty|edition|setup|installer|repack|steam|gog|rip|demo|trial|codex|skidrow|fitgirl|dodi|lite|v\\d+|by)\\b", "").replaceAll("(?i)\\bv?\\d+\\.\\d+(\\.\\d+)?\\b", "").trim().replaceAll("\\s+", " ");
    }

    private static JSONObject findBestMatch(JSONArray items, String targetName, String titleKey) throws JSONException {
        if (items == null || items.length() == 0) {
            return null;
        }
        String targetClean = targetName.toLowerCase().replaceAll("[^a-z0-9]", "");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String titleClean = item.optString(titleKey, "").toLowerCase().replaceAll("[^a-z0-9]", "");
            if (titleClean.equals(targetClean)) {
                return item;
            }
        }
        for (int i2 = 0; i2 < items.length(); i2++) {
            JSONObject item2 = items.getJSONObject(i2);
            String titleClean2 = item2.optString(titleKey, "").toLowerCase().replaceAll("[^a-z0-9]", "");
            if (titleClean2.startsWith(targetClean)) {
                return item2;
            }
        }
        for (int i3 = 0; i3 < items.length(); i3++) {
            JSONObject item3 = items.getJSONObject(i3);
            String titleClean3 = item3.optString(titleKey, "").toLowerCase().replaceAll("[^a-z0-9]", "");
            if (titleClean3.contains(targetClean)) {
                return item3;
            }
        }
        return items.getJSONObject(0);
    }

    private static boolean downloadSteamAsset(int id, File dst, boolean isCover) {
        String verticalUrl1 = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + id + "/library_600x900_2x.jpg";
        String verticalUrl2 = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + id + "/library_600x900.jpg";
        String headerUrl = "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + id + "/header.jpg";
        if (isCover) {
            try {
                downloadAndProcessImage(verticalUrl1, dst, true);
                return true;
            } catch (Exception e) {
                try {
                    downloadAndProcessImage(verticalUrl2, dst, true);
                    return true;
                } catch (Exception e2) {
                    try {
                        downloadAndProcessImage(headerUrl, dst, true);
                        return true;
                    } catch (Exception e3) {
                        return false;
                    }
                }
            }
        }
        try {
            downloadAndProcessImage(headerUrl, dst, false);
            return true;
        } catch (Exception e4) {
            return false;
        }
    }

    private static boolean fetchFromSteamAPI(String gameName, File dst, boolean isCover) {
        JSONObject bestMatch;
        if (STEAM_IDS.containsKey(gameName) && downloadSteamAsset(STEAM_IDS.get(gameName).intValue(), dst, isCover)) {
            return true;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://store.steampowered.com/api/storesearch/?term=" + URLEncoder.encode(gameName, "UTF-8") + "&l=english&cc=US");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                sb.append(line);
            }
            reader.close();
            JSONObject json = new JSONObject(sb.toString());
            JSONArray items = json.optJSONArray("items");
            bestMatch = findBestMatch(items, gameName, "name");
        } catch (Exception e) {
            if (conn == null) {
                return false;
            }
        } catch (Throwable th) {
            if (conn != null) {
                conn.disconnect();
            }
            throw th;
        }
        if (bestMatch != null) {
            boolean downloadSteamAsset = downloadSteamAsset(bestMatch.getInt("id"), dst, isCover);
            if (conn != null) {
                conn.disconnect();
            }
            return downloadSteamAsset;
        }
        if (conn == null) {
            return false;
        }
        conn.disconnect();
        return false;
    }

    private static boolean fetchFromGogAPI(String gameName, File dst, boolean isCover) {
        HttpURLConnection conn = null;
        try {
            try {
                URL url = new URL("https://catalog.gog.com/v1/catalog?limit=5&query=" + URLEncoder.encode(gameName, "UTF-8"));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setConnectTimeout(5000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                while (true) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    sb.append(line);
                }
                reader.close();
                JSONObject json = new JSONObject(sb.toString());
                JSONArray products = json.optJSONArray("products");
                JSONObject bestMatch = findBestMatch(products, gameName, MessageBundle.TITLE_ENTRY);
                if (bestMatch != null) {
                    String baseImageUrl = bestMatch.optString("coverVertical", "");
                    if (baseImageUrl.isEmpty()) {
                        baseImageUrl = bestMatch.optString("coverHorizontal", "");
                    }
                    if (!baseImageUrl.isEmpty()) {
                        String imageUrl = isCover ? baseImageUrl + "_glx_vertical_cover.jpg" : baseImageUrl + "_glx_bg_crop_1080x500.jpg";
                        try {
                            downloadAndProcessImage(imageUrl, dst, isCover);
                            return true;
                        } catch (Exception e) {
                            downloadAndProcessImage(baseImageUrl, dst, isCover);
                            if (conn != null) {
                                conn.disconnect();
                            }
                            return true;
                        }
                    }
                }
                if (conn == null) {
                    return false;
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        } catch (Exception e2) {
            if (conn == null) {
                return false;
            }
        }
        conn.disconnect();
        return false;
    }

    /* JADX WARN: Removed duplicated region for block: B:40:0x0103  */
    /*
        Code decompiled incorrectly, please refer to instructions dump.
        To view partially-correct add '--show-bad-code' argument
    */
    private static boolean fetchFromWikipedia(java.lang.String r17, java.io.File r18, boolean r19) {
        /*
            Method dump skipped, instructions count: 263
            To view this dump add '--comments-level debug' option
        */
        throw new UnsupportedOperationException("Method not decompiled: com.winlator.cmod.core.GameImageFetcher.fetchFromWikipedia(java.lang.String, java.io.File, boolean):boolean");
    }

    private static void downloadAndProcessImage(String urlString, File dst, boolean isCover) throws Exception {
        Bitmap resultBitmap;
        Bitmap resultBitmap2;
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        InputStream in = conn.getInputStream();
        try {
            Bitmap original = BitmapFactory.decodeStream(in);
            if (original == null) {
                throw new Exception();
            }
            int width = original.getWidth();
            int height = original.getHeight();
            if (isCover) {
                int targetWidth = (int) (height * 0.6666667f);
                if (targetWidth > width) {
                    int targetHeight = (int) (width * 1.5f);
                    int y = (height - targetHeight) / 2;
                    resultBitmap2 = Bitmap.createBitmap(original, 0, Math.max(0, y), width, Math.min(height, targetHeight));
                } else {
                    int x = (width - targetWidth) / 2;
                    resultBitmap2 = Bitmap.createBitmap(original, Math.max(0, x), 0, Math.min(width, targetWidth), height);
                }
                resultBitmap = Bitmap.createScaledBitmap(resultBitmap2, COVER_WIDTH, COVER_HEIGHT, true);
            } else {
                int size = Math.min(width, height);
                int x2 = (width - size) / 2;
                int y2 = (height - size) / 2;
                Bitmap resultBitmap3 = Bitmap.createBitmap(original, x2, y2, size, size);
                resultBitmap = Bitmap.createScaledBitmap(resultBitmap3, 256, 256, true);
            }
            FileOutputStream out = new FileOutputStream(dst);
            try {
                resultBitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.close();
                if (original != resultBitmap && !original.isRecycled()) {
                    original.recycle();
                }
                if (resultBitmap != null && !resultBitmap.isRecycled()) {
                    resultBitmap.recycle();
                }
                if (in != null) {
                    in.close();
                }
            } finally {
            }
        } catch (Throwable th) {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
            }
            throw th;
        }
    }
}
