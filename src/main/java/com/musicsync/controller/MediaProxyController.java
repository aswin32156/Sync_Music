package com.musicsync.controller;

import java.io.InputStream;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RequestCallback;
import org.springframework.web.client.ResponseExtractor;
import org.springframework.web.client.RestTemplate;

import jakarta.servlet.http.HttpServletResponse;

import com.musicsync.model.Song;
import com.musicsync.service.MusicService;
import com.musicsync.service.JioSaavnService;

@RestController
public class MediaProxyController {
    private static final Logger log = LoggerFactory.getLogger(MediaProxyController.class);

    private final MusicService musicService;
    private final JioSaavnService jioSaavnService;
    private final RestTemplate restTemplate;
    private final String jioCookie;

    public MediaProxyController(MusicService musicService, JioSaavnService jioSaavnService) {
        this.musicService = musicService;
        this.jioSaavnService = jioSaavnService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(30000);
        
        this.restTemplate = new RestTemplate(factory);
        String cookie = System.getenv("JIOSAAVN_COOKIE");
        this.jioCookie = (cookie == null || cookie.isBlank()) ? null : cookie.trim();
    }

    @GetMapping("/api/music/stream/{songId}")
    public void streamSong(@PathVariable String songId, HttpServletResponse response) {
        try {
            if (songId == null || songId.isBlank()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            Song song = musicService.getSongById(songId);
            if (song == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            String remoteUrl = song.getAudioUrl();
            if (song.getId().startsWith("jio_") && (remoteUrl == null || remoteUrl.isBlank() || remoteUrl.startsWith("jio_"))) {
                Song resolved = jioSaavnService.getSongById(song.getId().substring(4));
                if (resolved != null && resolved.getAudioUrl() != null && !resolved.getAudioUrl().isBlank()) {
                    remoteUrl = resolved.getAudioUrl();
                }
            }

            if (remoteUrl == null || remoteUrl.isBlank() || remoteUrl.startsWith("jio_")) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }

            // Stream remote response directly to client
            final String proxiedUrl = remoteUrl;
            RequestCallback requestCallback = clientHttpRequest -> {
                try {
                    // Always send browser-like headers to CDN
                    clientHttpRequest.getHeaders().add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36");
                    clientHttpRequest.getHeaders().add("Referer", "https://www.jiosaavn.com/");
                    if (this.jioCookie != null) {
                        clientHttpRequest.getHeaders().add("Cookie", this.jioCookie);
                    }
                    // Forward Range header for seeking support
                    try {
                        Object attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
                        if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes) {
                            String rangeHeader = ((org.springframework.web.context.request.ServletRequestAttributes) attrs).getRequest().getHeader("Range");
                            if (rangeHeader != null && !rangeHeader.isBlank()) {
                                clientHttpRequest.getHeaders().add("Range", rangeHeader);
                            }
                        }
                    } catch (Exception ignored) {}
                } catch (Exception e) {
                    // ignore header attach errors
                }
            };

            ResponseExtractor<Void> responseExtractor = (ClientHttpResponse clientResp) -> {
                // Forward status code (important for 206 Partial Content)
                int statusCode = clientResp.getStatusCode().value();
                if (statusCode == 206) {
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                }

                // Forward all response headers
                clientResp.getHeaders().forEach((name, values) -> {
                    if ("Transfer-Encoding".equalsIgnoreCase(name)) return;
                    if ("Date".equalsIgnoreCase(name)) return;
                    for (String value : values) {
                        response.addHeader(name, value);
                    }
                });

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

            restTemplate.execute(proxiedUrl, HttpMethod.GET, requestCallback, responseExtractor);

        } catch (Exception e) {
            log.error("Failed to stream song {}: {}", songId, e.getMessage());
            try { response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR); } catch (Exception ignored) {}
        }
    }
}
