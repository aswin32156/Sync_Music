package com.musicsync.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.musicsync.dto.CreateRoomRequest;
import com.musicsync.dto.JoinRoomRequest;
import com.musicsync.dto.RoomState;
import com.musicsync.model.Room;
import com.musicsync.model.Song;
import com.musicsync.service.MusicService;
import com.musicsync.service.RoomService;

@RestController
@RequestMapping("/api")
public class RoomController {

    private final RoomService roomService;
    private final MusicService musicService;

    public RoomController(RoomService roomService, MusicService musicService) {
        this.roomService = roomService;
        this.musicService = musicService;
    }

    @PostMapping("/rooms/create")
    public ResponseEntity<?> createRoom(@RequestBody CreateRoomRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        if (request.getRoomName() == null || request.getRoomName().isBlank()) {
            request.setRoomName(request.getUsername() + "'s Room");
        }
        String password = request.getPassword() != null && !request.getPassword().isBlank() 
            ? request.getPassword().trim() : null;
        Room room = roomService.createRoom(request.getUsername().trim(), request.getRoomName().trim(), password);
        RoomState state = roomService.getRoomState(room.getRoomCode());
        return ResponseEntity.ok(state);
    }

    @PostMapping("/rooms/join")
    public ResponseEntity<?> joinRoom(@RequestBody JoinRoomRequest request) {
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Username is required"));
        }
        if (request.getRoomCode() == null || request.getRoomCode().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Room code is required"));
        }
        String code = request.getRoomCode().trim().toUpperCase();
        if (!roomService.roomExists(code)) {
            return ResponseEntity.status(404).body(Map.of("error", "Room not found. Check the code and try again."));
        }
        try {
            String password = request.getPassword() != null ? request.getPassword().trim() : null;
            Room room = roomService.joinRoom(code, request.getUsername().trim(), password);
            RoomState state = roomService.getRoomState(room.getRoomCode());
            return ResponseEntity.ok(state);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() != null ? e.getMessage() : "Failed to join room";
            if ("Incorrect password".equals(message)) {
                return ResponseEntity.status(401).body(Map.of("error", message));
            }
            if (message.startsWith("Room is full")) {
                return ResponseEntity.status(409).body(Map.of("error", message));
            }
            return ResponseEntity.badRequest().body(Map.of("error", message));
        }
    }

    @GetMapping("/rooms/{roomCode}")
    public ResponseEntity<?> getRoomState(@PathVariable("roomCode") String roomCode) {
        RoomState state = roomService.getRoomState(roomCode.toUpperCase());
        if (state == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Room not found"));
        }
        return ResponseEntity.ok(state);
    }

    @GetMapping("/rooms/{roomCode}/exists")
    public ResponseEntity<?> checkRoom(@PathVariable("roomCode") String roomCode) {
        boolean exists = roomService.roomExists(roomCode.toUpperCase());
        return ResponseEntity.ok(Map.of("exists", exists));
    }

    @GetMapping("/music/library")
    public ResponseEntity<List<Song>> getLibrary() {
        return ResponseEntity.ok(musicService.getLibrary());
    }

    @GetMapping("/music/search")
    public ResponseEntity<List<Song>> searchSongs(@RequestParam(name = "q", defaultValue = "") String q) {
        return ResponseEntity.ok(musicService.searchSongs(q));
    }

    @GetMapping("/music/song/{songId}")
    public ResponseEntity<?> getSongById(@PathVariable("songId") String songId) {
        if (songId == null || songId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Song ID is required"));
        }

        Song song = musicService.getSongById(songId.trim());
        if (song == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Song not found or unavailable"));
        }

        return ResponseEntity.ok(song);
    }

    @GetMapping({"/music/search/external", "/music/searchExternal"})
    public ResponseEntity<List<Song>> searchExternal(
            @RequestParam(name = "q", defaultValue = "") String q,
            @RequestParam(name = "query", defaultValue = "") String query,
            @RequestParam(name = "limit", defaultValue = "200") int limit) {
        String searchTerm = !q.isBlank() ? q : query;
        if (searchTerm.isBlank()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(musicService.searchExternal(searchTerm, Math.min(limit, 300)));
    }

    @GetMapping("/music/sources")
    public ResponseEntity<?> getAvailableSources() {
        return ResponseEntity.ok(musicService.getAvailableSources());
    }

    @GetMapping("/stats")
    public ResponseEntity<?> getStats() {
        return ResponseEntity.ok(Map.of("activeRooms", roomService.getActiveRoomCount()));
    }
}
