package com.musicsync.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.musicsync.model.Song;

@Service
public class YouTubeService {

    private static final Logger log = LoggerFactory.getLogger(YouTubeService.class);
    private static final String API_URL = "https://www.googleapis.com/youtube/v3";
    private static final String YT_MUSIC_API_URL = "https://yt.lemnoslife.com/noKey";
    private static final String WEB_SEARCH_URL = "https://www.youtube.com/results?search_query=";
    private static final String MUSIC_WEB_SEARCH_URL = "https://music.youtube.com/search?q=";
    private static final String OEMBED_URL = "https://www.youtube.com/oembed?url=";
    private static final Pattern CLOCK_DURATION_PATTERN = Pattern.compile("\\b(?:\\d{1,2}:)?\\d{1,2}:\\d{2}\\b");
    private static final Pattern HOUR_PATTERN = Pattern.compile("(\\d+)\\s*(?:h|hr|hrs|hour|hours)\\b");
    private static final Pattern MINUTE_PATTERN = Pattern.compile("(\\d+)\\s*(?:m|min|mins|minute|minutes)\\b");
    private static final Pattern SECOND_PATTERN = Pattern.compile("(\\d+)\\s*(?:s|sec|secs|second|seconds)\\b");
    private static final Pattern YOUTUBE_API_KEY_PATTERN = Pattern.compile("^AIza[0-9A-Za-z_-]{20,}$");
    private static final int MAX_SEARCH_LIMIT = 200;
    private static final int API_PAGE_SIZE = 50;
    private static final int API_MAX_PAGES = 4;

    @Value("${youtube.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final JioSaavnService jioSaavnService;
    private final Map<String, String> fallbackAudioCache = new ConcurrentHashMap<>();
    private volatile boolean apiKeyUsable = true;
    private volatile boolean noKeyEndpointAvailable = true;

    public YouTubeService(JioSaavnService jioSaavnService) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(1500);
        requestFactory.setReadTimeout(5200);
        this.restTemplate = new RestTemplate(requestFactory);
        this.jioSaavnService = jioSaavnService;
    }

    public boolean isConfigured() {
        // Service remains available via keyless web fallback when API key is absent.
        return true;
    }

    public boolean isApiConfigured() {
        return hasLikelyValidApiKey() && apiKeyUsable;
    }

