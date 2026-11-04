package ru.radiokp.voennoerevue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.datasource.DefaultHttpDataSource;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

public final class AudioPlaybackService extends Service {
    public static final String ACTION_PLAY = "ru.radiokp.voennoerevue.PLAY";
    public static final String ACTION_TOGGLE = "ru.radiokp.voennoerevue.TOGGLE";
    public static final String ACTION_STOP = "ru.radiokp.voennoerevue.STOP";
    public static final String ACTION_SET_QUALITY = "ru.radiokp.voennoerevue.SET_QUALITY";
    public static final String EXTRA_URL = "stream_url";
    public static final String EXTRA_URL_CANDIDATES = "stream_url_candidates";
    public static final String EXTRA_DASH_URL_CANDIDATES = "dash_url_candidates";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_SOURCE_NAME = "source_name";
    public static final String EXTRA_MAX_AUDIO_BITRATE = "max_audio_bitrate_kbps";
    public static final String EXTRA_IS_MP3 = "is_mp3";

    private static final String CHANNEL_ID = "radio_kp_live_audio_v3";
    private static final int NOTIFICATION_ID = 6107;
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public interface PlaybackStateListener {
        void onPlaybackStateChanged(boolean playing, String message);
    }

    public final class LocalBinder extends Binder {
        public AudioPlaybackService getService() { return AudioPlaybackService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ExoPlayer player;
    private DefaultTrackSelector trackSelector;
    private String currentUrl = "";
    private ArrayList<String> currentCandidateUrls = new ArrayList<>();
    private ArrayList<String> currentDashCandidateUrls = new ArrayList<>();
    private String currentReferer = "https://vkvideo.ru/";
    private String currentSourceName = "Радио КП";
    private boolean currentIsMp3 = false;
    private int maxAudioBitrateKbps = 64;
    private static final int MAX_RADIO_RECONNECT_ATTEMPTS = 2;
    private int radioReconnectAttempts = 0;
    private int streamGeneration = 0;
    private String fallbackHlsUrl = "";
    private boolean usingOptimizedHls = false;
    private String audioModeStatus = "";
    private PlaybackStateListener listener;

    private static final class AudioPlaylistResolution {
        final String url;
        final String fallbackUrl;
        final String status;
        AudioPlaylistResolution(String url, String status) {
            this(url, null, status);
        }
        AudioPlaylistResolution(String url, String fallbackUrl, String status) {
            this.url = url;
            this.fallbackUrl = fallbackUrl;
            this.status = status;
        }
    }

    private final Runnable connectionTimeout = () -> {
        if (player != null && !currentUrl.isEmpty() && player.getPlaybackState() == Player.STATE_BUFFERING) {
            handlePlaybackFailure("источник слишком долго не отвечает");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        trackSelector = new DefaultTrackSelector(this);
        applyTrackSelectionParameters();
        player = new ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                updateNotification();
                notifyListener(isPlaying, playbackStatusMessage(isPlaying));
            }

            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    notifyListener(false, "Подключение к " + currentSourceName + "…");
                } else if (state == Player.STATE_READY) {
                    handler.removeCallbacks(connectionTimeout);
                    // Reset retry budget after every successful reconnect, so a long live programme can recover again later.
                    if (currentIsMp3) radioReconnectAttempts = 0;
                    notifyListener(player.isPlaying(), playbackStatusMessage(player.isPlaying()));
                } else if (state == Player.STATE_ENDED) {
                    handlePlaybackFailure("поток завершился");
                }
                updateNotification();
            }

