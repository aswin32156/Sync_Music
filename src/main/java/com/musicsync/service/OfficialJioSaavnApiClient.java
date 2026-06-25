package com.musicsync.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.musicsync.model.Song;

@Service
public class OfficialJioSaavnApiClient implements MusicProvider {

    private static final Logger log = LoggerFactory.getLogger(OfficialJioSaavnApiClient.class);

    private final String apiBaseUrl;
    private final String apiKey;
    private final boolean enabled;

    public OfficialJioSaavnApiClient(
            @Value("${jiosaavn.api-base-url:}") String apiBaseUrl,
            @Value("${jiosaavn.api-key:}") String apiKey) {
        this.apiBaseUrl = apiBaseUrl == null ? "" : apiBaseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.enabled = !this.apiBaseUrl.isBlank() && !this.apiKey.isBlank();
        if (!this.enabled) {
            log.info("Official JioSaavn API client is disabled. Set jiosaavn.api-base-url and jiosaavn.api-key to enable it.");
        }
    }

    @Override
    public String getProviderName() {
        return "OfficialJioSaavn";
    }

    @Override
    public List<Song> searchSongs(String query, int limit) {
        if (!enabled) {
            return List.of();
        }

        // Placeholder for the official API contract.
        // Wire this to the documented partner endpoints once credentials and schema are available.
        log.warn("Official JioSaavn API search is configured but not yet mapped to a provider schema.");
        return List.of();
    }

    @Override
    public Song getSongById(String id) {
        if (!enabled) {
            return null;
        }

        // Placeholder for authorized track lookup and playback URL resolution.
        log.warn("Official JioSaavn API lookup is configured but not yet mapped to a provider schema.");
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }
}