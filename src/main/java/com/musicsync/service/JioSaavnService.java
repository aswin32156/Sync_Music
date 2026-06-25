package com.musicsync.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicsync.model.Song;

@Service
public class JioSaavnService implements MusicProvider {

    private static final Logger log = LoggerFactory.getLogger(JioSaavnService.class);
    private final RestTemplate rest;
    private final String jioCookie;

    public JioSaavnService() {
        String cookie = System.getenv("JIOSAAVN_COOKIE");
        this.jioCookie = (cookie == null || cookie.isBlank()) ? null : cookie.trim();
        this.rest = new RestTemplate();

        // Always add browser-like headers - JioSaavn blocks Java's default User-Agent
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            request.getHeaders().add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
            request.getHeaders().add("Referer", "https://www.jiosaavn.com/");
            request.getHeaders().add("Accept", "application/json, text/javascript, */*; q=0.01");
            request.getHeaders().add("X-Requested-With", "XMLHttpRequest");
            request.getHeaders().add("Origin", "https://www.jiosaavn.com");
            if (this.jioCookie != null) {
                request.getHeaders().add("Cookie", this.jioCookie);
            }
            return execution.execute(request, body);
        };
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(this.rest.getInterceptors());
        interceptors.add(interceptor);
        this.rest.setInterceptors(interceptors);
    }

    public List<Song> searchSongs(String query, int limit) {
        List<Song> results = new ArrayList<>();
        if (query == null || query.isBlank()) return results;

        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            int page = 0;
            int fetched = 0;
            while (fetched < limit && page < 5) {
                String url = "https://www.jiosaavn.com/api.php?__call=search.getResults&q=" + encoded
                        + "&_format=json&_marker=0&p=" + page + "&n=" + Math.min(limit - fetched, 50);
                ResponseEntity<String> res = rest.getForEntity(url, String.class);
                if (res.getBody() == null) break;

                JsonObject json = JsonParser.parseString(res.getBody()).getAsJsonObject();

                JsonArray arr = null;
                if (json.has("results") && json.get("results").isJsonArray()) {
                    arr = json.getAsJsonArray("results");
                }
                if (arr == null || arr.size() == 0) break;

                System.out.println("JioSaavn search (page " + page + "): got " + arr.size() + " items");
                for (JsonElement e : arr) {
                    Song s = parseSongElement(e);
                    if (s != null && s.getId() != null && !s.getId().isBlank()) {
                        results.add(s);
                        fetched++;
                        if (fetched >= limit) break;
                    }
                }
                page++;
            }
            System.out.println("JioSaavn search: total " + results.size() + " songs for '" + query + "'");
        } catch (Exception e) {
            log.debug("JioSaavn search error for '{}': {}", query, e.getMessage());
        }

        return results;
    }

    @Override
    public String getProviderName() {
        return "JioSaavn";
    }

    public Song getSongById(String id) {
        if (id == null || id.isBlank()) return null;
        try {
            // Use pids parameter (not id) - confirmed working with JioSaavn API
            String url = "https://www.jiosaavn.com/api.php?__call=song.getDetails&pids=" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "&_format=json&_marker=0";
            log.info("Fetching JioSaavn song details for id: {}", id);
            ResponseEntity<String> res = rest.getForEntity(url, String.class);
            if (res.getBody() == null) {
                log.warn("JioSaavn getSongById returned null body for id: {}", id);
                return null;
            }
            String body = res.getBody().trim();
            // Handle empty array/object response
            if (body.equals("[]") || body.equals("{}")) {
                log.warn("JioSaavn getSongById returned empty for id: {}", id);
                return null;
            }
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            // Response format: { "song_id": { ... song details ... } }
            // Look for the entry matching our ID first
            if (json.has(id)) {
                JsonElement entry = json.get(id);
                if (entry != null && entry.isJsonObject()) {
                    Song s = parseSongObject(entry.getAsJsonObject());
                    if (s != null) return s;
                }
            }

            // Try all entries in the response object
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonObject()) {
                    Song s = parseSongObject(value.getAsJsonObject());
                    if (s != null) return s;
                }
            }

            log.warn("Could not parse JioSaavn song details response for id: {}", id);
            return null;
        } catch (Exception e) {
            log.warn("JioSaavn getSongById error for '{}': {}", id, e.getMessage());
            return null;
        }
    }

    private Song parseSongElement(JsonElement elem) {
        if (elem == null || !elem.isJsonObject()) return null;
        JsonObject obj = elem.getAsJsonObject();
        // search.getResults exposes 'id' at the top level directly
        if (obj.has("id")) {
            return parseSongObject(obj);
        }
        // fallback for other response shapes
        if (obj.has("song") && obj.get("song").isJsonObject()) {
            return parseSongObject(obj.getAsJsonObject("song"));
        }
        return null;
    }

    private Song parseSongObject(JsonObject song) {
        try {
            String rawId = getString(song, "id");
            if (rawId == null || rawId.isBlank()) {
                // Some shapes use 'perma_url' or 'id' inside nested objects
                rawId = getString(song, "songId");
            }
            if (rawId == null || rawId.isBlank()) return null;

            String id = "jio_" + rawId;
            String title = getString(song, "title");
            if ((title == null || title.isBlank()) && song.has("song")) title = getString(song, "song");
            if ((title == null || title.isBlank()) && song.has("name")) title = getString(song, "name");
            title = cleanText(title);
            if (title == null) title = "";

            // Artists
            String artist = "";
            if (song.has("primary_artists") && song.get("primary_artists").isJsonArray()) {
                StringBuilder b = new StringBuilder();
                JsonArray arr = song.getAsJsonArray("primary_artists");
                for (JsonElement a : arr) {
                    String n = getString(a.getAsJsonObject(), "name");
                    if (n != null && !n.isBlank()) {
                        if (b.length() > 0) b.append(", ");
                        b.append(n);
                    }
                }
                artist = b.toString();
            }
            if (artist.isBlank() && song.has("primary_artists") && song.get("primary_artists").isJsonPrimitive()) {
                artist = getString(song, "primary_artists");
                if (artist != null && !artist.isBlank()) System.out.println("parseSongObject: got artist from top-level primary_artists: '" + artist + "'");
            }
            if (artist.isBlank() && song.has("more_info") && song.get("more_info").isJsonObject()) {
                JsonObject more = song.getAsJsonObject("more_info");
                System.out.println("parseSongObject: more_info present for " + rawId + ", keys: " + more.keySet());
                if (more.has("primary_artists")) {
                    artist = getString(more, "primary_artists");
                    System.out.println("parseSongObject: got from more_info.primary_artists: '" + artist + "'");
                }
                if ((artist == null || artist.isBlank()) && more.has("singers")) {
                    artist = getString(more, "singers");
                    System.out.println("parseSongObject: got from more_info.singers: '" + artist + "'");
                }
            }
            if (artist == null || artist.isBlank()) artist = "Unknown Artist";

            String album = cleanText(getString(song, "album"));
            if (album == null) album = "";

            String image = getString(song, "image");
            if (image == null || image.isBlank()) {
                image = getString(song, "album_pic");
                if (image == null) image = "";
            }

            // Duration in seconds
            int duration = 0;
            if (song.has("duration") && !song.get("duration").isJsonNull()) {
                try { duration = Integer.parseInt(song.get("duration").getAsString()); } catch (Exception ignored) {}
            }
            if (duration == 0 && song.has("length") && !song.get("length").isJsonNull()) {
                try { duration = Integer.parseInt(song.get("length").getAsString()); } catch (Exception ignored) {}
            }

            // Audio URL preference chain: encrypted_media_url (full-length) > vlink (preview) > media_preview_url (preview)
            String audioUrl = null;

            // 1. Try encrypted_media_url / encrypted_media_path first (full-length after DES decryption)
            String encryptedUrl = null;
            if (song.has("more_info") && song.get("more_info").isJsonObject()) {
                JsonObject more = song.getAsJsonObject("more_info");
                if (more.has("encrypted_media_url")) encryptedUrl = getString(more, "encrypted_media_url");
                if ((encryptedUrl == null || encryptedUrl.isBlank()) && more.has("encrypted_media_path"))
                    encryptedUrl = getString(more, "encrypted_media_path");
            }
            if (encryptedUrl == null || encryptedUrl.isBlank()) {
                if (song.has("encrypted_media_url")) encryptedUrl = getString(song, "encrypted_media_url");
                if ((encryptedUrl == null || encryptedUrl.isBlank()) && song.has("encrypted_media_path"))
                    encryptedUrl = getString(song, "encrypted_media_path");
            }
            if (encryptedUrl != null && !encryptedUrl.isBlank()) {
                String decrypted = decryptMediaUrl(encryptedUrl);
                if (decrypted != null && !decrypted.isBlank()) {
                    audioUrl = decrypted;
                }
            }

            // 2. Fall back to vlink (preview) if encrypted decryption failed
            if (audioUrl == null || audioUrl.isBlank()) {
                if (song.has("vlink")) audioUrl = getString(song, "vlink");
                if ((audioUrl == null || audioUrl.isBlank()) && song.has("more_info") && song.get("more_info").isJsonObject()) {
                    JsonObject more = song.getAsJsonObject("more_info");
                    if (more.has("vlink")) audioUrl = getString(more, "vlink");
                }
            }

            // 3. Fall back to media_preview_url
            if ((audioUrl == null || audioUrl.isBlank()) && song.has("media_preview_url")) audioUrl = getString(song, "media_preview_url");

            if (audioUrl == null || audioUrl.isBlank()) {
                // fallback marker so callers know it's a Jio entry
                audioUrl = id;
            }

            return new Song(id, title, artist, album, image, duration, audioUrl);
        } catch (Exception e) {
            log.debug("Failed to parse Jio song object: {}", e.getMessage());
            return null;
        }
    }

    private String decryptMediaUrl(String encryptedUrl) {
        if (encryptedUrl == null || encryptedUrl.isBlank()) return null;
        String trimmed = encryptedUrl.trim();
        // If it's already a plain HTTP/HTTPS URL, use it directly
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        try {
            // DES/ECB/PKCS5Padding decryption with key "38346591"
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            SecretKeySpec keySpec = new SecretKeySpec("38346591".getBytes(StandardCharsets.UTF_8), "DES");
            Cipher cipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            byte[] decryptedBytes = cipher.doFinal(decoded);
            String url = new String(decryptedBytes, StandardCharsets.UTF_8).trim();

            // Clean up: trim extra data after .mp4 or .m4a extension
            int mp4Idx = url.indexOf(".mp4");
            if (mp4Idx >= 0) {
                url = url.substring(0, mp4Idx + 4);
            } else {
                int m4aIdx = url.indexOf(".m4a");
                if (m4aIdx >= 0) url = url.substring(0, m4aIdx + 4);
            }

            // Ensure https
            url = url.replace("http:", "https:");

            if (url.startsWith("http")) {
                return url;
            }
            log.warn("Decrypted URL does not start with http: {}", url);
            return null;
        } catch (Exception e) {
            log.debug("Failed to decrypt JioSaavn media URL: {}", e.getMessage());
            return null;
        }
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || key == null) return null;
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        try { return obj.get(key).getAsString(); } catch (Exception e) { return null; }
    }

    private String cleanText(String input) {
        if (input == null) return null;
        return input
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&apos;", "'");
    }
}
