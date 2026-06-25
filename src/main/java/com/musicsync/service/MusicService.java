package com.musicsync.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.musicsync.model.Song;

@Service
public class MusicService {

    private static final int SEARCH_PROVIDER_LIMIT_CAP = 200;  // Raised to fetch more from each provider
    private static final int SEARCH_JIO_TIMEOUT_MS = 5000;
    private static final int SEARCH_YOUTUBE_TIMEOUT_MS = 15000;
    private static final long SEARCH_CACHE_TTL_MS = 30_000;
    private static final String SEARCH_RESULT_VERSION = "search-relevance-v4";
    private static final Pattern SEARCH_NOISE_PATTERN = Pattern.compile("\\b(song|songs|audio|video|videos|music|lyric|lyrics|official|full)\\b", Pattern.CASE_INSENSITIVE);

    private final List<Song> library = new ArrayList<>();
    private final Map<String, Song> externalSongsCache = new ConcurrentHashMap<>();
    private final Map<String, CachedExternalSearch> externalSearchCache = new ConcurrentHashMap<>();
    private final JioSaavnService jioSaavnService;
    private final OfficialJioSaavnApiClient officialJioSaavnApiClient;
    private final YouTubeService youTubeService;

    private static final class CachedExternalSearch {
        private final long cachedAt;
        private final List<Song> results;

        private CachedExternalSearch(long cachedAt, List<Song> results) {
            this.cachedAt = cachedAt;
            this.results = results;
        }
    }

    @FunctionalInterface
    private interface SongSearchProvider {
        List<Song> search(String query, int limit);
    }

    public MusicService(JioSaavnService jioSaavnService,
                        OfficialJioSaavnApiClient officialJioSaavnApiClient,
                        YouTubeService youTubeService) {
        this.jioSaavnService = jioSaavnService;
        this.officialJioSaavnApiClient = officialJioSaavnApiClient;
        this.youTubeService = youTubeService;
        initializeLibrary();
    }

    private MusicProvider getActiveJioProvider() {
        if (officialJioSaavnApiClient != null && officialJioSaavnApiClient.isEnabled()) {
            return officialJioSaavnApiClient;
        }
        return jioSaavnService;
    }

