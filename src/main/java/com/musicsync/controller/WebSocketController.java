package com.musicsync.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;

import com.musicsync.dto.ChatRequest;
import com.musicsync.dto.PlaybackCommand;
import com.musicsync.dto.QueueRequest;
import com.musicsync.dto.RoomState;
import com.musicsync.model.ChatMessage;
import com.musicsync.model.PlaybackState;
import com.musicsync.model.Room;
import com.musicsync.model.Song;
import com.musicsync.model.User;
import com.musicsync.service.JioSaavnService;
import com.musicsync.service.MusicService;
import com.musicsync.service.RoomService;

@Controller
public class WebSocketController {

    private final SimpMessagingTemplate messagingTemplate;
    private final RoomService roomService;
    private final MusicService musicService;
    private final JioSaavnService jioSaavnService;

    public WebSocketController(SimpMessagingTemplate messagingTemplate,
                                RoomService roomService,
                                MusicService musicService,
                                JioSaavnService jioSaavnService) {
        this.messagingTemplate = messagingTemplate;
        this.roomService = roomService;
        this.musicService = musicService;
        this.jioSaavnService = jioSaavnService;
    }

    @MessageMapping("/room.register")
    public void registerToRoom(@Payload Map<String, String> payload,
                                SimpMessageHeaderAccessor headerAccessor) {
        String roomCode = payload.get("roomCode");
        String username = payload.get("username");
        if (roomCode == null || username == null) return;

        String sessionId = headerAccessor.getSessionId();
        roomService.updateUserSession(roomCode, username, sessionId);

        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        ChatMessage systemMsg = new ChatMessage(
            UUID.randomUUID().toString(), "System", "#1DB954",
            username + " joined the room", "system"
        );
        room.addChatMessage(systemMsg);

        broadcastRoomState(roomCode);
        messagingTemplate.convertAndSend("/topic/room/" + roomCode + "/chat", systemMsg);
    }

    @MessageMapping("/room.playback")
    public void handlePlayback(@Payload PlaybackCommand command,
                               SimpMessageHeaderAccessor headerAccessor) {
        String roomCode = command.getRoomCode();
        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        String action = command.getAction();
        if (action == null || action.isBlank()) return;

        String sessionId = headerAccessor.getSessionId();
        User sender = roomService.findUserBySession(roomCode, sessionId);
        if (sender == null) return;

        boolean hostOnlyAction = "next".equals(action)
                || "previous".equals(action)
                || "seek".equals(action)
                || "select".equals(action);
        if (hostOnlyAction && !sender.isHost()) {
            return;
        }

        PlaybackState state = room.getPlaybackState();

        switch (action) {
            case "play":
                state.setPlaying(true);
                state.setCurrentTime(command.getCurrentTime());
                break;
            case "pause":
                state.setPlaying(false);
                state.setCurrentTime(command.getCurrentTime());
                break;
            case "seek":
                state.setCurrentTime(command.getCurrentTime());
                break;
            case "next":
                int nextIndex = state.getCurrentSongIndex() + 1;
                if (nextIndex < room.getQueue().size()) {
                    state.setCurrentSongIndex(nextIndex);
                    state.setCurrentTime(0);
                    state.setPlaying(true);
                } else {
                    state.setPlaying(false);
                    state.setCurrentTime(0);
                }
                break;
            case "previous":
                int prevIndex = state.getCurrentSongIndex() - 1;
                if (prevIndex >= 0) {
                    state.setCurrentSongIndex(prevIndex);
                    state.setCurrentTime(0);
                    state.setPlaying(true);
                }
                break;
            case "select":
                int selectIndex = (int) command.getCurrentTime();
                if (selectIndex >= 0 && selectIndex < room.getQueue().size()) {
                    state.setCurrentSongIndex(selectIndex);
                    state.setCurrentTime(0);
                    state.setPlaying(true);
                }
                break;
            default:
                break;
        }

        broadcastPlaybackState(roomCode);
    }

    @MessageMapping("/room.queue.add")
    public void addToQueue(@Payload QueueRequest request) {
        String roomCode = request.getRoomCode();
        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        Song song = musicService.getSongById(request.getSongId());
        if (song != null && song.getDurationSeconds() <= 0 && request.getDurationSeconds() > 0) {
            song.setDurationSeconds(request.getDurationSeconds());
        }
        song = ensurePlayableSong(song, request);
        if (song == null) {
            song = musicService.resolveSongFromMetadata(
                    request.getSongId(),
                    request.getTitle(),
                    request.getArtist(),
                    request.getAlbum(),
                    request.getCoverUrl(),
                    request.getDurationSeconds());
        }
        if (song == null) return;

        Song queuedSong = new Song(
            song.getId(), song.getTitle(), song.getArtist(), song.getAlbum(),
            song.getCoverUrl(), song.getDurationSeconds(), song.getAudioUrl()
        );
        queuedSong.setAddedBy(request.getUsername());
        room.addSongToQueue(queuedSong);

        // Auto-play if this is the first song in the queue
        if (room.getQueue().size() == 1) {
            PlaybackState state = room.getPlaybackState();
            state.setCurrentSongIndex(0);
            state.setCurrentTime(0);
            state.setPlaying(true);
        }

        ChatMessage systemMsg = new ChatMessage(
            UUID.randomUUID().toString(), "System", "#1DB954",
            request.getUsername() + " added \"" + song.getTitle() + "\" to the queue", "system"
        );
        room.addChatMessage(systemMsg);

        // broadcastRoomState first so client loads the song, then playback state triggers play
        broadcastRoomState(roomCode);
        broadcastPlaybackState(roomCode);
        messagingTemplate.convertAndSend("/topic/room/" + roomCode + "/chat", systemMsg);
    }

