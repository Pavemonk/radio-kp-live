package ru.radiokp.voennoerevue;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements AudioPlaybackService.PlaybackStateListener {
    private static final int SOURCE_VK = 0;
    private static final int SOURCE_RUTUBE = 1;
    private static final int SOURCE_RADIO = 2;
    private static final String[] SOURCE_NAMES = {"VK Видео", "RuTube", "Радио КП"};
    private static final String VK_LIVES_URL = "https://vkvideo.ru/@radiokp/lives";
    private static final String RUTUBE_LIVE_FEED_URL = "https://rutube.ru/feeds/stream/";
    private static final String PREFS = "radio_kp_preferences";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences preferences;
    private TextView statusView;
    private TextView sourceView;
    private TextView elapsedView;
    private Button playPauseButton;
    private Button sourcePageButton;
    private Button[] sourceButtons = new Button[3];
    private ProgressBar liveProgress;
    private WebView webView;
    private LinearLayout webViewContainer;
    private AudioPlaybackService playbackService;
    private boolean bound = false;
    private volatile int currentSource = SOURCE_VK;
    private volatile int webSource = SOURCE_VK;
    private volatile String currentPageUrl = VK_LIVES_URL;
    private volatile boolean streamStarted = false;
    private boolean autoStart = true;
    private boolean autoFailover = true;
    private int vkQualityKbps = 64;
    private int rutubeQualityKbps = 64;
    private int radioQualityKbps = 64;
    private int attemptedSourcesMask = 0;
    private int attemptId = 0;
    private long liveStartEpochMs = 0L;
    private boolean liveSessionActive = false;
    private boolean isPlaying = false;
    private Runnable sourceTimeout;

    private final Runnable elapsedTicker = new Runnable() {
        @Override public void run() {
            if (liveSessionActive && liveStartEpochMs > 0L && elapsedView != null) {
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
        vkQualityKbps = preferences.getInt("vk_quality_kbps", 64);
        rutubeQualityKbps = preferences.getInt("rutube_quality_kbps", 64);
        radioQualityKbps = preferences.getInt("radio_quality_kbps", 64);
        buildUi();
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
        liveProgress.setMax(100);
        liveProgress.setProgress(0);
        liveProgress.setIndeterminate(true);
        liveProgress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 107, 53)));
        liveProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 107, 53)));
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(5));
        progressLp.topMargin = dp(19);
        root.addView(liveProgress, progressLp);

        elapsedView = text("ОЖИДАНИЕ ЭФИРА", 12, Color.rgb(155, 164, 178), true);
        LinearLayout.LayoutParams elapsedLp = new LinearLayout.LayoutParams(-1, -2);
        elapsedLp.topMargin = dp(8);
        elapsedLp.bottomMargin = dp(18);
        root.addView(elapsedView, elapsedLp);

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

        sourcePageButton = smallButton("ПОКАЗАТЬ СТРАНИЦУ ИСТОЧНИКА", false);
        sourcePageButton.setTextSize(11);
        sourcePageButton.setVisibility(View.GONE);
        sourcePageButton.setOnClickListener(v -> toggleSourcePage());
        LinearLayout.LayoutParams sourcePageLp = new LinearLayout.LayoutParams(-1, dp(40));
        sourcePageLp.topMargin = dp(9);
        root.addView(sourcePageButton, sourcePageLp);

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
        streamStarted = false;
        liveSessionActive = liveStartEpochMs > 0L;
        isPlaying = false;
        if (liveSessionActive) startElapsedTicker();
        else handler.removeCallbacks(elapsedTicker);
        if (playbackService != null && playbackService.hasStream()) playbackService.stopPlayback();
        releaseWebView();
        updateControls();

        if (source == SOURCE_RADIO) {
            if (sourcePageButton != null) sourcePageButton.setVisibility(View.GONE);
            startRadioStream();
            return;
        }
        webSource = source;
        currentPageUrl = source == SOURCE_VK ? VK_LIVES_URL : RUTUBE_LIVE_FEED_URL;
        statusView.setText(source == SOURCE_VK
                ? "Ищу текущую прямую трансляцию VK…"
                : "Ищу прямой эфир Радио КП в общей LIVE-ленте RuTube…");
        liveProgress.setIndeterminate(true);
        createWebViewIfNeeded();
        // Keep source discovery out of sight; the user can reveal the page only for manual selection.
        webViewContainer.setVisibility(View.GONE);
        if (sourcePageButton != null) {
            sourcePageButton.setText(source == SOURCE_VK ? "ПОКАЗАТЬ СТРАНИЦУ VK" : "ПОКАЗАТЬ СТРАНИЦУ RUTUBE");
            sourcePageButton.setVisibility(View.VISIBLE);
        }
        webView.loadUrl(currentPageUrl);
        final int scheduledAttempt = attemptId;
        sourceTimeout = () -> {
            if (scheduledAttempt == attemptId && !streamStarted && currentSource == source) {
                if (source == SOURCE_RUTUBE) {
                    statusView.setText("В LIVE-ленте RuTube не найден эфир Радио КП. Перехожу к следующему источнику…");
                } else {
                    statusView.setText("VK не ответил или эфир недоступен. Пробую RuTube…");
                }
                tryNextSource("Время ожидания истекло");
            }
        };
        handler.postDelayed(sourceTimeout, 35000L);
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
        Intent intent = new Intent(this, AudioPlaybackService.class)
                .setAction(AudioPlaybackService.ACTION_PLAY)
                .putExtra(AudioPlaybackService.EXTRA_URL, url)
                .putStringArrayListExtra(AudioPlaybackService.EXTRA_URL_CANDIDATES, urls)
                .putStringArrayListExtra(AudioPlaybackService.EXTRA_DASH_URL_CANDIDATES, dashUrls)
                .putExtra(AudioPlaybackService.EXTRA_REFERER, referer)
                .putExtra(AudioPlaybackService.EXTRA_SOURCE_NAME, sourceName)
                .putExtra(AudioPlaybackService.EXTRA_MAX_AUDIO_BITRATE, qualityKbps)
                .putExtra(AudioPlaybackService.EXTRA_IS_MP3, mp3);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
        updateControls();
    }

    private void tryNextSource(String reason) {
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
        if (sourcePageButton != null) sourcePageButton.setVisibility(View.GONE);
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
        if (sourcePageButton != null) sourcePageButton.setVisibility(View.GONE);
        liveProgress.setIndeterminate(false);
        liveProgress.setProgress(0);
        elapsedView.setText("ОСТАНОВЛЕНО");
        statusView.setText("Воспроизведение остановлено. Нажмите «Старт», чтобы подключиться снова.");
        updateControls();
    }

    private void toggleSourcePage() {
        if (webView == null || webViewContainer == null) return;
        boolean show = webViewContainer.getVisibility() != View.VISIBLE;
        webViewContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        if (sourcePageButton != null) {
            sourcePageButton.setText(show ? "СКРЫТЬ СТРАНИЦУ ИСТОЧНИКА" :
                    (webSource == SOURCE_VK ? "ПОКАЗАТЬ СТРАНИЦУ VK" : "ПОКАЗАТЬ СТРАНИЦУ RUTUBE"));
        }
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
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentPageUrl = url;
                if (source == SOURCE_VK && (url.contains("login.vk.ru") || url.contains("login.vk.com"))) {
                    statusView.setText("VK просит авторизоваться. Войдите во встроенной странице и вернитесь к эфирам.");
                } else if (source == SOURCE_RUTUBE && url.contains("/feeds/stream")) {
                    statusView.setText("Открыта лента прямых эфиров RuTube. Ищу канал Радио КП…");
                }
            }

            @Override public void onPageFinished(WebView view, String url) {
                currentPageUrl = url;
                if (source == SOURCE_VK && isVkPage(url)) {
                    view.evaluateJavascript(INSTALL_VK_HOOK_SCRIPT, null);
                    if (url.contains("/@radiokp/lives")) view.evaluateJavascript(AUTO_OPEN_VK_LIVE_SCRIPT, null);
                } else if (source == SOURCE_RUTUBE && isRutubePage(url)) {
                    view.evaluateJavascript(INSTALL_RUTUBE_HOOK_SCRIPT, null);
                    if (url.contains("/feeds/stream")) view.evaluateJavascript(AUTO_OPEN_RUTUBE_LIVE_SCRIPT, null);
                }
            }

            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                String method = request.getMethod();
                String host = request.getUrl().getHost();
                if (!"GET".equalsIgnoreCase(method)) return super.shouldInterceptRequest(view, request);
                if (source == SOURCE_VK && url.contains("video.getVideoDiscover") && isVkHost(host)) {
                    return fetchAndInspect(url, request.getRequestHeaders(), source);
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
            boolean selectedRutubeMetadata = source != SOURCE_RUTUBE
                    || !url.contains("/api/video/")
                    || isSelectedRutubeVideoMetadataUrl(url);
            long liveStartMs = selectedRutubeMetadata ? extractLiveStartTimeMs(body, source) : 0L;
            ArrayList<String> resolved = source == SOURCE_VK ? extractVkHlsUrls(body) : extractRutubeLiveHlsUrls(body);
            ArrayList<String> dashResolved = source == SOURCE_RUTUBE ? extractRutubeLiveDashUrls(body) : new ArrayList<>();
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
            "function report(t){try{if(typeof t==='string'&&t.indexOf('hls_live')!==-1&&window.AudioBridge)window.AudioBridge.onPossibleStreamText(t)}catch(e){}}" +
            "var f=window.fetch;if(f)window.fetch=function(){return f.apply(this,arguments).then(function(r){try{r.clone().text().then(report)}catch(e){}return r})};" +
            "var op=XMLHttpRequest.prototype.open,se=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){this.__radioKpUrl=String(u||'');return op.apply(this,arguments)};" +
            "XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{report(this.responseText)}catch(e){}});return se.apply(this,arguments)};" +
            "}catch(e){}})();";

    private static final String AUTO_OPEN_VK_LIVE_SCRIPT = "(function(){try{setTimeout(function(){" +
            "var els=[].slice.call(document.querySelectorAll('a[href]'));" +
            "for(var i=0;i<els.length;i++){var a=els[i],p=a.closest('article,[data-testid],[class*=VideoCard],[class*=video-card]')||a;" +
            "var t=((a.innerText||'')+' '+(p.innerText||'')).toLowerCase();" +
            "if((/прям.{0,8}эфир|сейчас в эфире|live now|live broadcast/.test(t))&&(/video-|live-/.test(a.href))){a.click();return;}}" +
            "},1300)}catch(e){}})();";

    private static final String INSTALL_RUTUBE_HOOK_SCRIPT = "(function(){try{if(window.__radioKpRutubeHook)return;window.__radioKpRutubeHook=true;" +
            "function hideVideo(){try{document.querySelectorAll('video').forEach(function(v){v.style.setProperty('position','fixed','important');v.style.setProperty('left','-5px','important');v.style.setProperty('top','-5px','important');v.style.setProperty('width','1px','important');v.style.setProperty('height','1px','important');v.style.setProperty('opacity','0.001','important');v.style.setProperty('pointer-events','none','important');v.controls=false;v.muted=true;try{v.pause();v.removeAttribute('autoplay')}catch(e){}})}catch(e){}}" +
            "hideVideo();try{new MutationObserver(hideVideo).observe(document.documentElement,{childList:true,subtree:true})}catch(e){}" +
            "function report(t){try{if(typeof t==='string'&&(t.indexOf('video_balancer')!==-1||t.indexOf('live_streams')!==-1||t.indexOf('is_live')!==-1||t.indexOf('is_livestream')!==-1)&&window.AudioBridge)window.AudioBridge.onPossibleStreamText(t)}catch(e){}}" +
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

    private final class StreamBridge {
        private final int source;
        StreamBridge(int source) { this.source = source; }
        @JavascriptInterface public void onPossibleStreamText(String body) {
            if (body == null || body.length() > 4 * 1024 * 1024 || source != currentSource || source != webSource) return;
            ArrayList<String> streams = source == SOURCE_VK ? extractVkHlsUrls(body) : extractRutubeLiveHlsUrls(body);
            ArrayList<String> dashStreams = source == SOURCE_RUTUBE ? extractRutubeLiveDashUrls(body) : new ArrayList<>();
            long liveStartMs = (!streams.isEmpty() || !dashStreams.isEmpty())
                    ? extractLiveStartTimeMs(body, source) : 0L;
            if (!streams.isEmpty() || !dashStreams.isEmpty()) handleStreamResolved(streams, dashStreams, source, liveStartMs);
        }
    }

    private static final int MAX_HLS_CANDIDATES = 8;
    private static final int MAX_DASH_CANDIDATES = 4;

    private static ArrayList<String> extractVkHlsUrls(String body) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) return new ArrayList<>(urls);
        Object parsed = parseJsonBody(body);
        if (parsed != null) {
            addHlsCandidate(findKey(parsed, "hls_live"), SOURCE_VK, urls);
            addHlsCandidate(findKey(parsed, "hls_live_playback"), SOURCE_VK, urls);
            collectM3u8Urls(parsed, SOURCE_VK, urls);
        }
        Matcher matcher = Pattern.compile("https?://[^\\s\"']+?\\.m3u8(?:\\?[^\\s\"']*)?", Pattern.CASE_INSENSITIVE).matcher(body);
        while (matcher.find() && urls.size() < MAX_HLS_CANDIDATES) {
            addHlsCandidate(matcher.group(), SOURCE_VK, urls);
        }
        return new ArrayList<>(urls);
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

    private static boolean isAllowedRutubeHls(String url) {
        if (url == null || !url.startsWith("https://") || !url.toLowerCase(Locale.ROOT).contains(".m3u8")) return false;
        String host = Uri.parse(url).getHost();
        return host != null && (host.equals("rutube.ru") || host.endsWith(".rutube.ru") || host.equals("rtbcdn.ru") || host.endsWith(".rtbcdn.ru") || host.equals("rtbcdn.net") || host.endsWith(".rtbcdn.net"));
    }

    private static boolean isVkCdnHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.endsWith(".okcdn.ru") || host.endsWith(".vkuservideo.net") || host.endsWith(".userapi.com") || host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com") || host.endsWith(".vk.com");
    }

    private static boolean isVkHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals("vkvideo.ru") || host.endsWith(".vkvideo.ru") || host.equals("vk.com") || host.endsWith(".vk.com") || host.equals("vk.ru") || host.endsWith(".vk.ru") || host.equals("api.vkvideo.ru") || host.endsWith(".api.vkvideo.ru");
    }

    private static boolean isRutubeHost(String host) {
        return host != null && (host.equalsIgnoreCase("rutube.ru") || host.toLowerCase(Locale.ROOT).endsWith(".rutube.ru"));
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
        streamStarted = true;
        cancelSourceTimeout();
        runOnUiThread(() -> {
            if (discoveredStartTimeMs > 0L) acceptLiveStartTime(discoveredStartTimeMs, source);
            liveSessionActive = true;
            startElapsedTicker();
            statusView.setText("Найдено HLS: " + safeHls.size() + " · DASH: " + safeDash.size() + ". Ищу отдельное аудио…");
            Runnable play = () -> {
                if (sourcePageButton != null) sourcePageButton.setVisibility(View.GONE);
                releaseWebView();
                String referer = source == SOURCE_VK ? "https://vkvideo.ru/" : "https://rutube.ru/";
                int quality = source == SOURCE_RUTUBE ? rutubeQualityKbps : vkQualityKbps;
                startPlayerService(primaryUrl, safeHls, safeDash, referer, SOURCE_NAMES[source], quality, false);
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
                    preferences.edit()
                            .putInt("vk_quality_kbps", vkQualityKbps)
                            .putInt("rutube_quality_kbps", rutubeQualityKbps)
                            .putInt("radio_quality_kbps", radioQualityKbps)
                            .putBoolean("auto_start", autoStart)
                            .putBoolean("auto_failover", autoFailover)
                            .apply();
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

    @Override public void onPlaybackStateChanged(boolean playing, String message) {
        runOnUiThread(() -> {
            if (message != null && message.startsWith("SOURCE_ERROR:")) {
                isPlaying = false;
                liveProgress.setIndeterminate(false);
                liveProgress.setProgress(0);
                if (liveStartEpochMs > 0L && liveSessionActive) updateElapsedView();
                if (autoFailover) {
                    statusView.setText("Поток " + SOURCE_NAMES[currentSource] + " прервался. Ищу резервный источник…");
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
                liveProgress.setIndeterminate(true);
            } else if (playing) {
                liveSessionActive = true;
                liveProgress.setIndeterminate(false);
                liveProgress.setProgress(100);
                updateElapsedView();
                startElapsedTicker();
            } else if (!playing) {
                liveProgress.setIndeterminate(false);
                liveProgress.setProgress(0);
                if (liveStartEpochMs > 0L && liveSessionActive) {
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
        }
        if (liveSessionActive) startElapsedTicker();
    }

    private void clearLiveStartTime() {
        liveStartEpochMs = 0L;
    }

    private void startElapsedTicker() {
        handler.removeCallbacks(elapsedTicker);
        updateElapsedView();
        if (liveSessionActive && liveStartEpochMs > 0L) handler.postDelayed(elapsedTicker, 1000L);
    }

    private void updateElapsedView() {
        if (!liveSessionActive || elapsedView == null) return;
        if (liveStartEpochMs > 0L) {
            long seconds = Math.max(0L, (System.currentTimeMillis() - liveStartEpochMs) / 1000L);
            elapsedView.setText("LIVE  ·  " + formatTime(seconds));
        } else {
            // The source is live, but no authoritative start time was supplied by its metadata.
            elapsedView.setText("LIVE");
        }
    }

    private static long extractLiveStartTimeMs(String body, int source) {
        Object parsed = parseJsonBody(body);
        if (parsed == null) return 0L;
        return findLiveStartTimeInTree(parsed, source);
    }

    private static long findLiveStartTimeInTree(Object node, int source) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if (hasLocalLiveMetadataMarker(object, source)) {
                String[] keys = {
                        "live_started_at", "live_started_ts",
                        "stream_started_at", "stream_started_ts",
                        "broadcast_started_at", "broadcast_started_ts",
                        "actual_start_time", "actual_started_at", "started_at"
                };
                for (String key : keys) {
                    long candidate = parseTimestampMillis(object.opt(key));
                    if (isPlausibleLiveStart(candidate)) return candidate;
                }
            }
            JSONArray names = object.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    long candidate = findLiveStartTimeInTree(object.opt(names.optString(i)), source);
                    if (candidate > 0L) return candidate;
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                long candidate = findLiveStartTimeInTree(array.opt(i), source);
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
            if (sourcePageButton != null) sourcePageButton.setVisibility(View.GONE);
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

    @Override public void onBackPressed() {
        if (webView != null && webViewContainer.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else if (webView != null) {
            releaseWebView();
            if (sourcePageButton != null) sourcePageButton.setVisibility(View.GONE);
            statusView.setText("Страница источника закрыта. Если эфир уже запущен, аудио продолжит играть.");
        } else {
            super.onBackPressed();
        }
    }

    @Override protected void onDestroy() {
        cancelSourceTimeout();
        handler.removeCallbacks(elapsedTicker);
        releaseWebView();
        super.onDestroy();
    }
}