            @Override public void onPlayerError(PlaybackException error) {
                handlePlaybackFailure(error.getErrorCodeName());
            }
        });
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_PLAY.equals(action)) {
            String url = intent.getStringExtra(EXTRA_URL);
            String referer = intent.getStringExtra(EXTRA_REFERER);
            String sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME);
            int quality = intent.getIntExtra(EXTRA_MAX_AUDIO_BITRATE, maxAudioBitrateKbps);
            boolean isMp3 = intent.getBooleanExtra(EXTRA_IS_MP3, false);
            ArrayList<String> suppliedCandidates = intent.getStringArrayListExtra(EXTRA_URL_CANDIDATES);
            currentCandidateUrls = new ArrayList<>();
            if (suppliedCandidates != null) {
                for (String candidate : suppliedCandidates) {
                    if (currentCandidateUrls.size() >= 8) break;
                    if (isAllowedHlsStream(candidate) && !currentCandidateUrls.contains(candidate)) currentCandidateUrls.add(candidate);
                }
            }
            ArrayList<String> suppliedDashCandidates = intent.getStringArrayListExtra(EXTRA_DASH_URL_CANDIDATES);
            currentDashCandidateUrls = new ArrayList<>();
            if (suppliedDashCandidates != null) {
                for (String candidate : suppliedDashCandidates) {
                    if (currentDashCandidateUrls.size() >= 4) break;
                    if (isAllowedDashStream(candidate) && !currentDashCandidateUrls.contains(candidate)) currentDashCandidateUrls.add(candidate);
                }
            }
            if (!isMp3 && currentCandidateUrls.isEmpty() && currentDashCandidateUrls.isEmpty() && isAllowedStream(url, false)) {
                if (isDashManifest(url)) currentDashCandidateUrls.add(url); else currentCandidateUrls.add(url);
            }
            if (!isAllowedStream(url, isMp3)) {
                notifyListener(false, "SOURCE_ERROR: недопустимый адрес потока");
                return START_NOT_STICKY;
            }
            if (!isMp3 || !url.equals(currentUrl)) radioReconnectAttempts = 0;
            currentUrl = url;
            currentIsMp3 = isMp3;
            maxAudioBitrateKbps = quality;
            if (referer != null && !referer.trim().isEmpty()) currentReferer = referer;
            if (sourceName != null && !sourceName.trim().isEmpty()) currentSourceName = sourceName;
            applyTrackSelectionParameters();
            startAsForeground();
            playStream(currentUrl);
        } else if (ACTION_TOGGLE.equals(action)) {
            togglePlayback();
        } else if (ACTION_STOP.equals(action)) {
            stopPlaybackAndService();
        } else if (ACTION_SET_QUALITY.equals(action)) {
            maxAudioBitrateKbps = intent.getIntExtra(EXTRA_MAX_AUDIO_BITRATE, maxAudioBitrateKbps);
            applyTrackSelectionParameters();
        }
        return START_NOT_STICKY;
    }

    public boolean isPlaying() { return player != null && player.isPlaying(); }
    public boolean hasStream() { return player != null && player.getMediaItemCount() > 0 && !currentUrl.isEmpty(); }
    public void setPlaybackStateListener(PlaybackStateListener value) { listener = value; }
    public void clearPlaybackStateListener() { listener = null; }

    public void setMaxAudioBitrate(int kbps) {
        maxAudioBitrateKbps = kbps;
        applyTrackSelectionParameters();
    }

    public void togglePlayback() {
        if (player == null || player.getMediaItemCount() == 0) return;
        if (player.isPlaying()) player.pause(); else player.play();
        updateNotification();
    }

    public void stopPlayback() { stopPlaybackAndService(); }

    private void applyTrackSelectionParameters() {
        if (trackSelector == null) return;
        int maxBitrate = (currentIsMp3 || maxAudioBitrateKbps <= 0)
                ? Integer.MAX_VALUE : maxAudioBitrateKbps * 1000;
        DefaultTrackSelector.Parameters parameters = trackSelector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setMaxAudioBitrate(maxBitrate)
                .build();
        trackSelector.setParameters(parameters);
    }

    private void playStream(String url) {
        handler.removeCallbacks(connectionTimeout);
        final int generation = ++streamGeneration;
        fallbackHlsUrl = currentIsMp3 ? "" : url;
        usingOptimizedHls = false;

        if (currentIsMp3) {
            usingOptimizedHls = false;
            audioModeStatus = "Радиопоток MP3 без видеодорожки";
            startProgressivePlayback(url, generation);
            return;
        }

        audioModeStatus = "Проверяю HLS и DASH…";
        notifyListener(false, "RuTube: проверяю DASH на отдельное аудиопредставление…");
        Thread resolver = new Thread(() -> {
            DashAudioResolution dashResolution = resolveDashAudioCandidates();
            AudioPlaylistResolution hlsResolution = null;
            if (dashResolution.url == null && isHlsManifest(url)) hlsResolution = resolveAudioOnlyPlaylists(url);
            final AudioPlaylistResolution finalHlsResolution = hlsResolution;
            handler.post(() -> {
                if (generation != streamGeneration || player == null || !url.equals(currentUrl)) return;
                if (dashResolution.url != null) {
                    usingOptimizedHls = true;
                    fallbackHlsUrl = currentCandidateUrls.isEmpty() ? "" : currentCandidateUrls.get(0);
                    audioModeStatus = dashResolution.status;
                    notifyListener(false, dashResolution.status + " — запускаю аудиопредставление DASH…");
                    startDashPlayback(dashResolution.url, generation);
                    return;
                }
                if (finalHlsResolution != null && finalHlsResolution.url != null && !finalHlsResolution.url.isEmpty()) {
                    usingOptimizedHls = true;
                    fallbackHlsUrl = finalHlsResolution.fallbackUrl == null ? url : finalHlsResolution.fallbackUrl;
                    audioModeStatus = dashResolution.status + " · " + finalHlsResolution.status;
                    notifyListener(false, audioModeStatus + " — запускаю оптимизированный HLS…");
                    startHlsPlayback(finalHlsResolution.url, generation);
                } else if (isHlsManifest(url)) {
                    usingOptimizedHls = false;
                    fallbackHlsUrl = "";
                    audioModeStatus = dashResolution.status + " · "
                            + (finalHlsResolution == null ? "HLS не проверялся" : finalHlsResolution.status);
                    notifyListener(false, audioModeStatus + " — запускаю исходный HLS…");
                    startHlsPlayback(url, generation);
                } else {
                    usingOptimizedHls = false;
                    fallbackHlsUrl = "";
                    audioModeStatus = dashResolution.status;
                    notifyListener(false, audioModeStatus);
                    failCurrentSource("В ответе RuTube не найден пригодный DASH-аудиопоток или HLS-поток");
                }
            });
        }, "RadioKp-DashHlsAudioResolver");
        resolver.setDaemon(true);
        resolver.start();
    }

    private void startProgressivePlayback(String url, int generation) {
        try {
            if (generation != streamGeneration || player == null) return;
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(createStreamHeaders());
            MediaItem item = new MediaItem.Builder().setUri(url).setMimeType(MimeTypes.AUDIO_MPEG).build();
            ProgressiveMediaSource source = new ProgressiveMediaSource.Factory(httpFactory).createMediaSource(item);
            prepareAndPlay(source);
        } catch (Exception e) {
            handlePlaybackFailure(e.getClass().getSimpleName());
        }
    }

    private void startDashPlayback(String url, int generation) {
        try {
            if (generation != streamGeneration || player == null) return;
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(createStreamHeaders());
            MediaItem item = new MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_MPD).build();
            DashMediaSource source = new DashMediaSource.Factory(httpFactory).createMediaSource(item);
            prepareAndPlay(source);
        } catch (Exception e) {
            handlePlaybackFailure(e.getClass().getSimpleName());
        }
    }

    private void startHlsPlayback(String url, int generation) {
        try {
            if (generation != streamGeneration || player == null) return;
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(USER_AGENT)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(createStreamHeaders());
            MediaItem item = new MediaItem.Builder().setUri(url).build();
            HlsMediaSource source = new HlsMediaSource.Factory(httpFactory).createMediaSource(item);
            prepareAndPlay(source);
        } catch (Exception e) {
            handlePlaybackFailure(e.getClass().getSimpleName());
        }
    }

    private Map<String, String> createStreamHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", currentReferer);
        headers.put("Origin", currentReferer.startsWith("https://rutube.ru")
                ? "https://rutube.ru"
                : (currentReferer.startsWith("https://radiokp.ru") ? "https://radiokp.ru" : "https://vkvideo.ru"));
        headers.put("Accept", "*/*");
        return headers;
    }

    private void prepareAndPlay(androidx.media3.exoplayer.source.MediaSource source) {
        player.setMediaSource(source);
        player.prepare();
        player.play();
        updateNotification();
        notifyListener(false, "Подключение к " + currentSourceName + "…");
        handler.removeCallbacks(connectionTimeout);
        handler.postDelayed(connectionTimeout, 35000L);
    }

    /**
     * Reads only the small HLS manifest. If it exposes a distinct AUDIO rendition
     * or an audio-only variant, return that playlist; otherwise use the original.
     */
    private static final class DashAudioResolution {
        final String url;
        final String status;
        DashAudioResolution(String url, String status) { this.url = url; this.status = status; }
    }

    private DashAudioResolution resolveDashAudioCandidates() {
        if (currentDashCandidateUrls == null || currentDashCandidateUrls.isEmpty()) {
            return new DashAudioResolution(null, "RuTube не объявил DASH/MPD в ответе LIVE API");
        }
        int checked = 0;
        String firstReason = "отдельное аудиопредставление не найдено";
        for (String manifestUrl : currentDashCandidateUrls) {
            if (checked >= 4) break;
            checked++;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Referer", currentReferer);
                connection.setRequestProperty("Origin", "https://rutube.ru");
                connection.setRequestProperty("Accept", "application/dash+xml,application/xml,text/xml,*/*");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    firstReason = "HTTP " + code;
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    int total = 0;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > 1024 * 1024) throw new IllegalStateException("MPD exceeds 1 MB");
                        output.write(buffer, 0, read);
                    }
                }
                String manifest = new String(output.toByteArray(), StandardCharsets.UTF_8);
                if (!manifest.contains("<MPD") && !manifest.contains(":MPD")) {
                    firstReason = "ответ не является MPD";
                    continue;
                }
                if (hasSeparateDashAudio(manifest)) {
                    return new DashAudioResolution(manifestUrl,
                            "Найдено DASH/MPD с отдельным аудиопредставлением (" + checked + " проверено)");
                }
                firstReason = "MPD загружен, но отдельный AdaptationSet audio отсутствует";
            } catch (Exception error) {
                firstReason = error.getClass().getSimpleName();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return new DashAudioResolution(null, "Проверено DASH/MPD: " + checked + " · " + firstReason);
    }

    private static boolean hasSeparateDashAudio(String manifest) {
        if (manifest == null || manifest.isEmpty()) return false;
        java.util.regex.Pattern setPattern = java.util.regex.Pattern.compile(
                "(?is)<(?:[A-Za-z0-9_]+:)?AdaptationSet\\b([^>]*)>(.*?)</(?:[A-Za-z0-9_]+:)?AdaptationSet\\s*>");
        java.util.regex.Matcher matcher = setPattern.matcher(manifest);
        while (matcher.find()) {
            String attributes = matcher.group(1) == null ? "" : matcher.group(1);
            String inner = matcher.group(2) == null ? "" : matcher.group(2);
            String mime = xmlAttribute(attributes, "mimeType");
            String contentType = xmlAttribute(attributes, "contentType");
            if ((mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/"))
                    || (contentType != null && contentType.equalsIgnoreCase("audio"))) return true;
            java.util.regex.Matcher representation = java.util.regex.Pattern.compile(
                    "(?is)<(?:[A-Za-z0-9_]+:)?Representation\\b([^>]*)>").matcher(inner);
            while (representation.find()) {
                String repMime = xmlAttribute(representation.group(1), "mimeType");
                String codecs = xmlAttribute(representation.group(1), "codecs");
                if ((repMime != null && repMime.toLowerCase(Locale.ROOT).startsWith("audio/"))
                        || isAudioOnlyCodecList(codecs)) return true;
            }
        }
        return false;
    }

    private static String xmlAttribute(String attributes, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?i)\\b" + java.util.regex.Pattern.quote(name) + "\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]").matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    private AudioPlaylistResolution resolveAudioOnlyPlaylists(String primaryUrl) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (currentCandidateUrls != null) {
            for (String candidate : currentCandidateUrls) {
                if (candidates.size() >= 8) break;
                if (isAllowedStream(candidate, false)) candidates.add(candidate);
            }
        }
        if (isAllowedStream(primaryUrl, false)) candidates.add(primaryUrl);
        if (candidates.isEmpty()) candidates.add(primaryUrl);

        int checked = 0;
        String firstFailure = null;
        for (String candidate : candidates) {
            checked++;
            AudioPlaylistResolution result = resolveAudioOnlyPlaylistCandidate(candidate);
            if (result.url != null && !result.url.isEmpty()) {
                return new AudioPlaylistResolution(result.url, candidate,
                        "Проверено " + checked + " из " + candidates.size() + " HLS-вариантов: " + result.status);
            }
            if (firstFailure == null) firstFailure = result.status;
        }
        String detail = firstFailure == null ? "не удалось прочитать манифесты" : firstFailure;
        return new AudioPlaylistResolution(null, primaryUrl,
                "Проверено " + checked + " HLS-вариант(ов); отдельный аудиопоток не найден (" + detail + ")");
    }

    private AudioPlaylistResolution resolveAudioOnlyPlaylistCandidate(String manifestUrl) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(manifestUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", currentReferer);
            connection.setRequestProperty("Origin", currentReferer.startsWith("https://rutube.ru")
                    ? "https://rutube.ru"
                    : (currentReferer.startsWith("https://radiokp.ru") ? "https://radiokp.ru" : "https://vkvideo.ru"));
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return new AudioPlaylistResolution(null, "Не удалось проверить HLS: HTTP " + responseCode);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                int total = 0;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > 512 * 1024) {
                        return new AudioPlaylistResolution(null, "HLS-манифест превышает лимит проверки 512 КБ");
                    }
                    output.write(buffer, 0, read);
                }
            }
            String manifest = new String(output.toByteArray(), StandardCharsets.UTF_8);
            if (!manifest.trim().startsWith("#EXTM3U")) {
                return new AudioPlaylistResolution(null, "Ответ сервера не является HLS-манифестом");
            }
            return findAudioOnlyPlaylistUrl(connection.getURL().toExternalForm(), manifest);
        } catch (Exception error) {
            return new AudioPlaylistResolution(null,
                    "Не удалось проверить HLS-манифест (" + error.getClass().getSimpleName() + ")");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private AudioPlaylistResolution findAudioOnlyPlaylistUrl(String baseUrl, String manifest) {
        if (manifest == null || !manifest.contains("#EXTM3U")) {
            return new AudioPlaylistResolution(null, "Некорректный HLS-манифест");
        }
        String[] lines = manifest.replace("\r", "").split("\n");
        java.util.HashSet<String> referencedAudioGroups = new java.util.HashSet<>();

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> attrs = parseHlsAttributes(line);
                String group = attrs.get("AUDIO");
                if (group != null && !group.isEmpty()) referencedAudioGroups.add(group);
            }
        }

        String selectedAudioUri = null;
        int selectedAudioScore = Integer.MIN_VALUE;
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (!line.startsWith("#EXT-X-MEDIA:")) continue;
            Map<String, String> attrs = parseHlsAttributes(line);
            if (!"AUDIO".equalsIgnoreCase(attrs.get("TYPE"))) continue;

            String group = attrs.get("GROUP-ID");
            String uri = attrs.get("URI");
            if (uri == null || uri.isEmpty() || group == null || !referencedAudioGroups.contains(group)) continue;

            String language = attrs.get("LANGUAGE");
            int score = 0;
            if (language != null && (language.toLowerCase(Locale.ROOT).startsWith("ru")
                    || language.toLowerCase(Locale.ROOT).startsWith("rus"))) score += 100;
            if ("YES".equalsIgnoreCase(attrs.get("DEFAULT"))) score += 20;
            if ("YES".equalsIgnoreCase(attrs.get("AUTOSELECT"))) score += 10;
            if (score > selectedAudioScore) {
                selectedAudioScore = score;
                selectedAudioUri = uri;
            }
        }
        if (selectedAudioUri != null) {
            String resolved = resolveSafePlaylistUri(baseUrl, selectedAudioUri);
            if (resolved != null) {
                return new AudioPlaylistResolution(resolved, "Используется отдельная аудиодорожка HLS (EXT-X-MEDIA)");
            }
            return new AudioPlaylistResolution(null, "HLS объявляет отдельную аудиодорожку, но её URL не прошёл проверку");
        }

        String bestUnderLimit = null;
        int bestUnderLimitBandwidth = -1;
        String lowestAudioVariant = null;
        int lowestAudioBandwidth = Integer.MAX_VALUE;
        String pendingVariantLine = null;
        int requestedBandwidth = maxAudioBitrateKbps <= 0
                ? Integer.MAX_VALUE : maxAudioBitrateKbps * 1000;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingVariantLine = line;
            } else if (pendingVariantLine != null && !line.isEmpty() && !line.startsWith("#")) {
                Map<String, String> attrs = parseHlsAttributes(pendingVariantLine);
                if (isAudioOnlyCodecList(attrs.get("CODECS"))) {
                    int bandwidth = parseBandwidth(attrs.get("AVERAGE-BANDWIDTH"));
                    if (bandwidth <= 0) bandwidth = parseBandwidth(attrs.get("BANDWIDTH"));
                    if (bandwidth <= 0) bandwidth = 1;
                    if (bandwidth < lowestAudioBandwidth) {
                        lowestAudioBandwidth = bandwidth;
                        lowestAudioVariant = line;
                    }
                    if (bandwidth <= requestedBandwidth && bandwidth > bestUnderLimitBandwidth) {
                        bestUnderLimitBandwidth = bandwidth;
                        bestUnderLimit = line;
                    }
                }
                pendingVariantLine = null;
            }
        }

        String selectedVariant = bestUnderLimit != null ? bestUnderLimit : lowestAudioVariant;
        if (selectedVariant != null) {
            String resolved = resolveSafePlaylistUri(baseUrl, selectedVariant);
            if (resolved != null) {
                return new AudioPlaylistResolution(resolved, "Используется отдельный аудио-only вариант HLS (без видеокодека)");
            }
            return new AudioPlaylistResolution(null, "Аудио-only вариант найден, но его URL не прошёл проверку");
        }

        // RuTube often publishes a muxed HLS master without an external
        // AUDIO rendition. Choose the lowest-bandwidth combined A/V child
        // playlist instead of letting ExoPlayer start at a high quality.
        // VK keeps its existing audio-only selection logic unchanged.
        if (isRutubeSource()) {
            AudioPlaylistResolution lowBandwidth = findLowestBandwidthRutubeVariant(baseUrl, lines);
            if (lowBandwidth != null && lowBandwidth.url != null) return lowBandwidth;
        }

        return new AudioPlaylistResolution(null,
                "Отдельная аудиодорожка или аудио-only вариант не объявлены; используется исходный HLS");
    }


    private boolean isRutubeSource() {
        return currentReferer != null && currentReferer.toLowerCase(Locale.ROOT).contains("rutube.ru");
    }

    /**
     * Last-resort traffic reduction for RuTube only: select the child playlist
     * with the smallest declared average/peak bandwidth. This remains a
     * combined audio+video stream; it is not audio-only.
     */
    private AudioPlaylistResolution findLowestBandwidthRutubeVariant(String baseUrl, String[] lines) {
        String pendingVariantLine = null;
        String lowestUri = null;
        int lowestBandwidth = Integer.MAX_VALUE;
        String lowestResolution = null;
        String lowestCodecs = null;
        int variantsSeen = 0;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingVariantLine = line;
                continue;
            }
            if (pendingVariantLine == null || line.isEmpty() || line.startsWith("#")) continue;

            Map<String, String> attrs = parseHlsAttributes(pendingVariantLine);
            int bandwidth = parseBandwidth(attrs.get("AVERAGE-BANDWIDTH"));
            if (bandwidth <= 0) bandwidth = parseBandwidth(attrs.get("BANDWIDTH"));
            variantsSeen++;

            String codecs = attrs.get("CODECS");
            // When CODECS is present, require an audio codec. This excludes
            // entries that explicitly advertise only a video representation.
            boolean hasAudio = hasAudioCodec(codecs);
            if (codecs != null && !codecs.trim().isEmpty() && !hasAudio) {
                pendingVariantLine = null;
                continue;
            }

            if (bandwidth > 0 && bandwidth < lowestBandwidth) {
                String resolved = resolveSafePlaylistUri(baseUrl, line);
                if (resolved != null) {
                    lowestBandwidth = bandwidth;
                    lowestUri = resolved;
                    lowestResolution = attrs.get("RESOLUTION");
                    lowestCodecs = codecs;
                }
            }
            pendingVariantLine = null;
        }

        if (lowestUri == null) return null;
        StringBuilder status = new StringBuilder("Используется самый низкобитрейтный HLS-вариант RuTube");
        status.append(" (").append(lowestBandwidth / 1000).append(" кбит/с");
        if (lowestResolution != null && !lowestResolution.isEmpty()) status.append(", ").append(lowestResolution);
        if (lowestCodecs != null && !lowestCodecs.isEmpty()) status.append(", ").append(lowestCodecs);
        status.append(" из ").append(variantsSeen).append(" вариантов; видео остаётся в потоке)");
        return new AudioPlaylistResolution(lowestUri, status.toString());
    }

    private static boolean hasAudioCodec(String codecs) {
        if (codecs == null || codecs.trim().isEmpty()) return false;
        for (String raw : codecs.toLowerCase(Locale.ROOT).split(",")) {
            String codec = raw.trim();
            if (codec.startsWith("mp4a") || codec.startsWith("ac-3") || codec.startsWith("ec-3")
                    || codec.startsWith("opus") || codec.startsWith("ac-4") || codec.startsWith("flac")
                    || codec.startsWith("alac")) return true;
        }
        return false;
    }

    private String playbackStatusMessage(boolean playing) {
        String state = playing ? "Идёт эфир" : "Пауза";
        if (currentIsMp3) return state + " · " + currentSourceName + " · Радиопоток MP3 без видео";
        if (audioModeStatus == null || audioModeStatus.isEmpty()) {
            return state + " · " + currentSourceName + " · режим аудио не определён";
        }
        return state + " · " + currentSourceName + " · " + audioModeStatus;
    }

    private String resolveSafePlaylistUri(String baseUrl, String playlistUri) {
        String resolved = resolvePlaylistUri(baseUrl, playlistUri);
        return resolved != null && isAllowedStream(resolved, false) ? resolved : null;
    }

    private static Map<String, String> parseHlsAttributes(String line) {
        Map<String, String> attrs = new HashMap<>();
        int colon = line.indexOf(':');
        if (colon < 0 || colon == line.length() - 1) return attrs;
        String text = line.substring(colon + 1);
        int position = 0;
        while (position < text.length()) {
            while (position < text.length() && (text.charAt(position) == ',' || Character.isWhitespace(text.charAt(position)))) position++;
            int equals = text.indexOf('=', position);
            if (equals < 0) break;
            String key = text.substring(position, equals).trim().toUpperCase(Locale.ROOT);
            position = equals + 1;
            String value;
            if (position < text.length() && text.charAt(position) == '"') {
                int closingQuote = text.indexOf('"', position + 1);
                if (closingQuote < 0) break;
                value = text.substring(position + 1, closingQuote);
                position = closingQuote + 1;
            } else {
                int comma = text.indexOf(',', position);
                if (comma < 0) {
                    value = text.substring(position).trim();
                    position = text.length();
                } else {
                    value = text.substring(position, comma).trim();
                    position = comma + 1;
                }
            }
            if (!key.isEmpty()) attrs.put(key, value);
        }
        return attrs;
    }

    private static boolean isAudioOnlyCodecList(String codecs) {
        if (codecs == null || codecs.trim().isEmpty()) return false;
        boolean hasAudioCodec = false;
        String lower = codecs.toLowerCase(Locale.ROOT);
        for (String rawCodec : lower.split(",")) {
            String codec = rawCodec.trim();
            if (codec.startsWith("avc") || codec.startsWith("hvc") || codec.startsWith("hev")
                    || codec.startsWith("vp8") || codec.startsWith("vp9") || codec.startsWith("vp09")
                    || codec.startsWith("av01") || codec.startsWith("theora")) return false;
            if (codec.startsWith("mp4a") || codec.startsWith("ac-3") || codec.startsWith("ec-3")
                    || codec.startsWith("opus") || codec.startsWith("ac-4") || codec.startsWith("flac")
                    || codec.startsWith("alac")) hasAudioCodec = true;
        }
        return hasAudioCodec;
    }

    private static int parseBandwidth(String value) {
        if (value == null) return -1;
        try { return Integer.parseInt(value.trim()); } catch (Exception ignored) { return -1; }
    }

    private static String resolvePlaylistUri(String baseUrl, String playlistUri) {
        try {
            return new URI(baseUrl).resolve(new URI(playlistUri.trim())).toString();
        } catch (Exception ignored) {
            try { return new URL(new URL(baseUrl), playlistUri.trim()).toExternalForm(); }
            catch (Exception alsoIgnored) { return null; }
        }
    }

    private static boolean isHlsManifest(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains(".m3u8");
    }

    private static boolean isDashManifest(String url) {
        return url != null && url.toLowerCase(Locale.ROOT).contains(".mpd");
    }

    private boolean isAllowedHlsStream(String url) {
        return isAllowedStream(url, false) && isHlsManifest(url);
    }

    private boolean isAllowedDashStream(String url) {
        return isAllowedStream(url, false) && isDashManifest(url);
    }

    private boolean isAllowedStream(String url, boolean mp3) {
        if (url == null) return false;
        try {
            android.net.Uri uri = android.net.Uri.parse(url);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || scheme == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if (mp3) {
                boolean allowedRadioHost = host.equals("kpradio32.streamr.ru") || host.equals("kpradio64.streamr.ru")
                        || host.equals("kpradio128.streamr.ru") || host.equals("kpradio.hostingradio.ru");
                return allowedRadioHost && (scheme.equals("http") || scheme.equals("https"));
            }
            if (!scheme.equals("https") || (!isHlsManifest(url) && !isDashManifest(url))) return false;
            boolean allowedHost = host.endsWith(".okcdn.ru") || host.endsWith(".vkuservideo.net") || host.endsWith(".userapi.com")
                    || host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com") || host.endsWith(".vk.com")
                    || host.equals("rutube.ru") || host.endsWith(".rutube.ru")
                    || host.equals("rtbcdn.ru") || host.endsWith(".rtbcdn.ru") || host.equals("rtbcdn.net") || host.endsWith(".rtbcdn.net");
            if (!allowedHost) return false;
            return !isDashManifest(url) || host.equals("rutube.ru") || host.endsWith(".rutube.ru")
                    || host.equals("rtbcdn.ru") || host.endsWith(".rtbcdn.ru") || host.equals("rtbcdn.net") || host.endsWith(".rtbcdn.net");
        } catch (Exception e) { return false; }
    }

    private void handlePlaybackFailure(String reason) {
        if (!currentIsMp3 && usingOptimizedHls && !fallbackHlsUrl.isEmpty()) {
            String fallback = fallbackHlsUrl;
            usingOptimizedHls = false;
            audioModeStatus = "Оптимизированный HLS-вариант не запустился; возвращаюсь к исходному HLS";
            streamGeneration++;
            handler.removeCallbacks(connectionTimeout);
            notifyListener(false, "Оптимизированный вариант недоступен. Возвращаюсь к исходному потоку…");
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) { }
                try { player.clearMediaItems(); } catch (Exception ignored) { }
            }
            startHlsPlayback(fallback, streamGeneration);
            return;
        }
        if (currentIsMp3 && !currentUrl.isEmpty() && radioReconnectAttempts < MAX_RADIO_RECONNECT_ATTEMPTS) {
            radioReconnectAttempts++;
            final String retryUrl = currentUrl;
            notifyListener(false, "Радиопоток прервался. Пробую переподключиться…");
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) { }
                try { player.clearMediaItems(); } catch (Exception ignored) { }
            }
            handler.removeCallbacks(connectionTimeout);
            long retryDelayMs = Math.min(1800L * radioReconnectAttempts, 5000L);
            handler.postDelayed(() -> {
                if (retryUrl.equals(currentUrl)) playStream(retryUrl);
            }, retryDelayMs);
            return;
        }
        failCurrentSource(reason);
    }

    private void failCurrentSource(String reason) {
        streamGeneration++;
        handler.removeCallbacks(connectionTimeout);
        String message = "SOURCE_ERROR: " + (reason == null ? "stream error" : reason);
        notifyListener(false, message);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.clearMediaItems(); } catch (Exception ignored) { }
        }
        currentUrl = "";
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        updateNotification();
        stopSelf();
    }

    private void startAsForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopPlaybackAndService() {
        streamGeneration++;
        handler.removeCallbacks(connectionTimeout);
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        currentUrl = "";
        notifyListener(false, "Остановлено");
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Радио КП — Прямой эфир", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Управление аудиотрансляцией Радио КП");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private PendingIntent serviceAction(String action, int requestCode) {
        Intent intent = new Intent(this, AudioPlaybackService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean playing = player != null && player.isPlaying();
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(playing ? android.R.drawable.ic_media_play : android.R.drawable.ic_media_pause)
                .setContentTitle("Радио КП — Прямой эфир")
                .setContentText(currentUrl.isEmpty() ? "Остановлено" : (playing ? "Идёт эфир · " + currentSourceName : "Пауза · " + currentSourceName))
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(!currentUrl.isEmpty())
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Пауза" : "Продолжить", serviceAction(ACTION_TOGGLE, 1))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", serviceAction(ACTION_STOP, 2));
        return builder.build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && !currentUrl.isEmpty()) manager.notify(NOTIFICATION_ID, buildNotification());
        else if (manager != null) manager.cancel(NOTIFICATION_ID);
    }

    private void notifyListener(boolean playing, String message) {
        PlaybackStateListener target = listener;
        if (target != null) {
            handler.post(() -> {
                if (listener == target) target.onPlaybackStateChanged(playing, message);
            });
        }
    }

    @Override public void onDestroy() {
        streamGeneration++;
        handler.removeCallbacks(connectionTimeout);
        if (player != null) player.release();
        player = null;
        super.onDestroy();
    }
}