    private void initializeLibrary() {
        // Using royalty-free sample audio from pixabay.com (Creative Commons)
        library.add(new Song("1", "Blinding Lights", "The Weeknd", "After Hours",
                "https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36", 200,
                "https://cdn.pixabay.com/audio/2022/05/27/audio_1808fbf07a.mp3"));
        library.add(new Song("2", "Starboy", "The Weeknd ft. Daft Punk", "Starboy",
                "https://i.scdn.co/image/ab67616d0000b273a048415d3328299c565cf565", 230,
                "https://cdn.pixabay.com/audio/2022/10/11/audio_2ceb382e2e.mp3"));
        library.add(new Song("3", "Shape of You", "Ed Sheeran", "÷ (Divide)",
                "https://i.scdn.co/image/ab67616d0000b273ba5db46f4b838ef6027e6f96", 234,
                "https://cdn.pixabay.com/audio/2022/01/18/audio_d0a13f69d2.mp3"));
        library.add(new Song("4", "Levitating", "Dua Lipa", "Future Nostalgia",
                "https://i.scdn.co/image/ab67616d0000b273bd26ede1ae69327010d49946", 203,
                "https://cdn.pixabay.com/audio/2021/11/25/audio_91b32e02f9.mp3"));
        library.add(new Song("5", "Save Your Tears", "The Weeknd", "After Hours",
                "https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36", 216,
                "https://cdn.pixabay.com/audio/2022/03/15/audio_115b9b3f25.mp3"));
        library.add(new Song("6", "Watermelon Sugar", "Harry Styles", "Fine Line",
                "https://i.scdn.co/image/ab67616d0000b273b46f74097655d7f353caab14", 174,
                "https://cdn.pixabay.com/audio/2022/08/04/audio_2dae668d83.mp3"));
        library.add(new Song("7", "drivers license", "Olivia Rodrigo", "SOUR",
                "https://i.scdn.co/image/ab67616d0000b273a91c10fe9472d9bd535571d7", 242,
                "https://cdn.pixabay.com/audio/2023/05/16/audio_166b39b10a.mp3"));
        library.add(new Song("8", "Peaches", "Justin Bieber", "Justice",
                "https://i.scdn.co/image/ab67616d0000b273e6f407c7f3a0ec98845e4431", 198,
                "https://cdn.pixabay.com/audio/2022/06/07/audio_b9bd4170e4.mp3"));
        library.add(new Song("9", "Good 4 U", "Olivia Rodrigo", "SOUR",
                "https://i.scdn.co/image/ab67616d0000b273a91c10fe9472d9bd535571d7", 178,
                "https://cdn.pixabay.com/audio/2023/09/04/audio_0e8a28a08c.mp3"));
        library.add(new Song("10", "Stay", "The Kid LAROI & Justin Bieber", "F*CK LOVE 3",
                "https://i.scdn.co/image/ab67616d0000b273a05a950d122466ffd3294d6a", 141,
                "https://cdn.pixabay.com/audio/2022/11/22/audio_febc508520.mp3"));
        library.add(new Song("11", "Montero", "Lil Nas X", "MONTERO",
                "https://i.scdn.co/image/ab67616d0000b273be82673b5f79d9658ec0a9fd", 137,
                "https://cdn.pixabay.com/audio/2022/04/27/audio_67bcb4e1c1.mp3"));
        library.add(new Song("12", "Heat Waves", "Glass Animals", "Dreamland",
                "https://i.scdn.co/image/ab67616d0000b273712701c5e263efc8726b1464", 239,
                "https://cdn.pixabay.com/audio/2023/07/30/audio_e5b1a26c75.mp3"));
    }

    public List<Song> getLibrary() {
        return new ArrayList<>(library);
    }

    public void cacheSong(Song song) {
        if (song != null && song.getId() != null && !song.getId().isBlank()) {
            externalSongsCache.put(song.getId(), song);
        }
    }

    public Song getSongById(String id) {
        // Check local library first
        Song local = library.stream()
                .filter(s -> s.getId().equals(id))
                .findFirst()
                .orElse(null);
        if (local != null) return local;

        // Check external songs cache
        Song cached = externalSongsCache.get(id);
        if (cached != null) {
            // Skip cache for songs with unresolved/preview audio URLs so they get re-resolved
            boolean needsReResolve = false;
            String cachedAudio = cached.getAudioUrl();
            if (id.startsWith("jio_") && cachedAudio != null && !cachedAudio.isBlank()) {
                String lower = cachedAudio.toLowerCase();
                if (lower.contains("preview") || lower.contains("jiotune")) {
                    needsReResolve = true;
                }
            }
            if (id.startsWith("yt_") && (cachedAudio == null || cachedAudio.isBlank())) {
                needsReResolve = true;
            }
            if (!needsReResolve) {
                return cached;
            }
        }

        // Try to fetch from external APIs by ID
        if (id.startsWith("jio_")) {
            Song song = getActiveJioProvider().getSongById(id.substring(4));
            if (song != null) {
                externalSongsCache.put(song.getId(), song);
                return song;
            }
        } else if (id.startsWith("ytv_")) {
            Song song = youTubeService.getVideoContentById(id.substring(4));
            if (song != null) {
                externalSongsCache.put(song.getId(), song);
                return song;
            }
        } else if (id.startsWith("yt_")) {
            if (cached != null
                    && (cached.getAudioUrl() == null || cached.getAudioUrl().isBlank())
                    && cached.getTitle() != null
                    && !cached.getTitle().isBlank()) {
                Song resolvedFromMetadata = youTubeService.resolveSongFromMetadata(
                        id.substring(3),
                        cached.getTitle(),
                        cached.getArtist(),
                        cached.getCoverUrl(),
                        cached.getDurationSeconds());
                if (resolvedFromMetadata != null) {
                    externalSongsCache.put(resolvedFromMetadata.getId(), resolvedFromMetadata);
                    return resolvedFromMetadata;
                }
            }

            Song song = youTubeService.getSongById(id.substring(3));
            if (song != null) {
                externalSongsCache.put(song.getId(), song);
                return song;
            }
        }
        return null;
    }

