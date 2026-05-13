package com.musicsync.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicsync.model.Song;

@Service
public class JioSaavnService {
    private static final Logger log = LoggerFactory.getLogger(JioSaavnService.class);
    private static final String BASE_URL = "https://www.jiosaavn.com/api.php";
    private static final int MAX_SEARCH_LIMIT = 300;
    private final RestTemplate restTemplate;

    public JioSaavnService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    public List<Song> searchSongs(String query, int limit) {
        List<Song> songs = new ArrayList<>();
        try {
            if (query == null || query.isBlank()) {
                return songs;
            }
            int requested = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
            log.info("JioSaavn searching for '{}' limit {}", query, requested);
            String url = BASE_URL + "?__call=autocomplete.get&_format=json&_marker=0&query=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) {
                log.warn("JioSaavn null response");
                return songs;
            }
            log.debug("JioSaavn response length: {}", response.length());
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (json.has("songs")) {
                JsonObject songsObj = json.getAsJsonObject("songs");
                if (songsObj.has("data")) {
                    JsonArray arr = songsObj.getAsJsonArray("data");
                    log.info("Found {} songs", arr.size());
                    for (JsonElement e : arr) {
                        if (songs.size() >= requested) break;
                        Song song = parseSong(e.getAsJsonObject());
                        if (song != null) songs.add(song);
                    }
                }
            }
            log.info("Returning {} songs", songs.size());
        } catch (Exception e) {
            log.error("JioSaavn search error", e);
        }
        return songs;
    }

    public Song getSongById(String saavnId) {
        try {
            if (saavnId == null || saavnId.isBlank()) return null;
            String url = BASE_URL + "?__call=song.getDetails&_format=json&pids=" + URLEncoder.encode(saavnId, StandardCharsets.UTF_8);
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return null;
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            if (json.has(saavnId)) return parseSong(json.getAsJsonObject(saavnId));
            return null;
        } catch (Exception e) {
            log.warn("JioSaavn getSongById error: {}", e.getMessage());
            return null;
        }
    }

    private Song parseSong(JsonObject obj) {
        try {
            String id = getField(obj, "id");
            if (id == null || id.isEmpty()) return null;
            String title = getField(obj, "title");
            if (title == null || title.isEmpty()) return null;
            String artist = "Unknown";
            if (obj.has("more_info") && obj.get("more_info").isJsonObject()) {
                String singers = getField(obj.getAsJsonObject("more_info"), "singers");
                if (singers != null && !singers.isEmpty()) artist = singers;
            }
            String album = getField(obj, "album");
            if (album == null) album = "";
            String image = getField(obj, "image");
            if (image == null) image = "";
            int duration = 0;
            if (obj.has("more_info")) {
                try {
                    String dur = getField(obj.getAsJsonObject("more_info"), "duration");
                    if (dur != null) duration = Integer.parseInt(dur);
                } catch (Exception ignored) {}
            }
            String audioUrl = null;
            if (obj.has("more_info")) {
                JsonObject moreInfo = obj.getAsJsonObject("more_info");
                audioUrl = getField(moreInfo, "preview_url");
                if (audioUrl == null || audioUrl.isEmpty()) {
                    audioUrl = getField(moreInfo, "vlink");
                }
                if (audioUrl == null || audioUrl.isEmpty()) {
                    audioUrl = getField(moreInfo, "media_preview_url");
                }
            }
            if (audioUrl == null || audioUrl.isEmpty()) audioUrl = "jio_" + id;
            return new Song("jio_" + id, title, artist, album, image, duration, audioUrl);
        } catch (Exception e) {
            log.debug("Parse error: {}", e.getMessage());
            return null;
        }
    }

    private String getField(JsonObject obj, String field) {
        if (obj == null || !obj.has(field)) return null;
        try {
            JsonElement e = obj.get(field);
            if (e.isJsonNull()) return null;
            if (e.isJsonPrimitive()) return e.getAsString();
        } catch (Exception ignored) {}
        return null;
    }
}
