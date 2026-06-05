package com.aeye.nativeapp;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.ByteArrayOutputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends ComponentActivity implements SensorEventListener {
    private enum Screen {
        HOME,
        SEARCH,
        NAVIGATION,
        SETTINGS
    }

    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;
    private static final int REQUEST_AUDIO_PERMISSION = 1003;
    private static final int REQUEST_SPEECH_INPUT = 2001;
    private static final int REQUEST_VOICE_COMMAND = 2002;
    // Keep object detection calm enough for walking guidance and demo readability.
    private static final long ANALYSIS_INTERVAL_MS = 1500;
    private static final long SPEAK_INTERVAL_MS = 3000;
    private static final long POST_INTERVAL_MS = 2500;
    private static final long HAZARD_ALERT_COOLDOWN_MS = 5000;
    private static final long VOICE_COMMAND_FOLLOW_UP_DELAY_MS = 2400;
    private static final String PREF_NAME = "a-eye-native";
    private static final String PREF_SERVER_URL = "server-url";
    private static final String PREF_RECENT_DESTINATIONS = "recent-destinations";
    private static final String PREF_FAVORITE_DESTINATION = "favorite-destination";
    private static final String PREF_GUARDIAN_PHONE = "guardian-phone";
    private static final String PREF_TTS_RATE = "tts-rate-percent";
    private static final String PREF_VIBRATION = "vibration-enabled";
    private static final String PREF_DETECTION_THRESHOLD = "detection-threshold-percent";
    private static final String DEVICE_ID = "demo-phone-01";

    private static final int COLOR_BLUE = Color.rgb(24, 95, 165);
    private static final int COLOR_BLUE_DARK = Color.rgb(13, 71, 124);
    private static final int COLOR_BLUE_LIGHT = Color.rgb(230, 241, 251);
    private static final int COLOR_BACKGROUND = Color.rgb(247, 247, 243);
    private static final int COLOR_SURFACE = Color.rgb(242, 241, 236);
    private static final int COLOR_SURFACE_ALT = Color.rgb(250, 249, 244);
    private static final int COLOR_BORDER = Color.rgb(201, 201, 193);
    private static final int COLOR_DANGER = Color.rgb(174, 35, 43);
    private static final int COLOR_WARNING = Color.rgb(245, 145, 24);
    private static final int COLOR_WARNING_BG = Color.rgb(255, 244, 224);
    private static final int COLOR_TEXT = Color.rgb(31, 31, 31);
    private static final int COLOR_MUTED = Color.rgb(96, 96, 92);

    private final Set<String> riskLabels = new HashSet<>();
    private final Map<String, String> koreanLabels = new HashMap<>();
    private final VoiceCommandParser voiceCommandParser = new VoiceCommandParser();
    private final LocalLlmInterpreter localLlmInterpreter = new RuleBasedLocalLlmInterpreter();

    private LinearLayout appRoot;
    private LinearLayout screenContainer;
    private LinearLayout bottomNav;
    private TextView statusText;
    private TextView guideText;
    private TextView detectionText;
    private TextView locationChipText;
    private TextView serverStatusText;
    private EditText serverInput;
    private EditText guardianInput;

    private ExecutorService cameraExecutor;
    private YoloDetector detector;
    private TextToSpeech tts;
    private ProcessCameraProvider cameraProvider;
    private GestureDetector navigationGestureDetector;
    private AlertDialog hazardDialog;
    private Handler mainHandler;
    private OkHttpClient streamHttpClient;
    private WebSocket streamWebSocket;

    private long lastAnalysisAt = 0L;
    private long lastSpokenAt = 0L;
    private long lastPostedAt = 0L;
    private long lastHazardAlertAt = 0L;
    private long hazardSuppressedUntil = 0L;
    private String lastHazardKey = "";
    private String lastGuideMessage = "목적지를 설정하세요.";
    private String lastServerStatusMessage = "서버 전송 대기 중";
    private String activeDestination = "";
    private String routeChipMessage = "목적지를 설정하세요.";
    private String nextActionMessage = "직진 200m";
    private String nextActionSubMessage = "다음: 우회전";
    private String nextPreviewMessage = "우회전 후 직진 500m";
    private boolean cameraRunning = false;
    private boolean navigationSessionActive = false;
    private boolean pendingSearchSpeech = false;
    private boolean pendingVoiceCommandAfterPermission = false;
    private boolean pendingDestinationSpeechAfterPermission = false;
    private boolean developerStreamingEnabled = false;
    private boolean voiceIntroSpoken = false;
    private Screen currentScreen = Screen.HOME;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private LocationManager locationManager;
    private double currentLat = 37.54167;
    private double currentLng = 126.84028;
    private Float currentHeading = null;

    private final LocationListener locationListener = location -> {
        currentLat = location.getLatitude();
        currentLng = location.getLongitude();
        runOnUiThread(this::updateLocationUi);
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mainHandler = new Handler(Looper.getMainLooper());
        streamHttpClient = new OkHttpClient();
        cameraExecutor = Executors.newSingleThreadExecutor();
        setupLabels();
        setupTts();
        setupUi();
        setupDetector();
        setupSensors();
        setupLocation();

        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private void setupUi() {
        appRoot = new LinearLayout(this);
        appRoot.setOrientation(LinearLayout.VERTICAL);
        appRoot.setBackgroundColor(COLOR_BACKGROUND);

        screenContainer = new LinearLayout(this);
        screenContainer.setOrientation(LinearLayout.VERTICAL);
        appRoot.addView(screenContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setGravity(Gravity.CENTER);
        bottomNav.setPadding(dp(12), dp(8), dp(12), dp(10));
        bottomNav.setBackgroundColor(Color.WHITE);
        appRoot.addView(bottomNav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        setContentView(appRoot);
        renderScreen(Screen.HOME, false);
    }

    private void renderScreen(Screen screen, boolean autoSpeech) {
        currentScreen = screen;
        pendingSearchSpeech = autoSpeech;
        clearScreenRefs();
        screenContainer.removeAllViews();

        if (screen == Screen.HOME) {
            screenContainer.addView(buildHomeScreen());
        } else if (screen == Screen.SEARCH) {
            screenContainer.addView(buildSearchScreen());
        } else if (screen == Screen.NAVIGATION) {
            screenContainer.addView(buildNavigationScreen());
        } else {
            screenContainer.addView(buildSettingsScreen());
        }

        renderBottomNav();
        updateLocationUi();

        if (screen == Screen.SEARCH && pendingSearchSpeech) {
            pendingSearchSpeech = false;
            mainHandler.postDelayed(() -> {
                if (currentScreen == Screen.SEARCH) {
                    startVoiceCommandInput();
                }
            }, 350L);
        }
    }

    private void clearScreenRefs() {
        statusText = null;
        guideText = null;
        detectionText = null;
        locationChipText = null;
        serverStatusText = null;
        serverInput = null;
        guardianInput = null;
        navigationGestureDetector = null;
    }

    private View buildHomeScreen() {
        LinearLayout root = baseScreenContent();

        root.addView(makeSmallTitle("① 홈"), margin(matchWrap(), 0, 0, 0, 12));

        locationChipText = makeChip("현위치 확인 중...");
        root.addView(locationChipText, margin(matchWrap(), 0, 0, 0, 16));

        Button voiceDestinationButton = makeButton("🎙\n목적지 말하기\n화면 아무 곳이나 탭", COLOR_BLUE, Color.WHITE);
        voiceDestinationButton.setTextSize(22);
        voiceDestinationButton.setMinHeight(dp(148));
        voiceDestinationButton.setOnClickListener(view -> startVoiceCommandInput());
        root.addView(voiceDestinationButton, margin(matchWrap(), 0, 0, 0, 10));

        if (navigationSessionActive) {
            Button returnToNavigationButton = makeButton(
                    "안내 중 · 돌아가기\n" + routeChipMessage,
                    COLOR_BLUE_LIGHT,
                    COLOR_BLUE_DARK
            );
            returnToNavigationButton.setTextSize(18);
            returnToNavigationButton.setOnClickListener(view -> renderScreen(Screen.NAVIGATION, false));
            root.addView(returnToNavigationButton, margin(matchWrap(), 0, 0, 0, 10));
        }

        LinearLayout quickGrid = new LinearLayout(this);
        quickGrid.setOrientation(LinearLayout.HORIZONTAL);

        Button favoriteButton = makeTileButton("☆\n즐겨찾기\n저장된 장소");
        favoriteButton.setOnClickListener(view -> startNavigationFromStoredDestination(true));
        quickGrid.addView(favoriteButton, weightedButtonParams(1f, 0, 0, 6, 0));

        Button recentButton = makeTileButton("◷\n최근 경로\n지난 이동");
        recentButton.setOnClickListener(view -> startNavigationFromStoredDestination(false));
        quickGrid.addView(recentButton, weightedButtonParams(1f, 6, 0, 0, 0));

        root.addView(quickGrid, margin(matchWrap(), 0, 0, 0, 10));

        Button emergencyButton = makeEmergencyButton("SOS  긴급 연락\n보호자에게 위치 전송");
        emergencyButton.setOnClickListener(view -> openGuardianSms());
        root.addView(emergencyButton, margin(matchWrap(), 0, 0, 0, 14));

        statusText = makePanelText(lastServerStatusMessage, 15, COLOR_MUTED);
        root.addView(statusText, matchWrap());

        return wrapScrollable(root);
    }

    private View buildSearchScreen() {
        LinearLayout root = baseScreenContent();

        root.addView(makeSmallTitle("② 목적지 입력"), margin(matchWrap(), 0, 0, 0, 12));

        Button speechPanel = makeButton("🎙\n말씀하세요\n\"강남역 1번 출구\"\n\n듣는 중...", COLOR_BLUE, Color.WHITE);
        speechPanel.setTextSize(22);
        speechPanel.setMinHeight(dp(250));
        speechPanel.setOnClickListener(view -> startVoiceCommandInput());
        root.addView(speechPanel, margin(matchWrap(), 0, 0, 0, 18));

        TextView recentTitle = makeSectionLabel("최근 목적지");
        root.addView(recentTitle, margin(matchWrap(), 0, 0, 0, 8));

        List<String> recentDestinations = getRecentDestinations();

        if (recentDestinations.isEmpty()) {
            TextView emptyText = makePanelText("최근 목적지가 없습니다.\n마이크 버튼을 눌러 목적지를 말해 주세요.", 17, COLOR_MUTED);
            root.addView(emptyText, matchWrap());
        } else {
            for (String destination : recentDestinations) {
                Button destinationButton = makeListButton(destination + "\n최근 목적지");
                destinationButton.setOnClickListener(view -> startNavigation(destination));
                root.addView(destinationButton, margin(matchWrap(), 0, 0, 0, 8));
            }
        }

        return wrapScrollable(root);
    }

    private View buildNavigationScreen() {
        LinearLayout root = baseScreenContent();
        root.setOnTouchListener(this::handleNavigationTouch);
        navigationGestureDetector = createNavigationGestureDetector();

        TextView title = makeSmallTitle("③ 경로 안내 중");
        root.addView(title, margin(matchWrap(), 0, 0, 0, 12));

        TextView routeChip = makeChip(routeChipMessage);
        routeChip.setTextColor(COLOR_TEXT);
        root.addView(routeChip, margin(matchWrap(), 0, 0, 0, 14));

        LinearLayout actionCard = new LinearLayout(this);
        actionCard.setOrientation(LinearLayout.VERTICAL);
        actionCard.setGravity(Gravity.CENTER);
        actionCard.setPadding(dp(18), dp(22), dp(18), dp(22));
        actionCard.setMinimumHeight(dp(220));
        actionCard.setBackground(makeRoundedDrawable(COLOR_BLUE_LIGHT, COLOR_BLUE_LIGHT, dp(14)));
        actionCard.setOnTouchListener(this::handleNavigationTouch);

        TextView arrowText = new TextView(this);
        arrowText.setText("↑");
        arrowText.setTextColor(COLOR_BLUE);
        arrowText.setTextSize(50);
        arrowText.setTypeface(Typeface.DEFAULT_BOLD);
        arrowText.setGravity(Gravity.CENTER);
        actionCard.addView(arrowText, matchWrap());

        guideText = new TextView(this);
        guideText.setText(nextActionMessage);
        guideText.setTextColor(COLOR_BLUE_DARK);
        guideText.setTextSize(26);
        guideText.setTypeface(Typeface.DEFAULT_BOLD);
        guideText.setGravity(Gravity.CENTER);
        actionCard.addView(guideText, margin(matchWrap(), 0, 4, 0, 4));

        TextView subText = new TextView(this);
        subText.setText(nextActionSubMessage);
        subText.setTextColor(COLOR_BLUE);
        subText.setTextSize(18);
        subText.setGravity(Gravity.CENTER);
        actionCard.addView(subText, matchWrap());

        root.addView(actionCard, margin(matchWrap(), 0, 0, 0, 12));

        TextView nextStepText = makePanelText("→ " + nextPreviewMessage, 17, COLOR_TEXT);
        root.addView(nextStepText, margin(matchWrap(), 0, 0, 0, 14));

        Button saveFavoriteButton = makeSecondaryButton("☆ 현재 목적지 즐겨찾기 저장", COLOR_TEXT);
        saveFavoriteButton.setTextSize(16);
        saveFavoriteButton.setOnClickListener(view -> saveActiveDestinationAsFavorite());
        root.addView(saveFavoriteButton, margin(matchWrap(), 0, 0, 0, 12));

        LinearLayout actionButtons = new LinearLayout(this);
        actionButtons.setOrientation(LinearLayout.HORIZONTAL);

        Button repeatButton = makeTileButton("🔊\n다시 읽기\n탭 또는 더블탭");
        repeatButton.setOnClickListener(view -> speak(lastGuideMessage, true));
        actionButtons.addView(repeatButton, weightedButtonParams(1f, 0, 0, 6, 0));

        Button stopButton = makeButton("□\n안내 종료", Color.rgb(255, 238, 240), COLOR_DANGER);
        stopButton.setMinHeight(dp(124));
        stopButton.setOnClickListener(view -> stopNavigation());
        actionButtons.addView(stopButton, weightedButtonParams(1f, 6, 0, 0, 0));

        root.addView(actionButtons, margin(matchWrap(), 0, 0, 0, 14));

        detectionText = makePanelText("카메라 감지 중\nGPS 연결 상태와 서버 전송 상태를 확인합니다.", 16, COLOR_MUTED);
        root.addView(detectionText, margin(matchWrap(), 0, 0, 0, 8));

        serverStatusText = makePanelText(lastServerStatusMessage, 15, COLOR_MUTED);
        root.addView(serverStatusText, matchWrap());

        return wrapScrollable(root);
    }

    private View buildSettingsScreen() {
        SharedPreferences preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        LinearLayout root = baseScreenContent();

        root.addView(makeSmallTitle("설정"), margin(matchWrap(), 0, 0, 0, 16));

        root.addView(makeSectionLabel("TTS 속도"), margin(matchWrap(), 0, 0, 0, 6));
        TextView ttsRateText = makePanelText("", 16, COLOR_TEXT);
        SeekBar ttsSeekBar = new SeekBar(this);
        ttsSeekBar.setMax(150);
        int ttsRatePercent = preferences.getInt(PREF_TTS_RATE, 95);
        ttsSeekBar.setProgress(Math.max(0, Math.min(150, ttsRatePercent - 50)));
        updateRateLabel(ttsRateText, ttsRatePercent);
        ttsSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int percent = progress + 50;
                updateRateLabel(ttsRateText, percent);
                applyTtsRate(percent);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                preferences.edit().putInt(PREF_TTS_RATE, seekBar.getProgress() + 50).apply();
            }
        });
        root.addView(ttsRateText, margin(matchWrap(), 0, 0, 0, 8));
        root.addView(ttsSeekBar, margin(matchWrap(), 0, 0, 0, 18));

        Switch vibrationSwitch = new Switch(this);
        vibrationSwitch.setText("진동 피드백");
        vibrationSwitch.setTextSize(18);
        vibrationSwitch.setTextColor(COLOR_TEXT);
        vibrationSwitch.setChecked(preferences.getBoolean(PREF_VIBRATION, true));
        vibrationSwitch.setPadding(dp(12), dp(12), dp(12), dp(12));
        vibrationSwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                preferences.edit().putBoolean(PREF_VIBRATION, isChecked).apply()
        );
        root.addView(vibrationSwitch, margin(matchWrap(), 0, 0, 0, 18));

        root.addView(makeSectionLabel("감지 민감도"), margin(matchWrap(), 0, 0, 0, 6));
        TextView thresholdText = makePanelText("", 16, COLOR_TEXT);
        SeekBar thresholdSeekBar = new SeekBar(this);
        thresholdSeekBar.setMax(40);
        int thresholdPercent = preferences.getInt(PREF_DETECTION_THRESHOLD, 50);
        thresholdSeekBar.setProgress(Math.max(0, Math.min(40, thresholdPercent - 50)));
        updateThresholdLabel(thresholdText, thresholdPercent);
        thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateThresholdLabel(thresholdText, progress + 50);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                preferences.edit().putInt(PREF_DETECTION_THRESHOLD, seekBar.getProgress() + 50).apply();
            }
        });
        root.addView(thresholdText, margin(matchWrap(), 0, 0, 0, 8));
        root.addView(thresholdSeekBar, margin(matchWrap(), 0, 0, 0, 18));

        root.addView(makeSectionLabel("서버 IP/포트"), margin(matchWrap(), 0, 0, 0, 6));
        serverInput = makeInput(preferences.getString(PREF_SERVER_URL, "http://10.0.2.2:8000"), "예: http://10.10.16.222:8000");
        serverInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(serverInput, margin(matchWrap(), 0, 0, 0, 10));

        Button serverCheckButton = makeSecondaryButton("서버 연결 확인", COLOR_TEXT);
        serverCheckButton.setOnClickListener(view -> checkServer());
        root.addView(serverCheckButton, margin(matchWrap(), 0, 0, 0, 18));

        root.addView(makeSectionLabel("개발자 옵션"), margin(matchWrap(), 0, 0, 0, 6));
        Button streamButton = makeSecondaryButton(
                developerStreamingEnabled ? "카메라 스트리밍 중지" : "카메라 스트리밍 시작",
                COLOR_TEXT
        );
        streamButton.setOnClickListener(view -> {
            if (developerStreamingEnabled) {
                stopDeveloperStreaming();
                streamButton.setText("카메라 스트리밍 시작");
            } else {
                startDeveloperStreaming();
                streamButton.setText("카메라 스트리밍 중지");
            }
        });
        root.addView(streamButton, margin(matchWrap(), 0, 0, 0, 18));

        root.addView(makeSectionLabel("보호자 전화번호"), margin(matchWrap(), 0, 0, 0, 6));
        guardianInput = makeInput(preferences.getString(PREF_GUARDIAN_PHONE, ""), "예: 01012345678");
        guardianInput.setInputType(InputType.TYPE_CLASS_PHONE);
        root.addView(guardianInput, margin(matchWrap(), 0, 0, 0, 10));

        Button saveButton = makeButton("설정 저장", COLOR_BLUE, Color.WHITE);
        saveButton.setOnClickListener(view -> saveSettings());
        root.addView(saveButton, margin(matchWrap(), 0, 0, 0, 10));

        serverStatusText = makePanelText(lastServerStatusMessage, 15, COLOR_MUTED);
        root.addView(serverStatusText, matchWrap());

        return wrapScrollable(root);
    }

    private void renderBottomNav() {
        bottomNav.removeAllViews();
        bottomNav.addView(makeNavButton("⌂\n홈", Screen.HOME), weightedButtonParams(1f, 0, 0, 4, 0));
        bottomNav.addView(makeNavButton("⌕\n검색", Screen.SEARCH), weightedButtonParams(1f, 4, 0, 4, 0));

        if (navigationSessionActive) {
            bottomNav.addView(makeNavButton("↑\n안내", Screen.NAVIGATION), weightedButtonParams(1f, 4, 0, 4, 0));
        }

        bottomNav.addView(makeNavButton("⚙\n설정", Screen.SETTINGS), weightedButtonParams(1f, 4, 0, 0, 0));
    }

    private Button makeNavButton(String text, Screen targetScreen) {
        boolean selected = currentScreen == targetScreen;
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setTextColor(selected ? COLOR_BLUE : COLOR_MUTED);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setMinHeight(dp(58));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(view -> renderScreen(targetScreen, targetScreen == Screen.SEARCH));
        return button;
    }

    private void setupDetector() {
        try {
            detector = new YoloDetector(this);
            lastServerStatusMessage = "YOLO COCO 모델 준비 완료";
            if (statusText != null) {
                statusText.setText(lastServerStatusMessage);
            }
        } catch (Exception error) {
            detector = null;
            lastServerStatusMessage = "YOLO 모델 파일을 찾지 못했습니다: " + error.getMessage();
            if (statusText != null) {
                statusText.setText(lastServerStatusMessage);
            }
        }
    }

    private void setupTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.KOREAN);
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                applyTtsRate(getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(PREF_TTS_RATE, 95));
                mainHandler.postDelayed(this::startVoiceFirstIntro, 800L);
            }
        });
    }

    private void setupLabels() {
        riskLabels.add("person");
        riskLabels.add("bicycle");
        riskLabels.add("car");
        riskLabels.add("motorcycle");
        riskLabels.add("bus");
        riskLabels.add("truck");
        riskLabels.add("traffic light");
        riskLabels.add("fire hydrant");
        riskLabels.add("stop sign");
        riskLabels.add("parking meter");
        riskLabels.add("bench");
        riskLabels.add("backpack");
        riskLabels.add("umbrella");
        riskLabels.add("handbag");
        riskLabels.add("suitcase");
        riskLabels.add("sports ball");
        riskLabels.add("skateboard");
        riskLabels.add("bottle");
        riskLabels.add("cup");
        riskLabels.add("bowl");
        riskLabels.add("chair");
        riskLabels.add("potted plant");
        riskLabels.add("dining table");
        riskLabels.add("cell phone");
        riskLabels.add("book");
        riskLabels.add("vase");

        koreanLabels.put("person", "사람");
        koreanLabels.put("bicycle", "자전거");
        koreanLabels.put("car", "차량");
        koreanLabels.put("motorcycle", "오토바이");
        koreanLabels.put("bus", "버스");
        koreanLabels.put("truck", "트럭");
        koreanLabels.put("traffic light", "신호등");
        koreanLabels.put("fire hydrant", "소화전");
        koreanLabels.put("stop sign", "정지 표지판");
        koreanLabels.put("parking meter", "주차 정산기");
        koreanLabels.put("bench", "벤치");
        koreanLabels.put("backpack", "가방");
        koreanLabels.put("umbrella", "우산");
        koreanLabels.put("handbag", "가방");
        koreanLabels.put("suitcase", "캐리어");
        koreanLabels.put("sports ball", "공");
        koreanLabels.put("skateboard", "스케이트보드");
        koreanLabels.put("bottle", "병");
        koreanLabels.put("cup", "컵");
        koreanLabels.put("bowl", "그릇");
        koreanLabels.put("chair", "의자");
        koreanLabels.put("potted plant", "화분");
        koreanLabels.put("dining table", "테이블");
        koreanLabels.put("cell phone", "휴대전화");
        koreanLabels.put("book", "책");
        koreanLabels.put("vase", "화분");
    }

    private void startNavigationFromStoredDestination(boolean favorite) {
        String destination = "";

        if (favorite) {
            destination = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(PREF_FAVORITE_DESTINATION, "");

            if (destination == null || destination.trim().isEmpty()) {
                updateGuide("즐겨찾기가 없습니다. 최근 목적지에서 선택하거나 새 목적지를 말해 주세요.");
                speak(lastGuideMessage, true);
                renderScreen(Screen.SEARCH, false);
                return;
            }

            startNavigation(destination);
            return;
        }

        List<String> recent = getRecentDestinations();

        if (recent.isEmpty()) {
            updateGuide("최근 목적지가 없습니다. 목적지를 먼저 말해 주세요.");
            speak(lastGuideMessage, true);
            renderScreen(Screen.SEARCH, true);
            return;
        }

        renderScreen(Screen.SEARCH, false);
    }

    private void startNavigation(String destination) {
        String normalizedDestination = destination == null ? "" : destination.trim();

        if (normalizedDestination.isEmpty()) {
            updateGuide("목적지를 인식하지 못했습니다.");
            speak(lastGuideMessage, true);
            renderScreen(Screen.SEARCH, true);
            return;
        }

        updateGuide("목적지를 확인하고 있습니다. " + normalizedDestination);
        speak(lastGuideMessage, true);

        ServerClient.requestMobileRoute(
                getServerUrl(),
                normalizedDestination,
                currentLat,
                currentLng,
                result -> runOnUiThread(() -> {
                    if (!result.ok) {
                        activeDestination = "";
                        navigationSessionActive = false;
                        updateGuide(result.reason);
                        speak(lastGuideMessage, true);
                        renderScreen(Screen.SEARCH, false);
                        return;
                    }

                    beginNavigation(result);
                })
        );
    }

    private void beginNavigation(ServerClient.RouteResult routeResult) {
        activeDestination = routeResult.destinationName;
        navigationSessionActive = true;
        saveRecentDestination(activeDestination);

        routeChipMessage = "→ "
                + activeDestination
                + " · "
                + formatDistance(routeResult.distanceMeter)
                + " · 약 "
                + Math.max(1, routeResult.durationMinute)
                + "분";
        nextActionMessage = routeResult.currentInstruction;
        nextActionSubMessage = routeResult.nextInstruction;
        nextPreviewMessage = routeResult.nextInstruction + " → " + activeDestination;
        lastGuideMessage = nextActionMessage + ". " + nextActionSubMessage;

        renderScreen(Screen.NAVIGATION, false);
        speak(lastGuideMessage, true);
        startCamera();
        mainHandler.postDelayed(this::startVoiceCommandInput, 2600L);
    }

    private void stopNavigation() {
        if (developerStreamingEnabled) {
            stopDeveloperStreaming();
        }

        stopCamera();
        activeDestination = "";
        navigationSessionActive = false;
        lastHazardKey = "";
        hazardSuppressedUntil = 0L;

        if (hazardDialog != null && hazardDialog.isShowing()) {
            hazardDialog.dismiss();
        }

        updateGuide("안내를 종료했습니다.");
        speak(lastGuideMessage, true);
        renderScreen(Screen.HOME, false);
        mainHandler.postDelayed(this::startVoiceCommandInput, 1200L);
    }

    private void startVoiceFirstIntro() {
        if (voiceIntroSpoken || isFinishing()) {
            return;
        }

        voiceIntroSpoken = true;
        updateGuide("음성 명령 대기 중입니다. 목적지를 말하거나, 다시 안내, 주변 시설, 위험 정보, 긴급 연락이라고 말해 주세요.");
        speak(lastGuideMessage, true);
        mainHandler.postDelayed(this::startVoiceCommandInput, 1200L);
    }

    private void startVoiceDestinationInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingDestinationSpeechAfterPermission = true;
            pendingVoiceCommandAfterPermission = false;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "목적지를 말씀해 주세요.");

        try {
            startActivityForResult(intent, REQUEST_SPEECH_INPUT);
        } catch (Exception error) {
            updateGuide("이 기기에서는 음성 입력을 사용할 수 없습니다.");
        }
    }

    private void startVoiceCommandInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceCommandAfterPermission = true;
            pendingDestinationSpeechAfterPermission = false;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "명령을 말씀해 주세요. 예: 화곡역 3번 출구로 안내해줘.");

        try {
            startActivityForResult(intent, REQUEST_VOICE_COMMAND);
        } catch (Exception error) {
            updateGuide("이 기기에서는 음성 명령을 사용할 수 없습니다.");
            speak(lastGuideMessage, true);
        }
    }

    private void handleVoiceCommand(String utterance) {
        VoiceCommand command = voiceCommandParser.parse(utterance);

        if (command.getIntent() == VoiceCommand.IntentType.UNKNOWN && localLlmInterpreter.isAvailable()) {
            command = localLlmInterpreter.interpret(utterance);
        }

        setServerStatus("음성 명령: " + command.toJsonLikeString());
        executeVoiceCommand(command);
    }

    private void executeVoiceCommand(VoiceCommand command) {
        switch (command.getIntent()) {
            case NAVIGATE:
                if (!command.hasDestination()) {
                    updateGuide("목적지를 다시 말씀해 주세요.");
                    speak(lastGuideMessage, true);
                    scheduleVoiceCommandFollowUp();
                    return;
                }
                updateGuide(command.getDestination() + " 안내를 시작합니다.");
                speak(lastGuideMessage, true);
                startNavigation(command.getDestination());
                return;
            case REPEAT_GUIDANCE:
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case NEXT_GUIDANCE:
                speak("다음 안내입니다. " + nextPreviewMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case NEARBY:
                readNearbyPlaces(command.getPlaceType());
                return;
            case RISK_INFO:
                readRiskInformation();
                return;
            case EMERGENCY:
                openGuardianSms();
                return;
            case STOP_NAVIGATION:
                stopNavigation();
                return;
            case CURRENT_LOCATION:
                updateGuide("현재 위치는 " + getLocationSpeechText());
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case OPEN_SETTINGS:
                renderScreen(Screen.SETTINGS, false);
                updateGuide("설정 화면입니다. 서버 주소, 보호자 번호, 음성 속도를 확인할 수 있습니다.");
                speak(lastGuideMessage, true);
                return;
            case FAVORITE:
                startNavigationFromStoredDestination(true);
                return;
            case UNKNOWN:
            default:
                updateGuide("명령을 이해하지 못했습니다. 목적지로 안내해줘, 다시 안내, 주변 편의점, 위험 정보, 긴급 연락처럼 말씀해 주세요.");
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
        }
    }

    private void readNearbyPlaces(String placeType) {
        updateGuide("현재 위치 기준 주변시설을 확인하고 있습니다.");
        speak(lastGuideMessage, true);
        ServerClient.requestNearby(
                getServerUrl(),
                currentLat,
                currentLng,
                placeType,
                message -> runOnUiThread(() -> {
                    updateGuide(message);
                    speak(lastGuideMessage, true);
                    scheduleVoiceCommandFollowUp();
                })
        );
    }

    private void readRiskInformation() {
        updateGuide("화곡 시범구역 위험 정보를 확인하고 있습니다.");
        speak(lastGuideMessage, true);
        ServerClient.requestRisks(
                getServerUrl(),
                message -> runOnUiThread(() -> {
                    updateGuide(message);
                    speak(lastGuideMessage, true);
                    scheduleVoiceCommandFollowUp();
                })
        );
    }

    private void scheduleVoiceCommandFollowUp() {
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && currentScreen != Screen.SETTINGS) {
                startVoiceCommandInput();
            }
        }, VOICE_COMMAND_FOLLOW_UP_DELAY_MS);
    }

    private void checkServer() {
        String url = serverInput == null ? getServerUrl() : serverInput.getText().toString().trim();
        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_SERVER_URL, url)
                .apply();

        setServerStatus("서버 연결을 확인하고 있습니다.");
        ServerClient.checkServer(url, message -> runOnUiThread(() -> {
            setServerStatus(message);
            updateGuide(message);
        }));
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();

        if (serverInput != null) {
            editor.putString(PREF_SERVER_URL, serverInput.getText().toString().trim());
        }

        if (guardianInput != null) {
            editor.putString(PREF_GUARDIAN_PHONE, guardianInput.getText().toString().trim());
        }

        editor.apply();
        setServerStatus("설정을 저장했습니다.");
        updateGuide("설정을 저장했습니다.");
        speak(lastGuideMessage, true);
    }

    private void openGuardianSms() {
        SharedPreferences preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String phone = preferences.getString(PREF_GUARDIAN_PHONE, "");
        String message = "A-eye 긴급 위치 공유\n현재 위치: "
                + String.format(Locale.KOREA, "%.6f, %.6f", currentLat, currentLng)
                + "\n지도: https://maps.google.com/?q=" + currentLat + "," + currentLng;

        ServerClient.postEmergency(
                getServerUrl(),
                DEVICE_ID,
                currentLat,
                currentLng,
                currentHeading,
                message,
                result -> runOnUiThread(() -> setServerStatus(result))
        );

        if (phone == null || phone.trim().isEmpty()) {
            updateGuide("보호자 번호가 없습니다. 설정에서 보호자 번호를 입력하세요.");
            speak(lastGuideMessage, true);
            return;
        }

        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(phone.trim())));

        try {
            startActivity(intent);
            updateGuide("긴급 위치를 서버에 전송하고 보호자 전화 화면을 열었습니다.");
            speak(lastGuideMessage, true);
        } catch (Exception error) {
            updateGuide("전화 앱을 열 수 없습니다. 설정에서 보호자 번호를 확인하세요.");
            speak(lastGuideMessage, true);
        }
    }

    private void startCamera() {
        if (cameraRunning) {
            return;
        }

        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }

        if (detector == null) {
            updateGuide("YOLO 모델 파일이 없어 카메라 안내를 시작할 수 없습니다.");
            return;
        }

        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeImage);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, imageAnalysis);

                cameraRunning = true;
                setServerStatus("카메라 감지 중 · 서버 전송 대기");
            } catch (Exception error) {
                updateGuide("카메라 시작에 실패했습니다. " + error.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera() {
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        cameraRunning = false;
        setServerStatus("카메라 감지 중지");
    }

    private void startDeveloperStreaming() {
        developerStreamingEnabled = true;
        startCamera();
        connectStreamingWebSocket();
    }

    private void stopDeveloperStreaming() {
        developerStreamingEnabled = false;

        if (streamWebSocket != null) {
            streamWebSocket.close(1000, "developer streaming stopped");
            streamWebSocket = null;
        }

        if (!navigationSessionActive) {
            stopCamera();
        }

        setServerStatus("카메라 스트리밍 중지");
    }

    private void connectStreamingWebSocket() {
        if (streamWebSocket != null) {
            return;
        }

        String baseUrl = getServerUrl();
        String wsUrl;

        if (baseUrl.startsWith("https://")) {
            wsUrl = "wss://" + baseUrl.substring("https://".length());
        } else if (baseUrl.startsWith("http://")) {
            wsUrl = "ws://" + baseUrl.substring("http://".length());
        } else {
            wsUrl = baseUrl;
        }

        if (wsUrl.endsWith("/")) {
            wsUrl = wsUrl.substring(0, wsUrl.length() - 1);
        }

        Request request = new Request.Builder()
                .url(wsUrl + "/ws/stream")
                .addHeader("bypass-tunnel-reminder", "true")
                .addHeader("ngrok-skip-browser-warning", "true")
                .build();
        streamWebSocket = streamHttpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                runOnUiThread(() -> setServerStatus("카메라 스트리밍 연결됨"));
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                runOnUiThread(() -> setServerStatus("스트리밍 서버 응답: " + text));
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                streamWebSocket = null;
                developerStreamingEnabled = false;
                runOnUiThread(() -> setServerStatus("카메라 스트리밍 실패: " + t.getMessage()));
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                streamWebSocket = null;
                runOnUiThread(() -> setServerStatus("카메라 스트리밍 종료"));
            }
        });
        setServerStatus("카메라 스트리밍 연결 중");
    }

    private void maybeSendStreamingFrame(Bitmap bitmap) {
        if (!developerStreamingEnabled || streamWebSocket == null || bitmap == null) {
            return;
        }

        try {
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, 320, 240, true);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 40, outputStream);
            streamWebSocket.send(ByteString.of(outputStream.toByteArray()));
        } catch (Exception error) {
            runOnUiThread(() -> setServerStatus("스트리밍 프레임 전송 실패: " + error.getMessage()));
        }
    }

    private void analyzeImage(ImageProxy imageProxy) {
        try {
            long now = System.currentTimeMillis();

            if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) {
                return;
            }

            lastAnalysisAt = now;
            Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
            Bitmap rotatedBitmap = ImageUtils.rotateBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());
            maybeSendStreamingFrame(rotatedBitmap);
            List<Detection> detections = detector.detect(rotatedBitmap);
            List<Detection> riskDetections = filterRiskDetections(detections);
            String detectionSummary = buildDetectionSummary(detections);

            runOnUiThread(() -> {
                if (detectionText != null) {
                    detectionText.setText(detectionSummary);
                }

                if (!riskDetections.isEmpty()) {
                    Detection primary = riskDetections.get(0);
                    String guideMessage = buildGuideMessage(primary);
                    maybePostDetection(riskDetections, guideMessage);

                    if (currentScreen == Screen.NAVIGATION) {
                        showHazardAlert(primary, guideMessage);
                    } else {
                        updateGuide(guideMessage);
                        speakThrottled(guideMessage);
                    }
                }
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                if (detectionText != null) {
                    detectionText.setText("분석 오류: " + error.getMessage());
                }
            });
        } finally {
            imageProxy.close();
        }
    }

    private List<Detection> filterRiskDetections(List<Detection> detections) {
        List<Detection> filtered = new ArrayList<>();
        float confidenceThreshold = getDetectionThreshold();

        for (Detection detection : detections) {
            if (detection.confidence >= confidenceThreshold
                    && riskLabels.contains(detection.label)
                    && detection.centerX > 0.22f
                    && detection.centerX < 0.78f) {
                filtered.add(detection);
            }
        }

        return filtered;
    }

    private String buildDetectionSummary(List<Detection> detections) {
        if (detections.isEmpty()) {
            return "감지 결과가 없습니다.";
        }

        StringBuilder builder = new StringBuilder("최근 감지\n");
        int count = Math.min(5, detections.size());

        for (int index = 0; index < count; index += 1) {
            Detection detection = detections.get(index);
            builder
                    .append(toKoreanLabel(detection.label))
                    .append(" ")
                    .append(Math.round(detection.confidence * 100))
                    .append("%")
                    .append(" · ")
                    .append(toDirection(detection.centerX))
                    .append(" · ")
                    .append(toDistanceLevel(detection.area()))
                    .append("\n");
        }

        return builder.toString().trim();
    }

    private String buildGuideMessage(Detection primary) {
        return toDirection(primary.centerX)
                + " "
                + toDistanceLevel(primary.area())
                + "에 "
                + toKoreanLabel(primary.label)
                + "이 있습니다. 주의하세요.";
    }

    private void showHazardAlert(Detection detection, String message) {
        String key = detection.label + ":" + toDirection(detection.centerX) + ":" + toDistanceLevel(detection.area());
        long now = System.currentTimeMillis();

        if (now < hazardSuppressedUntil) {
            return;
        }

        if (hazardDialog != null && hazardDialog.isShowing()) {
            return;
        }

        if (key.equals(lastHazardKey) && now - lastHazardAlertAt < HAZARD_ALERT_COOLDOWN_MS) {
            return;
        }

        lastHazardKey = key;
        lastHazardAlertAt = now;

        vibrateHazard();
        speak(message, true);

        LinearLayout dialogRoot = new LinearLayout(this);
        dialogRoot.setOrientation(LinearLayout.VERTICAL);
        dialogRoot.setPadding(dp(22), dp(18), dp(22), dp(18));
        dialogRoot.setBackground(makeRoundedDrawable(COLOR_WARNING_BG, COLOR_WARNING, dp(14)));

        TextView alertTitle = makeDialogText("⚠ 전방 장애물 감지", 16, COLOR_WARNING, true);
        dialogRoot.addView(alertTitle, margin(matchWrap(), 0, 0, 0, 14));

        TextView objectText = makeDialogText(
                objectIconForLabel(detection.label)
                        + "\n"
                        + toKoreanLabel(detection.label)
                        + "\n점자블록 위 감지",
                25,
                Color.rgb(120, 73, 12),
                true
        );
        objectText.setGravity(Gravity.CENTER);
        dialogRoot.addView(objectText, margin(matchWrap(), 0, 0, 0, 12));

        TextView detailText = makeDialogText(
                "신뢰도 "
                        + Math.round(detection.confidence * 100)
                        + "% · "
                        + toDirection(detection.centerX),
                16,
                Color.rgb(120, 73, 12),
                false
        );
        detailText.setGravity(Gravity.CENTER);
        dialogRoot.addView(detailText, margin(matchWrap(), 0, 0, 0, 14));

        TextView ttsText = makePanelText("TTS 출력:\n\"" + message + "\"", 16, COLOR_TEXT);
        dialogRoot.addView(ttsText, margin(matchWrap(), 0, 0, 0, 14));

        Button continueButton = makeSecondaryButton("경로 계속", COLOR_TEXT);
        continueButton.setOnClickListener(view -> {
            suppressHazardAlerts(5000L);

            if (hazardDialog != null) {
                hazardDialog.dismiss();
            }
        });
        dialogRoot.addView(continueButton, margin(matchWrap(), 0, 0, 0, 8));

        Button emergencyButton = makeEmergencyButton("SOS  긴급 연락");
        emergencyButton.setOnClickListener(view -> {
            suppressHazardAlerts(5000L);
            openGuardianSms();
        });
        dialogRoot.addView(emergencyButton, matchWrap());

        hazardDialog = new AlertDialog.Builder(this)
                .setView(dialogRoot)
                .create();
        hazardDialog.setOnDismissListener(dialog -> hazardDialog = null);
        hazardDialog.show();

        AlertDialog dialogToDismiss = hazardDialog;
        mainHandler.postDelayed(() -> {
            if (dialogToDismiss != null && dialogToDismiss.isShowing()) {
                dialogToDismiss.dismiss();
            }
        }, 5000L);
    }

    private void suppressHazardAlerts(long durationMs) {
        hazardSuppressedUntil = System.currentTimeMillis() + durationMs;
    }

    private boolean handleNavigationTouch(View view, MotionEvent event) {
        if (navigationGestureDetector != null) {
            navigationGestureDetector.onTouchEvent(event);
        }
        return true;
    }

    private GestureDetector createNavigationGestureDetector() {
        return new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            private static final int SWIPE_THRESHOLD = 90;
            private static final int SWIPE_VELOCITY_THRESHOLD = 90;

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                speak(lastGuideMessage, true);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                speak("다음 안내입니다. " + nextPreviewMessage, true);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent down, MotionEvent up, float velocityX, float velocityY) {
                if (down == null || up == null) {
                    return false;
                }

                float diffX = up.getX() - down.getX();
                float diffY = up.getY() - down.getY();

                if (Math.abs(diffX) > Math.abs(diffY)) {
                    if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                        if (diffX > 0) {
                            readRiskInformation();
                        } else {
                            readNearbyPlaces("");
                        }
                        return true;
                    }
                } else if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffY > 0) {
                        speak("현재 위치는 " + getLocationSpeechText(), true);
                    } else {
                        speak("진행 방향입니다. " + lastGuideMessage, true);
                    }
                    return true;
                }

                return false;
            }
        });
    }

    private void maybePostDetection(List<Detection> riskDetections, String message) {
        if (riskDetections.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (now - lastPostedAt < POST_INTERVAL_MS) {
            return;
        }

        lastPostedAt = now;

        ServerClient.postDetection(
                getServerUrl(),
                DEVICE_ID,
                currentLat,
                currentLng,
                currentHeading,
                riskDetections,
                message,
                result -> runOnUiThread(() -> setServerStatus(result))
        );
    }

    private void setupSensors() {
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        if (sensorManager != null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
    }

    private void setupLocation() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        if (!hasLocationPermission()) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQUEST_LOCATION_PERMISSION
            );
            return;
        }

        startLocationUpdates();
    }

    private void startLocationUpdates() {
        if (locationManager == null || !hasLocationPermission()) {
            return;
        }

        try {
            Location lastKnownGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location lastKnownNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            Location lastKnown = lastKnownGps != null ? lastKnownGps : lastKnownNetwork;

            if (lastKnown != null) {
                currentLat = lastKnown.getLatitude();
                currentLng = lastKnown.getLongitude();
                updateLocationUi();
            }

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    1.0f,
                    locationListener
            );
            locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    4000L,
                    3.0f,
                    locationListener
            );
        } catch (SecurityException ignored) {
            updateGuide("위치 권한이 없어 기본 위치로 서버에 전송합니다.");
        } catch (IllegalArgumentException ignored) {
            updateGuide("위치 서비스를 사용할 수 없어 기본 위치로 서버에 전송합니다.");
        }
    }

    private void updateLocationUi() {
        if (locationChipText == null) {
            return;
        }

        locationChipText.setText("현재 위치 "
                + String.format(Locale.KOREA, "%.5f, %.5f", currentLat, currentLng));
    }

    private String getLocationSpeechText() {
        return String.format(Locale.KOREA, "위도 %.5f, 경도 %.5f 입니다.", currentLat, currentLng);
    }

    private void updateGuide(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }

        lastGuideMessage = message;

        if (guideText != null) {
            guideText.setText(message);
        }

        if (statusText != null) {
            statusText.setText(message);
        }
    }

    private void setServerStatus(String message) {
        lastServerStatusMessage = message;

        if (serverStatusText != null) {
            serverStatusText.setText(message);
        }

        if (statusText != null && currentScreen == Screen.HOME) {
            statusText.setText(message);
        }
    }

    private void speakThrottled(String message) {
        long now = System.currentTimeMillis();

        if (now - lastSpokenAt < SPEAK_INTERVAL_MS && message.equals(lastGuideMessage)) {
            return;
        }

        lastSpokenAt = now;
        speak(message, false);
    }

    private void speak(String message, boolean flush) {
        if (tts == null || message == null || message.isEmpty()) {
            return;
        }

        int queueMode = flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        tts.speak(message, queueMode, null, "a-eye-guide");
    }

    private void applyTtsRate(int percent) {
        if (tts != null) {
            tts.setSpeechRate(percent / 100f);
        }
    }

    private void vibrateHazard() {
        if (!getSharedPreferences(PREF_NAME, MODE_PRIVATE).getBoolean(PREF_VIBRATION, true)) {
            return;
        }

        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        if (vibrator != null) {
            vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 200, 100, 200}, -1));
        }
    }

    private float getDetectionThreshold() {
        int percent = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(PREF_DETECTION_THRESHOLD, 50);
        return percent / 100f;
    }

    private String getServerUrl() {
        return getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(PREF_SERVER_URL, "http://10.0.2.2:8000");
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (sensorManager != null && rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI);
        }

        startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            }
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ROTATION_VECTOR) {
            return;
        }

        float[] rotationMatrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
        SensorManager.getOrientation(rotationMatrix, orientation);
        currentHeading = (float) ((Math.toDegrees(orientation[0]) + 360.0) % 360.0);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && currentScreen == Screen.NAVIGATION) {
                startCamera();
            } else if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                updateGuide("카메라 권한이 필요합니다.");
            }
        } else if (requestCode == REQUEST_LOCATION_PERMISSION) {
            if (hasLocationPermission()) {
                startLocationUpdates();
                updateGuide("위치 권한이 허용되었습니다.");
            } else {
                updateGuide("위치 권한이 없어 기본 위치로 서버에 전송합니다.");
            }
        } else if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (pendingVoiceCommandAfterPermission) {
                    pendingVoiceCommandAfterPermission = false;
                    startVoiceCommandInput();
                } else {
                    pendingDestinationSpeechAfterPermission = false;
                    startVoiceDestinationInput();
                }
            } else {
                pendingVoiceCommandAfterPermission = false;
                pendingDestinationSpeechAfterPermission = false;
                updateGuide("음성 입력 권한이 필요합니다.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if ((requestCode != REQUEST_SPEECH_INPUT && requestCode != REQUEST_VOICE_COMMAND)
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        ArrayList<String> results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);

        if (results == null || results.isEmpty()) {
            updateGuide("음성을 인식하지 못했습니다. 다시 말씀해 주세요.");
            speak(lastGuideMessage, true);
            if (requestCode == REQUEST_VOICE_COMMAND) {
                scheduleVoiceCommandFollowUp();
            }
            return;
        }

        if (requestCode == REQUEST_VOICE_COMMAND) {
            handleVoiceCommand(results.get(0));
        } else {
            startNavigation(results.get(0));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (hazardDialog != null && hazardDialog.isShowing()) {
            hazardDialog.dismiss();
        }

        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }

        if (streamWebSocket != null) {
            streamWebSocket.close(1000, "activity destroyed");
            streamWebSocket = null;
        }

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }

        if (detector != null) {
            detector.close();
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private List<String> getRecentDestinations() {
        String raw = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(PREF_RECENT_DESTINATIONS, "");
        List<String> results = new ArrayList<>();

        if (raw == null || raw.trim().isEmpty()) {
            return results;
        }

        String[] parts = raw.split("\\n");

        for (String part : parts) {
            String destination = part.trim();

            if (!destination.isEmpty()) {
                results.add(destination);
            }
        }

        return results;
    }

    private void saveRecentDestination(String destination) {
        List<String> recent = getRecentDestinations();
        recent.remove(destination);
        recent.add(0, destination);

        while (recent.size() > 5) {
            recent.remove(recent.size() - 1);
        }

        StringBuilder builder = new StringBuilder();

        for (String item : recent) {
            if (builder.length() > 0) {
                builder.append("\n");
            }

            builder.append(item);
        }

        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_RECENT_DESTINATIONS, builder.toString())
                .apply();
    }

    private void saveActiveDestinationAsFavorite() {
        if (activeDestination == null || activeDestination.trim().isEmpty()) {
            updateGuide("저장할 목적지가 없습니다.");
            speak(lastGuideMessage, true);
            return;
        }

        getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_FAVORITE_DESTINATION, activeDestination)
                .apply();
        updateGuide(activeDestination + "을 즐겨찾기에 저장했습니다.");
        speak(lastGuideMessage, true);
    }

    private String toKoreanLabel(String label) {
        String korean = koreanLabels.get(label);
        return korean == null ? label : korean;
    }

    private String toDirection(float centerX) {
        if (centerX < 0.38f) {
            return "좌측 전방";
        }

        if (centerX > 0.62f) {
            return "우측 전방";
        }

        return "전방";
    }

    private String toDistanceLevel(float area) {
        if (area > 0.20f) {
            return "가까운 거리";
        }

        if (area > 0.08f) {
            return "중간 거리";
        }

        return "먼 거리";
    }

    private String formatDistance(int distanceMeter) {
        if (distanceMeter >= 1000) {
            return String.format(Locale.KOREA, "%.1fkm", distanceMeter / 1000f);
        }

        return distanceMeter + "m";
    }

    private String objectIconForLabel(String label) {
        if ("motorcycle".equals(label)) {
            return "🏍";
        }

        if ("person".equals(label)) {
            return "사람";
        }

        if ("car".equals(label) || "bus".equals(label) || "truck".equals(label)) {
            return "차량";
        }

        return "!";
    }

    private LinearLayout baseScreenContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(16), dp(18), dp(18));
        return root;
    }

    private View wrapScrollable(LinearLayout content) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private TextView makeSmallTitle(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(COLOR_TEXT);
        textView.setTextSize(16);
        textView.setGravity(Gravity.CENTER);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    private TextView makeSectionLabel(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(COLOR_MUTED);
        textView.setTextSize(15);
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    private TextView makeChip(String text) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(COLOR_MUTED);
        textView.setTextSize(16);
        textView.setGravity(Gravity.CENTER);
        textView.setPadding(dp(16), dp(10), dp(16), dp(10));
        textView.setBackground(makeRoundedDrawable(COLOR_SURFACE_ALT, COLOR_BORDER, dp(24)));
        return textView;
    }

    private TextView makePanelText(String text, int textSize, int textColor) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(textColor);
        textView.setTextSize(textSize);
        textView.setLineSpacing(0, 1.12f);
        textView.setPadding(dp(16), dp(14), dp(16), dp(14));
        textView.setBackground(makeRoundedDrawable(COLOR_SURFACE, COLOR_SURFACE, dp(10)));
        return textView;
    }

    private TextView makeDialogText(String text, int textSize, int textColor, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(textColor);
        textView.setTextSize(textSize);
        textView.setLineSpacing(0, 1.12f);

        if (bold) {
            textView.setTypeface(Typeface.DEFAULT_BOLD);
        }

        return textView;
    }

    private EditText makeInput(String text, String hint) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(text);
        input.setHint(hint);
        input.setTextSize(18);
        input.setSelectAllOnFocus(true);
        input.setPadding(dp(16), dp(13), dp(16), dp(13));
        input.setBackground(makeRoundedDrawable(Color.WHITE, COLOR_BORDER, dp(8)));
        return input;
    }

    private Button makeButton(String text, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(20);
        button.setTextColor(textColor);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setBackground(makeRoundedDrawable(backgroundColor, backgroundColor, dp(12)));
        button.setAllCaps(false);
        button.setMinHeight(dp(72));
        return button;
    }

    private Button makeTileButton(String text) {
        Button button = makeButton(text, COLOR_SURFACE_ALT, COLOR_TEXT);
        button.setTextSize(18);
        button.setMinHeight(dp(112));
        button.setBackground(makeRoundedDrawable(COLOR_SURFACE_ALT, COLOR_BORDER, dp(14)));
        return button;
    }

    private Button makeListButton(String text) {
        Button button = makeButton(text, COLOR_SURFACE, COLOR_TEXT);
        button.setTextSize(17);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), dp(10), dp(18), dp(10));
        button.setBackground(makeRoundedDrawable(COLOR_SURFACE, COLOR_SURFACE, dp(10)));
        return button;
    }

    private Button makeSecondaryButton(String text, int textColor) {
        Button button = makeButton(text, COLOR_SURFACE_ALT, textColor);
        button.setBackground(makeRoundedDrawable(COLOR_SURFACE_ALT, COLOR_BORDER, dp(12)));
        return button;
    }

    private Button makeEmergencyButton(String text) {
        Button button = makeButton(text, COLOR_SURFACE_ALT, COLOR_DANGER);
        button.setTextSize(17);
        button.setBackground(makeRoundedDrawable(COLOR_SURFACE_ALT, COLOR_BORDER, dp(14)));
        return button;
    }

    private GradientDrawable makeRoundedDrawable(int fillColor, int strokeColor, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void updateRateLabel(TextView textView, int percent) {
        textView.setText("현재 속도: " + String.format(Locale.KOREA, "%.1fx", percent / 100f));
    }

    private void updateThresholdLabel(TextView textView, int percent) {
        textView.setText("현재 임계값: " + String.format(Locale.KOREA, "%.2f", percent / 100f));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams weightedButtonParams(float weight, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                weight
        );
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams params, int left, int top, int right, int bottom) {
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
