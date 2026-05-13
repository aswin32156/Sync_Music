package com.musicsync.controller;

import java.io.InputStream;
import java.io.OutputStream;

import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import com.musicsync.model.Song;
import com.musicsync.service.MusicService;

@RestController
public class MediaProxyController {
    private static final Logger log = LoggerFactory.getLogger(MediaProxyController.class);

    private final MusicService musicService;
    private final RestTemplate restTemplate;

    public MediaProxyController(MusicService musicService) {
        this.musicService = musicService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.restTemplate = new RestTemplate(factory);
    }

    @GetMapping("/api/music/stream/{songId}")
    public void streamSong(@PathVariable String songId, HttpServletResponse response) {
        try {
            if (songId == null || songId.isBlank()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            Song song = musicService.getSongById(songId);
            if (song == null || song.getAudioUrl() == null || song.getAudioUrl().isBlank()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            final String remoteUrl = song.getAudioUrl();

            // Stream remote response directly to client
            RequestCallback requestCallback = clientHttpRequest -> {};

            ResponseExtractor<Void> responseExtractor = (ClientHttpResponse clientResp) -> {
                String contentType = clientResp.getHeaders().getFirst("Content-Type");
                if (contentType != null) response.setContentType(contentType);
                String cl = clientResp.getHeaders().getFirst("Content-Length");
                if (cl != null) {
                    try { response.setContentLength(Integer.parseInt(cl)); } catch (Exception ignored) {}
                }

                try (InputStream in = clientResp.getBody(); OutputStream out = response.getOutputStream()) {
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                    out.flush();
                }
                return null;
            };

            restTemplate.execute(remoteUrl, org.springframework.http.HttpMethod.GET, requestCallback, responseExtractor);

        } catch (Exception e) {
            log.error("Failed to stream song {}: {}", songId, e.getMessage());
            try { response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); } catch (Exception ignored) {}
        }
    }
}
