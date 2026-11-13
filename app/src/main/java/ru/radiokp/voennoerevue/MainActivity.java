package ru.radiokp.voennoerevue;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity implements AudioPlaybackService.PlaybackStateListener {
    private static final int SOURCE_VK = 0;
    private static final int SOURCE_RUTUBE = 1;
    private static final int SOURCE_RADIO = 2;
    private static final String[] SOURCE_NAMES = {"VK Видео", "RuTube", "Радио КП"};
    private static final String VK_LIVES_URL = "https://vkvideo.ru/@radiokp/lives";
    private static final String RUTUBE_LIVE_FEED_URL = "https://rutube.ru/feeds/stream/";
    private static final String PREFS = "radio_kp_preferences";
    private static final String VK_API_ENDPOINT = "https://vk.com/al_video.php";
    private static final String VK_PROBE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final int MAX_VK_PROBE_IDS = 48;
    private static final int VK_PROBE_PARALLELISM = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private TextView statusView;
    private TextView sourceView;
    private TextView elapsedView;
    private final StringBuilder vkDebugLog = new StringBuilder(32768);
    private volatile boolean vkDebugEnabled;
    private LinearLayout vkDebugPanel;
    private TextView vkDebugText;
    private ScrollView vkDebugScroll;
    private static final int MAX_VK_DEBUG_CHARS = 90000;
    private TextView diagnosticView;
    private String rutubeApiDiagnostic = "Ожидание ответа LIVE API…";
    private String rutubeHlsDiagnostic = "Ожидание проверки HLS-плейлиста…";
    private volatile String vkApiDiagnostic = "VK: ожидание LIVE API…";
    private Button playPauseButton;
    private Button[] sourceButtons = new Button[3];
    private ProgressBar liveProgress;
    private WebView webView;
    private LinearLayout webViewContainer;
    private AudioPlaybackService playbackService;
    private boolean bound = false;
    private volatile int currentSource = SOURCE_VK;
    private volatile int webSource = SOURCE_VK;
    private volatile String currentPageUrl = VK_LIVES_URL;
    private volatile String vkSelectedVideoUrl = "";
    private volatile boolean vkProbeRunning = false;
    private final LinkedHashSet<String> vkDashProbeSeen = new LinkedHashSet<>();
    private final LinkedHashSet<String> rutubeHlsProbeSeen = new LinkedHashSet<>();
    // True only after the VK feed scanner selected a card with an explicit LIVE badge.
    private volatile boolean vkLiveCardSelected = false;
    private volatile boolean streamStarted = false;
    private boolean autoStart = true;
    private boolean autoFailover = true;
    private int vkQualityKbps = 64;
    private int rutubeQualityKbps = 64;
    private int radioQualityKbps = 64;
    private int attemptedSourcesMask = 0;
    private volatile int attemptId = 0;
    private long liveStartEpochMs = 0L;
    // Fallback clock: when the platform does not expose the real broadcast start.
    private long sessionStartEpochMs = 0L;
    // Display scale for the elapsed-time bar. One hour is the fallback when metadata has no total duration.
    private static final long DEFAULT_LIVE_SCALE_MS = 60L * 60L * 1000L;
    private long liveScaleDurationMs = DEFAULT_LIVE_SCALE_MS;
    private boolean liveScaleFromMetadata = false;
    private boolean liveSessionActive = false;
    private boolean isPlaying = false;
    private Runnable sourceTimeout;

    private final Runnable elapsedTicker = new Runnable() {
        @Override public void run() {
            if (liveSessionActive && (liveStartEpochMs > 0L || sessionStartEpochMs > 0L) && elapsedView != null) {
                updateElapsedView();
                handler.postDelayed(this, 1000L);
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            playbackService = ((AudioPlaybackService.LocalBinder) service).getService();
            playbackService.setPlaybackStateListener(MainActivity.this);
            playbackService.setMaxAudioBitrate(currentSource == SOURCE_RUTUBE ? rutubeQualityKbps :
                    (currentSource == SOURCE_RADIO ? radioQualityKbps : vkQualityKbps));
            if (playbackService.hasStream()) {
                liveSessionActive = true;
                isPlaying = playbackService.isPlaying();
                ensureSessionStartTime();
                startElapsedTicker();
            } else if (!streamStarted) {
                clearLiveStartTime();
            }
            updateControls();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (playbackService != null) playbackService.clearPlaybackStateListener();
            playbackService = null;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        autoStart = preferences.getBoolean("auto_start", true);
        autoFailover = preferences.getBoolean("auto_failover", true);
        vkDebugEnabled = preferences.getBoolean("vk_debug_enabled", false);
        vkQualityKbps = preferences.getInt("vk_quality_kbps", 64);
        rutubeQualityKbps = preferences.getInt("rutube_quality_kbps", 64);
        radioQualityKbps = preferences.getInt("radio_quality_kbps", 64);
        liveStartEpochMs = preferences.getLong("live_start_epoch_ms", 0L);
        sessionStartEpochMs = preferences.getLong("session_start_epoch_ms", 0L);
        liveScaleDurationMs = preferences.getLong("live_scale_duration_ms", DEFAULT_LIVE_SCALE_MS);
        liveScaleFromMetadata = preferences.getBoolean("live_scale_from_metadata", false);
        if (liveScaleDurationMs < 5L * 60L * 1000L || liveScaleDurationMs > 24L * 60L * 60L * 1000L) {
            liveScaleDurationMs = DEFAULT_LIVE_SCALE_MS;
            liveScaleFromMetadata = false;
        }
        buildUi();
        String webViewInfo = "API<26";
        if (Build.VERSION.SDK_INT >= 26) {
            android.content.pm.PackageInfo webViewPackage = WebView.getCurrentWebViewPackage();
            webViewInfo = webViewPackage == null ? "unknown"
                    : webViewPackage.packageName + "/" + webViewPackage.versionName;
        }
        debugLog("APP", "onCreate · autoStart=" + autoStart + " · autoFailover=" + autoFailover
                + " · device=" + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android=" + Build.VERSION.RELEASE + " API=" + Build.VERSION.SDK_INT
                + " · WebView=" + webViewInfo);
        requestNotificationPermissionIfNeeded();
        if (autoStart) {
            statusView.setText("Запускаю основной источник — VK Видео…");
            handler.postDelayed(() -> startSource(SOURCE_VK, true), 450L);
        } else {
            statusView.setText("Нажмите «Старт», чтобы подключиться к эфиру.");
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 61);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(15, 18, 23));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(18), dp(22), dp(18));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("РАДИО КП", 25, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button settingsButton = smallButton("⚙", false);
        settingsButton.setTextSize(22);
        settingsButton.setOnClickListener(v -> showSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(48), dp(44)));
        root.addView(header);

        TextView subtitle = text("ПРЯМОЙ ЭФИР  ·  АУДИОТРАНСЛЯЦИЯ", 13, Color.rgb(255, 112, 64), true);
        LinearLayout.LayoutParams subtitleLp = new LinearLayout.LayoutParams(-1, -2);
        subtitleLp.topMargin = dp(5);
        subtitleLp.bottomMargin = dp(22);
        root.addView(subtitle, subtitleLp);


        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(dp(15), dp(13), dp(15), dp(13));
        statusCard.setBackground(roundRect(Color.rgb(29, 33, 41), dp(13), Color.rgb(45, 50, 60), dp(1)));
        sourceView = text("Источник: VK Видео", 14, Color.rgb(255, 125, 80), true);
        statusCard.addView(sourceView);
        statusView = text("Подготовка к подключению…", 14, Color.rgb(226, 230, 236), false);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(7);
        statusCard.addView(statusView, statusLp);
        root.addView(statusCard);

        liveProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        liveProgress.setMax(1000);
        liveProgress.setProgress(0);
        // Keep a clearly visible track even when the elapsed portion is only a few seconds.
        GradientDrawable progressTrack = new GradientDrawable();
        progressTrack.setColor(Color.rgb(48, 54, 64));
        progressTrack.setCornerRadius(dp(4));
        GradientDrawable progressFill = new GradientDrawable();
        progressFill.setColor(Color.rgb(255, 107, 53));
        progressFill.setCornerRadius(dp(4));
        ClipDrawable clippedProgress = new ClipDrawable(progressFill, Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable progressLayers = new LayerDrawable(new android.graphics.drawable.Drawable[]{progressTrack, clippedProgress});
        progressLayers.setId(0, android.R.id.background);
        progressLayers.setId(1, android.R.id.progress);
        liveProgress.setProgressDrawable(progressLayers);
        liveProgress.setMinimumHeight(dp(7));
        liveProgress.setIndeterminate(true);
        liveProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 107, 53)));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(7));
        progressLp.topMargin = dp(19);
        root.addView(liveProgress, progressLp);

        elapsedView = text("ОЖИДАНИЕ ЭФИРА", 12, Color.rgb(155, 164, 178), true);
        LinearLayout.LayoutParams elapsedLp = new LinearLayout.LayoutParams(-1, -2);
        elapsedLp.topMargin = dp(8);
        elapsedLp.bottomMargin = dp(18);
        root.addView(elapsedView, elapsedLp);

        diagnosticView = text("Диагностика эфира: ожидаю данные…", 10, Color.rgb(112, 122, 137), false);
        diagnosticView.setVisibility(View.GONE);
        LinearLayout.LayoutParams diagnosticLp = new LinearLayout.LayoutParams(-1, -2);
        diagnosticLp.bottomMargin = dp(10);
        root.addView(diagnosticView, diagnosticLp);

        vkDebugPanel = new LinearLayout(this);
        vkDebugPanel.setOrientation(LinearLayout.VERTICAL);

        TextView debugHeading = text("ЖУРНАЛ ДИАГНОСТИКИ", 12, Color.rgb(255, 125, 80), true);
        LinearLayout.LayoutParams debugHeadingLp = new LinearLayout.LayoutParams(-1, -2);
        debugHeadingLp.topMargin = dp(10);
        debugHeadingLp.bottomMargin = dp(6);
        vkDebugPanel.addView(debugHeading, debugHeadingLp);

        Button copyDebugButton = smallButton("КОПИРОВАТЬ DEBUG-ЛОГ", false);
        copyDebugButton.setOnClickListener(v -> copyVkDebugLog());
        vkDebugPanel.addView(copyDebugButton, new LinearLayout.LayoutParams(-1, dp(44)));

        vkDebugScroll = new ScrollView(this);
        vkDebugScroll.setFillViewport(false);
        vkDebugScroll.setBackground(roundRect(Color.rgb(20, 23, 29), dp(8), Color.rgb(48, 55, 66), dp(1)));
        vkDebugText = text("Журнал пока пуст.", 10, Color.rgb(190, 199, 211), false);
        vkDebugText.setTypeface(Typeface.MONOSPACE);
        vkDebugText.setPadding(dp(9), dp(8), dp(9), dp(8));
        vkDebugScroll.addView(vkDebugText, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams debugScrollLp = new LinearLayout.LayoutParams(-1, dp(230));
        debugScrollLp.topMargin = dp(7);
        debugScrollLp.bottomMargin = dp(12);
        vkDebugPanel.addView(vkDebugScroll, debugScrollLp);
        vkDebugPanel.setVisibility(vkDebugEnabled ? View.VISIBLE : View.GONE);
        root.addView(vkDebugPanel);

        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        Button previous = smallButton("‹  ИСТОЧНИК", false);
        previous.setOnClickListener(v -> switchRelativeSource(-1));
        transport.addView(previous, new LinearLayout.LayoutParams(0, dp(52), 1f));
        playPauseButton = smallButton("СТАРТ", true);
        playPauseButton.setOnClickListener(v -> togglePlayback());
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(0, dp(58), 1.2f);
        playLp.leftMargin = dp(8);
        playLp.rightMargin = dp(8);
        transport.addView(playPauseButton, playLp);
        Button next = smallButton("ИСТОЧНИК  ›", false);
        next.setOnClickListener(v -> switchRelativeSource(1));
        transport.addView(next, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(transport);

        Button stop = smallButton("СТОП", false);
        stop.setOnClickListener(v -> stopPlayback());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, dp(44));
        stopLp.topMargin = dp(10);
        root.addView(stop, stopLp);

        TextView sourceCaption = text("ИСТОЧНИК ВЕЩАНИЯ", 11, Color.rgb(130, 140, 154), true);
        LinearLayout.LayoutParams captionLp = new LinearLayout.LayoutParams(-1, -2);
        captionLp.topMargin = dp(25);
        captionLp.bottomMargin = dp(9);
        root.addView(sourceCaption, captionLp);

        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.HORIZONTAL);
        String[] names = {"VK Видео", "RuTube", "Радио КП"};
        for (int i = 0; i < 3; i++) {
            final int source = i;
            Button chip = smallButton(names[i], false);
            chip.setTextSize(12);
            chip.setOnClickListener(v -> startSource(source, false));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
            if (i > 0) chipLp.leftMargin = dp(6);
            choices.addView(chip, chipLp);
            sourceButtons[i] = chip;
        }
        root.addView(choices);

        TextView note = text("VK — основной  ·  RuTube — резерв  ·  Радио — последний резерв", 11, Color.rgb(130, 140, 154), false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(11);
        root.addView(note, noteLp);

        webViewContainer = new LinearLayout(this);
        webViewContainer.setOrientation(LinearLayout.VERTICAL);
        webViewContainer.setBackground(roundRect(Color.WHITE, dp(12), Color.rgb(55, 60, 69), dp(1)));
        webViewContainer.setVisibility(View.GONE);
        LinearLayout.LayoutParams webLp = new LinearLayout.LayoutParams(-1, dp(330));
        webLp.topMargin = dp(18);
        root.addView(webViewContainer, webLp);

        TextView footer = text("Аудио без видеодорожки  ·  минимальный фоновый плеер", 11, Color.rgb(112, 122, 137), false);
        LinearLayout.LayoutParams footerLp = new LinearLayout.LayoutParams(-1, -2);
        footerLp.topMargin = dp(25);
        root.addView(footer, footerLp);

        setContentView(scroll);
        updateControls();
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button smallButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setBackground(roundRect(primary ? Color.rgb(255, 103, 48) : Color.rgb(34, 39, 48), dp(13),
                primary ? Color.rgb(255, 125, 75) : Color.rgb(60, 66, 77), dp(1)));
        return button;
    }

    private GradientDrawable roundRect(int fill, int radius, int stroke, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) drawable.setStroke(strokeWidth, stroke);
        return drawable;
    }

    private void updateControls() {
        if (playPauseButton == null) return;
        boolean hasStream = playbackService != null && playbackService.hasStream();
        boolean playing = playbackService != null && playbackService.isPlaying();
        playPauseButton.setText(playing ? "ПАУЗА" : (hasStream ? "ПРОДОЛЖИТЬ" : "СТАРТ"));
        playPauseButton.setBackground(roundRect(Color.rgb(255, 103, 48), dp(13), Color.rgb(255, 125, 75), dp(1)));
        for (int i = 0; i < sourceButtons.length; i++) {
            if (sourceButtons[i] == null) continue;
            boolean selected = i == currentSource;
            sourceButtons[i].setTextColor(selected ? Color.rgb(255, 135, 91) : Color.WHITE);
            sourceButtons[i].setBackground(roundRect(selected ? Color.rgb(56, 38, 33) : Color.rgb(34, 39, 48), dp(13),
                    selected ? Color.rgb(255, 107, 53) : Color.rgb(60, 66, 77), dp(1)));
        }
        if (sourceView != null) sourceView.setText("ИСТОЧНИК  ·  " + SOURCE_NAMES[currentSource].toUpperCase(Locale.ROOT));
    }

    private void startSource(int source, boolean automatic) {
        if (source < SOURCE_VK || source > SOURCE_RADIO) return;
        debugLog("SOURCE", "start · source=" + SOURCE_NAMES[source] + " · automatic=" + automatic + " · nextAttempt=" + (attemptId + 1));
        if (source == SOURCE_RUTUBE) {
            synchronized (rutubeHlsProbeSeen) { rutubeHlsProbeSeen.clear(); }
        }
        cancelSourceTimeout();
        attemptId++;
        if (!automatic) attemptedSourcesMask = 0;
        int bit = 1 << source;
        if (automatic && (attemptedSourcesMask & bit) != 0) {
            tryNextSource("Источник уже проверялся");
            return;
        }
        attemptedSourcesMask |= bit;
        currentSource = source;
        vkLiveCardSelected = false;
        vkSelectedVideoUrl = "";
        vkProbeRunning = false;
        streamStarted = false;
        liveSessionActive = liveStartEpochMs > 0L || sessionStartEpochMs > 0L;
        isPlaying = false;
        if (liveSessionActive) startElapsedTicker();
        else handler.removeCallbacks(elapsedTicker);
        if (playbackService != null && playbackService.hasStream()) playbackService.stopPlayback();
        releaseWebView();
        updateControls();

        if (source == SOURCE_RADIO) {
            startRadioStream();
            return;
        }
        webSource = source;
        currentPageUrl = source == SOURCE_VK ? VK_LIVES_URL : RUTUBE_LIVE_FEED_URL;
        debugLog("SOURCE", "load feed · " + debugSafeUrl(currentPageUrl) + " · attempt=" + attemptId);
        statusView.setText(source == SOURCE_VK
                ? "Ищу текущую прямую трансляцию VK…"
                : "Ищу прямой эфир Радио КП в общей LIVE-ленте RuTube…");
        if (source == SOURCE_VK) {
            vkApiDiagnostic = "VK: открываю LIVE-ленту @radiokp…";
            renderVkDiagnostics();
        } else if (diagnosticView != null) {
            diagnosticView.setVisibility(View.GONE);
        }
        liveProgress.setIndeterminate(true);
        createWebViewIfNeeded();
        // Keep source discovery hidden; the WebView is used only to extract stream metadata.
        webViewContainer.setVisibility(View.GONE);
        webView.loadUrl(currentPageUrl);
        final int scheduledAttempt = attemptId;
        sourceTimeout = () -> {
            if (scheduledAttempt == attemptId && !streamStarted && currentSource == source) {
                debugLog("TIMEOUT", "источник " + SOURCE_NAMES[source] + " · timeout callback · attempt=" + scheduledAttempt);
                if (source == SOURCE_RUTUBE) {
                    statusView.setText("В LIVE-ленте RuTube не найден эфир Радио КП. Перехожу к следующему источнику…");
                } else {
                    statusView.setText("VK не ответил или эфир недоступен. Пробую RuTube…");
                }
                tryNextSource("Время ожидания истекло");
            }
        };
        handler.postDelayed(sourceTimeout, source == SOURCE_VK ? 55000L : 35000L);
    }

    private void startRadioStream() {
        streamStarted = true;
        liveSessionActive = true;
        updateElapsedView();
        statusView.setText("Подключаю резервный радиопоток · " + radioQualityKbps + " кбит/с…");
        liveProgress.setIndeterminate(true);
        String url;
        if (radioQualityKbps <= 32) url = "http://kpradio32.streamr.ru/";
        else if (radioQualityKbps <= 64) url = "http://kpradio64.streamr.ru/";
        else url = "http://kpradio128.streamr.ru/";
        startPlayerService(url, "https://radiokp.ru/player", SOURCE_NAMES[SOURCE_RADIO], radioQualityKbps, true);
    }

    private void startPlayerService(String url, String referer, String sourceName, int qualityKbps, boolean mp3) {
        ArrayList<String> urls = new ArrayList<>();
        if (url != null) urls.add(url);
        startPlayerService(url, urls, referer, sourceName, qualityKbps, mp3);
    }

    private void startPlayerService(String url, ArrayList<String> urls, String referer, String sourceName, int qualityKbps, boolean mp3) {
        startPlayerService(url, urls, new ArrayList<>(), referer, sourceName, qualityKbps, mp3);
    }

    private void startPlayerService(String url, ArrayList<String> urls, ArrayList<String> dashUrls, String referer, String sourceName, int qualityKbps, boolean mp3) {
        startPlayerService(url, urls, dashUrls, referer, sourceName, qualityKbps, mp3, null);
    }

    private void startPlayerService(String url, ArrayList<String> urls, ArrayList<String> dashUrls, String referer, String sourceName, int qualityKbps, boolean mp3, String cookie) {
        debugLog("PLAYER_START", "source=" + sourceName + " · stream=" + debugSafeUrl(url)
                + " · hls=" + debugCandidateHosts(urls) + " · dashCount=" + (dashUrls == null ? 0 : dashUrls.size())
                + " · qualityLimit=" + qualityKbps + "kbps · referer=" + debugSafeUrl(referer));
        Intent intent = new Intent(this, AudioPlaybackService.class)
                .setAction(AudioPlaybackService.ACTION_PLAY)
                .putExtra(AudioPlaybackService.EXTRA_URL, url)
                .putStringArrayListExtra(AudioPlaybackService.EXTRA_URL_CANDIDATES, urls)
                .putStringArrayListExtra(AudioPlaybackService.EXTRA_DASH_URL_CANDIDATES, dashUrls)
                .putExtra(AudioPlaybackService.EXTRA_REFERER, referer)
                .putExtra(AudioPlaybackService.EXTRA_SOURCE_NAME, sourceName)
                .putExtra(AudioPlaybackService.EXTRA_MAX_AUDIO_BITRATE, qualityKbps)
                .putExtra(AudioPlaybackService.EXTRA_IS_MP3, mp3)
                .putExtra(AudioPlaybackService.EXTRA_COOKIE, cookie);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        updateControls();
    }

    private void tryNextSource(String reason) {
        debugLog("FAILOVER", "reason=" + reason + " · from=" + SOURCE_NAMES[currentSource] + " · triedMask=" + attemptedSourcesMask);
        cancelSourceTimeout();
        if (!autoFailover) {
            statusView.setText("Источник недоступен. Можно выбрать другой вручную.");
            liveProgress.setIndeterminate(false);
            liveProgress.setProgress(0);
            return;
        }
        for (int i = currentSource + 1; i <= SOURCE_RADIO; i++) {
            if ((attemptedSourcesMask & (1 << i)) == 0) {
                statusView.setText("Источник недоступен. Переключаюсь на " + SOURCE_NAMES[i] + "…");
                startSource(i, true);
                return;
            }
        }
        isPlaying = false;
        liveSessionActive = false;
        clearLiveStartTime();
        liveProgress.setIndeterminate(false);
        liveProgress.setProgress(0);
        elapsedView.setText("НЕТ ДОСТУПНОГО ЭФИРА");
        statusView.setText("Все три источника недоступны. Можно попробовать ещё раз или выбрать источник вручную.");
        updateControls();
    }

    private void switchRelativeSource(int direction) {
        int next = (currentSource + direction + 3) % 3;
        startSource(next, false);
    }

    private void togglePlayback() {
        if (playbackService != null && playbackService.hasStream()) {
            playbackService.togglePlayback();
        } else {
            startSource(currentSource, false);
        }
        updateControls();
    }

    private void stopPlayback() {
        cancelSourceTimeout();
        attemptId++;
        streamStarted = false;
        isPlaying = false;
        liveSessionActive = false;
        clearLiveStartTime();
        handler.removeCallbacks(elapsedTicker);
        if (playbackService != null) playbackService.stopPlayback();
        releaseWebView();
        liveProgress.setIndeterminate(false);
        liveProgress.setProgress(0);
        elapsedView.setText("ОСТАНОВЛЕНО");
        statusView.setText("Воспроизведение остановлено. Нажмите «Старт», чтобы подключиться снова.");
        updateControls();
    }

    private void createWebViewIfNeeded() {
        if (webView != null) return;
        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webViewContainer.addView(webView, new LinearLayout.LayoutParams(-1, -1));
        configureWebView(webSource);
    }

    private void releaseWebView() {
        WebView old = webView;
        webView = null;
        if (old != null) {
            try { old.stopLoading(); } catch (Exception ignored) { }
            try { old.removeJavascriptInterface("AudioBridge"); } catch (Exception ignored) { }
            try { old.setWebViewClient(null); } catch (Exception ignored) { }
            try { old.setWebChromeClient(null); } catch (Exception ignored) { }
            try {
                if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
            } catch (Exception ignored) { }
            try { old.destroy(); } catch (Exception ignored) { }
        }
        if (webViewContainer != null) webViewContainer.setVisibility(View.GONE);
    }

    private void configureWebView(final int source) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadsImagesAutomatically(false);
        settings.setBlockNetworkImage(true);
        settings.setSupportMultipleWindows(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);
        webView.addJavascriptInterface(new StreamBridge(source), "AudioBridge");
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    debugLog("WEB_CONSOLE", "JS error · line=" + message.lineNumber() + " · " + safeShort(message.message(), 220));
                }
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentPageUrl = url;
                debugLog("WEB", "page started · " + debugSafeUrl(url));
                if (source == SOURCE_VK && isSelectedVkVideoPage(url)) {
                    // Navigation to a video page was initiated from the selected LIVE card or by the user.
                    vkLiveCardSelected = true;
                } else if (source == SOURCE_VK && url.contains("/@radiokp/lives")) {
                    vkLiveCardSelected = false;
                }
                if (source == SOURCE_VK && (url.contains("login.vk.ru") || url.contains("login.vk.com"))) {
                    statusView.setText("VK просит авторизоваться. Войдите во встроенной странице и вернитесь к эфирам.");
                } else if (source == SOURCE_RUTUBE && url.contains("/feeds/stream")) {
                    statusView.setText("Открыта лента прямых эфиров RuTube. Ищу канал Радио КП…");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                currentPageUrl = url;
                debugLog("WEB", "page finished · " + debugSafeUrl(url) + " · title=" + safeShort(view.getTitle(), 100));
                if (source == SOURCE_VK) {
                    String feedCookie = CookieManager.getInstance().getCookie(VK_LIVES_URL);
                    String apiCookie = CookieManager.getInstance().getCookie(VK_API_ENDPOINT);
                    debugLog("VK_AUTH", "feedCookie=" + (feedCookie == null || feedCookie.isEmpty() ? "absent" : "present")
                            + " (" + (feedCookie == null ? 0 : feedCookie.length()) + " chars)"
                            + " · apiCookie=" + (apiCookie == null || apiCookie.isEmpty() ? "absent" : "present")
                            + " (" + (apiCookie == null ? 0 : apiCookie.length()) + " chars)");
                }
                if (source == SOURCE_VK && isVkPage(url)) {
                    debugLog("VK", "installing player hook on " + debugSafeUrl(url));
                    view.evaluateJavascript(INSTALL_VK_HOOK_SCRIPT, null);
                    if (isVkLiveFeedPage(url)) {
                        debugLog("VK", "injecting LIVE feed scanner");
                        view.postDelayed(() -> {
                            if (webView == view && currentSource == SOURCE_VK && !streamStarted) {
                                view.evaluateJavascript(AUTO_OPEN_VK_LIVE_SCRIPT, null);
                            }
                        }, 500L);
                    } else if (isSelectedVkVideoPage(url)) {
                        debugLog("VK", "video page detected; injecting single video probe");
                        view.postDelayed(() -> {
                            if (webView == view && currentSource == SOURCE_VK && !streamStarted) {
                                view.evaluateJavascript(PROBE_CURRENT_VK_VIDEO_SCRIPT, null);
                            }
                        }, 500L);
                    }
                } else if (source == SOURCE_RUTUBE && isRutubePage(url)) {
                    view.evaluateJavascript(INSTALL_RUTUBE_HOOK_SCRIPT, null);
                    if (url.contains("/feeds/stream")) view.evaluateJavascript(AUTO_OPEN_RUTUBE_LIVE_SCRIPT, null);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    debugLog("WEB_ERROR", "main frame · code=" + (error == null ? "?" : error.getErrorCode())
                            + " · " + (error == null ? "" : safeShort(error.getDescription().toString(), 180))
                            + " · " + debugSafeUrl(request.getUrl().toString()));
                }
            }

            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (request != null && request.isForMainFrame()) {
                    debugLog("WEB_HTTP", "main frame HTTP " + (response == null ? "?" : response.getStatusCode())
                            + " · " + debugSafeUrl(request.getUrl().toString()));
                }
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String method = request.getMethod();
                String host = request.getUrl().getHost();
                if (!"GET".equalsIgnoreCase(method)) return super.shouldInterceptRequest(view, request);
                if (source == SOURCE_VK && url.contains("video.getVideoDiscover") && isVkHost(host)) {
                    debugLog("VK_DISCOVER", "seen method=" + method + " · " + debugSafeUrl(url));
                    if ("GET".equalsIgnoreCase(method)) return fetchAndInspect(url, request.getRequestHeaders(), source);
                    debugLog("VK_DISCOVER", "not intercepted: method is not GET");
                }
                if (source == SOURCE_RUTUBE && (url.contains("/api/play/options/") || url.contains("/api/video/")) && isRutubeHost(host)) {
                    return fetchAndInspect(url, request.getRequestHeaders(), source);
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
    }

    private WebResourceResponse fetchAndInspect(String url, Map<String, String> originalHeaders, int source) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(18000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("GET");
            for (String name : new String[]{"User-Agent", "Referer", "Origin", "Accept", "Accept-Language"}) {
                String value = findHeaderIgnoreCase(originalHeaders, name);
                if (value != null) connection.setRequestProperty(name, value);
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if ((cookie == null || cookie.isEmpty()) && originalHeaders != null) cookie = findHeaderIgnoreCase(originalHeaders, "Cookie");
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = input == null ? new byte[0] : readAll(input, 4 * 1024 * 1024);
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (source == SOURCE_VK) {
                debugLog("VK_HTTP", "HTTP " + code + " · type=" + connection.getContentType()
                        + " · bytes=" + bytes.length + " · cookie=" + (cookie == null || cookie.isEmpty() ? "absent" : "present")
                        + " · url=" + debugSafeUrl(url) + " · json=" + debugJsonShape(body));
            }
            boolean selectedVkVideo = source != SOURCE_VK
                    || isVkLiveFeedPage(currentPageUrl)
                    || (vkLiveCardSelected && isSelectedVkVideoPage(currentPageUrl));
            boolean selectedRutubeMetadata = source != SOURCE_RUTUBE
                    || !url.contains("/api/video/")
                    || isSelectedRutubeVideoMetadataUrl(url);
            boolean selectedSourceMetadata = selectedVkVideo && selectedRutubeMetadata;
            long liveStartMs = selectedSourceMetadata ? extractLiveStartTimeMs(body, source) : 0L;
            long liveDurationMs = selectedSourceMetadata ? extractLiveDurationMs(body, source) : 0L;
            if (source == SOURCE_RUTUBE && selectedRutubeMetadata) {
                String diagnostic = buildRutubeApiDiagnostic(url, code, body, liveStartMs, liveDurationMs);
                runOnUiThread(() -> {
                    rutubeApiDiagnostic = diagnostic;
                    renderRutubeDiagnostics();
                });
            }
            if (liveDurationMs > 0L) runOnUiThread(() -> acceptLiveDuration(liveDurationMs, source));
            ArrayList<String> resolved = selectedSourceMetadata
                    ? (source == SOURCE_VK
                        ? (url.contains("video.getVideoDiscover") ? extractVkLiveDiscoverHlsUrls(body) : extractVkHlsUrls(body))
                        : extractRutubeLiveHlsUrls(body))
                    : new ArrayList<>();
            ArrayList<String> dashResolved = source == SOURCE_RUTUBE && selectedSourceMetadata
                    ? extractRutubeLiveDashUrls(body)
                    : (source == SOURCE_VK && selectedSourceMetadata && !resolved.isEmpty()
                        ? extractVkDashUrls(body) : new ArrayList<>());
            if (source == SOURCE_RUTUBE) {
                ArrayList<String> diagnosticHls = extractRutubeDiagnosticHlsUrls(body);
                ArrayList<String> diagnosticDash = extractRutubeDiagnosticDashUrls(body);
                debugLog("RUTUBE_API", "endpoint=" + (url.contains("/api/video/") ? "video API" : "play/options or feed")
                        + " · HTTP=" + code + " · type=" + safeShort(connection.getContentType(), 80)
                        + " · bytes=" + bytes.length + " · cookie=" + (cookie == null || cookie.isEmpty() ? "absent" : "present")
                        + " · selected=" + selectedSourceMetadata + " · json=" + debugJsonShape(body));
                debugLog("RUTUBE_JSON_FIELDS", summarizeRutubeJsonFields(body));
                debugLog("RUTUBE_FORMAT_URLS", "liveHLS=" + debugCandidateHosts(resolved)
                        + " · allHLS=" + debugCandidateHosts(diagnosticHls)
                        + " · liveDASH=" + debugCandidateHosts(dashResolved)
                        + " · allDASH=" + debugCandidateHosts(diagnosticDash));
                if (selectedSourceMetadata) {
                    probeRutubeHlsManifests(diagnosticHls, cookie,
                            findHeaderIgnoreCase(originalHeaders, "User-Agent"),
                            findHeaderIgnoreCase(originalHeaders, "Referer"));
                    probeRutubeDashManifests(diagnosticDash, cookie,
                            findHeaderIgnoreCase(originalHeaders, "User-Agent"),
                            findHeaderIgnoreCase(originalHeaders, "Referer"));
                }
            }
            if (source == SOURCE_VK) {
                debugLog("VK_PARSE", "selected=" + selectedSourceMetadata + " · HLS=" + resolved.size()
                        + " · HLS hosts=" + debugCandidateHosts(resolved)
                        + " · liveStart=" + liveStartMs);
                debugLog("VK_DASH_FOUND", "candidateCount=" + dashResolved.size()
                        + " · hosts=" + debugCandidateHosts(dashResolved));
                probeVkDashManifests(dashResolved);
            }
            if (!resolved.isEmpty() || !dashResolved.isEmpty()) {
                handleStreamResolved(resolved, dashResolved, source, liveStartMs);
            } else if (liveStartMs > 0L) {
                runOnUiThread(() -> acceptLiveStartTime(liveStartMs, source));
            }
            if (resolved.isEmpty() && dashResolved.isEmpty() && source == SOURCE_RUTUBE && isExplicitNonLiveRutubeResponse(body)) {
                runOnUiThread(() -> {
                    if (webView != null && webSource == SOURCE_RUTUBE && !streamStarted) {
                        webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){try{v.muted=true;v.pause()}catch(e){}})", null);
                        statusView.setText("Это запись из архива, а не прямой эфир. Выберите на канале видео с пометкой LIVE.");
                    }
                });
            }
            Map<String, String> responseHeaders = new HashMap<>();
            responseHeaders.put("Access-Control-Allow-Origin", "*");
            responseHeaders.put("Cache-Control", "no-store");
            return new WebResourceResponse("application/json", "UTF-8", code > 0 ? code : 200,
                    code >= 400 ? "Source response" : "OK", responseHeaders, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            if (source == SOURCE_VK) debugLog("VK_HTTP_ERROR", e.getClass().getSimpleName() + " · " + safeShort(e.getMessage(), 220));
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String findHeaderIgnoreCase(Map<String, String> headers, String target) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(target)) return entry.getValue();
        }
        return null;
    }

    private static byte[] readAll(InputStream input, int maxBytes) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > maxBytes) throw new IllegalStateException("Source response too large");
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static final String INSTALL_VK_HOOK_SCRIPT = "(function(){try{if(window.__radioKpHook)return;window.__radioKpHook=true;" +
            "function hideVideo(){try{document.querySelectorAll('video').forEach(function(v){v.style.setProperty('position','fixed','important');v.style.setProperty('left','-5px','important');v.style.setProperty('top','-5px','important');v.style.setProperty('width','1px','important');v.style.setProperty('height','1px','important');v.style.setProperty('opacity','0.001','important');v.style.setProperty('pointer-events','none','important');v.controls=false;v.muted=true;try{v.pause();v.removeAttribute('autoplay')}catch(e){}})}catch(e){}}" +
            "hideVideo();try{new MutationObserver(hideVideo).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}" +
            "function report(t){try{if(typeof t==='string'&&(t.indexOf('playerParams')!==-1||t.indexOf('hls')!==-1||t.indexOf('live_mp4')!==-1||t.indexOf('postlive_mp4')!==-1)&&window.AudioBridge)window.AudioBridge.onPossibleStreamText(t)}catch(e){}}" +
            "var f=window.fetch;if(f)window.fetch=function(){return f.apply(this,arguments).then(function(r){try{r.clone().text().then(report)}catch(e){}return r})};" +
            "try{if(window.cur&&window.cur.apiPrefetchCache)report(JSON.stringify({apiPrefetchCache:window.cur.apiPrefetchCache}))}catch(e){}" +
            "var op=XMLHttpRequest.prototype.open,se=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){this.__radioKpUrl=String(u||'');return op.apply(this,arguments)};" +
            "XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{report(this.responseText)}catch(e){}});return se.apply(this,arguments)};" +
            "}catch(e){}})();";

    /**
     * VK's channel LIVE page is a video list. Query each video through the same POST
     * endpoint used by VK's player (al_video.php, act=show), then let Java accept
     * only the item whose player.params[0].live is 2.
     */
    private static final String AUTO_OPEN_VK_LIVE_SCRIPT = "(function(){try{" +
            "if(window.__radioKpLiveProbe)return;window.__radioKpLiveProbe=true;" +
            "function idFrom(h){try{h=decodeURIComponent(String(h||''))}catch(e){}" +
            "var m=h.match(/(?:video|live)(-?[0-9]+)_([0-9]+)/i);if(m)return m[1]+'_'+m[2];" +
            "try{var u=new URL(h,location.href),o=u.searchParams.get('oid'),i=u.searchParams.get('id');if(o&&i)return o+'_'+i}catch(e){}return null;}" +
            "function collect(){var ids=[],seen={};function add(h){var id=idFrom(h);if(id&&!seen[id]&&ids.length<" + MAX_VK_PROBE_IDS + "){seen[id]=1;ids.push(id);}}" +
            "try{var anchors=[].slice.call(document.querySelectorAll('a[href]'));" +
            "anchors.forEach(function(a){var p=a,hit=false;for(var d=0;p&&d<6;d++,p=p.parentElement){var t=(p.innerText||p.textContent||'').toLowerCase().replace(/\\s+/g,' ');if(t.length<1200&&(t.indexOf('радио кп')>=0||t.indexOf('радио комсомольская правда')>=0||t.indexOf('комсомольская правда')>=0)){hit=true;break;}}if(hit)add(a.href);});" +
            "anchors.forEach(function(a){add(a.href);});" +
            "var html=document.documentElement?document.documentElement.innerHTML:'';" +
            "var re=/(?:video|live)(-?[0-9]+)_([0-9]+)/ig,m;while((m=re.exec(html))&&ids.length<" + MAX_VK_PROBE_IDS + ")add(m[0]);" +
            "}catch(e){}return ids;}" +
            "async function probe(){var ids=[],seen={};for(var pass=0;pass<12;pass++){var found=collect();found.forEach(function(id){if(!seen[id]&&ids.length<" + MAX_VK_PROBE_IDS + "){seen[id]=1;ids.push(id);}});if(ids.length>=" + MAX_VK_PROBE_IDS + ")break;await new Promise(function(r){setTimeout(r,600)});}" +
            "if(window.AudioBridge)window.AudioBridge.onVkProbeCandidateCount(ids.length);" +
            "if(!ids.length){if(window.AudioBridge)window.AudioBridge.onVkProbeFinished('В LIVE-разделе не найдено ссылок на видеокарточки');return;}" +
            "if(window.AudioBridge)window.AudioBridge.onVkProbeCandidates(JSON.stringify(ids));}" +
            "setTimeout(probe,300);" +
            "}catch(e){if(window.AudioBridge)window.AudioBridge.onVkProbeFinished('Ошибка запуска VK API probe: '+String(e));}})();";

    private static final String PROBE_CURRENT_VK_VIDEO_SCRIPT = "(function(){try{" +
            "var h=location.href;try{h=decodeURIComponent(h)}catch(e){};var m=h.match(/(?:video|live)(-?[0-9]+)_([0-9]+)/i);" +
            "if(!m){if(window.AudioBridge)window.AudioBridge.onVkProbeFinished('Не удалось определить ID открытого видео VK');return;}" +
            "var id=m[1]+'_'+m[2];if(window.AudioBridge)window.AudioBridge.onVkProbeCandidates(JSON.stringify([id]));" +
            "}catch(e){if(window.AudioBridge)window.AudioBridge.onVkProbeFinished('Ошибка проверки видео VK: '+String(e));}})();";

    private static final String INSTALL_RUTUBE_HOOK_SCRIPT = "(function(){try{if(window.__radioKpRutubeHook)return;window.__radioKpRutubeHook=true;" +
            "function hideVideo(){try{document.querySelectorAll('video').forEach(function(v){v.style.setProperty('position','fixed','important');v.style.setProperty('left','-5px','important');v.style.setProperty('top','-5px','important');v.style.setProperty('width','1px','important');v.style.setProperty('height','1px','important');v.style.setProperty('opacity','0.001','important');v.style.setProperty('pointer-events','none','important');v.controls=false;v.muted=true;try{v.pause();v.removeAttribute('autoplay')}catch(e){}})}catch(e){}}" +
            "hideVideo();try{new MutationObserver(hideVideo).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}" +
            "function report(t){try{if(typeof t==='string'&&(t.indexOf('video_balancer')!==-1||t.indexOf('live_streams')!==-1||t.indexOf('is_live')!==-1||t.indexOf('is_livestream')!==-1||t.indexOf('.m3u8')!==-1||t.indexOf('.mpd')!==-1)&&window.AudioBridge)window.AudioBridge.onPossibleStreamText(t)}catch(e){}}" +
            "var f=window.fetch;if(f)window.fetch=function(){return f.apply(this,arguments).then(function(r){try{r.clone().text().then(report)}catch(e){}return r})};" +
            "var op=XMLHttpRequest.prototype.open,se=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){this.__radioKpUrl=String(u||'');return op.apply(this,arguments)};" +
            "XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{report(this.responseText)}catch(e){}});return se.apply(this,arguments)};" +
            "}catch(e){}})();";

    private static final String AUTO_OPEN_RUTUBE_LIVE_SCRIPT = "(function(){try{" +
            "function scan(){try{var els=[].slice.call(document.querySelectorAll('a[href*=\"/video/\"]'));" +
            "for(var i=0;i<els.length;i++){var a=els[i],p=a;" +
            "for(var d=0;p&&d<7;d++,p=p.parentElement){var t=(p.innerText||p.textContent||'').toLowerCase().replace(/\\s+/g,' ').trim();" +
            "if(t.length<1600&&(t.indexOf('радио комсомольская правда')>=0||t.indexOf('радио «комсомольская правда»')>=0)){a.click();return true;}}}" +
            "}catch(e){}return false;}" +
            "var tries=0;function tick(){if(scan())return;if(++tries<32)setTimeout(tick,1000);}" +
            "setTimeout(tick,1200);" +
            "}catch(e){}})();";

    /**
     * Use VK's canonical al_video.php endpoint natively. A WebView POST from
     * vkvideo.ru to vk.com is cross-origin and may be blocked by browser policy.
     */
    private static final class VkProbeResult {
        final String videoId;
        final String summary;
        final ArrayList<String> streams;
        final long liveStartMs;
        VkProbeResult(String videoId, String summary, ArrayList<String> streams, long liveStartMs) {
            this.videoId = videoId;
            this.summary = summary;
            this.streams = streams;
            this.liveStartMs = liveStartMs;
        }
    }

    private VkProbeResult requestVkVideoNative(String videoId, String cookie) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(VK_API_ENDPOINT).openConnection();
            connection.setConnectTimeout(4500);
            connection.setReadTimeout(5500);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", VK_PROBE_USER_AGENT);
            connection.setRequestProperty("Referer", VK_API_ENDPOINT);
            connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            connection.setRequestProperty("Accept", "*/*");
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            String form = "act=show&video=" + URLEncoder.encode(videoId, "UTF-8") + "&al=1";
            byte[] formBytes = form.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(formBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(formBytes);
            }
            int code = connection.getResponseCode();
            InputStream response = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] bytes = response == null ? new byte[0] : readAll(response, 4 * 1024 * 1024);
            String body = new String(bytes, StandardCharsets.UTF_8);
            ArrayList<String> streams = code >= 200 && code < 300
                    ? extractVkHlsUrls(body) : new ArrayList<>();
            String type = connection.getContentType();
            String summary = "HTTP " + code + " · " + (type == null ? "без Content-Type" : type)
                    + " · bytes=" + bytes.length
                    + " · " + findVkLiveStateSummary(body) + " · HLS " + streams.size()
                    + " · cookie=" + (cookie == null || cookie.isEmpty() ? "нет" : "есть")
                    + " · " + debugJsonShape(body);
            long liveStart = streams.isEmpty() ? 0L : extractLiveStartTimeMs(body, SOURCE_VK);
            return new VkProbeResult(videoId, summary, streams, liveStart);
        } catch (Exception error) {
            return new VkProbeResult(videoId, error.getClass().getSimpleName()
                    + " · " + safeShort(error.getMessage(), 160), new ArrayList<>(), 0L);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void probeVkVideosNative(ArrayList<String> videoIds, int expectedAttemptId) {
        debugLog("VK_PROBE_START", "videoIds=" + videoIds.size() + " · parallel=" + VK_PROBE_PARALLELISM
                + " · IDs=" + videoIds + " · attempt=" + expectedAttemptId);
        Thread worker = new Thread(() -> {
            String cookie = CookieManager.getInstance().getCookie(VK_API_ENDPOINT);
            if (cookie == null || cookie.isEmpty()) cookie = CookieManager.getInstance().getCookie(VK_LIVES_URL);
            final String requestCookie = cookie;
            int checked = 0;
            String lastResult = "ответа нет";
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(VK_PROBE_PARALLELISM, videoIds.size()));
            CompletionService<VkProbeResult> completed = new ExecutorCompletionService<>(pool);
            int submitted = 0;
            for (String videoId : videoIds) {
                if (expectedAttemptId != attemptId || currentSource != SOURCE_VK || webSource != SOURCE_VK || streamStarted) break;
                completed.submit(() -> requestVkVideoNative(videoId, requestCookie));
                submitted++;
            }
            pool.shutdown();
            long deadline = System.currentTimeMillis() + 42000L;
            try {
                while (checked < submitted
                        && expectedAttemptId == attemptId && currentSource == SOURCE_VK
                        && webSource == SOURCE_VK && !streamStarted) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0L) break;
                    Future<VkProbeResult> future = completed.poll(remaining, TimeUnit.MILLISECONDS);
                    if (future == null) break;
                    VkProbeResult result = future.get();
                    checked++;
                    lastResult = result.videoId + " · " + result.summary;
                    debugLog("VK_AL_VIDEO", "id=" + result.videoId + " · " + result.summary
                            + " · hlsHosts=" + debugCandidateHosts(result.streams));
                    if (!result.streams.isEmpty()
                            && expectedAttemptId == attemptId && currentSource == SOURCE_VK && !streamStarted) {
                        vkSelectedVideoUrl = "https://vkvideo.ru/video" + result.videoId;
                        currentPageUrl = vkSelectedVideoUrl;
                        vkLiveCardSelected = true;
                        setVkDiagnostic("Найден активный эфир · " + lastResult + " · запускаю аудио");
                        handleStreamResolved(result.streams, new ArrayList<>(), SOURCE_VK, result.liveStartMs);
                        break;
                    }
                }
            } catch (Exception error) {
                lastResult = error.getClass().getSimpleName();
            } finally {
                pool.shutdownNow();
            }
            if (expectedAttemptId == attemptId && currentSource == SOURCE_VK && !streamStarted) {
                debugLog("VK_PROBE_END", "checked=" + checked + "/" + videoIds.size()
                        + " · last=" + lastResult + " · elapsedMs=" + (System.currentTimeMillis() - (deadline - 42000L)));
                setVkDiagnostic("Проверено " + checked + " из " + videoIds.size()
                        + " видео · активный live=2 не найден · " + lastResult);
            }
            vkProbeRunning = false;
        }, "RadioKp-VkNativeProbe");
        worker.setDaemon(true);
        worker.start();
    }

    private final class StreamBridge {
        private final int source;
        StreamBridge(int source) { this.source = source; }
        @JavascriptInterface public void onSelectedVkVideoUrl(String url) {
            if (source != SOURCE_VK || source != currentSource || source != webSource || !isSelectedVkVideoPage(url)) return;
            vkLiveCardSelected = true;
            currentPageUrl = url;
            debugLog("VK_SELECTION", "selected page=" + debugSafeUrl(url));
        }
        @JavascriptInterface public void onLiveScanFailed() {
            debugLog("VK_SCAN_FAIL", "scanner reports no LIVE card");
            if (source != SOURCE_VK || source != currentSource || source != webSource || streamStarted) return;
            runOnUiThread(() -> {
                if (currentSource == SOURCE_VK && !streamStarted) {
                    statusView.setText("Не нашёл карточку Радио КП с явной меткой LIVE. Запись вместо эфира запускать не буду.");
                }
            });
        }
        @JavascriptInterface public void onVkProbeCandidateCount(int count) {
            if (source != SOURCE_VK || source != currentSource || streamStarted) return;
            debugLog("VK_SCAN", "DOM scanner candidate count=" + count);
            setVkDiagnostic("LIVE-лента открыта · найдено видеокарточек: " + count + " · проверяю их через VK API");
        }
        @JavascriptInterface public void onVkProbeCandidates(String json) {
            if (source != SOURCE_VK || source != currentSource || source != webSource || streamStarted || vkProbeRunning) return;
            ArrayList<String> ids = new ArrayList<>();
            try {
                JSONArray array = new JSONArray(json == null ? "[]" : json);
                for (int i = 0; i < array.length() && ids.size() < MAX_VK_PROBE_IDS; i++) {
                    String id = array.optString(i, "");
                    if (id.matches("-?\\d+_\\d+") && !ids.contains(id)) ids.add(id);
                }
            } catch (Exception ignored) { }
            if (ids.isEmpty()) {
                setVkDiagnostic("VK: видеокарточки не содержат распознаваемых ID");
                return;
            }
            vkProbeRunning = true;
            debugLog("VK_SCAN", "candidate IDs (" + ids.size() + ")=" + ids);
            setVkDiagnostic("Найдено видео: " + ids.size() + " · проверяю параллельно до " + VK_PROBE_PARALLELISM);
            probeVkVideosNative(ids, attemptId);
        }
        @JavascriptInterface public boolean onVkProbeResponse(String videoId, int httpCode, String body) {
            if (source != SOURCE_VK || source != currentSource || source != webSource || streamStarted
                    || body == null || body.length() > 4 * 1024 * 1024) return false;
            ArrayList<String> streams = extractVkHlsUrls(body);
            String state = findVkLiveStateSummary(body);
            debugLog("VK_AL_VIDEO_BRIDGE", "id=" + videoId + " · HTTP " + httpCode
                    + " · " + state + " · HLS=" + streams.size()
                    + " · hosts=" + debugCandidateHosts(streams));
            setVkDiagnostic("al_video.php · " + videoId + " · HTTP " + httpCode + " · " + state
                    + " · HLS: " + streams.size());
            if (streams.isEmpty()) return false;
            vkSelectedVideoUrl = "https://vkvideo.ru/video" + videoId;
            currentPageUrl = vkSelectedVideoUrl;
            vkLiveCardSelected = true;
            long liveStartMs = extractLiveStartTimeMs(body, SOURCE_VK);
            handleStreamResolved(streams, new ArrayList<>(), SOURCE_VK, liveStartMs);
            return true;
        }
        @JavascriptInterface public void onVkProbeError(String videoId, String error) {
            if (source != SOURCE_VK || source != currentSource || streamStarted) return;
            debugLog("VK_AL_VIDEO_ERROR", "id=" + videoId + " · " + error);
            setVkDiagnostic("al_video.php · " + videoId + " · " + error);
        }
        @JavascriptInterface public void onVkProbeFinished(String message) {
            if (source != SOURCE_VK || source != currentSource || streamStarted) return;
            debugLog("VK_SCAN_END", safeShort(message, 300));
            setVkDiagnostic("VK: " + message);
        }
        @JavascriptInterface public void onPossibleStreamText(String body) {
            if (body == null || body.length() > 4 * 1024 * 1024 || source != currentSource || source != webSource) return;
            if (source == SOURCE_VK && !isVkLiveFeedPage(currentPageUrl)
                    && (!vkLiveCardSelected || !isSelectedVkVideoPage(currentPageUrl))) return;
            ArrayList<String> streams = source == SOURCE_VK ? extractVkHlsUrls(body) : extractRutubeLiveHlsUrls(body);
            if (source == SOURCE_VK) {
                debugLog("VK_PLAYER_TEXT", "bodyBytes=" + body.length()
                        + " · json=" + debugJsonShape(body) + " · HLS=" + streams.size()
                        + " · hosts=" + debugCandidateHosts(streams));
                ArrayList<String> vkDashResearch = streams.isEmpty() ? new ArrayList<>() : extractVkDashUrls(body);
                debugLog("VK_DASH_FOUND", "candidateCount=" + vkDashResearch.size()
                        + " · hosts=" + debugCandidateHosts(vkDashResearch));
                probeVkDashManifests(vkDashResearch);
            } else if (source == SOURCE_RUTUBE) {
                ArrayList<String> diagnosticHls = extractRutubeDiagnosticHlsUrls(body);
                ArrayList<String> diagnosticDash = extractRutubeDiagnosticDashUrls(body);
                debugLog("RUTUBE_PLAYER_TEXT", "chars=" + body.length()
                        + " · json=" + debugJsonShape(body)
                        + " · liveHLS=" + streams.size()
                        + " · allHLS=" + debugCandidateHosts(diagnosticHls)
                        + " · allDASH=" + debugCandidateHosts(diagnosticDash));
                debugLog("RUTUBE_JSON_FIELDS", summarizeRutubeJsonFields(body));
                String cookie = CookieManager.getInstance().getCookie("https://rutube.ru/");
                probeRutubeHlsManifests(diagnosticHls, cookie, VK_PROBE_USER_AGENT, currentPageUrl);
                probeRutubeDashManifests(diagnosticDash, cookie, VK_PROBE_USER_AGENT, currentPageUrl);
            }
            ArrayList<String> dashStreams = source == SOURCE_RUTUBE
                    ? extractRutubeLiveDashUrls(body)
                    : (source == SOURCE_VK && !streams.isEmpty() ? extractVkDashUrls(body) : new ArrayList<>());
            long liveStartMs = (!streams.isEmpty() || !dashStreams.isEmpty())
                    ? extractLiveStartTimeMs(body, source) : 0L;
            long liveDurationMs = (!streams.isEmpty() || !dashStreams.isEmpty())
                    ? extractLiveDurationMs(body, source) : 0L;
            if (!streams.isEmpty() || !dashStreams.isEmpty()) handleStreamResolved(streams, dashStreams, source, liveStartMs);
            if (liveDurationMs > 0L) runOnUiThread(() -> acceptLiveDuration(liveDurationMs, source));
        }
    }

    private static final int MAX_HLS_CANDIDATES = 8;
    private static final int MAX_DASH_CANDIDATES = 4;

    /**
     * The VK mobile player discovery method returns hls_live directly in its
     * JSON response. hls_live_playback is a delayed edge and is intentionally skipped.
     */
    private static ArrayList<String> extractVkLiveDiscoverHlsUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        Object parsed = parseJsonBody(body);
        collectVkLiveDiscoverFields(parsed, urls, 0);
        return new ArrayList<>(urls);
    }

    private static void collectVkLiveDiscoverFields(Object node, LinkedHashSet<String> urls, int depth) {
        if (node == null || depth > 16 || urls.size() >= MAX_HLS_CANDIDATES) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            Object liveHls = object.opt("hls_live");
            if (liveHls instanceof String) addHlsCandidate((String) liveHls, SOURCE_VK, urls);
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                    String name = names.optString(i);
                    if (!"hls_live_playback".equalsIgnoreCase(name)) {
                        collectVkLiveDiscoverFields(object.opt(name), urls, depth + 1);
                    }
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                collectVkLiveDiscoverFields(array.opt(i), urls, depth + 1);
            }
        }
    }

    private static ArrayList<String> extractVkHlsUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        Object parsed = parseJsonBody(body);
        JSONObject alVideoData = findVkAlVideoPlayerData(parsed);
        if (alVideoData != null) {
            // al_video.php is the authoritative response: the actual player
            // params are opts.player.params[0], not arbitrary nested live fields.
            if (!isVkAlVideoLive(alVideoData)) return new ArrayList<>(urls);
            JSONArray names = alVideoData.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                    String name = names.optString(i);
                    String lower = name.toLowerCase(Locale.ROOT);
                    Object value = alVideoData.opt(name);
                    if (lower.startsWith("hls") && !"hls_live_playback".equals(lower) && value instanceof String) {
                        addHlsCandidate((String) value, SOURCE_VK, urls);
                    }
                }
            }
            return new ArrayList<>(urls);
        }
        if (parsed instanceof JSONObject && ((JSONObject) parsed).has("payload")) {
            // An al_video.php response without the canonical player params is
            // an error/permission response, not a reason to scan arbitrary URLs.
            return new ArrayList<>(urls);
        }
        if (parsed != null) collectVkPlayerFormats(parsed, urls, false, 0);
        collectVkEmbeddedPlayerParams(body, urls);
        collectVkApiPrefetchHls(body, urls);
        return new ArrayList<>(urls);
    }

    private static boolean isVkAlVideoLive(JSONObject data) {
        if (data == null) return false;
        Object live = data.opt("live");
        return (live instanceof Number && ((Number) live).intValue() == 2)
                || "2".equals(String.valueOf(live));
    }

    /**
     * Mirrors VK's canonical al_video extractor path:
     * response.payload[-1].player.params[0].
     */
    private static JSONObject findVkAlVideoPlayerData(Object parsed) {
        if (!(parsed instanceof JSONObject)) return null;
        JSONObject root = (JSONObject) parsed;

        // Actual al_video.php JSON shape is:
        // {"payload": ["0", [ ... infoPage, ..., opts ]]}
        // yt-dlp first unpacks payload into (code, payload), then reads
        // payload[-1].player.params[0]. The inner array is essential.
        JSONArray envelope = root.optJSONArray("payload");
        if (envelope == null || envelope.length() < 2) return null;
        Object responseCode = envelope.opt(0);
        String code = String.valueOf(responseCode);
        if ("3".equals(code) || "8".equals(code)) return null;

        JSONArray optionsList = envelope.optJSONArray(1);
        if (optionsList == null || optionsList.length() == 0) {
            // Compatibility with a flattened payload variant, if VK ever
            // returns it. Never treat an arbitrary status entry as opts.
            return null;
        }
        JSONObject options = optionsList.optJSONObject(optionsList.length() - 1);
        if (options == null) return null;
        JSONObject player = options.optJSONObject("player");
        if (player == null) return null;
        JSONArray params = player.optJSONArray("params");
        if (params == null || params.length() == 0) return null;
        return params.optJSONObject(0);
    }

    /**
     * VK al_video.php act=show returns a payload whose final opts object contains
     * player.params[0]. The video page may also embed the same object as
     * "var playerParams = {...}". Only live=2 is an actual running broadcast;
     * live=3 is a finished recording. HLS format keys start with "hls".
     */
    private static void collectVkPlayerFormats(Object node, LinkedHashSet<String> urls,
                                               boolean inheritedLive, int depth) {
        if (node == null || depth > 18 || urls.size() >= MAX_HLS_CANDIDATES) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            int state = getVkLiveState(object);
            if (state < 0) return;
            boolean active = inheritedLive || state > 0;
            if (active) {
                JSONArray names = object.names();
                if (names != null) {
                    for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                        String name = names.optString(i).toLowerCase(Locale.ROOT);
                        if (name.startsWith("hls") && !"hls_live_playback".equals(name)) {
                            Object value = object.opt(names.optString(i));
                            if (value instanceof String) addHlsCandidate((String) value, SOURCE_VK, urls);
                        }
                    }
                }
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                    Object child = object.opt(names.optString(i));
                    if (child instanceof String) collectVkEmbeddedPlayerParams((String) child, urls);
                    else collectVkPlayerFormats(child, urls, active, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                collectVkPlayerFormats(array.opt(i), urls, inheritedLive, depth + 1);
            }
        } else if (node instanceof String) {
            collectVkEmbeddedPlayerParams((String) node, urls);
        }
    }

    private static void collectVkEmbeddedPlayerParams(String text, LinkedHashSet<String> urls) {
        if (text == null || text.isEmpty() || urls.size() >= MAX_HLS_CANDIDATES) return;
        Matcher matcher = Pattern.compile("(?:var\\s+playerParams\\s*=|playerParams\\s*:)").matcher(text);
        while (matcher.find() && urls.size() < MAX_HLS_CANDIDATES) {
            int open = text.indexOf('{', matcher.end());
            if (open < 0) continue;
            String json = extractBalancedJsonObject(text, open);
            if (json == null) continue;
            try {
                collectVkPlayerFormats(new JSONObject(json), urls, false, 0);
            } catch (Exception ignored) { }
        }
    }

    /**
     * Modern VK Video pages preload video.get responses in window.cur.apiPrefetchCache.
     * A live broadcast exposes files.hls; saved videos expose files.hls_ondemand.
     * Only the live-only key is accepted from this cache.
     */
    private static void collectVkApiPrefetchHls(String body, LinkedHashSet<String> urls) {
        if (body == null || body.isEmpty() || urls.size() >= MAX_HLS_CANDIDATES) return;
        Matcher matcher = Pattern.compile("[\\\"']apiPrefetchCache[\\\"']\\s*:\\s*").matcher(body);
        while (matcher.find() && urls.size() < MAX_HLS_CANDIDATES) {
            int open = body.indexOf('[', matcher.end());
            if (open < 0) continue;
            String json = extractBalancedJsonArray(body, open);
            if (json == null) continue;
            try {
                collectVkPrefetchEntries(new JSONArray(json), urls, 0);
            } catch (Exception ignored) { }
        }
    }

    private static void collectVkPrefetchEntries(Object node, LinkedHashSet<String> urls, int depth) {
        if (node == null || depth > 16 || urls.size() >= MAX_HLS_CANDIDATES) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            String method = object.optString("method", "");
            if ("video.get".equalsIgnoreCase(method)) {
                collectVkVideoGetFiles(object.opt("response"), urls, depth + 1);
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                    collectVkPrefetchEntries(object.opt(names.optString(i)), urls, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                collectVkPrefetchEntries(array.opt(i), urls, depth + 1);
            }
        }
    }

    private static void collectVkVideoGetFiles(Object node, LinkedHashSet<String> urls, int depth) {
        if (node == null || depth > 18 || urls.size() >= MAX_HLS_CANDIDATES) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject files = object.optJSONObject("files");
            if (files != null && !files.has("hls_ondemand")) {
                Object hls = files.opt("hls");
                if (hls instanceof String) addHlsCandidate((String) hls, SOURCE_VK, urls);
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                    collectVkVideoGetFiles(object.opt(names.optString(i)), urls, depth + 1);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                collectVkVideoGetFiles(array.opt(i), urls, depth + 1);
            }
        }
    }

    private static String extractBalancedJsonArray(String text, int start) {
        if (text == null || start < 0 || start >= text.length() || text.charAt(start) != '[') return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '[') depth++;
            else if (c == ']' && --depth == 0) return text.substring(start, i + 1);
        }
        return null;
    }

    private static String extractBalancedJsonObject(String text, int start) {
        if (text == null || start < 0 || start >= text.length() || text.charAt(start) != '{') return null;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return text.substring(start, i + 1);
        }
        return null;
    }

    /** 1 = live, -1 = post-live/upcoming/not-live, 0 = unknown. */
    private static int getVkLiveState(JSONObject object) {
        String status = object.optString("live_status", "").toLowerCase(Locale.ROOT);
        if ("started".equals(status) || "is_live".equals(status)) return 1;
        if ("post_live".equals(status) || "was_live".equals(status) || "ended".equals(status)
                || "finished".equals(status) || "recording".equals(status) || "offline".equals(status)
                || "failed".equals(status) || "upcoming".equals(status) || "waiting".equals(status)
                || "not_live".equals(status)) return -1;
        Object live = object.opt("live");
        if (live instanceof Number) {
            int value = ((Number) live).intValue();
            if (value == 2) return 1;
            if (value != 2) return -1;
        } else if (live instanceof Boolean) {
            return (Boolean) live ? 1 : -1;
        } else if (live != null) {
            String value = String.valueOf(live);
            if ("2".equals(value) || "true".equalsIgnoreCase(value)) return 1;
            if ("3".equals(value) || "1".equals(value) || "0".equals(value)
                    || "false".equalsIgnoreCase(value)) return -1;
        }
        Object isLive = object.opt("is_live");
        if (Boolean.TRUE.equals(isLive) || "true".equalsIgnoreCase(String.valueOf(isLive))
                || "1".equals(String.valueOf(isLive))) return 1;
        if (Boolean.FALSE.equals(isLive) || "false".equalsIgnoreCase(String.valueOf(isLive))
                || "0".equals(String.valueOf(isLive))) return -1;
        Object isLivestream = object.opt("is_livestream");
        if (Boolean.TRUE.equals(isLivestream) || "true".equalsIgnoreCase(String.valueOf(isLivestream))
                || "1".equals(String.valueOf(isLivestream))) return 1;
        if (Boolean.FALSE.equals(isLivestream) || "false".equalsIgnoreCase(String.valueOf(isLivestream))
                || "0".equals(String.valueOf(isLivestream))) return -1;
        return 0;
    }

    private static String findVkLiveStateSummary(String body) {
        if (body == null) return "ответ пуст";
        Object parsed = parseJsonBody(body);
        JSONObject data = findVkAlVideoPlayerData(parsed);
        if (data != null && data.has("live")) return "player.live=" + data.opt("live");
        if (data != null && data.has("live_status")) return "live_status=" + data.opt("live_status");
        Matcher live = Pattern.compile("[\\\"']live[\\\"']\\s*:\\s*(-?\\d+|true|false)", Pattern.CASE_INSENSITIVE).matcher(body);
        if (live.find()) return "player.live=" + live.group(1);
        Matcher status = Pattern.compile("[\\\"']live_status[\\\"']\\s*:\\s*[\\\"']([^\\\"']+)", Pattern.CASE_INSENSITIVE).matcher(body);
        if (status.find()) return "live_status=" + status.group(1);
        return body.contains("playerParams") ? "playerParams есть, live не найден" : "playerParams не найден";
    }

    private static ArrayList<String> extractRutubeLiveHlsUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        Object parsed = parseJsonBody(body);
        if (parsed != null) {
            collectRutubeLiveHlsUrls(parsed, urls);
            boolean explicitlyLive = containsBooleanFlag(parsed, "is_live", true)
                    || containsBooleanFlag(parsed, "is_livestream", true);
            if (urls.isEmpty() && explicitlyLive) collectM3u8Urls(parsed, SOURCE_RUTUBE, urls);
            if (urls.isEmpty() && !explicitlyLive) return new ArrayList<>(urls);
        } else {
            if (!Pattern.compile("[\\\"'](?:is_live|is_livestream)[\\\"']\\s*:\\s*(true|1)", Pattern.CASE_INSENSITIVE).matcher(body).find()) {
                return new ArrayList<>(urls);
            }
        }
        Matcher matcher = Pattern.compile("https?://[^\\s\"']+?\\.m3u8(?:\\?[^\\s\"']*)?", Pattern.CASE_INSENSITIVE).matcher(body);
        while (matcher.find() && urls.size() < MAX_HLS_CANDIDATES) {
            addHlsCandidate(matcher.group(), SOURCE_RUTUBE, urls);
        }
        return new ArrayList<>(urls);
    }

    private static void collectRutubeLiveHlsUrls(Object node, LinkedHashSet<String> urls) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject liveStreams = object.optJSONObject("live_streams");
            if (liveStreams != null) {
                Object hls = liveStreams.opt("hls");
                collectM3u8Urls(hls, SOURCE_RUTUBE, urls);
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                    collectRutubeLiveHlsUrls(object.opt(names.optString(i)), urls);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                collectRutubeLiveHlsUrls(array.opt(i), urls);
            }
        }
    }

    private static void collectM3u8Urls(Object node, int source, LinkedHashSet<String> urls) {
        if (urls.size() >= MAX_HLS_CANDIDATES) return;
        if (node instanceof String) {
            addHlsCandidate((String) node, source, urls);
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                    collectM3u8Urls(object.opt(names.optString(i)), source, urls);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_HLS_CANDIDATES; i++) {
                collectM3u8Urls(array.opt(i), source, urls);
            }
        }
    }

    private static void addHlsCandidate(String rawUrl, int source, LinkedHashSet<String> urls) {
        if (rawUrl == null || urls.size() >= MAX_HLS_CANDIDATES) return;
        String candidate = cleanUrl(rawUrl.trim());
        if (candidate.startsWith("//")) candidate = "https:" + candidate;
        boolean allowed = source == SOURCE_VK ? isAllowedVkHls(candidate) : isAllowedRutubeHls(candidate);
        if (allowed) urls.add(candidate);
    }

    private static String extractVkHlsUrl(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            Object parsed = body.trim().startsWith("{") ? new JSONObject(body) : (body.trim().startsWith("[") ? new JSONArray(body) : null);
            if (parsed != null) {
                String value = findKey(parsed, "hls_live");
                if (!isAllowedVkHls(value)) value = findKey(parsed, "hls_live_playback");
                if (isAllowedVkHls(value)) return cleanUrl(value);
            }
        } catch (Exception ignored) { }
        Pattern p = Pattern.compile("[\\\"']hls_live(?:_playback)?[\\\"']\\s*:\\s*[\\\"'](https?:[^\\\"']+?\\.m3u8[^\\\"']*)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(body);
        while (m.find()) {
            String value = cleanUrl(m.group(1));
            if (isAllowedVkHls(value)) return value;
        }
        return null;
    }

    private boolean isSelectedRutubeVideoMetadataUrl(String apiUrl) {
        if (apiUrl == null || currentPageUrl == null) return false;
        Matcher api = Pattern.compile("/api/video/([a-f0-9]+)/", Pattern.CASE_INSENSITIVE).matcher(apiUrl);
        Matcher page = Pattern.compile("/video/([a-f0-9]+)/?", Pattern.CASE_INSENSITIVE).matcher(currentPageUrl);
        return api.find() && page.find() && api.group(1).equalsIgnoreCase(page.group(1));
    }

    private static boolean isExplicitNonLiveRutubeResponse(String body) {
        if (body == null) return false;
        Object parsed = parseJsonBody(body);
        return containsBooleanFlag(parsed, "is_live", false)
                || Pattern.compile("[\"']is_live[\"']\\s*:\\s*(false|0)", Pattern.CASE_INSENSITIVE).matcher(body).find();
    }

    private static String extractRutubeLiveHlsUrl(String body) {
        if (body == null || body.isEmpty()) return null;
        Object parsed = parseJsonBody(body);
        if (parsed != null) {
            // RUTUBE's live play-options response carries its HLS URLs under live_streams.hls.
            // That structure is itself a live-only marker; some responses omit a separate is_live flag.
            String liveUrl = findRutubeLiveHls(parsed);
            if (isAllowedRutubeHls(liveUrl)) return cleanUrl(liveUrl);

            // If the response uses another HLS field, only accept it with an explicit live flag.
            boolean explicitlyLive = containsBooleanFlag(parsed, "is_live", true)
                    || containsBooleanFlag(parsed, "is_livestream", true);
            if (!explicitlyLive) return null;
        }
        // Fallback for non-standard JSON formatting. Require an explicit live marker.
        if (!Pattern.compile("[\\\"'](?:is_live|is_livestream)[\\\"']\\s*:\\s*(true|1)", Pattern.CASE_INSENSITIVE).matcher(body).find()) return null;
        Matcher m = Pattern.compile("https?:[^\\\"'\\s]+?\\.m3u8(?:\\?[^\\\"'\\s]*)?", Pattern.CASE_INSENSITIVE).matcher(body);
        while (m.find()) {
            String value = cleanUrl(m.group());
            if (isAllowedRutubeHls(value)) return value;
        }
        return null;
    }

    /**
     * RuTube LIVE may expose DASH alongside HLS. Only inspect URLs located
     * inside live_streams, or accept generic MPD URLs when the API explicitly
     * marks the response as live. Archive video-balancer URLs are not accepted.
     */
    private static ArrayList<String> extractRutubeLiveDashUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        Object parsed = parseJsonBody(body);
        boolean explicitlyLive = false;
        if (parsed != null) {
            collectRutubeLiveDashUrls(parsed, urls);
            explicitlyLive = containsBooleanFlag(parsed, "is_live", true)
                    || containsBooleanFlag(parsed, "is_livestream", true);
            if (urls.isEmpty() && explicitlyLive) collectMpdUrls(parsed, urls);
        } else {
            explicitlyLive = Pattern.compile("[\\\"'](?:is_live|is_livestream)[\\\"']\\s*:\\s*(true|1)", Pattern.CASE_INSENSITIVE).matcher(body).find();
        }
        if (explicitlyLive || !urls.isEmpty()) {
            Matcher matcher = Pattern.compile("https?://[^\\s\\\"'<>]+?\\.mpd(?:\\?[^\\s\\\"'<>]*)?", Pattern.CASE_INSENSITIVE).matcher(body);
            while (matcher.find() && urls.size() < MAX_DASH_CANDIDATES) addDashCandidate(matcher.group(), urls);
        }
        return new ArrayList<>(urls);
    }

    private static void collectRutubeLiveDashUrls(Object node, LinkedHashSet<String> urls) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject liveStreams = object.optJSONObject("live_streams");
            if (liveStreams != null) collectMpdUrls(liveStreams, urls);
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_DASH_CANDIDATES; i++) {
                    collectRutubeLiveDashUrls(object.opt(names.optString(i)), urls);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_DASH_CANDIDATES; i++) {
                collectRutubeLiveDashUrls(array.opt(i), urls);
            }
        }
    }

    private static void collectMpdUrls(Object node, LinkedHashSet<String> urls) {
        if (node instanceof String) {
            addDashCandidate((String) node, urls);
        } else if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length() && urls.size() < MAX_DASH_CANDIDATES; i++) {
                    collectMpdUrls(object.opt(names.optString(i)), urls);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && urls.size() < MAX_DASH_CANDIDATES; i++) collectMpdUrls(array.opt(i), urls);
        }
    }

    private static void addDashCandidate(String rawUrl, LinkedHashSet<String> urls) {
        if (rawUrl == null || urls.size() >= MAX_DASH_CANDIDATES) return;
        String candidate = cleanUrl(rawUrl.trim());
        if (isAllowedRutubeDash(candidate)) urls.add(candidate);
    }

    private static boolean isAllowedRutubeDash(String url) {
        if (url == null || !url.startsWith("https://") || !url.toLowerCase(Locale.ROOT).contains(".mpd")) return false;
        String host = Uri.parse(url).getHost();
        return host != null && (host.equals("rutube.ru") || host.endsWith(".rutube.ru")
                || host.equals("rtbcdn.ru") || host.endsWith(".rtbcdn.ru")
                || host.equals("rtbcdn.net") || host.endsWith(".rtbcdn.net"));
    }

    private static Object parseJsonBody(String body) {
        try {
            String trimmed = body.trim();
            if (trimmed.startsWith("{")) return new JSONObject(trimmed);
            if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean containsBooleanFlag(Object node, String key, boolean expected) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(key)) {
                Object value = object.opt(key);
                boolean actual = Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
                boolean explicitlyFalse = Boolean.FALSE.equals(value) || "false".equalsIgnoreCase(String.valueOf(value)) || "0".equals(String.valueOf(value));
                if ((expected && actual) || (!expected && explicitlyFalse)) return true;
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    if (containsBooleanFlag(object.opt(names.optString(i)), key, expected)) return true;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                if (containsBooleanFlag(array.opt(i), key, expected)) return true;
            }
        }
        return false;
    }

    private static String findRutubeLiveHls(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONObject liveStreams = object.optJSONObject("live_streams");
            if (liveStreams != null) {
                JSONArray hls = liveStreams.optJSONArray("hls");
                if (hls != null) {
                    for (int i = 0; i < hls.length(); i++) {
                        Object item = hls.opt(i);
                        if (item instanceof JSONObject) {
                            String url = ((JSONObject) item).optString("url", null);
                            if (url != null && url.toLowerCase(Locale.ROOT).contains(".m3u8")) return url;
                        } else if (item instanceof String && ((String) item).toLowerCase(Locale.ROOT).contains(".m3u8")) {
                            return (String) item;
                        }
                    }
                }
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String found = findRutubeLiveHls(object.opt(names.optString(i)));
                    if (found != null) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findRutubeLiveHls(array.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String findRutubeM3u8(Object node) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String name = names.optString(i);
                    Object child = object.opt(name);
                    if (name.equalsIgnoreCase("m3u8") && child instanceof String && ((String) child).contains(".m3u8")) return (String) child;
                    String found = findRutubeM3u8(child);
                    if (found != null) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findRutubeM3u8(array.opt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String findKey(Object node, String sought) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (object.has(sought)) {
                String value = object.optString(sought, null);
                if (value != null && value.toLowerCase(Locale.ROOT).contains(".m3u8")) return value;
            }
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i);
                if (!name.equals(sought)) {
                    String found = findKey(object.opt(name), sought);
                    if (found != null) return found;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                String found = findKey(array.opt(i), sought);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String cleanUrl(String value) {
        return value == null ? null : value.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&");
    }

    private static boolean isAllowedVkHls(String url) {
        return url != null && url.startsWith("https://") && url.toLowerCase(Locale.ROOT).contains(".m3u8") && isVkCdnHost(Uri.parse(url).getHost());
    }


    /**
     * Diagnostic-only VK DASH discovery. Candidates are accepted only when
     * they are HTTPS MPD URLs on the same VK CDN allowlist as VK HLS.
     * These URLs are never passed to the playback resolver in this build.
     */
    private static ArrayList<String> extractVkDashUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        String normalized = cleanUrl(body);
        Matcher matcher = Pattern.compile("https?://[^\\s\\\"'<>]+?\\.mpd(?:\\?[^\\s\\\"'<>]*)?", Pattern.CASE_INSENSITIVE).matcher(normalized);
        while (matcher.find() && urls.size() < MAX_DASH_CANDIDATES) {
            String candidate = cleanUrl(matcher.group()).replaceAll("[),;]+$", "");
            if (candidate.startsWith("https://") && candidate.toLowerCase(Locale.ROOT).contains(".mpd")) {
                String host = Uri.parse(candidate).getHost();
                if (isVkCdnHost(host)) urls.add(candidate);
            }
        }
        return new ArrayList<>(urls);
    }

    private void probeVkDashManifests(ArrayList<String> candidates) {
        if (!vkDebugEnabled || candidates == null || candidates.isEmpty()) return;
        ArrayList<String> fresh = new ArrayList<>();
        synchronized (vkDashProbeSeen) {
            for (String candidate : candidates) {
                if (fresh.size() >= 3) break;
                if (vkDashProbeSeen.size() >= 6) break;
                if (candidate != null && vkDashProbeSeen.add(candidate)) fresh.add(candidate);
            }
        }
        for (String candidate : fresh) {
            Thread probe = new Thread(() -> inspectVkDashManifest(candidate), "RadioKp-VkDashProbe");
            probe.setDaemon(true);
            probe.start();
        }
    }

    private void inspectVkDashManifest(String manifestUrl) {
        HttpURLConnection connection = null;
        String host;
        try { host = Uri.parse(manifestUrl).getHost(); }
        catch (Exception ignored) { host = "unknown"; }
        try {
            connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", VK_PROBE_USER_AGENT);
            connection.setRequestProperty("Referer", currentPageUrl == null ? VK_LIVES_URL : currentPageUrl);
            connection.setRequestProperty("Origin", "https://vkvideo.ru");
            connection.setRequestProperty("Accept", "application/dash+xml,application/xml,text/xml,*/*");
            connection.setRequestProperty("Range", "bytes=0-196607");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                debugLog("VK_DASH_PROBE", "host=" + host + " · HTTP " + code);
                return;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int total = 0, count;
                while ((count = input.read(buffer)) != -1 && total < 196608) {
                    int accepted = Math.min(count, 196608 - total);
                    output.write(buffer, 0, accepted);
                    total += accepted;
                    if (accepted < count) break;
                }
            }
            String xml = output.toString(StandardCharsets.UTF_8.name());
            boolean isMpd = Pattern.compile("<(?:[A-Za-z0-9_]+:)?MPD\\b", Pattern.CASE_INSENSITIVE).matcher(xml).find();
            int audioSets = 0, videoSets = 0, unknownSets = 0, representations = 0;
            StringBuilder tracks = new StringBuilder();
            Matcher sets = Pattern.compile("(?is)<(?:[A-Za-z0-9_]+:)?AdaptationSet\\b([^>]*)>(.*?)</(?:[A-Za-z0-9_]+:)?AdaptationSet\\s*>").matcher(xml);
            while (sets.find()) {
                String attrs = sets.group(1);
                String inner = sets.group(2);
                String mime = xmlAttributeValue(attrs, "mimeType");
                String content = xmlAttributeValue(attrs, "contentType");
                String codecs = xmlAttributeValue(attrs, "codecs");
                Matcher reps = Pattern.compile("(?is)<(?:[A-Za-z0-9_]+:)?Representation\\b([^>]*)").matcher(inner);
                while (reps.find()) {
                    representations++;
                    if (codecs == null) codecs = xmlAttributeValue(reps.group(1), "codecs");
                }
                String signature = ((mime == null ? "" : mime) + " " + (content == null ? "" : content) + " " + (codecs == null ? "" : codecs)).toLowerCase(Locale.ROOT);
                boolean audio = signature.contains("audio/") || signature.contains("audio ") || signature.matches(".*\\b(mp4a|opus|ac-3|ec-3|ac-4|flac|alac)[^ ]*.*");
                boolean video = signature.contains("video/") || signature.contains("video ") || signature.matches(".*\\b(avc|hvc|hev|vp09|vp9|av01)[^ ]*.*");
                if (audio) audioSets++;
                else if (video) videoSets++;
                else unknownSets++;
                if (tracks.length() < 1100) {
                    tracks.append(" {").append(audio ? "AUDIO" : (video ? "VIDEO" : "OTHER"));
                    if (mime != null) tracks.append(" mime=").append(mime);
                    if (content != null) tracks.append(" type=").append(content);
                    if (codecs != null) tracks.append(" codecs=").append(codecs);
                    tracks.append("}");
                }
            }
            String summary = "host=" + host + " · HTTP=" + code
                    + " · contentType=" + safeShort(connection.getContentType(), 80)
                    + " · MPD=" + isMpd + " · bytes=" + xml.length()
                    + " · AdaptationSet audio=" + audioSets + " video=" + videoSets
                    + " other=" + unknownSets + " · Representation=" + representations
                    + tracks;
            debugLog("VK_DASH_PROBE", summary);
        } catch (Exception error) {
            debugLog("VK_DASH_PROBE", "host=" + host + " · " + error.getClass().getSimpleName()
                    + " · " + safeShort(error.getMessage(), 160));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String xmlAttributeValue(String attributes, String name) {
        if (attributes == null) return null;
        Matcher matcher = Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\s*=\\s*\\\"([^\\\"]+)\\\"").matcher(attributes);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static boolean isAllowedRutubeHls(String url) {
        if (url == null || !url.startsWith("https://") || !url.toLowerCase(Locale.ROOT).contains(".m3u8")) return false;
        String host = Uri.parse(url).getHost();
        return host != null && (host.equals("rutube.ru") || host.endsWith(".rutube.ru") || host.equals("rtbcdn.ru") || host.endsWith(".rtbcdn.ru") || host.equals("rtbcdn.net") || host.endsWith(".rtbcdn.net"));
    }

    private static boolean isVkCdnHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.endsWith(".okcdn.ru") || host.endsWith(".vkuservideo.net") || host.endsWith(".vkuservideo.com")
                || host.endsWith(".vkuservideo.ru") || host.endsWith(".userapi.com") || host.endsWith(".userapi.ru")
                || host.endsWith(".vkuseraudio.net") || host.endsWith(".vkuseraudio.ru") || host.endsWith(".vkuseraudio.com")
                || host.endsWith(".vkuserlive.net") || host.endsWith(".vkuserlive.com") || host.endsWith(".vkuser.net")
                || host.endsWith(".vkusercdn.ru") || host.endsWith(".cdn-vk.ru") || host.endsWith(".cdn-vk.net")
                || host.endsWith(".cdn-vk.com") || host.endsWith(".vk-cdn.me") || host.endsWith(".vk-cdn.net")
                || host.endsWith(".vkcache.com") || host.endsWith(".vkcdnservice.com") || host.endsWith(".mycdn.me")
                || host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com") || host.endsWith(".vk.com");
    }

    private static boolean isVkHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru") || host.endsWith(".vk.ru") || host.equals("api.vkvideo.ru") || host.endsWith(".api.vkvideo.ru");
    }

    private static boolean isRutubeHost(String host) {
        return host != null && (host.equalsIgnoreCase("rutube.ru") || host.toLowerCase(Locale.ROOT).endsWith(".rutube.ru"));
    }

    private boolean isVkLiveFeedPage(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            String path = uri.getPath() == null ? "" : uri.getPath();
            String normalizedPath = path.replaceAll("/+$", "");
            if (!isVkPage(url)) return false;

            // vkvideo.ru currently redirects /@radiokp/lives to the mobile
            // channel root /@radiokp. The channel page still contains video
            // cards; scan those cards and let al_video.php's live=2 marker
            // distinguish an actual live broadcast from archived videos.
            if (normalizedPath.equalsIgnoreCase("/@radiokp/lives")) return true;
            return normalizedPath.equalsIgnoreCase("/@radiokp")
                    && host != null
                    && (host.equalsIgnoreCase("m.vkvideo.ru")
                        || host.equalsIgnoreCase("vkvideo.ru"));
        } catch (Exception ignored) { }
        return false;
    }

    private boolean isSelectedVkVideoPage(String url) {
        if (url == null) return false;
        try {
            Uri uri = Uri.parse(url);
            String full = uri.toString();
            if (Pattern.compile("(?:^|[/?#&=])(?:video|live)-\\-?\\d+_\\d+(?:[/?&#]|$)", Pattern.CASE_INSENSITIVE).matcher(full).find()) return true;
            String path = uri.getPath() == null ? "" : uri.getPath();
            if (path.endsWith("/video_ext.php")) {
                return uri.getQueryParameter("oid") != null && uri.getQueryParameter("id") != null;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private boolean isVkPage(String url) {
        try {
            String host = Uri.parse(url).getHost();
            return host != null && (host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru") || host.endsWith(".vk.ru"));
        } catch (Exception e) { return false; }
    }

    private boolean isRutubePage(String url) {
        try { return isRutubeHost(Uri.parse(url).getHost()); } catch (Exception e) { return false; }
    }

    private void handleStreamResolved(String url, int source) {
        ArrayList<String> urls = new ArrayList<>();
        if (url != null) urls.add(url);
        handleStreamResolved(urls, source);
    }

    private void handleStreamResolved(ArrayList<String> urls, int source) {
        handleStreamResolved(urls, new ArrayList<>(), source);
    }

    private void handleStreamResolved(ArrayList<String> hlsUrls, ArrayList<String> dashUrls, int source) {
        handleStreamResolved(hlsUrls, dashUrls, source, 0L);
    }

    private void handleStreamResolved(ArrayList<String> hlsUrls, ArrayList<String> dashUrls, int source, long discoveredStartTimeMs) {
        if (((hlsUrls == null || hlsUrls.isEmpty()) && (dashUrls == null || dashUrls.isEmpty()))
                || source != currentSource || source != webSource || streamStarted) return;
        ArrayList<String> safeHls = hlsUrls == null ? new ArrayList<>() : hlsUrls;
        ArrayList<String> safeDash = dashUrls == null ? new ArrayList<>() : dashUrls;
        String primaryUrl = !safeHls.isEmpty() ? safeHls.get(0) : safeDash.get(0);
        debugLog("STREAM_FOUND", "source=" + SOURCE_NAMES[source] + " · hls=" + safeHls.size()
                + " · hosts=" + debugCandidateHosts(safeHls) + " · dash=" + safeDash.size()
                + " · liveStart=" + discoveredStartTimeMs);
        streamStarted = true;
        cancelSourceTimeout();
        runOnUiThread(() -> {
            if (discoveredStartTimeMs > 0L) acceptLiveStartTime(discoveredStartTimeMs, source);
            liveSessionActive = true;
            startElapsedTicker();
            statusView.setText("Найдено HLS: " + safeHls.size() + " · DASH: " + safeDash.size() + ". Ищу отдельное аудио…");
            Runnable play = () -> {
                    releaseWebView();
                String referer = source == SOURCE_VK
                        ? (vkSelectedVideoUrl.isEmpty() ? "https://vkvideo.ru/" : vkSelectedVideoUrl)
                        : "https://rutube.ru/";
                int quality = source == SOURCE_RUTUBE ? rutubeQualityKbps : vkQualityKbps;
                String streamCookie = source == SOURCE_VK ? CookieManager.getInstance().getCookie(primaryUrl) : null;
                startPlayerService(primaryUrl, safeHls, safeDash, referer, SOURCE_NAMES[source], quality, false, streamCookie);
            };
            if (webView != null) {
                webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(v){try{v.muted=true;v.pause()}catch(e){}})", value -> runOnUiThread(play));
            } else play.run();
        });
    }

    private void cancelSourceTimeout() {
        if (sourceTimeout != null) handler.removeCallbacks(sourceTimeout);
        sourceTimeout = null;
    }

    private void showSettings() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(8), dp(18), dp(4));
        panel.setBackgroundColor(Color.rgb(22, 26, 33));

        int[] videoOptions = {0, 32, 64, 96, 128, 192};
        String[] videoLabels = {"Авто", "32 кбит/с", "64 кбит/с", "96 кбит/с", "128 кбит/с", "192 кбит/с"};

        panel.addView(text("Максимальный битрейт аудио VK", 14, Color.WHITE, true));
        RadioGroup vkGroup = new RadioGroup(this);
        addRadioOptions(vkGroup, videoOptions, videoLabels, vkQualityKbps);
        panel.addView(vkGroup);

        TextView rutubeTitle = text("Максимальный битрейт аудио RuTube", 14, Color.WHITE, true);
        LinearLayout.LayoutParams rutubeTitleLp = new LinearLayout.LayoutParams(-1, -2);
        rutubeTitleLp.topMargin = dp(12);
        panel.addView(rutubeTitle, rutubeTitleLp);
        RadioGroup rutubeGroup = new RadioGroup(this);
        addRadioOptions(rutubeGroup, videoOptions, videoLabels, rutubeQualityKbps);
        panel.addView(rutubeGroup);

        TextView radioTitle = text("Качество третьего источника — Радио КП", 14, Color.WHITE, true);
        LinearLayout.LayoutParams radioTitleLp = new LinearLayout.LayoutParams(-1, -2);
        radioTitleLp.topMargin = dp(14);
        panel.addView(radioTitle, radioTitleLp);
        RadioGroup radioGroup = new RadioGroup(this);
        int[] radioOptions = {32, 64, 128};
        String[] radioLabels = {"32 кбит/с · экономный", "64 кбит/с · оптимальный", "128 кбит/с · высокий"};
        addRadioOptions(radioGroup, radioOptions, radioLabels, radioQualityKbps);
        panel.addView(radioGroup);

        CheckBox autoStartBox = new CheckBox(this);
        autoStartBox.setText("Автозапуск при открытии приложения");
        autoStartBox.setTextColor(Color.WHITE);
        autoStartBox.setChecked(autoStart);
        LinearLayout.LayoutParams autoStartLp = new LinearLayout.LayoutParams(-1, -2);
        autoStartLp.topMargin = dp(14);
        panel.addView(autoStartBox, autoStartLp);

        CheckBox failoverBox = new CheckBox(this);
        failoverBox.setText("Автоматически переходить к следующему источнику");
        failoverBox.setTextColor(Color.WHITE);
        failoverBox.setChecked(autoFailover);
        panel.addView(failoverBox);

        TextView diagnosticsTitle = text("Диагностика", 14, Color.WHITE, true);
        LinearLayout.LayoutParams diagnosticsTitleLp = new LinearLayout.LayoutParams(-1, -2);
        diagnosticsTitleLp.topMargin = dp(14);
        panel.addView(diagnosticsTitle, diagnosticsTitleLp);

        CheckBox vkDebugBox = new CheckBox(this);
        vkDebugBox.setText("Включить подробный журнал диагностики (VK/RuTube)");
        vkDebugBox.setTextColor(Color.WHITE);
        vkDebugBox.setChecked(vkDebugEnabled);
        panel.addView(vkDebugBox);

        TextView debugNote = text("Включайте журнал только при поиске неисправностей. При выключении новые события не записываются, а накопленный журнал очищается.", 12, Color.rgb(150, 158, 171), false);
        LinearLayout.LayoutParams debugNoteLp = new LinearLayout.LayoutParams(-1, -2);
        debugNoteLp.leftMargin = dp(4);
        debugNoteLp.topMargin = dp(2);
        panel.addView(debugNote, debugNoteLp);

        TextView note = text("Для VK и RuTube это верхний предел, если платформа отдаёт несколько аудиодорожек. Радио: 32 / 64 / 128 кбит/с (примерно 14 / 29 / 58 МБ за час).", 12, Color.rgb(150, 158, 171), false);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(12);
        panel.addView(note, noteLp);

        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(false);
        settingsScroll.addView(panel, new ScrollView.LayoutParams(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки")
                .setView(settingsScroll)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, which) -> {
                    vkQualityKbps = selectedRadioValue(vkGroup, videoOptions, vkQualityKbps);
                    rutubeQualityKbps = selectedRadioValue(rutubeGroup, videoOptions, rutubeQualityKbps);
                    radioQualityKbps = selectedRadioValue(radioGroup, radioOptions, radioQualityKbps);
                    autoStart = autoStartBox.isChecked();
                    autoFailover = failoverBox.isChecked();
                    boolean wasVkDebugEnabled = vkDebugEnabled;
                    vkDebugEnabled = vkDebugBox.isChecked();
                    preferences.edit()
                            .putInt("vk_quality_kbps", vkQualityKbps)
                            .putInt("rutube_quality_kbps", rutubeQualityKbps)
                            .putInt("radio_quality_kbps", radioQualityKbps)
                            .putBoolean("auto_start", autoStart)
                            .putBoolean("auto_failover", autoFailover)
                            .putBoolean("vk_debug_enabled", vkDebugEnabled)
                            .apply();
                    if (!vkDebugEnabled) {
                        synchronized (vkDebugLog) { vkDebugLog.setLength(0); }
                        if (vkDebugText != null) vkDebugText.setText("Журнал отключён.");
                    } else if (!wasVkDebugEnabled) {
                        synchronized (vkDebugLog) { vkDebugLog.setLength(0); }
                        debugLog("DIAGNOSTICS", "Журнал диагностики включён пользователем");
                    }
                    if (vkDebugPanel != null) vkDebugPanel.setVisibility(vkDebugEnabled ? View.VISIBLE : View.GONE);
                    applyQualityToActivePlayer();
                    Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
                }).create();
        dialog.show();
    }

    private void addRadioOptions(RadioGroup group, int[] values, String[] labels, int selected) {
        group.setOrientation(RadioGroup.VERTICAL);
        int checkedId = -1;
        for (int i = 0; i < values.length; i++) {
            RadioButton button = new RadioButton(this);
            button.setId(1000 + group.getChildCount() + (values[0] == 32 ? 100 : 0));
            button.setText(labels[i]);
            button.setTag(values[i]);
            button.setTextColor(Color.rgb(230, 233, 238));
            button.setTextSize(14);
            group.addView(button);
            if (values[i] == selected) checkedId = button.getId();
        }
        if (checkedId != -1) group.check(checkedId);
    }

    private int selectedRadioValue(RadioGroup group, int[] values, int fallback) {
        View checked = group.findViewById(group.getCheckedRadioButtonId());
        if (checked instanceof RadioButton && checked.getTag() instanceof Integer) return (Integer) checked.getTag();
        return fallback;
    }

    private void applyQualityToActivePlayer() {
        if (playbackService == null || !playbackService.hasStream()) return;
        if (currentSource == SOURCE_RADIO) {
            startSource(SOURCE_RADIO, false); // restart on the actual 32/64/128 kbps endpoint
        } else {
            playbackService.setMaxAudioBitrate(currentSource == SOURCE_RUTUBE ? rutubeQualityKbps : vkQualityKbps);
        }
    }

    @Override public void onLiveProgramDateTime(long timestampMs) {
        int source = webSource;
        runOnUiThread(() -> {
            acceptLiveStartTime(timestampMs, source);
            rutubeHlsDiagnostic = "HLS: найдено время сегмента " + formatDateTime(timestampMs)
                    + " · EXT-X-PROGRAM-DATE-TIME";
            renderRutubeDiagnostics();
        });
    }

    @Override public void onHlsDiagnostics(String diagnostic) {
        debugLog("HLS_INSPECT", diagnostic);
        runOnUiThread(() -> {
            rutubeHlsDiagnostic = diagnostic;
            renderRutubeDiagnostics();
        });
    }

    private void renderRutubeDiagnostics() {
        if (diagnosticView == null || currentSource != SOURCE_RUTUBE) return;
        diagnosticView.setVisibility(View.VISIBLE);
        diagnosticView.setText("ДИАГНОСТИКА · " + rutubeApiDiagnostic
                + "\n" + rutubeHlsDiagnostic);
    }

    private void debugLog(String stage, String details) {
        if (!vkDebugEnabled) return;
        String message = safeShort(details == null ? "" : details.replace('\n', ' ').replace('\r', ' '), 1800);
        String stamp = new java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
                .format(new java.util.Date());
        String line = stamp + " | " + stage + " | " + message + "\n";
        final String snapshot;
        synchronized (vkDebugLog) {
            vkDebugLog.append(line);
            if (vkDebugLog.length() > MAX_VK_DEBUG_CHARS) {
                int trimTo = vkDebugLog.length() - MAX_VK_DEBUG_CHARS;
                int newline = vkDebugLog.indexOf("\n", trimTo);
                vkDebugLog.delete(0, newline >= 0 ? newline + 1 : trimTo);
            }
            snapshot = vkDebugLog.toString();
        }
        runOnUiThread(() -> {
            if (vkDebugText != null) {
                vkDebugText.setText(snapshot);
                if (vkDebugScroll != null) vkDebugScroll.post(() -> vkDebugScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void copyVkDebugLog() {
        String contents;
        synchronized (vkDebugLog) { contents = vkDebugLog.toString(); }
        if (contents.isEmpty()) {
            Toast.makeText(this, "Журнал пока пуст", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Radio KP VK debug log", contents));
        Toast.makeText(this, "Журнал диагностики скопирован. Вставь его сюда целиком.", Toast.LENGTH_LONG).show();
    }

    private static String safeShort(String value, int maxChars) {
        if (value == null) return "";
        String safe = value.replaceAll("(?i)(https?://[^\\s?\\\"']+)\\?[^\\s\\\"']*", "$1?[query hidden]");
        safe = safe.replaceAll("(?i)(access_token|refresh_token|token|sig|signature|auth|authorization|cookie|vk_access_token)=[^&\\s]+", "$1=[hidden]");
        safe = safe.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (safe.length() > maxChars) safe = safe.substring(0, maxChars) + "…";
        return safe;
    }

    private static String debugSafeUrl(String raw) {
        if (raw == null || raw.isEmpty()) return "(пустой URL)";
        try {
            Uri uri = Uri.parse(raw);
            String host = uri.getHost() == null ? "?" : uri.getHost();
            String path = uri.getPath() == null ? "/" : uri.getPath();
            return uri.getScheme() + "://" + host + path + (uri.getEncodedQuery() == null ? "" : " [query скрыт]");
        } catch (Exception ignored) {
            return "(URL не разобран)";
        }
    }

    private static String debugCandidateHosts(ArrayList<String> urls) {
        if (urls == null || urls.isEmpty()) return "нет";
        ArrayList<String> safe = new ArrayList<>();
        for (String url : urls) {
            if (safe.size() >= 8) break;
            safe.add(debugSafeUrl(url));
        }
        return safe.toString();
    }

    private static String debugJsonShape(String body) {
        if (body == null || body.isEmpty()) return "empty";
        Object parsed = parseJsonBody(body);
        if (parsed instanceof JSONObject) {
            JSONObject object = (JSONObject) parsed;
            JSONArray names = object.names();
            ArrayList<String> keys = new ArrayList<>();
            if (names != null) for (int i = 0; i < names.length() && i < 24; i++) keys.add(names.optString(i));
            String shape = "object keys=" + keys;
            JSONArray payload = object.optJSONArray("payload");
            if (payload != null) {
                shape += " payload.len=" + payload.length();
                Object second = payload.opt(1);
                if (second instanceof JSONArray) {
                    JSONArray inner = (JSONArray) second;
                    shape += " payload[1].len=" + inner.length();
                    JSONObject last = inner.optJSONObject(inner.length() - 1);
                    if (last != null) {
                        JSONObject player = last.optJSONObject("player");
                        shape += " final.keys=" + limitedJsonKeys(last, 18);
                        if (player != null) {
                            shape += " player.keys=" + limitedJsonKeys(player, 18);
                            JSONArray params = player.optJSONArray("params");
                            shape += " params.len=" + (params == null ? "null" : params.length());
                            JSONObject firstParams = params == null ? null : params.optJSONObject(0);
                            if (firstParams != null) {
                                shape += " params[0].keys=" + limitedJsonKeys(firstParams, 24)
                                        + " live=" + firstParams.opt("live");
                            }
                        }
                    }
                }
            }
            return shape;
        }
        if (parsed instanceof JSONArray) return "array len=" + ((JSONArray) parsed).length();
        return "not-json · chars=" + body.length();
    }

    private static String limitedJsonKeys(JSONObject object, int limit) {
        JSONArray names = object == null ? null : object.names();
        ArrayList<String> keys = new ArrayList<>();
        if (names != null) for (int i = 0; i < names.length() && i < limit; i++) keys.add(names.optString(i));
        return keys.toString();
    }

    private void setVkDiagnostic(String value) {
        debugLog("VK_STATUS", value);
        vkApiDiagnostic = value == null ? "VK: диагностика недоступна" : value;
        runOnUiThread(this::renderVkDiagnostics);
    }

    private void renderVkDiagnostics() {
        if (diagnosticView == null || currentSource != SOURCE_VK) return;
        diagnosticView.setVisibility(View.VISIBLE);
        diagnosticView.setText("ДИАГНОСТИКА · " + vkApiDiagnostic);
    }

    private static String formatDateTime(long timestampMs) {
        return new java.text.SimpleDateFormat("dd.MM HH:mm:ss", Locale.ROOT)
                .format(new java.util.Date(timestampMs));
    }


    private static ArrayList<String> extractRutubeDiagnosticHlsUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        Object parsed = parseJsonBody(body);
        if (parsed != null) collectM3u8Urls(parsed, SOURCE_RUTUBE, urls);
        Matcher matcher = Pattern.compile("https?://[^\\s\\\"'<>]+?\\.m3u8(?:\\?[^\\s\\\"'<>]*)?", Pattern.CASE_INSENSITIVE).matcher(body);
        while (matcher.find() && urls.size() < MAX_HLS_CANDIDATES) {
            addHlsCandidate(matcher.group(), SOURCE_RUTUBE, urls);
        }
        return new ArrayList<>(urls);
    }

    private static ArrayList<String> extractRutubeDiagnosticDashUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        Object parsed = parseJsonBody(body);
        if (parsed != null) collectMpdUrls(parsed, urls);
        Matcher matcher = Pattern.compile("https?://[^\\s\\\"'<>]+?\\.mpd(?:\\?[^\\s\\\"'<>]*)?", Pattern.CASE_INSENSITIVE).matcher(body);
        while (matcher.find() && urls.size() < MAX_DASH_CANDIDATES) {
            addDashCandidate(matcher.group(), urls);
        }
        return new ArrayList<>(urls);
    }

    private static String summarizeRutubeJsonFields(String body) {
        Object parsed = parseJsonBody(body);
        if (parsed == null) return "JSON не разобран; " + debugJsonShape(body);
        StringBuilder out = new StringBuilder();
        int[] budget = {0};
        collectRutubeJsonFields(parsed, "$", 0, out, budget);
        return out.length() == 0 ? "ключи потоков не найдены; root=" + debugJsonShape(body) : out.toString();
    }

    private static void collectRutubeJsonFields(Object node, String path, int depth,
                                                  StringBuilder out, int[] budget) {
        if (node == null || depth > 8 || budget[0] >= 36) return;
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            JSONArray names = object.names();
            if (names == null) return;
            for (int i = 0; i < names.length() && budget[0] < 36; i++) {
                String key = names.optString(i);
                Object value = object.opt(key);
                String childPath = path + "." + key;
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.matches(".*(live_streams?|hls|dash|mpd|manifest|video_balancer|video|url|src|stream|format|quality|audio|codec|playlist|bitrate|adaptive|source).*")) {
                    if (out.length() > 0) out.append(" · ");
                    out.append(childPath).append("=").append(describeRutubeJsonValue(value));
                    budget[0]++;
                }
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    collectRutubeJsonFields(value, childPath, depth + 1, out, budget);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length() && i < 12 && budget[0] < 36; i++) {
                Object value = array.opt(i);
                if (value instanceof JSONObject || value instanceof JSONArray) {
                    collectRutubeJsonFields(value, path + "[" + i + "]", depth + 1, out, budget);
                }
            }
        }
    }

    private static String describeRutubeJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) return "object{" + limitedJsonKeys((JSONObject) value, 10) + "}";
        if (value instanceof JSONArray) return "array[" + ((JSONArray) value).length() + "]";
        if (value instanceof String) {
            String text = (String) value;
            if (text.contains(".m3u8") || text.contains(".mpd")) return debugSafeUrl(text);
            return "string(" + text.length() + ")";
        }
        return String.valueOf(value);
    }

    private void probeRutubeHlsManifests(ArrayList<String> candidates, String cookie,
                                          String userAgent, String referer) {
        if (candidates == null || candidates.isEmpty()) return;
        ArrayList<String> queue = new ArrayList<>();
        synchronized (rutubeHlsProbeSeen) {
            for (String candidate : candidates) {
                if (queue.size() >= MAX_HLS_CANDIDATES) break;
                if (isAllowedRutubeHls(candidate) && rutubeHlsProbeSeen.size() < 40
                        && rutubeHlsProbeSeen.add(candidate)) queue.add(candidate);
            }
        }
        if (queue.isEmpty()) return;
        String actualAgent = userAgent == null || userAgent.isEmpty() ? VK_PROBE_USER_AGENT : userAgent;
        String actualReferer = referer == null || referer.isEmpty() ? "https://rutube.ru/" : referer;
        Thread worker = new Thread(() -> {
            int index = 0;
            while (index < queue.size() && index < 16) {
                String manifestUrl = queue.get(index++);
                ArrayList<String> children = inspectRutubeHlsManifest(manifestUrl, cookie, actualAgent, actualReferer);
                for (String child : children) {
                    if (queue.size() >= 16) break;
                    synchronized (rutubeHlsProbeSeen) {
                        if (rutubeHlsProbeSeen.size() < 40 && rutubeHlsProbeSeen.add(child)) queue.add(child);
                    }
                }
            }
            debugLog("RUTUBE_HLS_SCAN_END", "проверено плейлистов=" + index + " · очередь=" + queue.size());
        }, "RadioKp-RutubeHlsProbe");
        worker.setDaemon(true);
        worker.start();
    }

    private ArrayList<String> inspectRutubeHlsManifest(String manifestUrl, String cookie,
                                                        String userAgent, String referer) {
        ArrayList<String> childPlaylists = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Referer", referer);
            connection.setRequestProperty("Origin", "https://rutube.ru");
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,application/x-mpegURL,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Range", "bytes=0-262143");
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                debugLog("RUTUBE_HLS_PROBE", debugSafeUrl(manifestUrl) + " · HTTP=" + code);
                return childPlaylists;
            }
            InputStream input = connection.getInputStream();
            byte[] bytes = input == null ? new byte[0] : readAll(input, 512 * 1024);
            String manifest = new String(bytes, StandardCharsets.UTF_8);
            if (!manifest.startsWith("#EXTM3U")) {
                debugLog("RUTUBE_HLS_PROBE", debugSafeUrl(manifestUrl) + " · HTTP=" + code
                        + " · type=" + safeShort(connection.getContentType(), 80)
                        + " · bytes=" + bytes.length + " · ответ не похож на HLS");
                return childPlaylists;
            }

            String pendingVariant = null;
            int variants = 0;
            int mediaSegments = 0;
            String targetDuration = null;
            boolean hasMap = false;
            boolean hasKey = false;
            ArrayList<String> variantSummary = new ArrayList<>();
            ArrayList<String> audioSummary = new ArrayList<>();
            String[] lines = manifest.replace("\r", "").split("\n");
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    pendingVariant = line;
                    continue;
                }
                if (line.startsWith("#EXT-X-MEDIA:")) {
                    Map<String, String> attrs = parseRutubeHlsAttributes(line);
                    if ("AUDIO".equalsIgnoreCase(attrs.get("TYPE"))) {
                        String rawUri = attrs.get("URI");
                        String resolved = rawUri == null ? null : resolveRutubeHlsUri(manifestUrl, rawUri);
                        if (resolved != null && !childPlaylists.contains(resolved)) childPlaylists.add(resolved);
                        audioSummary.add("group=" + attrs.get("GROUP-ID") + ",name=" + attrs.get("NAME")
                                + ",channels=" + attrs.get("CHANNELS") + ",uri="
                                + (resolved == null ? (rawUri == null ? "нет" : "не прошёл allowlist") : debugSafeUrl(resolved)));
                    }
                    continue;
                }
                if (pendingVariant != null && !line.isEmpty() && !line.startsWith("#")) {
                    Map<String, String> attrs = parseRutubeHlsAttributes(pendingVariant);
                    String resolved = resolveRutubeHlsUri(manifestUrl, line);
                    if (resolved != null && !childPlaylists.contains(resolved)) childPlaylists.add(resolved);
                    String codecs = attrs.get("CODECS");
                    boolean audioOnly = isAudioOnlyCodecList(codecs);
                    variants++;
                    variantSummary.add("bw=" + attrs.get("BANDWIDTH")
                            + ",avg=" + attrs.get("AVERAGE-BANDWIDTH")
                            + ",res=" + attrs.get("RESOLUTION")
                            + ",codecs=" + codecs
                            + ",audioGroup=" + attrs.get("AUDIO")
                            + ",audioOnly=" + audioOnly
                            + ",uri=" + (resolved == null ? "не прошёл allowlist" : debugSafeUrl(resolved)));
                    pendingVariant = null;
                    continue;
                }
                if (line.startsWith("#EXTINF:")) mediaSegments++;
                else if (line.startsWith("#EXT-X-TARGETDURATION:")) targetDuration = line.substring(line.indexOf(':') + 1);
                else if (line.startsWith("#EXT-X-MAP:")) hasMap = true;
                else if (line.startsWith("#EXT-X-KEY:")) hasKey = true;
            }

            String kind = variants > 0 ? "HLS_MASTER" : "HLS_MEDIA";
            debugLog("RUTUBE_HLS_PROBE", debugSafeUrl(manifestUrl)
                    + " · HTTP=" + code + " · type=" + safeShort(connection.getContentType(), 70)
                    + " · bytes=" + bytes.length + " · " + kind
                    + " · variants=" + variants + " · segments=" + mediaSegments
                    + " · targetDuration=" + targetDuration + " · map=" + hasMap + " · key=" + hasKey);
            if (!variantSummary.isEmpty()) {
                debugLog("RUTUBE_HLS_VARIANTS", debugSafeUrl(manifestUrl) + " · " + variantSummary);
            }
            if (!audioSummary.isEmpty()) {
                debugLog("RUTUBE_HLS_AUDIO", debugSafeUrl(manifestUrl) + " · " + audioSummary);
            }
        } catch (Exception error) {
            debugLog("RUTUBE_HLS_PROBE", debugSafeUrl(manifestUrl) + " · "
                    + error.getClass().getSimpleName() + " · " + safeShort(error.getMessage(), 160));
        } finally {
            if (connection != null) connection.disconnect();
        }
        return childPlaylists;
    }

    private static Map<String, String> parseRutubeHlsAttributes(String line) {
        Map<String, String> attrs = new HashMap<>();
        int colon = line.indexOf(':');
        if (colon < 0 || colon == line.length() - 1) return attrs;
        String text = line.substring(colon + 1);
        int position = 0;
        while (position < text.length()) {
            while (position < text.length()
                    && (text.charAt(position) == ',' || Character.isWhitespace(text.charAt(position)))) position++;
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

    private static String resolveRutubeHlsUri(String baseUrl, String rawUri) {
        if (rawUri == null || rawUri.trim().isEmpty()) return null;
        try {
            String resolved = new URL(new URL(baseUrl), rawUri.trim()).toExternalForm();
            return isAllowedRutubeHls(resolved) ? resolved : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void probeRutubeDashManifests(ArrayList<String> candidates, String cookie,
                                           String userAgent, String referer) {
        if (candidates == null || candidates.isEmpty()) return;
        Thread worker = new Thread(() -> {
            int checked = 0;
            for (String manifestUrl : candidates) {
                if (checked >= MAX_DASH_CANDIDATES || !isAllowedRutubeDash(manifestUrl)) break;
                checked++;
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
                    connection.setInstanceFollowRedirects(true);
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(7000);
                    connection.setRequestProperty("User-Agent", userAgent == null ? VK_PROBE_USER_AGENT : userAgent);
                    connection.setRequestProperty("Referer", referer == null ? "https://rutube.ru/" : referer);
                    connection.setRequestProperty("Origin", "https://rutube.ru");
                    connection.setRequestProperty("Accept", "application/dash+xml,application/xml,text/xml,*/*");
                    connection.setRequestProperty("Accept-Encoding", "identity");
                    if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                    int code = connection.getResponseCode();
                    if (code < 200 || code >= 300) {
                        debugLog("RUTUBE_DASH_PROBE", debugSafeUrl(manifestUrl) + " · HTTP=" + code);
                        continue;
                    }
                    InputStream input = connection.getInputStream();
                    byte[] bytes = input == null ? new byte[0] : readAll(input, 512 * 1024);
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    int audioSets = 0;
                    int videoSets = 0;
                    int otherSets = 0;
                    int representations = 0;
                    ArrayList<String> repSummary = new ArrayList<>();
                    Matcher sets = Pattern.compile("(?is)<AdaptationSet\\b([^>]*)>(.*?)</AdaptationSet>").matcher(xml);
                    while (sets.find()) {
                        String attrs = sets.group(1);
                        String inner = sets.group(2);
                        String mime = xmlAttributeValue(attrs, "mimeType");
                        String content = xmlAttributeValue(attrs, "contentType");
                        boolean audio = "audio".equalsIgnoreCase(content) || (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("audio/"));
                        boolean video = "video".equalsIgnoreCase(content) || (mime != null && mime.toLowerCase(Locale.ROOT).startsWith("video/"));
                        if (audio) audioSets++; else if (video) videoSets++; else otherSets++;
                        Matcher reps = Pattern.compile("(?is)<Representation\\b([^>]*)>").matcher(inner);
                        while (reps.find()) {
                            representations++;
                            if (repSummary.size() < 8) {
                                String repAttrs = reps.group(1);
                                repSummary.add("mime=" + xmlAttributeValue(repAttrs, "mimeType")
                                        + ",codecs=" + xmlAttributeValue(repAttrs, "codecs")
                                        + ",bw=" + xmlAttributeValue(repAttrs, "bandwidth"));
                            }
                        }
                    }
                    debugLog("RUTUBE_DASH_PROBE", debugSafeUrl(manifestUrl) + " · HTTP=" + code
                            + " · MPD=" + xml.contains("<MPD") + " · bytes=" + bytes.length
                            + " · AdaptationSet audio=" + audioSets + " video=" + videoSets
                            + " other=" + otherSets + " · Representation=" + representations
                            + " · " + repSummary);
                } catch (Exception error) {
                    debugLog("RUTUBE_DASH_PROBE", debugSafeUrl(manifestUrl) + " · "
                            + error.getClass().getSimpleName() + " · " + safeShort(error.getMessage(), 160));
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }
        }, "RadioKp-RutubeDashProbe");
        worker.setDaemon(true);
        worker.start();
    }

    private static String buildRutubeApiDiagnostic(String url, int httpCode, String body,
                                                    long startMs, long durationMs) {
        String endpoint = url.contains("/api/video/") ? "video API" : "LIVE API/лента";
        String liveMarker = body != null && (body.contains("\"is_live\"") ||
                body.contains("\"is_livestream\"") || body.contains("\"live_streams\""))
                ? "LIVE-маркеры есть" : "LIVE-маркеры не обнаружены";
        return endpoint + " HTTP " + httpCode + " · " + liveMarker
                + " · start=" + (startMs > 0L ? formatDateTime(startMs) : "нет")
                + " · duration=" + (durationMs > 0L ? (durationMs / 60000L) + " мин" : "нет");
    }

    @Override public void onPlaybackStateChanged(boolean playing, String message) {
        debugLog("PLAYER_STATE", "playing=" + playing + " · " + message);
        runOnUiThread(() -> {
            if (message != null && message.startsWith("SOURCE_ERROR:")) {
                isPlaying = false;
                liveProgress.setIndeterminate(false);
                if (liveSessionActive) updateElapsedView();
                else liveProgress.setProgress(0);
                if (autoFailover) {
                    String detail = message.substring("SOURCE_ERROR:".length()).trim();
                statusView.setText("Ошибка " + SOURCE_NAMES[currentSource] + ": " + detail + " · переключаюсь на резервный источник…");
                    handler.postDelayed(() -> tryNextSource("Ошибка воспроизведения"), 450L);
                } else {
                    statusView.setText("Не удалось воспроизвести " + SOURCE_NAMES[currentSource] + ". Выберите другой источник.");
                    elapsedView.setText("ОШИБКА ПОДКЛЮЧЕНИЯ");
                }
                updateControls();
                return;
            }
            isPlaying = playing;
            statusView.setText(message);
            if (message != null && (message.contains("Подключ") || message.contains("буфер"))) {
                if (liveSessionActive && (liveStartEpochMs > 0L || sessionStartEpochMs > 0L)) {
                    liveProgress.setIndeterminate(false);
                    updateElapsedView();
                } else {
                    liveProgress.setIndeterminate(true);
                }
            } else if (playing) {
                liveSessionActive = true;
                ensureSessionStartTime();
                liveProgress.setIndeterminate(false);
                updateElapsedView();
                startElapsedTicker();
            } else if (!playing) {
                liveProgress.setIndeterminate(false);
                if (liveSessionActive && (liveStartEpochMs > 0L || sessionStartEpochMs > 0L)) {
                    updateElapsedView();
                    startElapsedTicker();
                } else if (liveSessionActive) {
                    elapsedView.setText(message != null && message.toLowerCase(Locale.ROOT).contains("пауза")
                            ? "ПАУЗА" : "LIVE");
                } else {
                    elapsedView.setText(message != null && message.toLowerCase(Locale.ROOT).contains("пауза")
                            ? "ПАУЗА" : "ОЖИДАНИЕ ЭФИРА");
                }
            }
            updateControls();
        });
    }

    private void acceptLiveStartTime(long timestampMs, int source) {
        if (timestampMs <= 0L || source != currentSource || (source != SOURCE_RADIO && source != webSource)) return;
        // Keep the first trustworthy start timestamp for this broadcast session across source changes.
        if (liveStartEpochMs == 0L) {
            liveStartEpochMs = timestampMs;
            if (preferences != null) preferences.edit().putLong("live_start_epoch_ms", liveStartEpochMs).apply();
        }
        if (liveSessionActive) startElapsedTicker();
    }

    private void clearLiveStartTime() {
        liveStartEpochMs = 0L;
        sessionStartEpochMs = 0L;
        liveScaleDurationMs = DEFAULT_LIVE_SCALE_MS;
        liveScaleFromMetadata = false;
        if (preferences != null) preferences.edit()
                .remove("live_start_epoch_ms")
                .remove("session_start_epoch_ms")
                .remove("live_scale_duration_ms")
                .remove("live_scale_from_metadata")
                .apply();
        updateLiveProgress();
    }

    private void acceptLiveDuration(long durationMs, int source) {
        if (durationMs < 5L * 60L * 1000L || durationMs > 24L * 60L * 60L * 1000L
                || source != currentSource || source != webSource || liveScaleFromMetadata) return;
        liveScaleDurationMs = durationMs;
        liveScaleFromMetadata = true;
        if (preferences != null) preferences.edit()
                .putLong("live_scale_duration_ms", liveScaleDurationMs)
                .putBoolean("live_scale_from_metadata", true)
                .apply();
        updateLiveProgress();
    }

    private void ensureSessionStartTime() {
        if (sessionStartEpochMs > 0L) return;
        sessionStartEpochMs = System.currentTimeMillis();
        if (preferences != null) preferences.edit()
                .putLong("session_start_epoch_ms", sessionStartEpochMs)
                .apply();
    }

    private void startElapsedTicker() {
        handler.removeCallbacks(elapsedTicker);
        updateElapsedView();
        if (liveSessionActive && (liveStartEpochMs > 0L || sessionStartEpochMs > 0L)) {
            handler.postDelayed(elapsedTicker, 1000L);
        }
    }

    private void updateElapsedView() {
        if (!liveSessionActive || elapsedView == null) return;
        long anchor = liveStartEpochMs > 0L ? liveStartEpochMs : sessionStartEpochMs;
        if (liveStartEpochMs > 0L) {
            long seconds = Math.max(0L, (System.currentTimeMillis() - liveStartEpochMs) / 1000L);
            elapsedView.setText("LIVE  ·  " + formatTime(seconds));
        } else if (sessionStartEpochMs > 0L) {
            long seconds = Math.max(0L, (System.currentTimeMillis() - sessionStartEpochMs) / 1000L);
            elapsedView.setText("LIVE  ·  с подключения " + formatTime(seconds));
        } else {
            elapsedView.setText("LIVE");
        }
        if (anchor > 0L) updateLiveProgress();
    }

    private void updateLiveProgress() {
        if (liveProgress == null) return;
        long anchor = liveStartEpochMs > 0L ? liveStartEpochMs : sessionStartEpochMs;
        if (!liveSessionActive || anchor <= 0L) {
            liveProgress.setIndeterminate(false);
            liveProgress.setProgress(0);
            return;
        }
        long elapsed = Math.max(0L, System.currentTimeMillis() - anchor);
        long scale = Math.max(1L, liveScaleDurationMs);
        // Never let a long-running broadcast pin the bar at 100%: grow the scale
        // in whole broadcast-duration steps (or whole hours for the fallback).
        if (elapsed >= scale) {
            long steps = (elapsed / scale) + 1L;
            if (steps > Long.MAX_VALUE / scale) steps = Long.MAX_VALUE / scale;
            scale = Math.max(scale, steps * scale);
            liveScaleDurationMs = scale;
            if (preferences != null) preferences.edit().putLong("live_scale_duration_ms", scale).apply();
        }
        long scaledProgress = (elapsed * 1000L) / scale;
        int progress = elapsed > 0L ? (int) Math.max(1L, Math.min(999L, scaledProgress)) : 0;
        liveProgress.setIndeterminate(false);
        liveProgress.setProgress(progress);
    }

    private static long extractLiveStartTimeMs(String body, int source) {
        Object parsed = parseJsonBody(body);
        if (parsed == null) return 0L;
        return findLiveStartTimeInTree(parsed, source);
    }

    private static long extractLiveDurationMs(String body, int source) {
        Object parsed = parseJsonBody(body);
        if (parsed == null) return 0L;
        return findLiveDurationInTree(parsed, source);
    }

    private static long findLiveDurationInTree(Object node, int source) {
        return findLiveDurationInTree(node, source, false);
    }

    private static long findLiveDurationInTree(Object node, int source, boolean inheritedLiveContext) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            boolean liveContext = inheritedLiveContext || hasLocalLiveMetadataMarker(object, source);
            if (liveContext) {
                String[] durationKeys = {
                        "broadcast_duration_ms", "stream_duration_ms", "live_duration_ms",
                        "total_duration_ms", "expected_duration_ms", "planned_duration_ms",
                        "broadcast_duration_seconds", "stream_duration_seconds", "live_duration_seconds",
                        "total_duration_seconds", "expected_duration_seconds", "planned_duration_seconds",
                        "broadcast_duration", "stream_duration", "live_duration", "total_duration",
                        "expected_duration", "planned_duration", "scheduled_duration", "duration_seconds"
                };
                for (String key : durationKeys) {
                    long duration = parseDurationMillis(object.opt(key), key);
                    if (isPlausibleLiveDuration(duration)) return duration;
                }
                // A generic duration is accepted only on an explicitly LIVE object that also has a start timestamp.
                if (object.has("duration") && hasLiveStartField(object)) {
                    long duration = parseDurationMillis(object.opt("duration"), "duration");
                    if (isPlausibleLiveDuration(duration)) return duration;
                }
                long start = findStartOnObject(object);
                String[] endKeys = {"planned_end_at", "scheduled_end_at", "expected_end_at", "broadcast_end_at", "stream_end_at", "live_end_at"};
                for (String key : endKeys) {
                    long end = parseTimestampMillis(object.opt(key));
                    if (start > 0L && end > start) {
                        long duration = end - start;
                        if (isPlausibleLiveDuration(duration)) return duration;
                    }
                }
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    long candidate = findLiveDurationInTree(object.opt(names.optString(i)), source, liveContext);
                    if (candidate > 0L) return candidate;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                long candidate = findLiveDurationInTree(array.opt(i), source, inheritedLiveContext);
                if (candidate > 0L) return candidate;
            }
        }
        return 0L;
    }

    private static boolean hasLiveStartField(JSONObject object) {
        String[] keys = {"live_started_at", "live_started_ts", "live_start_at", "live_start_time", "live_start_ts",
                "stream_started_at", "stream_started_ts", "stream_start_at", "stream_start_time", "stream_start_ts",
                "broadcast_started_at", "broadcast_started_ts", "broadcast_start_at", "broadcast_start_time", "broadcast_start_ts",
                "actual_start_time", "actual_start_ts", "actual_started_at", "actual_started_ts",
                "started_at", "started_ts", "start_time", "start_ts"};
        for (String key : keys) if (object.has(key) && parseTimestampMillis(object.opt(key)) > 0L) return true;
        return false;
    }

    private static long findStartOnObject(JSONObject object) {
        String[] keys = {"live_started_at", "live_started_ts", "live_start_at", "live_start_time", "live_start_ts",
                "stream_started_at", "stream_started_ts", "stream_start_at", "stream_start_time", "stream_start_ts",
                "broadcast_started_at", "broadcast_started_ts", "broadcast_start_at", "broadcast_start_time", "broadcast_start_ts",
                "actual_start_time", "actual_start_ts", "actual_started_at", "actual_started_ts",
                "started_at", "started_ts", "start_time", "start_ts"};
        for (String key : keys) {
            long value = parseTimestampMillis(object.opt(key));
            if (isPlausibleLiveStart(value)) return value;
        }
        return 0L;
    }

    private static long parseDurationMillis(Object value, String key) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) {
            double raw = ((Number) value).doubleValue();
            if (!Double.isFinite(raw) || raw <= 0d) return 0L;
            if (key.endsWith("_ms") || raw > 86400d) return (long) raw;
            return (long) (raw * 1000d);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return 0L;
        try {
            if (text.matches("\\d{1,2}:\\d{2}(:\\d{2})?")) {
                String[] parts = text.split(":");
                long seconds = parts.length == 3
                        ? Long.parseLong(parts[0]) * 3600L + Long.parseLong(parts[1]) * 60L + Long.parseLong(parts[2])
                        : Long.parseLong(parts[0]) * 60L + Long.parseLong(parts[1]);
                return seconds * 1000L;
            }
            if (text.matches("(?i)PT.*")) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(?i)PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?").matcher(text);
                if (matcher.matches()) {
                    double hours = matcher.group(1) == null ? 0d : Double.parseDouble(matcher.group(1));
                    double minutes = matcher.group(2) == null ? 0d : Double.parseDouble(matcher.group(2));
                    double seconds = matcher.group(3) == null ? 0d : Double.parseDouble(matcher.group(3));
                    return (long) ((hours * 3600d + minutes * 60d + seconds) * 1000d);
                }
            }
            double raw = Double.parseDouble(text);
            if (raw <= 0d) return 0L;
            if (key.endsWith("_ms") || raw > 86400d) return (long) raw;
            return (long) (raw * 1000d);
        } catch (Exception ignored) { return 0L; }
    }

    private static boolean isPlausibleLiveDuration(long durationMs) {
        return durationMs >= 5L * 60L * 1000L && durationMs <= 24L * 60L * 60L * 1000L;
    }

    private static long findLiveStartTimeInTree(Object node, int source) {
        return findLiveStartTimeInTree(node, source, false);
    }

    private static long findLiveStartTimeInTree(Object node, int source, boolean inheritedLiveContext) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            boolean liveContext = inheritedLiveContext || hasLocalLiveMetadataMarker(object, source);
            if (liveContext) {
                String[] keys = {
                        "live_started_at", "live_started_ts", "live_start_at", "live_start_time", "live_start_ts",
                        "stream_started_at", "stream_started_ts", "stream_start_at", "stream_start_time", "stream_start_ts",
                        "broadcast_started_at", "broadcast_started_ts", "broadcast_start_at", "broadcast_start_time", "broadcast_start_ts",
                        "actual_start_time", "actual_start_ts", "actual_started_at", "actual_started_ts",
                        "started_at", "started_ts", "start_time", "start_ts"
                };
                for (String key : keys) {
                    long candidate = parseTimestampMillis(object.opt(key));
                    if (isPlausibleLiveStart(candidate)) return candidate;
                }
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    long candidate = findLiveStartTimeInTree(object.opt(names.optString(i)), source, liveContext);
                    if (candidate > 0L) return candidate;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                long candidate = findLiveStartTimeInTree(array.opt(i), source, inheritedLiveContext);
                if (candidate > 0L) return candidate;
            }
        }
        return 0L;
    }

    private static boolean hasLocalLiveMetadataMarker(JSONObject object, int source) {
        if (isTruthy(object.opt("is_live")) || isTruthy(object.opt("is_livestream"))
                || isTruthy(object.opt("live"))) return true;
        if ("live".equalsIgnoreCase(object.optString("type", ""))
                || "live".equalsIgnoreCase(object.optString("live_status", ""))
                || "started".equalsIgnoreCase(object.optString("live_status", ""))) return true;
        return source == SOURCE_RUTUBE && object.has("live_streams");
    }

    private static boolean isTruthy(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value))
                || "1".equals(String.valueOf(value));
    }

    private static long parseTimestampMillis(Object value) {
        if (value == null || value == JSONObject.NULL) return 0L;
        if (value instanceof Number) {
            double raw = ((Number) value).doubleValue();
            return raw > 1000000000000d ? (long) raw : (long) (raw * 1000d);
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) return 0L;
        try {
            double raw = Double.parseDouble(text);
            return raw > 1000000000000d ? (long) raw : (long) (raw * 1000d);
        } catch (NumberFormatException ignored) { }
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
                "yyyy-MM-dd'T'HH:mm:ssXXX",
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'"
        };
        for (String pattern : patterns) {
            try {
                java.text.SimpleDateFormat parser = new java.text.SimpleDateFormat(pattern, Locale.US);
                parser.setLenient(false);
                if (pattern.endsWith("'Z'")) parser.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                java.util.Date parsed = parser.parse(text);
                if (parsed != null) return parsed.getTime();
            } catch (Exception ignored) { }
        }
        return 0L;
    }

    private static boolean isPlausibleLiveStart(long timestampMs) {
        if (timestampMs < 946684800000L) return false; // Before 2000-01-01 is not a valid stream start.
        long now = System.currentTimeMillis();
        return timestampMs <= now + 2L * 60L * 1000L
                && timestampMs >= now - 14L * 24L * 60L * 60L * 1000L;
    }

    private static String formatTime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        return hours > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, secs) : String.format(Locale.ROOT, "%02d:%02d", minutes, secs);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onStart() {
        super.onStart();
        Intent service = new Intent(this, AudioPlaybackService.class);
        bound = bindService(service, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        if (webView != null && !streamStarted) {
            cancelSourceTimeout();
            releaseWebView();
            if (statusView != null) statusView.setText("Поиск эфира приостановлен. Вернитесь в приложение и нажмите «Старт».");
        }
        if (bound) {
            if (playbackService != null) playbackService.clearPlaybackStateListener();
            unbindService(serviceConnection);
            bound = false;
            playbackService = null;
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        cancelSourceTimeout();
        handler.removeCallbacks(elapsedTicker);
        releaseWebView();
        super.onDestroy();
    }
}