    public Song resolveSongFromMetadata(String id,
                                        String title,
                                        String artist,
                                        String album,
                                        String coverUrl,
                                        int durationSeconds) {
        if (id == null || id.isBlank()) return null;

        String normalizedTitle = title != null ? title.trim() : "";
        String normalizedArtist = artist != null ? artist.trim() : "";
        String normalizedAlbum = album != null ? album.trim() : "";
        String normalizedCover = coverUrl != null ? coverUrl.trim() : "";
        int normalizedDuration = Math.max(0, durationSeconds);

        Song resolved = null;

        if (id.startsWith("ytv_")) {
            String videoId = id.substring(4);
            String safeTitle = normalizedTitle.isBlank() ? "YouTube Video" : normalizedTitle;
            String safeArtist = normalizedArtist.isBlank() ? "YouTube" : normalizedArtist;
            String safeAlbum = normalizedAlbum.isBlank() ? "YouTube Video" : normalizedAlbum;
            String safeCover = normalizedCover.isBlank()
                    ? "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg"
                    : normalizedCover;
            resolved = new Song(id, safeTitle, safeArtist, safeAlbum, safeCover, normalizedDuration, "");
        } else if (id.startsWith("yt_")) {
            String videoId = id.substring(4);
            String safeTitle = normalizedTitle.isBlank() ? "YouTube Video" : normalizedTitle;
            String safeArtist = normalizedArtist.isBlank() ? "YouTube" : normalizedArtist;
            String safeAlbum = normalizedAlbum.isBlank() ? "YouTube Video" : normalizedAlbum;
            String safeCover = normalizedCover.isBlank()
                    ? "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg"
                    : normalizedCover;
            resolved = new Song(id, safeTitle, safeArtist, safeAlbum, safeCover, normalizedDuration, "");
        }

        if (resolved != null && resolved.getId() != null && !resolved.getId().isBlank()) {
            externalSongsCache.put(resolved.getId(), resolved);
        }

        return resolved;
    }

    public List<Song> searchSongs(String query) {
        if (query == null || query.isBlank()) {
            return getLibrary();
        }
        String lowerQuery = query.toLowerCase();
        return library.stream()
                .filter(s -> s.getTitle().toLowerCase().contains(lowerQuery) ||
                             s.getArtist().toLowerCase().contains(lowerQuery) ||
                             s.getAlbum().toLowerCase().contains(lowerQuery))
                .toList();
    }

