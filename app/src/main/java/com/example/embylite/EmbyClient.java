package com.example.embylite;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class EmbyClient {
    static final class Session {
        final String userId;
        final String token;

        Session(String userId, String token) {
            this.userId = userId;
            this.token = token;
        }
    }

    private final String serverUrl;
    private final String apiRoot;
    private final String deviceId;
    private String token;

    EmbyClient(String serverUrl, String deviceId, String token) {
        this.serverUrl = trimTrailingSlash(serverUrl.trim());
        this.apiRoot = this.serverUrl.endsWith("/emby")
                ? this.serverUrl
                : this.serverUrl + "/emby";
        this.deviceId = deviceId;
        this.token = token == null ? "" : token;
    }

    Session login(String username, String password) throws Exception {
        JSONObject body = new JSONObject()
                .put("Username", username)
                .put("Pw", password);
        JSONObject result = requestJson(
                "POST",
                apiRoot + "/Users/AuthenticateByName",
                body.toString(),
                false
        );
        token = result.getString("AccessToken");
        return new Session(result.getJSONObject("User").getString("Id"), token);
    }

    List<Movie> loadMovies(String userId, boolean favoritesOnly) throws Exception {
        String url = apiRoot + "/Users/" + encode(userId) + "/Items"
                + "?Recursive=true"
                + "&IncludeItemTypes=Movie,MusicVideo"
                + "&Fields=MediaSources,Overview"
                + "&SortBy=SortName"
                + "&SortOrder=Ascending"
                + (favoritesOnly ? "&Filters=IsFavorite" : "");
        return loadItems(url);
    }

    List<Movie> loadCollections(String userId) throws Exception {
        String url = apiRoot + "/Users/" + encode(userId) + "/Items"
                + "?Recursive=true"
                + "&IncludeItemTypes=BoxSet"
                + "&SortBy=SortName"
                + "&SortOrder=Ascending";
        return loadItems(url);
    }

    List<Movie> loadCollectionItems(String userId, String collectionId) throws Exception {
        String url = apiRoot + "/Users/" + encode(userId) + "/Items"
                + "?ParentId=" + encode(collectionId)
                + "&Recursive=true"
                + "&IncludeItemTypes=Movie,MusicVideo"
                + "&Fields=MediaSources,Overview"
                + "&SortBy=SortName"
                + "&SortOrder=Ascending";
        return loadItems(url);
    }

    void setFavorite(String userId, String itemId, boolean favorite) throws Exception {
        String url = apiRoot + "/Users/" + encode(userId)
                + "/FavoriteItems/" + encode(itemId);
        requestJson(favorite ? "POST" : "DELETE", url, null, true);
    }

    void addToCollection(String collectionId, String itemId) throws Exception {
        String url = apiRoot + "/Collections/" + encode(collectionId)
                + "/Items?Ids=" + encode(itemId);
        requestJson("POST", url, null, true);
    }

    void deleteItem(String itemId) throws Exception {
        requestJson("DELETE", apiRoot + "/Items?Ids=" + encode(itemId), null, true);
    }

    private List<Movie> loadItems(String url) throws Exception {
        JSONObject result = requestJson("GET", url, null, true);
        JSONArray items = result.optJSONArray("Items");
        List<Movie> movies = new ArrayList<>();
        if (items == null) return movies;

        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONArray sources = item.optJSONArray("MediaSources");
            JSONObject source = sources != null && sources.length() > 0
                    ? sources.optJSONObject(0)
                    : null;
            JSONObject userData = item.optJSONObject("UserData");
            JSONObject imageTags = item.optJSONObject("ImageTags");
            String primaryImageTag = item.optString("PrimaryImageTag", "");
            String thumbImageTag = item.optString("ThumbImageTag", "");
            if (imageTags != null) {
                if (primaryImageTag.isEmpty()) {
                    primaryImageTag = imageTags.optString("Primary", "");
                }
                if (thumbImageTag.isEmpty()) {
                    thumbImageTag = imageTags.optString("Thumb", "");
                }
            }
            movies.add(new Movie(
                    item.getString("Id"),
                    item.optString("Name", "未命名影片"),
                    item.has("ProductionYear") ? String.valueOf(item.optInt("ProductionYear")) : "",
                    item.optString("Overview", ""),
                    primaryImageTag,
                    thumbImageTag,
                    source == null ? "" : source.optString("Id", ""),
                    source == null ? "" : source.optString("Container", ""),
                    source == null ? 0 : source.optLong("Size", 0),
                    "BoxSet".equals(item.optString("Type")),
                    userData != null && userData.optBoolean("IsFavorite", false)
            ));
        }
        return movies;
    }

    Bitmap loadPoster(Movie movie, int width) throws Exception {
        Bitmap primary = loadImage(movie, "Primary", movie.primaryImageTag, width);
        if (primary != null) return primary;
        return loadImage(movie, "Thumb", movie.thumbImageTag, width);
    }

    private Bitmap loadImage(Movie movie, String imageType, String imageTag, int width)
            throws Exception {
        StringBuilder url = new StringBuilder(apiRoot)
                .append("/Items/").append(encode(movie.id))
                .append("/Images/").append(imageType)
                .append("?maxWidth=").append(width)
                .append("&quality=85&api_key=").append(encode(token));
        if (!imageTag.isEmpty()) {
            url.append("&tag=").append(encode(imageTag));
        }
        HttpURLConnection connection = open(url.toString(), "GET", false);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            return null;
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            return BitmapFactory.decodeStream(input);
        } finally {
            connection.disconnect();
        }
    }

    String streamUrl(Movie movie) throws Exception {
        StringBuilder url = new StringBuilder(apiRoot)
                .append("/Videos/").append(encode(movie.id))
                .append("/").append(encodePathSegment(movie.fileName()))
                .append("?static=true")
                .append("&api_key=").append(encode(token))
                .append("&PlaySessionId=").append(UUID.randomUUID().toString().replace("-", ""));
        if (!movie.mediaSourceId.isEmpty()) {
            url.append("&MediaSourceId=").append(encode(movie.mediaSourceId));
        }
        return url.toString();
    }

    private JSONObject requestJson(String method, String url, String body, boolean authenticated)
            throws Exception {
        HttpURLConnection connection = open(url, method, authenticated);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = connection.getResponseCode();
        InputStream raw = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = readText(raw);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new Exception("服务器返回 " + code + (response.isEmpty() ? "" : "：" + response));
        }
        return response.isEmpty() ? new JSONObject() : new JSONObject(response);
    }

    private HttpURLConnection open(String url, String method, boolean authenticated)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(25_000);
        connection.setRequestProperty(
                "X-Emby-Authorization",
                "Emby Client=\"Emby Lite\", Device=\"Android\", DeviceId=\""
                        + deviceId + "\", Version=\"1.0.0\""
        );
        if (authenticated && !token.isEmpty()) {
            connection.setRequestProperty("X-Emby-Token", token);
        }
        return connection;
    }

    private static String readText(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String encodePathSegment(String value) throws Exception {
        return encode(value).replace("+", "%20");
    }
}