    @MessageMapping("/room.queue.remove")
    public void removeFromQueue(@Payload QueueRequest request,
                                SimpMessageHeaderAccessor headerAccessor) {
        String roomCode = request.getRoomCode();
        if (roomCode == null || roomCode.isBlank()) return;

        String songId = request.getSongId();
        if (songId == null || songId.isBlank()) return;

        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        String sessionId = headerAccessor.getSessionId();
        // Allow removal if user is found by sessionId, or if they sent a username we can identify
        User sender = roomService.findUserBySession(roomCode, sessionId);
        if (sender == null && (request.getUsername() == null || request.getUsername().isBlank())) {
            return;
        }
        // If sender still null but we have a username, try to find by username
        if (sender == null && request.getUsername() != null) {
            sender = roomService.findUserInRoom(roomCode, request.getUsername());
        }
        // Allow the removal to proceed if room exists and request is well-formed
        // (sender presence is verified by them being able to send a WebSocket message)

        int removedIndex = -1;
        List<Song> q = room.getQueue();
        for (int i = 0; i < q.size(); i++) {
            if (q.get(i).getId().equals(songId)) {
                removedIndex = i;
                break;
            }
        }

        room.removeSongFromQueue(songId);

        // Adjust currentSongIndex if needed
        if (removedIndex >= 0) {
            PlaybackState state = room.getPlaybackState();
            int currentIdx = state.getCurrentSongIndex();
            if (removedIndex < currentIdx) {
                state.setCurrentSongIndex(currentIdx - 1);
            } else if (removedIndex == currentIdx) {
                // Current song removed - stop or play next
                if (currentIdx >= room.getQueue().size()) {
                    state.setCurrentSongIndex(Math.max(0, room.getQueue().size() - 1));
                }
                if (room.getQueue().isEmpty()) {
                    state.setPlaying(false);
                    state.setCurrentTime(0);
                    state.setCurrentSongIndex(0);
                } else {
                    state.setCurrentTime(0);
                    state.setPlaying(true);
                }
            }
        }

        broadcastRoomState(roomCode);
        broadcastPlaybackState(roomCode);
    }

    @MessageMapping("/room.queue.reorder")
    public void reorderQueue(@Payload Map<String, Object> payload) {
        String roomCode = (String) payload.get("roomCode");
        int fromIndex = ((Number) payload.get("fromIndex")).intValue();
        int toIndex = ((Number) payload.get("toIndex")).intValue();

        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        // Adjust currentSongIndex to follow the currently playing song
        PlaybackState state = room.getPlaybackState();
        int currentIdx = state.getCurrentSongIndex();

        room.reorderQueue(fromIndex, toIndex);

        if (currentIdx == fromIndex) {
            state.setCurrentSongIndex(toIndex);
        } else if (fromIndex < currentIdx && toIndex >= currentIdx) {
            state.setCurrentSongIndex(currentIdx - 1);
        } else if (fromIndex > currentIdx && toIndex <= currentIdx) {
            state.setCurrentSongIndex(currentIdx + 1);
        }

        broadcastRoomState(roomCode);
        broadcastPlaybackState(roomCode);
    }

    @MessageMapping("/room.chat")
    public void handleChat(@Payload ChatRequest request) {
        String roomCode = request.getRoomCode();
        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        String message = request.getMessage();
        if (message == null || message.isBlank()) return;
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }

        User user = roomService.findUserInRoom(roomCode, request.getUsername());
        String avatarColor = user != null ? user.getAvatarColor() : "#1DB954";

        ChatMessage chatMsg = new ChatMessage(
            UUID.randomUUID().toString(),
            request.getUsername(),
            avatarColor,
            message,
            "user"
        );
        room.addChatMessage(chatMsg);