    public List<Song> searchExternal(String query, int limit) {
        System.out.println("searchExternal called with query='" + query + "' limit=" + limit);
        List<Song> results = new ArrayList<>();
        if (query == null || query.isBlank()) return results;

        int normalizedLimit = Math.max(1, Math.min(limit, 300));
        int providerLimit = Math.max(6, Math.min(normalizedLimit, SEARCH_PROVIDER_LIMIT_CAP));
        String normalizedQuery = query.trim();
        String searchKey = SEARCH_RESULT_VERSION + "#" + normalizedQuery.toLowerCase() + "#" + providerLimit;

        CachedExternalSearch cachedSearch = externalSearchCache.get(searchKey);
        long now = System.currentTimeMillis();
        if (cachedSearch != null && (now - cachedSearch.cachedAt) <= SEARCH_CACHE_TTL_MS) {
            System.out.println("Returning cached results");
            return new ArrayList<>(cachedSearch.results);
        }
        System.out.println("No cache hit, calling providers");

        CompletableFuture<List<Song>> jioFuture = CompletableFuture
            .supplyAsync(() -> collectProviderResultsWithVariants(getActiveJioProvider()::searchSongs, normalizedQuery, providerLimit))
            .completeOnTimeout(List.of(), SEARCH_JIO_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> List.of());

        CompletableFuture<List<Song>> ytMusicFuture = CompletableFuture
            .supplyAsync(() -> collectProviderResultsWithVariants(youTubeService::searchSongs, normalizedQuery, providerLimit))
            .completeOnTimeout(List.of(), SEARCH_YOUTUBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> List.of());

        CompletableFuture<List<Song>> ytVideoFuture = CompletableFuture
            .supplyAsync(() -> collectProviderResultsWithVariants(youTubeService::searchVideoContent, normalizedQuery, providerLimit))
            .completeOnTimeout(List.of(), SEARCH_YOUTUBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(e -> List.of());

        List<Song> jioResults = safeJoin(jioFuture);
        List<Song> ytResults = safeJoin(ytMusicFuture);
        List<Song> ytvResults = safeJoin(ytVideoFuture);

        jioResults = rankByRelevance(normalizedQuery, jioResults);
        ytResults = rankByRelevance(normalizedQuery, ytResults);
        ytvResults = rankByRelevance(normalizedQuery, ytvResults);

        // Keep preview items in search results - they will be resolved to full-length
        // when the song is added to queue via getSongById() / ensurePlayableSong()
        // Only filter out entries with no valid ID or title
        List<Song> filteredJio = new ArrayList<>();
        for (Song s : jioResults) {
            if (s == null) continue;
            if (s.getId() == null || s.getId().isBlank()) continue;
            if (s.getTitle() == null || s.getTitle().isBlank()) continue;
            filteredJio.add(s);
        }
        jioResults = filteredJio;

        if (!ytResults.isEmpty() && !ytvResults.isEmpty()) {
            Set<String> videoIdsInYtv = new LinkedHashSet<>();
            for (Song videoSong : ytvResults) {
                if (videoSong == null || videoSong.getId() == null) continue;
                if (videoSong.getId().startsWith("ytv_") && videoSong.getId().length() > 4) {
                    videoIdsInYtv.add(videoSong.getId().substring(4));
                }
            }

            if (!videoIdsInYtv.isEmpty()) {
                List<Song> nonOverlappingYtMusic = new ArrayList<>();
                for (Song ytSong : ytResults) {
                    if (ytSong == null || ytSong.getId() == null) continue;
                    if (ytSong.getId().startsWith("yt_") && ytSong.getId().length() > 3) {
                        String videoId = ytSong.getId().substring(3);
                        if (videoIdsInYtv.contains(videoId)) {
                            continue;
                        }
                    }

                    nonOverlappingYtMusic.add(ytSong);
                }
                ytResults = nonOverlappingYtMusic;
            }
        }

        if (ytResults.isEmpty() && !ytvResults.isEmpty()) {
            List<Song> synthesizedMusic = synthesizeMusicResultsFromYouTubeVideos(ytvResults, providerLimit);
            if (!synthesizedMusic.isEmpty()) {
                Set<String> synthesizedIds = new LinkedHashSet<>();
                for (Song s : synthesizedMusic) {
                    if (s != null && s.getId() != null && s.getId().startsWith("yt_")) {
                        synthesizedIds.add(s.getId().substring(3));
                    }
                }

                if (!synthesizedIds.isEmpty()) {
                    List<Song> remainingVideo = new ArrayList<>();
                    for (Song v : ytvResults) {
                        if (v == null || v.getId() == null || !v.getId().startsWith("ytv_")) {
                            if (v != null) remainingVideo.add(v);
                            continue;
                        }
                        String videoId = v.getId().substring(4);
                        if (!synthesizedIds.contains(videoId)) {
                            remainingVideo.add(v);
                        }
                    }
                    ytvResults = remainingVideo;
                }

                ytResults = synthesizedMusic;
            }
        }

        if (ytvResults.isEmpty() && !ytResults.isEmpty()) {
            ytvResults = synthesizeVideoResultsFromYouTubeMusic(ytResults, providerLimit);
        }

        Set<String> seenIds = new LinkedHashSet<>();
        // Prefer YouTube Music and Video results over JioSaavn previews
        appendUnique(results, ytResults, seenIds);
        appendUnique(results, ytvResults, seenIds);
        appendUnique(results, jioResults, seenIds);

        // Cache results so queue add can resolve by ID later.
        for (Song song : results) {
            if (song == null || song.getId() == null) continue;

            externalSongsCache.put(song.getId(), song);
        }

        boolean hasAnyYouTubeResult = !ytResults.isEmpty() || !ytvResults.isEmpty();
        if (hasAnyYouTubeResult) {
            externalSearchCache.put(searchKey, new CachedExternalSearch(now, new ArrayList<>(results)));
        } else {
            // Do not cache degraded snapshots; allow a new attempt on the next search.
            externalSearchCache.remove(searchKey);
        }

        return results;
    }

    private List<Song> safeJoin(CompletableFuture<List<Song>> future) {
        try {
            return future.join();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void appendUnique(List<Song> target, List<Song> source, Set<String> seenIds) {
        if (source == null || source.isEmpty()) return;

        for (Song song : source) {
            if (song == null || song.getId() == null || song.getId().isBlank()) continue;
            if (seenIds.add(song.getId())) {
                target.add(song);
            }
        }
    }

    private List<Song> collectProviderResultsWithVariants(SongSearchProvider provider,
                                                          String query,
                                                          int limit) {
        if (provider == null || query == null || query.isBlank() || limit <= 0) {
            return List.of();
        }

        List<String> variants = buildQueryVariants(query);
        Map<String, Song> merged = new LinkedHashMap<>();

        for (String variant : variants) {
            List<Song> batch;
            try {
                batch = provider.search(variant, limit);
            } catch (Exception e) {
                System.err.println("Provider search error for variant '" + variant + "': " + e.getMessage());
                e.printStackTrace();
                continue;
            }

            if (batch == null || batch.isEmpty()) {
                continue;
            }

            for (Song song : batch) {
                if (song == null || song.getId() == null || song.getId().isBlank()) continue;
                merged.putIfAbsent(song.getId(), song);
                if (merged.size() >= limit) {
                    return rankByRelevance(query, new ArrayList<>(merged.values()));
                }
            }
        }

        return rankByRelevance(query, new ArrayList<>(merged.values()));
    }

    private List<String> buildQueryVariants(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(trimmed);

        String strippedNoise = SEARCH_NOISE_PATTERN.matcher(trimmed).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (!strippedNoise.isBlank()) {
            variants.add(strippedNoise);
        }

        String normalized = normalizeForMatch(trimmed);
        if (!normalized.isBlank() && !normalized.equalsIgnoreCase(trimmed)) {
            variants.add(normalized);
        }

        String[] words = strippedNoise.isBlank() ? trimmed.split("\\s+") : strippedNoise.split("\\s+");
        if (words.length >= 2) {
            variants.add(words[0] + " " + words[1]);
        }
        if (words.length >= 3) {
            variants.add(words[0] + " " + words[1] + " " + words[2]);
        }

        return new ArrayList<>(variants);
    }

    private List<Song> rankByRelevance(String query, List<Song> songs) {
        if (songs == null || songs.isEmpty()) {
            return List.of();
        }

        String normalizedQuery = normalizeForMatch(query);
        List<String> queryTokens = tokenize(normalizedQuery);

        List<Song> ranked = new ArrayList<>(songs);
        ranked.sort(Comparator.comparingInt((Song song) -> computeSearchScore(song, normalizedQuery, queryTokens)).reversed());
        return ranked;
    }

    private int computeSearchScore(Song song, String normalizedQuery, List<String> queryTokens) {
        if (song == null) return Integer.MIN_VALUE;

        String title = normalizeForMatch(song.getTitle());
        String artist = normalizeForMatch(song.getArtist());
        String album = normalizeForMatch(song.getAlbum());
        String full = (title + " " + artist + " " + album).trim();

        int score = 0;

        if (!normalizedQuery.isBlank()) {
            if (title.equals(normalizedQuery)) {
                score += 180;
            } else if (title.contains(normalizedQuery)) {
                score += 130;
            } else if (full.contains(normalizedQuery)) {
                score += 110;
            }
        }

        Set<String> uniqueTokens = new HashSet<>(queryTokens);
        for (String token : uniqueTokens) {
            if (token.length() <= 1) continue;
            if (title.contains(token)) {
                score += 24;
            } else if (artist.contains(token) || album.contains(token)) {
                score += 12;
            }
        }

        if (song.getId() != null) {
            if (song.getId().startsWith("yt_")) {
                score += 8;
            } else if (song.getId().startsWith("ytv_")) {
                score += 4;
            }
        }

        return score;
    }

    private List<String> tokenize(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        for (String token : normalizedText.split("\\s+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalizeForMatch(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private List<Song> synthesizeVideoResultsFromYouTubeMusic(List<Song> ytResults, int limit) {
        List<Song> synthesized = new ArrayList<>();
        if (ytResults == null || ytResults.isEmpty()) return synthesized;

        int max = Math.max(1, Math.min(limit, ytResults.size()));
        for (Song song : ytResults) {
            if (song == null || song.getId() == null || !song.getId().startsWith("yt_")) continue;

            String videoId = song.getId().substring(3);
            if (videoId.isBlank()) continue;

            String title = (song.getTitle() == null || song.getTitle().isBlank()) ? "YouTube Video" : song.getTitle();
            String artist = (song.getArtist() == null || song.getArtist().isBlank()) ? "YouTube" : song.getArtist();
            String coverUrl = (song.getCoverUrl() == null || song.getCoverUrl().isBlank())
                    ? "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg"
                    : song.getCoverUrl();

            synthesized.add(new Song(
                    "ytv_" + videoId,
                    title,
                    artist,
                    "YouTube Video",
                    coverUrl,
                    Math.max(0, song.getDurationSeconds()),
                    ""));

            if (synthesized.size() >= max) {
                break;
            }
        }

        return synthesized;
    }

    private List<Song> synthesizeMusicResultsFromYouTubeVideos(List<Song> ytvResults, int limit) {
        List<Song> synthesized = new ArrayList<>();
        if (ytvResults == null || ytvResults.isEmpty()) return synthesized;

        int max = Math.max(1, Math.min(limit, ytvResults.size()));
        for (Song videoSong : ytvResults) {
            if (videoSong == null || videoSong.getId() == null || !videoSong.getId().startsWith("ytv_")) {
                continue;
            }

            String title = videoSong.getTitle() == null ? "" : videoSong.getTitle().toLowerCase();
            boolean likelyMusic = title.contains("audio")
                    || title.contains("acoustic")
                    || title.contains("remix")
                    || title.contains("instrumental")
                    || title.contains("song")
                    || title.contains("lyrics")
                    || title.contains("lyric")
                    || title.contains("official music")
                    || title.contains("official");
            if (!likelyMusic) {
                continue;
            }

            String videoId = videoSong.getId().substring(4);
            if (videoId.isBlank()) continue;

            synthesized.add(new Song(
                    "yt_" + videoId,
                    videoSong.getTitle(),
                    videoSong.getArtist(),
                    "YouTube Music",
                    videoSong.getCoverUrl(),
                    Math.max(0, videoSong.getDurationSeconds()),
                    ""
            ));

            if (synthesized.size() >= max) {
                break;
            }
        }

        return synthesized;
    }

    public Map<String, Object> getAvailableSources() {
        boolean youtubeConfigured = youTubeService.isConfigured();
        List<String> sources = new ArrayList<>();
        if (youtubeConfigured) {
            sources.add("youtube");
            sources.add("youtubevideo");
        }
        return Map.of(
            "sources", sources,
            "youtubeConfigured", youtubeConfigured,
            "youtubeApiConfigured", youTubeService.isApiConfigured(),
            "spotifyConfigured", false
        );
    }
}