    private boolean hasLikelyValidApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            return false;
        }
        return YOUTUBE_API_KEY_PATTERN.matcher(apiKey.trim()).matches();
    }

    public List<Song> searchSongs(String query, int limit) {
        List<Song> songs = new ArrayList<>();
        if (query == null || query.isBlank()) return songs;

        int cappedLimit = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        String musicQuery = buildMusicQuery(query);
        Set<String> seenIds = new LinkedHashSet<>();

        List<Song> musicWebSongs = searchSongsFromYouTubeMusicWeb(musicQuery, cappedLimit);
        for (Song song : musicWebSongs) {
            if (!isLikelyMusicResult(song)) {
                continue;
            }
            if (seenIds.add(song.getId())) {
                songs.add(song);
            }
            if (songs.size() >= cappedLimit) {
                return songs;
            }
        }

        if (isApiConfigured()) {
            List<Song> apiSongs = searchSongsWithApi(musicQuery, cappedLimit);
            for (Song song : apiSongs) {
                if (!isLikelyMusicResult(song)) {
                    continue;
                }
                if (seenIds.add(song.getId())) {
                    songs.add(song);
                }
                if (songs.size() >= cappedLimit) {
                    return songs;
                }
            }
        }

        if (songs.size() < cappedLimit && noKeyEndpointAvailable) {
            // Dedicated YouTube Music API path. Keep this as final fallback because DNS/network
            // failures on third-party hosts can block longer than regular YouTube web lookups.
            List<Song> musicApiSongs = searchSongsWithMusicApi(musicQuery, cappedLimit);
            for (Song song : musicApiSongs) {
                if (!isLikelyMusicResult(song)) {
                    continue;
                }
                if (seenIds.add(song.getId())) {
                    songs.add(song);
                }
                if (songs.size() >= cappedLimit) {
                    return songs;
                }
            }
        }

        if (songs.isEmpty()) {
            // Last fallback: use regular YouTube web search with music query and strict filtering.
            List<Song> webSongs = searchSongsFromWeb(musicQuery, cappedLimit);
            for (Song song : webSongs) {
                if (!isLikelyMusicResult(song)) {
                    continue;
                }
                if (seenIds.add(song.getId())) {
                    songs.add(song);
                }
                if (songs.size() >= cappedLimit) {
                    break;
                }
            }
        }

        return songs;
    }

    private List<Song> searchSongsFromYouTubeMusicWeb(String query, int limit) {
        List<Song> songs = new ArrayList<>();

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = restTemplate.getForObject(MUSIC_WEB_SEARCH_URL + encodedQuery, String.class);
            if (response == null || response.isBlank()) return songs;

            String initialData = extractInitialDataJson(response);
            if (initialData == null || initialData.isBlank()) return songs;

            JsonObject dataObj = JsonParser.parseString(initialData).getAsJsonObject();
            List<JsonObject> renderers = new ArrayList<>();
            collectMusicRenderers(dataObj, renderers);

            Set<String> seen = new LinkedHashSet<>();
            for (JsonObject renderer : renderers) {
                Song song = parseMusicRenderer(renderer);
                if (song == null) continue;
                if (seen.add(song.getId())) {
                    songs.add(song);
                }
                if (songs.size() >= limit) break;
            }
        } catch (Exception e) {
            log.warn("YouTube Music web search failed for query '{}': {}", query, e.getMessage());
        }

        return songs;
    }

    private void collectMusicRenderers(JsonElement element, List<JsonObject> renderers) {
        if (element == null || element.isJsonNull()) return;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("musicResponsiveListItemRenderer")
                    && object.get("musicResponsiveListItemRenderer").isJsonObject()) {
                renderers.add(object.getAsJsonObject("musicResponsiveListItemRenderer"));
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collectMusicRenderers(entry.getValue(), renderers);
            }
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectMusicRenderers(item, renderers);
            }
        }
    }

    private Song parseMusicRenderer(JsonObject renderer) {
        String videoId = extractMusicVideoId(renderer);
        if (videoId == null || videoId.isBlank()) return null;

        String title = extractMusicText(renderer, 0);
        if (title == null || title.isBlank()) return null;

        String artist = extractMusicArtist(renderer);
        if (artist == null || artist.isBlank()) {
            artist = "Unknown Artist";
        }

        int duration = extractMusicDuration(renderer);
        String coverUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        String audioUrl = fallbackAudioCache.getOrDefault(videoId, "");

        return new Song("yt_" + videoId, title, artist, "YouTube Music", coverUrl, duration, audioUrl);
    }

    private String extractMusicVideoId(JsonObject renderer) {
        if (renderer == null) return null;

        if (renderer.has("playlistItemData") && renderer.get("playlistItemData").isJsonObject()) {
            String id = getStringField(renderer.getAsJsonObject("playlistItemData"), "videoId");
            if (id != null && !id.isBlank()) return id;
        }

        if (renderer.has("navigationEndpoint") && renderer.get("navigationEndpoint").isJsonObject()) {
            JsonObject endpoint = renderer.getAsJsonObject("navigationEndpoint");
            if (endpoint.has("watchEndpoint") && endpoint.get("watchEndpoint").isJsonObject()) {
                String id = getStringField(endpoint.getAsJsonObject("watchEndpoint"), "videoId");
                if (id != null && !id.isBlank()) return id;
            }
        }

        if (renderer.has("flexColumns") && renderer.get("flexColumns").isJsonArray()) {
            for (JsonElement columnElement : renderer.getAsJsonArray("flexColumns")) {
                if (!columnElement.isJsonObject()) continue;
                JsonObject columnObj = columnElement.getAsJsonObject();
                if (!columnObj.has("musicResponsiveListItemFlexColumnRenderer")) continue;
                JsonObject flex = columnObj.getAsJsonObject("musicResponsiveListItemFlexColumnRenderer");
                if (!flex.has("text") || !flex.get("text").isJsonObject()) continue;
                JsonObject textObj = flex.getAsJsonObject("text");
                if (!textObj.has("runs") || !textObj.get("runs").isJsonArray()) continue;
                for (JsonElement runElement : textObj.getAsJsonArray("runs")) {
                    if (!runElement.isJsonObject()) continue;
                    JsonObject runObj = runElement.getAsJsonObject();
                    if (!runObj.has("navigationEndpoint") || !runObj.get("navigationEndpoint").isJsonObject()) continue;
                    JsonObject endpoint = runObj.getAsJsonObject("navigationEndpoint");
                    if (!endpoint.has("watchEndpoint") || !endpoint.get("watchEndpoint").isJsonObject()) continue;
                    String id = getStringField(endpoint.getAsJsonObject("watchEndpoint"), "videoId");
                    if (id != null && !id.isBlank()) return id;
                }
            }
        }

        return null;
    }

    private String extractMusicText(JsonObject renderer, int columnIndex) {
        if (renderer == null || !renderer.has("flexColumns") || !renderer.get("flexColumns").isJsonArray()) {
            return null;
        }
        JsonArray columns = renderer.getAsJsonArray("flexColumns");
        if (columns.size() <= columnIndex || !columns.get(columnIndex).isJsonObject()) {
            return null;
        }
        JsonObject col = columns.get(columnIndex).getAsJsonObject();
        if (!col.has("musicResponsiveListItemFlexColumnRenderer")
                || !col.get("musicResponsiveListItemFlexColumnRenderer").isJsonObject()) {
            return null;
        }
        JsonObject flex = col.getAsJsonObject("musicResponsiveListItemFlexColumnRenderer");
        return getTextField(flex.get("text"));
    }

    private String extractMusicArtist(JsonObject renderer) {
        String text = extractMusicText(renderer, 1);
        if (text == null || text.isBlank()) return null;
        String[] parts = text.split("\\u2022");
        String artist = parts.length > 0 ? parts[0].trim() : text.trim();
        return artist.isBlank() ? text.trim() : artist;
    }

    private int extractMusicDuration(JsonObject renderer) {
        if (renderer == null) return 0;

        // Many YT Music results expose duration in subtitle/accessibility instead of fixedColumns.
        int subtitleDuration = extractDurationFromText(getTextField(renderer.get("subtitle")));
        if (subtitleDuration > 0) return subtitleDuration;

        if (renderer.has("accessibility") && renderer.get("accessibility").isJsonObject()) {
            JsonObject accessibility = renderer.getAsJsonObject("accessibility");
            if (accessibility.has("accessibilityData") && accessibility.get("accessibilityData").isJsonObject()) {
                String label = getStringField(accessibility.getAsJsonObject("accessibilityData"), "label");
                int accessibilityDuration = extractDurationFromText(label);
                if (accessibilityDuration > 0) return accessibilityDuration;
            }
        }

        if (!renderer.has("fixedColumns") || !renderer.get("fixedColumns").isJsonArray()) {
            return 0;
        }

        JsonArray fixedColumns = renderer.getAsJsonArray("fixedColumns");
        for (JsonElement element : fixedColumns) {
            if (!element.isJsonObject()) continue;
            JsonObject obj = element.getAsJsonObject();
            if (!obj.has("musicResponsiveListItemFixedColumnRenderer")
                    || !obj.get("musicResponsiveListItemFixedColumnRenderer").isJsonObject()) {
                continue;
            }
            JsonObject fixed = obj.getAsJsonObject("musicResponsiveListItemFixedColumnRenderer");
            String text = getTextField(fixed.get("text"));
            int duration = parseDurationLabel(text);
            if (duration > 0) return duration;
        }

        // Last resort: inspect the serialized renderer for embedded duration markers.
        int parsedFromJson = parseDurationLabel(renderer.toString());
        if (parsedFromJson > 0) return parsedFromJson;

        return 0;
    }

    private int extractDurationFromText(String text) {
        if (text == null || text.isBlank()) return 0;

        String[] tokens = text.split("[•·|,/]");
        for (int i = tokens.length - 1; i >= 0; i--) {
            String token = tokens[i].trim();
            int parsed = parseDurationLabel(token);
            if (parsed > 0) return parsed;
        }

        return parseDurationLabel(text);
    }

    private List<Song> searchSongsWithMusicApi(String query, int limit) {
        List<Song> songs = new ArrayList<>();

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            int pageSize = Math.min(API_PAGE_SIZE, Math.max(1, limit));
            String nextPageToken = null;

            for (int page = 0; page < API_MAX_PAGES && songs.size() < limit; page++) {
                String url = YT_MUSIC_API_URL + "/search?part=snippet&type=video&videoCategoryId=10"
                        + "&maxResults=" + pageSize + "&q=" + encodedQuery;
                if (nextPageToken != null && !nextPageToken.isBlank()) {
                    url += "&pageToken=" + URLEncoder.encode(nextPageToken, StandardCharsets.UTF_8);
                }

                String response = restTemplate.getForObject(url, String.class);
                if (response == null || response.isBlank()) return songs;

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonArray items = json.has("items") ? json.getAsJsonArray("items") : null;
                if (items == null || items.isEmpty()) return songs;

                for (int i = 0; i < items.size() && songs.size() < limit; i++) {
                    JsonObject item = items.get(i).getAsJsonObject();
                    Song song = parseSearchItem(item);
                    if (song != null) {
                        songs.add(song);
                    }
                }

                nextPageToken = getStringField(json, "nextPageToken");
                if (nextPageToken == null || nextPageToken.isBlank()) {
                    break;
                }
            }
        } catch (Exception e) {
            noKeyEndpointAvailable = false;
            log.warn("YouTube Music API search failed for query '{}': {}", query, e.getMessage());
        }

        return songs;
    }

    private String buildMusicQuery(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) {
            return normalized;
        }
        String lower = normalized.toLowerCase();
        if (lower.contains(" audio") || lower.contains(" song") || lower.contains(" track")) {
            return normalized;
        }
        return normalized + " audio";
    }

    private boolean isLikelyMusicResult(Song song) {
        if (song == null) return false;
        String title = song.getTitle() == null ? "" : song.getTitle().toLowerCase();

        String[] blockedTitleTokens = {
            "reaction", "trailer", "teaser", "vlog", "shorts"
        };
        for (String token : blockedTitleTokens) {
            if (title.contains(token)) {
                return false;
            }
        }

        return true;
    }

    private List<Song> searchSongsWithApi(String query, int limit) {
        List<Song> songs = new ArrayList<>();

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            int pageSize = Math.min(API_PAGE_SIZE, Math.max(1, limit));
            String nextPageToken = null;

            for (int page = 0; page < API_MAX_PAGES && songs.size() < limit; page++) {
                String url = API_URL + "/search?part=snippet&type=video&videoCategoryId=10&maxResults=" + pageSize
                        + "&q=" + encodedQuery + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
                if (nextPageToken != null && !nextPageToken.isBlank()) {
                    url += "&pageToken=" + URLEncoder.encode(nextPageToken, StandardCharsets.UTF_8);
                }

                String response = restTemplate.getForObject(url, String.class);
                if (response == null) return songs;

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonArray items = json.has("items") ? json.getAsJsonArray("items") : null;
                if (items == null || items.isEmpty()) return songs;

                for (int i = 0; i < items.size() && songs.size() < limit; i++) {
                    JsonObject item = items.get(i).getAsJsonObject();
                    Song song = parseSearchItem(item);
                    if (song != null) {
                        songs.add(song);
                    }
                }

                nextPageToken = getStringField(json, "nextPageToken");
                if (nextPageToken == null || nextPageToken.isBlank()) {
                    break;
                }
            }
        } catch (Exception e) {
            if (looksLikeInvalidApiKey(e)) {
                apiKeyUsable = false;
            }
            log.warn("YouTube Data API search failed for query '{}': {}", query, e.getMessage());
        }

        return songs;
    }

    private List<Song> searchSongsFromWeb(String query, int limit) {
        List<Song> songs = new ArrayList<>();

        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = restTemplate.getForObject(WEB_SEARCH_URL + encodedQuery, String.class);
            if (response == null || response.isBlank()) return songs;

            String initialData = extractInitialDataJson(response);
            if (initialData == null || initialData.isBlank()) return songs;

            JsonObject initialDataObject = JsonParser.parseString(initialData).getAsJsonObject();
            List<JsonObject> renderers = new ArrayList<>();
            collectVideoRenderers(initialDataObject, renderers);

            Set<String> seenVideoIds = new LinkedHashSet<>();
            for (JsonObject renderer : renderers) {
                Song song = parseVideoRenderer(renderer);
                if (song == null) continue;
                if (seenVideoIds.add(song.getId())) {
                    songs.add(song);
                }
                if (songs.size() >= limit) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("YouTube web fallback search failed for query '{}': {}", query, e.getMessage());
        }

        return songs;
    }

    public Song getSongById(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;

        if (isApiConfigured()) {
            try {
                String url = API_URL + "/videos?part=snippet,contentDetails&id="
                        + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
                        + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

                String response = restTemplate.getForObject(url, String.class);
                if (response != null) {
                    JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                    JsonArray items = json.has("items") ? json.getAsJsonArray("items") : null;
                    if (items != null && !items.isEmpty()) {
                        JsonObject item = items.get(0).getAsJsonObject();
                        Song song = parseVideoItem(item, videoId);
                        if (song != null) {
                            return song;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("YouTube Data API getSongById failed for id '{}': {}", videoId, e.getMessage());
            }
        }

        return getSongByIdFromOEmbed(videoId);
    }

    public Song resolveSongFromMetadata(String videoId,
                                        String title,
                                        String artist,
                                        String coverUrl,
                                        int durationSeconds) {
        if (videoId == null || videoId.isBlank()) return null;
        if (title == null || title.isBlank()) return null;

        String normalizedArtist = (artist == null || artist.isBlank()) ? "Unknown Artist" : artist;
        String normalizedCoverUrl = coverUrl == null ? "" : coverUrl;
        int normalizedDuration = Math.max(0, durationSeconds);

        String audioUrl = resolveFallbackAudio(videoId, title, normalizedArtist);
        if (audioUrl == null || audioUrl.isBlank()) {
            return null;
        }

        return new Song("yt_" + videoId,
                title,
                normalizedArtist,
                "YouTube Music",
                normalizedCoverUrl,
                normalizedDuration,
                audioUrl);
    }

    private Song getSongByIdFromOEmbed(String videoId) {
        try {
            String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
            String url = OEMBED_URL + URLEncoder.encode(watchUrl, StandardCharsets.UTF_8) + "&format=json";

            String response = restTemplate.getForObject(url, String.class);
            if (response == null) return null;

            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            String title = getStringField(json, "title");
            if (title == null || title.isBlank()) return null;

            String artist = getStringField(json, "author_name");
            if (artist == null || artist.isBlank()) artist = "Unknown Artist";

            String coverUrl = getStringField(json, "thumbnail_url");
            if (coverUrl == null) coverUrl = "";

            String audioUrl = resolveFallbackAudio(videoId, title, artist);
            if (audioUrl == null || audioUrl.isBlank()) return null;

            return new Song("yt_" + videoId, title, artist, "YouTube Music", coverUrl, 0, audioUrl);
        } catch (Exception e) {
            log.warn("YouTube oEmbed getSongById failed for id '{}': {}", videoId, e.getMessage());
            return null;
        }
    }

    private Song parseSearchItem(JsonObject item) {
        if (!item.has("id") || !item.get("id").isJsonObject()) return null;
        JsonObject idObj = item.getAsJsonObject("id");
        String videoId = getStringField(idObj, "videoId");
        if (videoId == null || videoId.isEmpty()) return null;

        if (!item.has("snippet") || !item.get("snippet").isJsonObject()) return null;
        JsonObject snippet = item.getAsJsonObject("snippet");

        String title = getStringField(snippet, "title");
        if (title == null || title.isEmpty()) return null;

        String artist = getStringField(snippet, "channelTitle");
        if (artist == null || artist.isEmpty()) artist = "Unknown Artist";

        int duration = extractDurationFromSearchItem(item);
        String coverUrl = getThumbnailUrl(snippet);
        String audioUrl = fallbackAudioCache.getOrDefault(videoId, "");

        return new Song("yt_" + videoId, title, artist, "YouTube Music", coverUrl, duration, audioUrl);
    }

    private int extractDurationFromSearchItem(JsonObject item) {
        if (item == null) return 0;

        if (item.has("contentDetails") && item.get("contentDetails").isJsonObject()) {
            String isoDuration = getStringField(item.getAsJsonObject("contentDetails"), "duration");
            int parsedIso = parseDurationSeconds(isoDuration);
            if (parsedIso > 0) return parsedIso;
        }

        if (item.has("duration") && !item.get("duration").isJsonNull()) {
            int parsed = parseDurationLabel(item.get("duration").getAsString());
            if (parsed > 0) return parsed;
        }

        if (item.has("lengthText") && item.get("lengthText").isJsonObject()) {
            int parsed = parseDurationLabel(getTextField(item.get("lengthText")));
            if (parsed > 0) return parsed;
        }

        if (item.has("lengthSeconds") && !item.get("lengthSeconds").isJsonNull()) {
            try {
                int parsed = Integer.parseInt(item.get("lengthSeconds").getAsString());
                if (parsed > 0) return parsed;
            } catch (NumberFormatException e) {
                // Ignore malformed duration values and fall through to 0.
            }
        }

        return 0;
    }

    private Song parseVideoRenderer(JsonObject renderer) {
        String videoId = getStringField(renderer, "videoId");
        if (videoId == null || videoId.isBlank()) return null;

        String title = getTextField(renderer.get("title"));
        if (title == null || title.isBlank()) return null;

        String artist = getTextField(renderer.get("ownerText"));
        if (artist == null || artist.isBlank()) {
            artist = getTextField(renderer.get("longBylineText"));
        }
        if (artist == null || artist.isBlank()) {
            artist = "Unknown Artist";
        }

        int duration = parseDurationLabel(getTextField(renderer.get("lengthText")));
        String coverUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        String audioUrl = fallbackAudioCache.getOrDefault(videoId, "");

        return new Song("yt_" + videoId, title, artist, "YouTube Music", coverUrl, duration, audioUrl);
    }

    private Song parseVideoItem(JsonObject item, String videoId) {
        if (!item.has("snippet") || !item.get("snippet").isJsonObject()) return null;
        JsonObject snippet = item.getAsJsonObject("snippet");

        String title = getStringField(snippet, "title");
        if (title == null || title.isEmpty()) return null;

        String artist = getStringField(snippet, "channelTitle");
        if (artist == null || artist.isEmpty()) artist = "Unknown Artist";

        int duration = 0;
        if (item.has("contentDetails") && item.get("contentDetails").isJsonObject()) {
            String isoDuration = getStringField(item.getAsJsonObject("contentDetails"), "duration");
            duration = parseDurationSeconds(isoDuration);
        }

        String coverUrl = getThumbnailUrl(snippet);
        String audioUrl = resolveFallbackAudio(videoId, title, artist);
        if (audioUrl == null || audioUrl.isEmpty()) return null;

        return new Song("yt_" + videoId, title, artist, "YouTube Music", coverUrl, duration, audioUrl);
    }

    private void collectVideoRenderers(JsonElement element, List<JsonObject> renderers) {
        if (element == null || element.isJsonNull()) return;

        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("videoRenderer") && object.get("videoRenderer").isJsonObject()) {
                renderers.add(object.getAsJsonObject("videoRenderer"));
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                collectVideoRenderers(entry.getValue(), renderers);
            }
            return;
        }

        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                collectVideoRenderers(item, renderers);
            }
        }
    }

    private String extractInitialDataJson(String html) {
        if (html == null || html.isBlank()) return null;

        String[] markers = {
            "var ytInitialData = ",
            "window[\"ytInitialData\"] = ",
            "ytInitialData = "
        };

        int startIndex = -1;
        for (String marker : markers) {
            int markerIndex = html.indexOf(marker);
            if (markerIndex >= 0) {
                startIndex = markerIndex + marker.length();
                break;
            }
        }

        if (startIndex < 0) return null;

        int jsonStart = html.indexOf('{', startIndex);
        if (jsonStart < 0) return null;

        int depth = 0;
        boolean inString = false;
        boolean escape = false;

        for (int i = jsonStart; i < html.length(); i++) {
            char c = html.charAt(i);

            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
                continue;
            }

            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return html.substring(jsonStart, i + 1);
                }
            }
        }

        return null;
    }

    private String getTextField(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;

        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }

        if (!element.isJsonObject()) return null;
        JsonObject object = element.getAsJsonObject();

        String simpleText = getStringField(object, "simpleText");
        if (simpleText != null && !simpleText.isBlank()) {
            return simpleText;
        }

        if (object.has("runs") && object.get("runs").isJsonArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonElement runElement : object.getAsJsonArray("runs")) {
                if (!runElement.isJsonObject()) continue;
                String runText = getStringField(runElement.getAsJsonObject(), "text");
                if (runText == null || runText.isBlank()) continue;
                if (text.length() > 0) text.append(' ');
                text.append(runText);
            }
            return text.length() == 0 ? null : text.toString();
        }

        return null;
    }

    private int parseDurationLabel(String durationLabel) {
        if (durationLabel == null || durationLabel.isBlank()) return 0;

        String trimmed = durationLabel.trim();

        // Extract common clock formats even when embedded in larger text.
        Matcher clockMatcher = CLOCK_DURATION_PATTERN.matcher(trimmed);
        if (clockMatcher.find()) {
            String[] parts = clockMatcher.group().split(":");
            int total = 0;
            for (String part : parts) {
                if (part.isBlank()) return 0;
                try {
                    total = total * 60 + Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return total;
        }

        // Handle labels like "3 minutes 28 seconds".
        String lower = trimmed.toLowerCase();
        int hours = extractUnitValue(HOUR_PATTERN, lower);
        int minutes = extractUnitValue(MINUTE_PATTERN, lower);
        int seconds = extractUnitValue(SECOND_PATTERN, lower);
        int textDuration = (hours * 3600) + (minutes * 60) + seconds;
        if (textDuration > 0) {
            return textDuration;
        }

        String normalized = trimmed.replaceAll("[^0-9:]", "");
        if (normalized.isBlank() || !normalized.contains(":")) return 0;

        String[] parts = normalized.split(":");
        int total = 0;
        for (String part : parts) {
            if (part.isBlank()) return 0;
            try {
                total = total * 60 + Integer.parseInt(part);
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        return total;
    }

    private int extractUnitValue(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private String resolveFallbackAudio(String videoId, String title, String artist) {
        String cached = fallbackAudioCache.get(videoId);
        if (cached != null) {
            return cached;
        }

        try {
            String rawTitle = title == null ? "" : title.trim();
            String rawArtist = artist == null ? "" : artist.trim();
            String cleanedTitle = cleanTitleForFallback(rawTitle);

            Set<String> queries = new LinkedHashSet<>();
            if (!rawTitle.isBlank() || !rawArtist.isBlank()) {
                queries.add((rawTitle + " " + rawArtist).trim());
            }
            if (!cleanedTitle.isBlank() && !rawArtist.isBlank()) {
                queries.add((cleanedTitle + " " + rawArtist).trim());
            }
            if (!cleanedTitle.isBlank()) {
                queries.add(cleanedTitle);
                String shortTitle = firstWords(cleanedTitle, 5);
                if (!shortTitle.isBlank()) {
                    queries.add(shortTitle);
                }
            }

            if (queries.isEmpty()) {
                fallbackAudioCache.put(videoId, "");
                return "";
            }

            String titleNorm = normalizeForMatch(cleanedTitle.isBlank() ? rawTitle : cleanedTitle);
            String artistNorm = normalizeForMatch(rawArtist);

            Song bestCandidate = null;
            int bestScore = Integer.MIN_VALUE;

            for (String query : queries) {
                if (query == null || query.isBlank()) continue;

                List<Song> candidates = jioSaavnService.searchSongs(query, 6);
                for (Song candidate : candidates) {
                    if (candidate == null) continue;

                    String candidateAudio = candidate.getAudioUrl();
                    if (candidateAudio == null || candidateAudio.isBlank()) {
                        continue;
                    }

                    int score = computeFallbackScore(candidate, titleNorm, artistNorm);
                    if (score > bestScore) {
                        bestScore = score;
                        bestCandidate = candidate;
                    }
                }
            }

            if (bestCandidate != null && bestCandidate.getAudioUrl() != null && !bestCandidate.getAudioUrl().isBlank()) {
                String fallbackUrl = bestCandidate.getAudioUrl();
                // If the candidate URL is a preview or the candidate has no encrypted URL,
                // try to resolve the full-length song via getSongById
                if (fallbackUrl.contains("preview") || fallbackUrl.contains("media_preview")
                        || (bestCandidate.getDurationSeconds() > 0 && bestCandidate.getDurationSeconds() < 30)
                        || bestCandidate.getId().startsWith("jio_")) {
                    try {
                        String jioId = bestCandidate.getId();
                        if (jioId != null && jioId.startsWith("jio_")) {
                            Song fullSong = jioSaavnService.getSongById(jioId.substring(4));
                            if (fullSong != null && fullSong.getAudioUrl() != null
                                    && !fullSong.getAudioUrl().isBlank()
                                    && !fullSong.getAudioUrl().contains("preview")
                                    && !fullSong.getAudioUrl().equals(jioId)) {
                                fallbackUrl = fullSong.getAudioUrl();
                            }
                        }
                    } catch (Exception ignored) {}
                }
                fallbackAudioCache.put(videoId, fallbackUrl);
                return fallbackUrl;
            }
        } catch (Exception e) {
            log.debug("YouTube fallback audio lookup failed for '{}': {}", videoId, e.getMessage());
        }

        fallbackAudioCache.put(videoId, "");
        return "";
    }

    private String cleanTitleForFallback(String title) {
        if (title == null || title.isBlank()) return "";

        String cleaned = title;
        cleaned = cleaned.replaceAll("(?i)\\(.*?\\)|\\[.*?\\]", " ");
        cleaned = cleaned.replaceAll("(?i)official\\s+(music\\s+video|video|audio|lyric\\s+video|lyrics)", " ");
        cleaned = cleaned.replaceAll("(?i)lyrics?|full\\s*song|audio|video|hd|4k|remaster(ed)?", " ");
        cleaned = cleaned.replaceAll("(?i)\\bfeat\\.?\\b|\\bft\\.?\\b", " ");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9\\s]", " ");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();
        return cleaned;
    }

    private String firstWords(String text, int maxWords) {
        if (text == null || text.isBlank() || maxWords <= 0) return "";

        String[] words = text.trim().split("\\s+");
        if (words.length <= maxWords) return text.trim();

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < maxWords; i++) {
            if (i > 0) builder.append(' ');
            builder.append(words[i]);
        }
        return builder.toString();
    }

    private String normalizeForMatch(String value) {
        if (value == null || value.isBlank()) return "";
        return value.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private int computeFallbackScore(Song candidate, String titleNorm, String artistNorm) {
        String candidateTitle = normalizeForMatch(candidate.getTitle());
        String candidateArtist = normalizeForMatch(candidate.getArtist());

        int score = 0;

        if (!titleNorm.isBlank() && !candidateTitle.isBlank()) {
            if (candidateTitle.equals(titleNorm)) {
                score += 80;
            } else if (candidateTitle.contains(titleNorm) || titleNorm.contains(candidateTitle)) {
                score += 55;
            }

            score += Math.min(sharedWordCount(titleNorm, candidateTitle) * 8, 32);
        }

        if (!artistNorm.isBlank() && !candidateArtist.isBlank()) {
            if (candidateArtist.contains(artistNorm) || artistNorm.contains(candidateArtist)) {
                score += 22;
            }

            score += Math.min(sharedWordCount(artistNorm, candidateArtist) * 6, 18);
        }

        int duration = candidate.getDurationSeconds();
        if (duration > 30) {
            score += 4;
        }

        return score;
    }

    private int sharedWordCount(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return 0;
        }

        Set<String> leftWords = new LinkedHashSet<>(List.of(left.split("\\s+")));
        Set<String> rightWords = new LinkedHashSet<>(List.of(right.split("\\s+")));
        leftWords.removeIf(word -> word.length() <= 1);
        rightWords.removeIf(word -> word.length() <= 1);

        if (leftWords.isEmpty() || rightWords.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (String word : leftWords) {
            if (rightWords.contains(word)) {
                count++;
            }
        }
        return count;
    }

    private int parseDurationSeconds(String isoDuration) {
        if (isoDuration == null || isoDuration.isBlank()) return 0;
        try {
            return (int) Duration.parse(isoDuration).getSeconds();
        } catch (Exception e) {
            return 0;
        }
    }

    private String getThumbnailUrl(JsonObject snippet) {
        if (!snippet.has("thumbnails") || !snippet.get("thumbnails").isJsonObject()) return "";
        JsonObject thumbnails = snippet.getAsJsonObject("thumbnails");

        String[] keys = { "high", "medium", "default" };
        for (String key : keys) {
            if (thumbnails.has(key) && thumbnails.get(key).isJsonObject()) {
                String url = getStringField(thumbnails.getAsJsonObject(key), "url");
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }
        }
        return "";
    }

    private String getStringField(JsonObject obj, String field) {
        if (obj != null && obj.has(field) && !obj.get(field).isJsonNull()) {
            return obj.get(field).getAsString();
        }
        return null;
    }

    // ===== YouTube Video Content (ytv_ prefix) =====

    public List<Song> searchVideoContent(String query, int limit) {
        List<Song> songs = new ArrayList<>();
        if (query == null || query.isBlank()) return songs;

        int cappedLimit = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        Set<String> seenIds = new LinkedHashSet<>();

        if (isApiConfigured()) {
            List<Song> apiSongs = searchVideoContentWithApi(query, cappedLimit);
            for (Song song : apiSongs) {
                if (seenIds.add(song.getId())) songs.add(song);
                if (songs.size() >= cappedLimit) return songs;
            }
        }

        List<Song> webSongs = searchVideoContentFromWeb(query, cappedLimit);
        for (Song song : webSongs) {
            if (seenIds.add(song.getId())) songs.add(song);
            if (songs.size() >= cappedLimit) break;
        }

        return songs;
    }

    private List<Song> searchVideoContentWithApi(String query, int limit) {
        List<Song> songs = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            int pageSize = Math.min(API_PAGE_SIZE, Math.max(1, limit));
            String nextPageToken = null;

            for (int page = 0; page < API_MAX_PAGES && songs.size() < limit; page++) {
                String url = API_URL + "/search?part=snippet&type=video&maxResults=" + pageSize
                        + "&videoEmbeddable=true&videoSyndicated=true"
                        + "&q=" + encodedQuery + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
                if (nextPageToken != null && !nextPageToken.isBlank()) {
                    url += "&pageToken=" + URLEncoder.encode(nextPageToken, StandardCharsets.UTF_8);
                }

                String response = restTemplate.getForObject(url, String.class);
                if (response == null) return songs;
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonArray items = json.has("items") ? json.getAsJsonArray("items") : null;
                if (items == null || items.isEmpty()) return songs;
                for (int i = 0; i < items.size() && songs.size() < limit; i++) {
                    JsonObject item = items.get(i).getAsJsonObject();
                    Song song = parseVideoContentSearchItem(item);
                    if (song != null) songs.add(song);
                }

                nextPageToken = getStringField(json, "nextPageToken");
                if (nextPageToken == null || nextPageToken.isBlank()) {
                    break;
                }
            }

            // Batch-fetch durations for video content from API
            if (!songs.isEmpty()) {
                List<String> videoIds = new ArrayList<>();
                for (Song s : songs) {
                    if (s != null && s.getId() != null && s.getId().startsWith("ytv_")) {
                        videoIds.add(s.getId().substring(4));
                    }
                }
                if (!videoIds.isEmpty()) {
                    Map<String, Integer> durations = fetchVideoDurations(videoIds);
                    for (Song s : songs) {
                        if (s != null && s.getId() != null && s.getId().startsWith("ytv_")) {
                            String vid = s.getId().substring(4);
                            Integer dur = durations.get(vid);
                            if (dur != null && dur > 0) {
                                s.setDurationSeconds(dur);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            if (looksLikeInvalidApiKey(e)) {
                apiKeyUsable = false;
            }
            log.warn("YouTube Data API video content search failed for query '{}': {}", query, e.getMessage());
        }
        return songs;
    }

    private Map<String, Integer> fetchVideoDurations(List<String> videoIds) {
        Map<String, Integer> durations = new HashMap<>();
        if (videoIds == null || videoIds.isEmpty() || !isApiConfigured()) return durations;

        try {
            for (int start = 0; start < videoIds.size(); start += 50) {
                int end = Math.min(start + 50, videoIds.size());
                String ids = String.join(",", videoIds.subList(start, end));
                String url = API_URL + "/videos?part=contentDetails&id="
                        + URLEncoder.encode(ids, StandardCharsets.UTF_8)
                        + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

                String response = restTemplate.getForObject(url, String.class);
                if (response == null) continue;

                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                JsonArray items = json.has("items") ? json.getAsJsonArray("items") : null;
                if (items == null) continue;

                for (JsonElement item : items) {
                    if (!item.isJsonObject()) continue;
                    JsonObject obj = item.getAsJsonObject();
                    String vid = getStringField(obj, "id");
                    if (vid == null || vid.isBlank()) continue;
                    if (!obj.has("contentDetails") || !obj.get("contentDetails").isJsonObject()) continue;
                    String isoDuration = getStringField(obj.getAsJsonObject("contentDetails"), "duration");
                    int duration = parseDurationSeconds(isoDuration);
                    if (duration > 0) {
                        durations.put(vid, duration);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch video durations: {}", e.getMessage());
        }

        return durations;
    }

    private boolean looksLikeInvalidApiKey(Exception e) {
        if (e == null || e.getMessage() == null) {
            return false;
        }
        String message = e.getMessage().toLowerCase();
        return message.contains("api key not valid") || message.contains("api_key_invalid");
    }

    private List<Song> searchVideoContentFromWeb(String query, int limit) {
        List<Song> songs = new ArrayList<>();
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String response = restTemplate.getForObject(WEB_SEARCH_URL + encodedQuery, String.class);
            if (response == null || response.isBlank()) return songs;
            String initialData = extractInitialDataJson(response);
            if (initialData == null || initialData.isBlank()) return songs;
            JsonObject initialDataObject = JsonParser.parseString(initialData).getAsJsonObject();
            List<JsonObject> renderers = new ArrayList<>();
            collectVideoRenderers(initialDataObject, renderers);
            Set<String> seenVideoIds = new LinkedHashSet<>();
            for (JsonObject renderer : renderers) {
                Song song = parseVideoContentRenderer(renderer);
                if (song == null) continue;
                if (seenVideoIds.add(song.getId())) songs.add(song);
                if (songs.size() >= limit) break;
            }
        } catch (Exception e) {
            log.warn("YouTube web fallback video content search failed for query '{}': {}", query, e.getMessage());
        }
        return songs;
    }

    private Song parseVideoContentSearchItem(JsonObject item) {
        if (!item.has("id") || !item.get("id").isJsonObject()) return null;
        JsonObject idObj = item.getAsJsonObject("id");
        String videoId = getStringField(idObj, "videoId");
        if (videoId == null || videoId.isEmpty()) return null;
        if (!item.has("snippet") || !item.get("snippet").isJsonObject()) return null;
        JsonObject snippet = item.getAsJsonObject("snippet");
        String title = getStringField(snippet, "title");
        if (title == null || title.isEmpty()) return null;
        String artist = getStringField(snippet, "channelTitle");
        if (artist == null || artist.isEmpty()) artist = "YouTube";
        String coverUrl = getThumbnailUrl(snippet);
        return new Song("ytv_" + videoId, title, artist, "YouTube Video", coverUrl, 0, "");
    }

    private Song parseVideoContentRenderer(JsonObject renderer) {
        String videoId = getStringField(renderer, "videoId");
        if (videoId == null || videoId.isBlank()) return null;
        String title = getTextField(renderer.get("title"));
        if (title == null || title.isBlank()) return null;
        String artist = getTextField(renderer.get("ownerText"));
        if (artist == null || artist.isBlank()) artist = getTextField(renderer.get("longBylineText"));
        if (artist == null || artist.isBlank()) artist = "YouTube";
        int duration = parseDurationLabel(getTextField(renderer.get("lengthText")));
        String coverUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return new Song("ytv_" + videoId, title, artist, "YouTube Video", coverUrl, duration, "");
    }

    public Song getVideoContentById(String videoId) {
        if (videoId == null || videoId.isBlank()) return null;

        // Try API first for full details including duration
        if (isApiConfigured()) {
            try {
                String url = API_URL + "/videos?part=snippet,contentDetails&id="
                        + URLEncoder.encode(videoId, StandardCharsets.UTF_8)
                        + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
                String response = restTemplate.getForObject(url, String.class);
                if (response != null) {
                    JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                    JsonArray items = json.has("items") ? json.getAsJsonArray("items") : null;
                    if (items != null && !items.isEmpty()) {
                        JsonObject item = items.get(0).getAsJsonObject();
                        if (item.has("snippet") && item.get("snippet").isJsonObject()) {
                            JsonObject snippet = item.getAsJsonObject("snippet");
                            String title = getStringField(snippet, "title");
                            String artist = getStringField(snippet, "channelTitle");
                            String coverUrl = getThumbnailUrl(snippet);
                            int duration = 0;
                            if (item.has("contentDetails") && item.get("contentDetails").isJsonObject()) {
                                String isoDuration = getStringField(item.getAsJsonObject("contentDetails"), "duration");
                                duration = parseDurationSeconds(isoDuration);
                            }
                            if (title != null && !title.isBlank()) {
                                if (artist == null || artist.isBlank()) artist = "YouTube";
                                if (coverUrl == null || coverUrl.isBlank()) {
                                    coverUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                                }
                                return new Song("ytv_" + videoId, title, artist, "YouTube Video", coverUrl, duration, "");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("YouTube Data API getVideoContentById failed for id '{}': {}", videoId, e.getMessage());
            }
        }

        // Fallback: oEmbed for title and thumbnail
        try {
            String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
            String url = OEMBED_URL + URLEncoder.encode(watchUrl, StandardCharsets.UTF_8) + "&format=json";
            String response = restTemplate.getForObject(url, String.class);
            if (response != null) {
                JsonObject json = JsonParser.parseString(response).getAsJsonObject();
                String title = getStringField(json, "title");
                if (title != null && !title.isBlank()) {
                    String artist = getStringField(json, "author_name");
                    if (artist == null || artist.isBlank()) artist = "YouTube";
                    String coverUrl = getStringField(json, "thumbnail_url");
                    if (coverUrl == null) coverUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                    return new Song("ytv_" + videoId, title, artist, "YouTube Video", coverUrl, 0, "");
                }
            }
        } catch (Exception e) {
            log.warn("YouTube oEmbed getVideoContentById failed for id '{}': {}", videoId, e.getMessage());
        }

        // Scrape watch page for duration as last resort
        try {
            String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
            String html = restTemplate.getForObject(watchUrl, String.class);
            if (html != null) {
                String title = null;
                int duration = 0;

                // Try to extract title
                java.util.regex.Matcher titleMatcher = java.util.regex.Pattern.compile("<title>(.*?)</title>").matcher(html);
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1).replace(" - YouTube", "").trim();
                }

                // Try to extract duration from ytInitialPlayerResponse
                java.util.regex.Pattern durationPattern = java.util.regex.Pattern.compile("\"lengthSeconds\":\"?(\\d+)\"?");
                java.util.regex.Matcher durMatcher = durationPattern.matcher(html);
                if (durMatcher.find()) {
                    try {
                        duration = Integer.parseInt(durMatcher.group(1));
                    } catch (NumberFormatException ignored) {}
                }

                if (title != null && !title.isBlank()) {
                    return new Song("ytv_" + videoId, title, "YouTube", "YouTube Video",
                            "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg", duration, "");
                }
            }
        } catch (Exception e) {
            log.warn("YouTube web scrape getVideoContentById failed for id '{}': {}", videoId, e.getMessage());
        }

        String coverUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        return new Song("ytv_" + videoId, "YouTube Video", "YouTube", "YouTube Video", coverUrl, 0, "");
    }
}
