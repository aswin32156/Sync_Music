package com.musicsync.service;

import java.util.List;

import com.musicsync.model.Song;

public interface MusicProvider {

    String getProviderName();

    List<Song> searchSongs(String query, int limit);

    Song getSongById(String id);
}