        messagingTemplate.convertAndSend("/topic/room/" + roomCode + "/chat", chatMsg);
    }

    @MessageMapping("/room.sync")
    public void syncRequest(@Payload Map<String, String> payload,
                             SimpMessageHeaderAccessor headerAccessor) {
        String roomCode = payload.get("roomCode");
        if (roomCode == null) return;

        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        String sessionId = headerAccessor.getSessionId();
        if (sessionId == null || sessionId.isBlank()) return;

        RoomState state = roomService.getRoomState(roomCode);
        if (state != null) {
            PlaybackState ps = state.getPlaybackState();
            ps.setCurrentTime(room.getPlaybackState().getEstimatedCurrentTime());
            messagingTemplate.convertAndSendToUser(
                sessionId, "/queue/sync", state,
                createHeaders(sessionId).getMessageHeaders()
            );
        }
    }

    @Scheduled(fixedRate = 1000)
    public void broadcastActivePlaybackSyncTicks() {
        for (Room room : roomService.getAllRoomsSnapshot()) {
            if (room == null) continue;

            PlaybackState state = room.getPlaybackState();
            Song currentSong = room.getCurrentSong();
            if (state == null || currentSong == null || !state.isPlaying()) {
                continue;
            }

            double estimatedTime = state.getEstimatedCurrentTime();
            int songDuration = currentSong.getDurationSeconds();

            if (songDuration > 0 && estimatedTime >= songDuration) {
                advanceToNextSong(room);
                continue;
            }

            broadcastPlaybackState(room.getRoomCode(), true);
        }
    }

    private void advanceToNextSong(Room room) {
        PlaybackState state = room.getPlaybackState();
        int nextIndex = state.getCurrentSongIndex() + 1;

        if (nextIndex < room.getQueue().size()) {
            state.setCurrentSongIndex(nextIndex);
            state.setCurrentTime(0);
            state.setPlaying(true);
        } else {
            state.setPlaying(false);
            state.setCurrentTime(0);
        }

        broadcastRoomState(room.getRoomCode());
        broadcastPlaybackState(room.getRoomCode(), false);
    }

    private void broadcastRoomState(String roomCode) {
        RoomState state = roomService.getRoomState(roomCode);
        if (state != null) {
            state.setCurrentSong(ensurePlayableSong(state.getCurrentSong(), null));
            messagingTemplate.convertAndSend("/topic/room/" + roomCode + "/state", state);
        }
    }

    private void broadcastPlaybackState(String roomCode) {
        broadcastPlaybackState(roomCode, false);
    }

    private void broadcastPlaybackState(String roomCode, boolean syncTick) {
        Room room = roomService.getRoom(roomCode);
        if (room == null) return;

        PlaybackState ps = room.getPlaybackState();
        PlaybackState psCopy = new PlaybackState();
        psCopy.setPlaying(ps.isPlaying());
        psCopy.setCurrentTime(ps.getEstimatedCurrentTime());
        psCopy.setLastUpdated(ps.getLastUpdated());
        psCopy.setCurrentSongIndex(ps.getCurrentSongIndex());

        Map<String, Object> playbackUpdate = new java.util.HashMap<>();
        playbackUpdate.put("playbackState", psCopy);
        Song currentSong = ensurePlayableSong(room.getCurrentSong(), null);
        playbackUpdate.put("currentSong", currentSong != null ? currentSong : Map.of());
        playbackUpdate.put("queueSize", room.getQueue().size());
        playbackUpdate.put("syncTick", syncTick);
        playbackUpdate.put("serverTimeMs", System.currentTimeMillis());
        messagingTemplate.convertAndSend("/topic/room/" + roomCode + "/playback", (Object) playbackUpdate);
    }

    private Song ensurePlayableSong(Song song, QueueRequest request) {
        if (song == null || song.getId() == null || song.getId().isBlank()) {
            return null;
        }

        boolean needsAudio = song.getAudioUrl() == null
                || song.getAudioUrl().isBlank()
                || song.getAudioUrl().equals(song.getId())
                || song.getAudioUrl().startsWith("jio_");
        if (!needsAudio) {
            return song;
        }

        try {
            Song resolved = null;
            if (song.getId().startsWith("jio_")) {
                resolved = jioSaavnService.getSongById(song.getId().substring(4));
            } else if (song.getId().startsWith("yt_") || song.getId().startsWith("ytv_")) {
                String songId = request != null && request.getSongId() != null ? request.getSongId() : song.getId();
                String title = request != null && request.getTitle() != null && !request.getTitle().isBlank()
                        ? request.getTitle() : song.getTitle();
                String artist = request != null && request.getArtist() != null && !request.getArtist().isBlank()
                        ? request.getArtist() : song.getArtist();
                String coverUrl = request != null && request.getCoverUrl() != null && !request.getCoverUrl().isBlank()
                        ? request.getCoverUrl() : song.getCoverUrl();
                int duration = request != null && request.getDurationSeconds() > 0
                        ? request.getDurationSeconds() : song.getDurationSeconds();
                resolved = musicService.resolveSongFromMetadata(songId, title, artist, song.getAlbum(), coverUrl, duration);
            }

            if (resolved != null && resolved.getAudioUrl() != null && !resolved.getAudioUrl().isBlank()) {
                return resolved;
            }
        } catch (Exception ignored) {
        }

        return song;
    }

    private org.springframework.messaging.simp.SimpMessageHeaderAccessor createHeaders(String sessionId) {
        org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor =
            org.springframework.messaging.simp.SimpMessageHeaderAccessor.create(
                org.springframework.messaging.simp.SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        return headerAccessor;
    }
}
