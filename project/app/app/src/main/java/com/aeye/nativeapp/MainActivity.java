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
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
        SETTINGS
    }

    private enum VoiceMenuState {
        NONE,
        MAIN_MENU,
        DESTINATION,
        CONFIRM_DESTINATION,
        NEARBY_TYPE
    }

    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final int REQUEST_LOCATION_PERMISSION = 1002;
    private static final int REQUEST_AUDIO_PERMISSION = 1003;
    private static final int REQUEST_SPEECH_INPUT = 2001;
    private static final int REQUEST_VOICE_COMMAND = 2002;
    // Keep object detection calm enough for walking guidance and demo readability.
    private static final long ANALYSIS_INTERVAL_MS = 1500;
    private static final long STREAM_FRAME_INTERVAL_MS = 220;
    private static final long SPEAK_INTERVAL_MS = 3000;
    private static final long POST_INTERVAL_MS = 2500;
    private static final long MOBILE_COMMAND_POLL_INTERVAL_MS = 1500;
    private static final long SCENE_GUIDANCE_INTERVAL_MS = 12000;
    private static final long HAZARD_ALERT_COOLDOWN_MS = 5000;
    private static final long GENERAL_HAZARD_SPEAK_INTERVAL_MS = 8000;
    private static final long TACTILE_DEBUG_POST_INTERVAL_MS = 5000;
    private static final long VOICE_COMMAND_FOLLOW_UP_DELAY_MS = 2400;
    private static final long BRAILLE_LOST_TIMEOUT_MS = 5000;
    private static final int BRAILLE_STABLE_DETECTION_COUNT = 2;
    private static final float TACTILE_IOU_THRESHOLD = 0.01f;
    private static final float GENERAL_HAZARD_MIN_AREA = 0.035f;
    private static final float BRAILLE_DETECTION_THRESHOLD = 0.50f;
    private static final float TACTILE_OBSTACLE_SCORE_THRESHOLD = 0.35f;
    private static final float TACTILE_EXPAND_X_RATIO = 0.38f;
    private static final float TACTILE_EXPAND_Y_RATIO = 0.45f;
    private static final float TACTILE_MIN_EXPANDED_HALF_WIDTH = 0.14f;
    private static final float TACTILE_MIN_EXPANDED_HALF_HEIGHT = 0.10f;
    private static final float TACTILE_EXPANDED_OBSTACLE_OVERLAP_THRESHOLD = 0.02f;
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
    private static final int COLOR_BACKGROUND = Color.rgb(247, 247, 243);
    private static final int COLOR_SURFACE = Color.rgb(242, 241, 236);
    private static final int COLOR_SURFACE_ALT = Color.rgb(250, 249, 244);
    private static final int COLOR_BORDER = Color.rgb(201, 201, 193);
    private static final int COLOR_DANGER = Color.rgb(174, 35, 43);
    private static final int COLOR_WARNING = Color.rgb(245, 145, 24);
    private static final int COLOR_WARNING_BG = Color.rgb(255, 244, 224);
    private static final int COLOR_TEXT = Color.rgb(31, 31, 31);
    private static final int COLOR_MUTED = Color.rgb(96, 96, 92);
    private static final int COLOR_NUNI_BLACK = Color.rgb(0, 0, 0);
    private static final int COLOR_NUNI_YELLOW = Color.rgb(255, 215, 0);

    private final Set<String> riskLabels = new HashSet<>();
    private final Map<String, String> koreanLabels = new HashMap<>();
    private final VoiceCommandParser voiceCommandParser = new VoiceCommandParser();
    private final LocalLlmInterpreter localLlmInterpreter = new RuleBasedLocalLlmInterpreter();

    private LinearLayout screenContainer;
    private TextView statusText;
    private TextView guideText;
    private TextView detectionText;
    private TextView locationChipText;
    private TextView serverStatusText;
    private EditText serverInput;
    private EditText guardianInput;
    private NuniWaveformView nuniWaveformView;

    private ExecutorService cameraExecutor;
    private YoloDetector detector;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private ProcessCameraProvider cameraProvider;
    private AlertDialog hazardDialog;
    private Handler mainHandler;
    private OkHttpClient streamHttpClient;
    private WebSocket streamWebSocket;
    private RouteTracker routeTracker;

    private long lastAnalysisAt = 0L;
    private long lastStreamedAt = 0L;
    private long lastSpokenAt = 0L;
    private long lastPostedAt = 0L;
    private long lastSceneGuidanceAt = 0L;
    private long lastHazardAlertAt = 0L;
    private long lastGeneralHazardSpokenAt = 0L;
    private long lastTactileDebugPostedAt = 0L;
    private long lastBrailleDetectedAt = 0L;
    private long hazardSuppressedUntil = 0L;
    private int brailleDetectionStreak = 0;
    private String lastHazardKey = "";
    private String lastGuideMessage = "누니야 하고 목적지를 말씀해 주세요.";
    private String lastServerStatusMessage = "서버 전송 대기 중";
    private String activeDestination = "";
    private String nextActionMessage = "직진 200m";
    private String nextPreviewMessage = "우회전 후 직진 500m";
    private boolean cameraRunning = false;
    private boolean navigationSessionActive = false;
    private boolean pendingVoiceCommandAfterPermission = false;
    private boolean developerStreamingEnabled = false;
    private boolean commandPollingActive = false;
    private boolean wakeListening = false;
    private boolean commandListening = false;
    private boolean voiceIntroSpoken = false;
    private boolean isTtsSpeaking = false;
    private float maxRmsDuringSession = -100f;
    private String guidanceMode = "general";
    private String lastSceneDescription = "";
    private String pendingDestinationConfirmation = "";
    private VoiceMenuState voiceMenuState = VoiceMenuState.NONE;
    private Screen currentScreen = Screen.HOME;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private LocationManager locationManager;
    private double currentLat = 37.54167;
    private double currentLng = 126.84028;
    private Float currentHeading = null;
    private final List<Detection> lastDetectionsSnapshot = new ArrayList<>();

    private final LocationListener locationListener = location -> {
        currentLat = location.getLatitude();
        currentLng = location.getLongitude();
        runOnUiThread(this::updateLocationUi);
        
        // 경로 추적 및 안내 로직
        if (navigationSessionActive && routeTracker != null) {
            routeTracker.track(currentLat, currentLng, new RouteTracker.NavigationListener() {
                @Override
                public void onUpdate(String instruction, int remainingDistance) {
                    runOnUiThread(() -> {
                        updateGuide(instruction);
                        speak(instruction, true);
                    });
                }

                @Override
                public void onStepCompleted(int nextStepIndex) {
                    runOnUiThread(() -> {
                        if (routeTracker == null) {
                            return;
                        }

                        nextActionMessage = routeTracker.getCurrentInstruction();
                        nextPreviewMessage = routeTracker.getNextInstruction();
                        updateGuide(nextActionMessage);
                        speak(lastGuideMessage, true);
                    });
                }

                @Override
                public void onOffRoute() {
                    runOnUiThread(() -> {
                        updateGuide("경로를 벗어났습니다. 경로를 다시 탐색합니다.");
                        speak(lastGuideMessage, true);
                        startNavigation(activeDestination); // 재탐색
                    });
                }

                @Override
                public void onArrival() {
                    runOnUiThread(() -> {
                        updateGuide("목적지에 도착했습니다. 안내를 종료합니다.");
                        speak(lastGuideMessage, true);
                        stopNavigation();
                    });
                }
            });
        }
        
        // 실시간 위치 정보를 서버로 전송
        ServerClient.postLocation(
                getServerUrl(),
                DEVICE_ID,
                currentLat,
                currentLng,
                currentHeading,
                null
        );
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(COLOR_NUNI_BLACK);
        getWindow().setNavigationBarColor(COLOR_NUNI_BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        mainHandler = new Handler(Looper.getMainLooper());
        streamHttpClient = new OkHttpClient();
        cameraExecutor = Executors.newSingleThreadExecutor();
        setupLabels();
        setupTts();
        setupUi();
        setupDetector();
        setupSensors();
        setupLocation();
        startMobileCommandPolling();

        if (!hasCameraPermission()) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
        } else {
            mainHandler.postDelayed(this::startWakeListening, 900L);
        }
    }

    private void setupUi() {
        screenContainer = new LinearLayout(this);
        screenContainer.setOrientation(LinearLayout.VERTICAL);
        screenContainer.setBackgroundColor(COLOR_BACKGROUND);
        setContentView(screenContainer);
        renderScreen(Screen.HOME, false);
    }

    private void renderScreen(Screen screen, boolean autoSpeech) {
        currentScreen = screen;
        clearScreenRefs();
        screenContainer.removeAllViews();

        if (screen == Screen.HOME) {
            screenContainer.addView(buildHomeScreen());
        } else {
            screenContainer.addView(buildSettingsScreen());
        }

        updateLocationUi();
    }

    private void clearScreenRefs() {
        statusText = null;
        guideText = null;
        detectionText = null;
        locationChipText = null;
        serverStatusText = null;
        serverInput = null;
        guardianInput = null;
        nuniWaveformView = null;
    }

    private View buildHomeScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_NUNI_BLACK);

        root.addView(makeNuniCorner(true, true));
        root.addView(makeNuniCorner(true, false));
        root.addView(makeNuniCorner(false, true));
        root.addView(makeNuniCorner(false, false));
        root.addView(makeNuniStatusDot(navigationSessionActive));

        nuniWaveformView = new NuniWaveformView(this);
        nuniWaveformView.setState(navigationSessionActive ? "navigating" : "idle");
        FrameLayout.LayoutParams waveformParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        );
        waveformParams.gravity = Gravity.CENTER;
        root.addView(nuniWaveformView, waveformParams);

        if (navigationSessionActive) {
            root.addView(makeNuniStepDots());
        }

        // 설정 버튼 (오른쪽 아래, 반전 색상)
        Button settingsBtn = new Button(this);
        settingsBtn.setText("⚙");
        settingsBtn.setTextSize(24);
        settingsBtn.setTextColor(COLOR_NUNI_BLACK);
        settingsBtn.setPadding(0, 0, 0, 0);
        settingsBtn.setGravity(Gravity.CENTER);
        // 배경은 노란색 동그라미 (반전)
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(COLOR_NUNI_YELLOW);
        settingsBtn.setBackground(shape);

        FrameLayout.LayoutParams btnParams = new FrameLayout.LayoutParams(dp(56), dp(56));
        btnParams.gravity = Gravity.BOTTOM | Gravity.END;
        btnParams.setMargins(0, 0, dp(24), dp(24));
        root.addView(settingsBtn, btnParams);
        settingsBtn.setOnClickListener(v -> renderScreen(Screen.SETTINGS, false));

        statusText = new TextView(this);
        statusText.setText(lastServerStatusMessage);
        statusText.setVisibility(View.GONE);
        root.addView(statusText, new FrameLayout.LayoutParams(1, 1));

        guideText = new TextView(this);
        guideText.setText(lastGuideMessage);
        guideText.setVisibility(View.GONE);
        root.addView(guideText, new FrameLayout.LayoutParams(1, 1));

        locationChipText = new TextView(this);
        locationChipText.setVisibility(View.GONE);
        root.addView(locationChipText, new FrameLayout.LayoutParams(1, 1));

        return root;
    }

    private View buildSettingsScreen() {
        SharedPreferences preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        LinearLayout root = baseScreenContent();

        // 상단 헤더 (제목 + 닫기 버튼)
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        
        TextView title = makeSmallTitle("설정");
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        
        Button closeBtn = new Button(this);
        closeBtn.setText("닫기");
        closeBtn.setTextColor(COLOR_BLUE);
        closeBtn.setBackground(null);
        closeBtn.setOnClickListener(v -> renderScreen(Screen.HOME, false));
        header.addView(closeBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));
        
        root.addView(header, margin(matchWrap(), 0, 0, 0, 16));

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

    private void renderBottomNav() { }
    private View buildSearchScreen() { return null; }
    private View buildNavigationScreen() { return null; }
    private Button makeNavButton(String text, Screen targetScreen) { return null; }

    private void setupDetector() {
        try {
            detector = new YoloDetector(this);
            lastServerStatusMessage = "누니 보행 감지 모델 준비 완료";
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
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());

                tts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        isTtsSpeaking = true;
                        runOnUiThread(() -> stopListeningImmediately());
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        isTtsSpeaking = false;
                        runOnUiThread(() -> {
                            if (wakeListening || commandListening || voiceMenuState != VoiceMenuState.NONE) {
                                mainHandler.postDelayed(() -> resumeListeningAfterTts(), 400L);
                            }
                        });
                    }

                    @Override
                    public void onError(String utteranceId) {
                        isTtsSpeaking = false;
                    }
                });

                applyTtsRate(getSharedPreferences(PREF_NAME, MODE_PRIVATE).getInt(PREF_TTS_RATE, 95));
            }
        });
    }

    private void stopListeningImmediately() {
        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private void resumeListeningAfterTts() {
        if (isFinishing() || isTtsSpeaking) {
            return;
        }

        if (voiceMenuState != VoiceMenuState.NONE) {
            startVoiceCommandInput();
        } else {
            startWakeListening();
        }
    }

    private void setupLabels() {
        riskLabels.add("person");
        riskLabels.add("bicycle");
        riskLabels.add("car");
        riskLabels.add("motorcycle");
        riskLabels.add("kickboard");
        riskLabels.add("bus");
        riskLabels.add("truck");
        riskLabels.add("red_light");
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
        koreanLabels.put("braille_block", "점자블록");
        koreanLabels.put("bicycle", "자전거");
        koreanLabels.put("car", "차량");
        koreanLabels.put("motorcycle", "오토바이");
        koreanLabels.put("kickboard", "킥보드");
        koreanLabels.put("green_light", "초록 신호");
        koreanLabels.put("red_light", "빨간 신호");
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
        String destination;

        if (favorite) {
            destination = getSharedPreferences(PREF_NAME, MODE_PRIVATE).getString(PREF_FAVORITE_DESTINATION, "");

            if (destination == null || destination.trim().isEmpty()) {
                updateGuide("즐겨찾기가 없습니다. 최근 목적지에서 선택하거나 새 목적지를 말해 주세요.");
                speak(lastGuideMessage, true);
                return;
            }

            startNavigation(destination);
            return;
        }

        List<String> recent = getRecentDestinations();

        if (recent.isEmpty()) {
            updateGuide("최근 목적지가 없습니다. 목적지를 먼저 말해 주세요.");
            speak(lastGuideMessage, true);
            return;
        }

        updateGuide("가장 최근 목적지인 " + recent.get(0) + " 안내를 시작합니다.");
        speak(lastGuideMessage, true);
        startNavigation(recent.get(0));
    }

    private void startNavigation(String destination) {
        String normalizedDestination = destination == null ? "" : destination.trim();

        if (normalizedDestination.isEmpty()) {
            updateGuide("목적지를 인식하지 못했습니다.");
            speak(lastGuideMessage, true);
            renderScreen(Screen.HOME, false);
            scheduleWakeListening(1600L);
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
                        renderScreen(Screen.HOME, false);
                        scheduleWakeListening(2200L);
                        return;
                    }

                    beginNavigation(result);
                })
        );
    }

    private void beginNavigation(ServerClient.RouteResult routeResult) {
        activeDestination = routeResult.destinationName;
        navigationSessionActive = true;
        guidanceMode = "general";
        lastGeneralHazardSpokenAt = 0L;
        lastTactileDebugPostedAt = 0L;
        resetBrailleAutoState();
        saveRecentDestination(activeDestination);

        if (!routeResult.geometry.isEmpty() && !routeResult.steps.isEmpty()) {
            routeTracker = new RouteTracker(routeResult);
        } else {
            routeTracker = null;
        }

        nextActionMessage = routeResult.currentInstruction;
        nextPreviewMessage = routeResult.nextInstruction + " → " + activeDestination;
        lastGuideMessage = activeDestination + "까지 안내를 시작합니다. " + nextActionMessage;

        renderScreen(Screen.HOME, false);
        speak(lastGuideMessage, true);
        startCamera();
        scheduleWakeListening(2600L);
    }

    private void stopNavigation() {
        if (developerStreamingEnabled) {
            stopDeveloperStreaming();
        }

        stopCamera();
        activeDestination = "";
        navigationSessionActive = false;
        routeTracker = null;
        lastHazardKey = "";
        lastGeneralHazardSpokenAt = 0L;
        lastTactileDebugPostedAt = 0L;
        resetBrailleAutoState();
        hazardSuppressedUntil = 0L;

        if (hazardDialog != null && hazardDialog.isShowing()) {
            hazardDialog.dismiss();
        }

        updateGuide("안내를 종료했습니다.");
        speak(lastGuideMessage, true);
        renderScreen(Screen.HOME, false);
        scheduleWakeListening(1200L);
    }

    private void startMobileCommandPolling() {
        if (commandPollingActive) {
            return;
        }

        commandPollingActive = true;
        pollMobileCommands();
    }

    private void pollMobileCommands() {
        if (!commandPollingActive || isFinishing()) {
            return;
        }

        ServerClient.fetchMobileCommands(
                getServerUrl(),
                DEVICE_ID,
                result -> runOnUiThread(() -> {
                    if (result != null && result.ok) {
                        for (String command : result.commands) {
                            handleRemoteControlCommand(command);
                        }

                        if (result.streamRequested && !developerStreamingEnabled) {
                            handleRemoteControlCommand("start_stream");
                        } else if (!result.streamRequested && developerStreamingEnabled) {
                            handleRemoteControlCommand("stop_stream");
                        }
                    }

                    if (commandPollingActive && !isFinishing()) {
                        mainHandler.postDelayed(this::pollMobileCommands, MOBILE_COMMAND_POLL_INTERVAL_MS);
                    }
                })
        );
    }

    private void handleRemoteControlCommand(String command) {
        if ("start_stream".equals(command) || "start_streaming".equals(command)) {
            if (!developerStreamingEnabled) {
                startDeveloperStreaming();
                updateGuide("관제에서 카메라 스트리밍을 요청했습니다. 주변 화면을 전송합니다.");
                speak(lastGuideMessage, true);
            }
            return;
        }

        if ("stop_stream".equals(command) || "stop_streaming".equals(command)) {
            if (developerStreamingEnabled) {
                stopDeveloperStreaming();
                updateGuide("관제 카메라 스트리밍을 종료했습니다.");
                speak(lastGuideMessage, true);
            }
        }
    }

    private void startVoiceFirstIntro() {
        if (voiceIntroSpoken || isFinishing()) {
            return;
        }

        voiceIntroSpoken = true;
        startWakeListening();
    }

    private void startVoiceCommandInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceCommandAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }

        commandListening = true;
        wakeListening = false;

        if (nuniWaveformView != null) {
            nuniWaveformView.setState("listening");
        }

        startSpeechRecognizer(true);
    }

    private void startWakeListening() {
        if (isFinishing()) {
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingVoiceCommandAfterPermission = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            return;
        }

        commandListening = false;
        wakeListening = true;

        if (nuniWaveformView != null) {
            nuniWaveformView.setState(navigationSessionActive ? "navigating" : "idle");
        }

        startSpeechRecognizer(false);
    }

    private void startSpeechRecognizer(boolean commandMode) {
        if (isTtsSpeaking) {
            return;
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateGuide("이 기기에서는 음성 인식을 사용할 수 없습니다.");
            return;
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        } else {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
            }
        }

        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                maxRmsDuringSession = -100f; // 세션 시작 시 초기화
            }

            @Override
            public void onBeginningOfSpeech() {
                if (nuniWaveformView != null) {
                    nuniWaveformView.setState("listening");
                }
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                if (rmsdB > maxRmsDuringSession) {
                    maxRmsDuringSession = rmsdB;
                }
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                if (nuniWaveformView != null) {
                    nuniWaveformView.setState("processing");
                }
            }

            @Override
            public void onError(int error) {
                commandListening = false;
                wakeListening = false;

                if (nuniWaveformView != null) {
                    nuniWaveformView.setState(navigationSessionActive ? "navigating" : "idle");
                }

                // 에러 발생 시 즉시 재시작하지 않고 약간의 휴지기를 가짐 (무한 루프 방지)
                if (commandMode) {
                    if (voiceMenuState != VoiceMenuState.NONE) {
                        repromptVoiceMenu();
                        return;
                    }

                    updateGuide("잘 듣지 못했습니다. 잠시 후 누니야라고 다시 불러 주세요.");
                    speak(lastGuideMessage, true);
                    scheduleWakeListening(2500L); 
                } else {
                    // 호출어 대기 중 에러(소음 등)는 조금 더 길게 대기 후 재시작
                    scheduleWakeListening(1500L);
                }
            }

            @Override
            public void onResults(Bundle results) {
                commandListening = false;
                wakeListening = false;
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                String utterance = matches == null || matches.isEmpty() ? "" : matches.get(0);

                // 소리 크기가 너무 작으면 (임계값 7.0 미만) 주변 소음으로 간주하고 무시
                if (maxRmsDuringSession < 7.0f && !utterance.isEmpty()) {
                    scheduleWakeListening(500L);
                    return;
                }

                // 누니 자신의 음성 피드백 무시 로직
                if (isSelfFeedback(utterance, lastGuideMessage)) {
                    scheduleWakeListening(500L);
                    return;
                }

                if (commandMode) {
                    if (utterance.trim().isEmpty()) {
                        updateGuide("명령을 듣지 못했습니다. 누니야라고 다시 불러 주세요.");
                        speak(lastGuideMessage, true);
                        scheduleWakeListening(1600L);
                        return;
                    }

                    handleVoiceCommand(utterance);
                    return;
                }

                handleWakeResult(utterance);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        // 마이크 소스를 통화용(7)으로 변경하여 노이즈 제거 강화
        intent.putExtra("android.speech.extra.AUDIO_SOURCE", 7); 
        // 침묵 감지 시간을 약간 더 여유 있게 조정
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, commandMode ? 4000L : 2000L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, commandMode ? 2000L : 800L);
        intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, commandMode ? 1500L : 700L);

        try {
            speechRecognizer.startListening(intent);
        } catch (Exception error) {
            updateGuide("이 기기에서는 음성 명령을 사용할 수 없습니다.");
            speak(lastGuideMessage, true);
        }
    }

    private boolean isSelfFeedback(String utterance, String lastSpoken) {
        if (utterance == null || utterance.trim().isEmpty() || lastSpoken == null || lastSpoken.isEmpty()) {
            return false;
        }
        
        String cleanUtterance = utterance.replaceAll("\\s+", "");
        String cleanLastSpoken = lastSpoken.replaceAll("\\s+", "");
        
        // 1. 단순 포함 관계 확인
        if (cleanUtterance.length() > 3 && cleanLastSpoken.contains(cleanUtterance)) {
            return true;
        }
        
        // 2. 글자 수 대비 일치도 확인 (누니야 단어 제외)
        int matchCount = 0;
        for (int i = 0; i < Math.min(cleanUtterance.length(), cleanLastSpoken.length()); i++) {
            if (cleanUtterance.charAt(i) == cleanLastSpoken.charAt(i)) {
                matchCount++;
            }
        }
        
        float ratio = (float) matchCount / Math.max(cleanUtterance.length(), 1);
        return ratio > 0.7f; // 70% 이상 일치하면 자가 피드백으로 간주
    }

    private void handleWakeResult(String utterance) {
        if (!containsWakeWord(utterance)) {
            scheduleWakeListening(500L);
            return;
        }

        String command = removeWakeWord(utterance).trim();

        // 명령어 텍스트가 조금이라도 있으면 무조건 서버(LLM)에 먼저 물어봅니다.
        if (!command.isEmpty()) {
            handleVoiceCommand(command);
            return;
        }

        // 아무 말 없이 "누니야"라고만 불렀을 때만 로컬 메뉴 로직으로 진입합니다.
        enterVoiceMainMenu();
    }

    private void enterVoiceMainMenu() {
        voiceMenuState = VoiceMenuState.MAIN_MENU;
        pendingDestinationConfirmation = "";
        updateGuide("네, 어떤 일을 할까요?");
        speak(lastGuideMessage, true);
        scheduleMenuListening(2200L); // 1500L에서 2200L로 연장
    }

    private void scheduleMenuListening(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && voiceMenuState != VoiceMenuState.NONE) {
                startVoiceCommandInput();
            }
        }, delayMs);
    }

    private boolean handleVoiceMenuCommand(String utterance) {
        String normalized = normalizeVoiceToken(utterance);

        if (normalized.isEmpty()) {
            repromptVoiceMenu();
            return true;
        }

        if (isCancelCommand(normalized)) {
            voiceMenuState = VoiceMenuState.NONE;
            updateGuide("메뉴를 종료했습니다. 다시 필요하면 누니야라고 불러 주세요.");
            speak(lastGuideMessage, true);
            scheduleWakeListening(2000L);
            return true;
        }

        if (voiceMenuState == VoiceMenuState.MAIN_MENU) {
            if (isDestinationMenuCommand(normalized)) {
                voiceMenuState = VoiceMenuState.DESTINATION;
                updateGuide("목적지를 말씀하세요. 예를 들어 화곡역 3번 출구라고 말하면 됩니다.");
                speak(lastGuideMessage, true);
                scheduleMenuListening(3400L);
                return true;
            }

            if (isNearbyMenuCommand(normalized)) {
                voiceMenuState = VoiceMenuState.NEARBY_TYPE;
                updateGuide("주변시설을 찾습니다. 화장실, 편의점, 약국, 병원처럼 말씀하세요.");
                speak(lastGuideMessage, true);
                scheduleMenuListening(3500L);
                return true;
            }

            if (isRiskMenuCommand(normalized)) {
                voiceMenuState = VoiceMenuState.NONE;
                readRiskInformation();
                return true;
            }

            if (isEmergencyMenuCommand(normalized)) {
                voiceMenuState = VoiceMenuState.NONE;
                requestEmergencySupport();
                return true;
            }

            if (isStreamingStartCommand(normalized)) {
                voiceMenuState = VoiceMenuState.NONE;
                updateGuide("카메라 스트리밍은 관제에서 스트림 보기를 누르면 자동으로 시작됩니다.");
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return true;
            }

            if (isStreamingStopCommand(normalized)) {
                voiceMenuState = VoiceMenuState.NONE;
                updateGuide("카메라 스트리밍은 관제에서 종료할 수 있습니다.");
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return true;
            }

            repromptVoiceMenu();
            return true;
        }

        if (voiceMenuState == VoiceMenuState.DESTINATION) {
            String destination = stripMenuFiller(utterance);

            if (destination.length() < 2) {
                updateGuide("목적지를 다시 말씀해 주세요.");
                speak(lastGuideMessage, true);
                scheduleMenuListening(2200L);
                return true;
            }

            pendingDestinationConfirmation = destination;
            voiceMenuState = VoiceMenuState.CONFIRM_DESTINATION;
            updateGuide("목적지가 " + destination + " 맞습니까? 맞으면 맞아, 아니면 아니야라고 말씀하세요.");
            speak(lastGuideMessage, true);
            scheduleMenuListening(4300L);
            return true;
        }

        if (voiceMenuState == VoiceMenuState.CONFIRM_DESTINATION) {
            if (isYesCommand(normalized)) {
                String destination = pendingDestinationConfirmation;
                voiceMenuState = VoiceMenuState.NONE;
                pendingDestinationConfirmation = "";
                updateGuide(destination + " 안내를 시작합니다.");
                speak(lastGuideMessage, true);
                startNavigation(destination);
                return true;
            }

            if (isNoCommand(normalized)) {
                pendingDestinationConfirmation = "";
                voiceMenuState = VoiceMenuState.DESTINATION;
                updateGuide("알겠습니다. 목적지를 다시 말씀하세요.");
                speak(lastGuideMessage, true);
                scheduleMenuListening(2600L);
                return true;
            }

            String destination = stripMenuFiller(utterance);
            if (destination.length() >= 2) {
                pendingDestinationConfirmation = destination;
                updateGuide("목적지가 " + destination + " 맞습니까? 맞으면 맞아, 아니면 아니야라고 말씀하세요.");
                speak(lastGuideMessage, true);
                scheduleMenuListening(4300L);
                return true;
            }

            updateGuide("맞으면 맞아, 아니면 아니야라고 말씀하세요.");
            speak(lastGuideMessage, true);
            scheduleMenuListening(2600L);
            return true;
        }

        if (voiceMenuState == VoiceMenuState.NEARBY_TYPE) {
            String placeType = parseMenuPlaceType(normalized);
            voiceMenuState = VoiceMenuState.NONE;
            readNearbyPlaces(placeType);
            return true;
        }

        return false;
    }

    private void repromptVoiceMenu() {
        updateGuide("잘 듣지 못했습니다. 다시 말씀해 주세요.");
        speak(lastGuideMessage, true);
        scheduleMenuListening(2500L); // 2000L에서 2500L로 연장
    }

    private boolean looksLikeMenuCommand(String utterance) {
        String normalized = normalizeVoiceToken(utterance);

        if (normalized.length() <= 8 || normalized.contains("메뉴")) {
            return isDestinationMenuCommand(normalized)
                    || isNearbyMenuCommand(normalized)
                    || isRiskMenuCommand(normalized)
                    || isEmergencyMenuCommand(normalized)
                    || isStreamingStartCommand(normalized)
                    || isStreamingStopCommand(normalized);
        }

        return containsAnyToken(normalized, "목적지안내", "주변시설", "위험정보", "긴급연락", "도움", "도와줘", "스트리밍모드");
    }

    private String normalizeVoiceToken(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.KOREA).replaceAll("\\s+", "");
    }

    private boolean containsAnyToken(String normalized, String... tokens) {
        for (String token : tokens) {
            if (normalized.contains(normalizeVoiceToken(token))) {
                return true;
            }
        }

        return false;
    }

    private boolean isDestinationMenuCommand(String normalized) {
        return containsAnyToken(
                normalized,
                "1",
                "1번",
                "일번",
                "하나",
                "첫번째",
                "메뉴1",
                "메뉴일",
                "목적지",
                "길안내",
                "경로안내"
        );
    }

    private boolean isNearbyMenuCommand(String normalized) {
        return containsAnyToken(
                normalized,
                "2",
                "2번",
                "이번",
                "둘",
                "두번째",
                "메뉴2",
                "메뉴이",
                "주변",
                "근처",
                "가까운",
                "시설",
                "화장실",
                "편의점"
        );
    }

    private boolean isRiskMenuCommand(String normalized) {
        return containsAnyToken(
                normalized,
                "3",
                "3번",
                "삼번",
                "셋",
                "세번째",
                "메뉴3",
                "메뉴삼",
                "위험",
                "장애물"
        );
    }

    private boolean isEmergencyMenuCommand(String normalized) {
        return containsAnyToken(
                normalized,
                "4",
                "4번",
                "사번",
                "넷",
                "네번째",
                "메뉴4",
                "메뉴사",
                "긴급",
                "보호자",
                "도와줘",
                "도와주세요",
                "도움",
                "sos"
        );
    }

    private boolean isStreamingStartCommand(String normalized) {
        return containsAnyToken(normalized, "스트리밍켜", "스트리밍시작", "스트리밍모드켜", "카메라전송", "관제전송");
    }

    private boolean isStreamingStopCommand(String normalized) {
        return containsAnyToken(normalized, "스트리밍꺼", "스트리밍중지", "스트리밍모드꺼", "카메라중지", "영상중지");
    }

    private boolean isCancelCommand(String normalized) {
        return containsAnyToken(normalized, "취소", "그만", "메뉴종료", "닫아");
    }

    private boolean isYesCommand(String normalized) {
        return containsAnyToken(normalized, "맞아", "맞습니다", "응", "네", "예", "그래", "확인", "좋아", "오케이", "ok");
    }

    private boolean isNoCommand(String normalized) {
        return containsAnyToken(normalized, "아니", "아니야", "틀려", "다시", "재입력");
    }

    private String parseMenuPlaceType(String normalized) {
        if (containsAnyToken(normalized, "화장실", "공중화장실", "화장")) {
            return "toilet";
        }

        if (containsAnyToken(normalized, "편의점", "마트", "가게", "상점", "매장")) {
            return "store";
        }

        if (containsAnyToken(normalized, "약국", "약방", "약")) {
            return "pharmacy";
        }

        if (containsAnyToken(normalized, "병원", "의원", "의료")) {
            return "hospital";
        }

        if (containsAnyToken(normalized, "지하철", "역", "출구", "전철")) {
            return "subway";
        }

        if (containsAnyToken(normalized, "카페", "커피")) {
            return "cafe";
        }

        if (containsAnyToken(normalized, "식당", "음식점", "밥집")) {
            return "restaurant";
        }

        if (containsAnyToken(normalized, "주민센터", "관공서", "구청", "동사무소")) {
            return "public_office";
        }

        return "";
    }

    private String stripMenuFiller(String utterance) {
        String result = utterance == null ? "" : utterance.trim();
        String[] fillers = {
                "목적지",
                "안내",
                "안내해줘",
                "안내해 줘",
                "길 안내",
                "경로 안내",
                "찾아줘",
                "가줘",
                "가 줘",
                "으로",
                "로"
        };

        for (String filler : fillers) {
            result = result.replace(filler, " ");
        }

        return result.replaceAll("\\s+", " ").trim();
    }

    private void scheduleWakeListening(long delayMs) {
        mainHandler.postDelayed(() -> {
            if (!isFinishing() && !commandListening) {
                startWakeListening();
            }
        }, delayMs);
    }

    private boolean containsWakeWord(String utterance) {
        String normalized = utterance == null ? "" : utterance.replace(" ", "").toLowerCase(Locale.KOREA);
        return normalized.contains("누니야")
                || normalized.contains("누니")
                || normalized.contains("눈이야")
                || normalized.contains("눈이");
    }

    private String removeWakeWord(String utterance) {
        String result = utterance == null ? "" : utterance;
        String[] wakeWords = {"누니야", "누니", "눈이야", "눈이"};

        for (String wakeWord : wakeWords) {
            result = result.replace(wakeWord, " ");
        }

        return result.replaceAll("\\s+", " ").trim();
    }

    private void handleVoiceCommand(String utterance) {
        if (voiceMenuState != VoiceMenuState.NONE && handleVoiceMenuCommand(utterance)) {
            return;
        }

        if (nuniWaveformView != null) {
            nuniWaveformView.setState("processing");
        }

        updateGuide("누니가 명령을 확인하고 있습니다.");
        setServerStatus("음성 명령: " + utterance);

        ServerClient.requestAssistantCommand(
                getServerUrl(),
                DEVICE_ID,
                utterance,
                currentLat,
                currentLng,
                result -> runOnUiThread(() -> {
                    if (!result.ok) {
                        setServerStatus(result.message);
                        handleVoiceCommandLocally(utterance);
                        return;
                    }

                    setServerStatus("누니 명령: " + result.intent + " / " + result.action);
                    executeAssistantResult(result, utterance);
                })
        );
    }

    private void handleVoiceCommandLocally(String utterance) {
        VoiceCommand command = voiceCommandParser.parse(utterance);

        if (command.getIntent() == VoiceCommand.IntentType.UNKNOWN && localLlmInterpreter.isAvailable()) {
            command = localLlmInterpreter.interpret(utterance);
        }

        setServerStatus("로컬 명령: " + command.toJsonLikeString());
        executeVoiceCommand(command);
    }

    private void executeAssistantResult(ServerClient.AssistantResult result, String utterance) {
        String action = result.action == null ? "speak" : result.action;

        switch (action) {
            case "start_navigation":
                if (result.route != null && result.route.ok) {
                    updateGuide(result.message);
                    speak(lastGuideMessage, true);
                    mainHandler.postDelayed(() -> beginNavigation(result.route), 900L);
                    return;
                }

                if (result.destinationKeyword != null && !result.destinationKeyword.trim().isEmpty()) {
                    startNavigation(result.destinationKeyword);
                    return;
                }

                updateGuide("목적지를 다시 말씀해 주세요.");
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case "repeat_guidance":
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case "next_guidance":
                speak("다음 안내입니다. " + nextPreviewMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case "help":
                requestControlHelp();
                return;
            case "emergency":
                requestEmergencySupport();
                return;
            case "stop_navigation":
                stopNavigation();
                return;
            case "set_guidance_mode":
                guidanceMode = "tactile".equals(result.guidanceMode) ? "tactile" : "general";
                lastHazardKey = "";
                lastGeneralHazardSpokenAt = 0L;
                lastTactileDebugPostedAt = 0L;
                resetBrailleAutoState();
                if ("tactile".equals(guidanceMode)) {
                    lastBrailleDetectedAt = System.currentTimeMillis();
                }
                hazardSuppressedUntil = System.currentTimeMillis() + 2500L;
                updateGuide(result.message.isEmpty()
                        ? ("tactile".equals(guidanceMode) ? "점자 안내 모드로 전환했습니다." : "일반 안내 모드로 전환했습니다.")
                        : result.message);
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case "open_settings":
                renderScreen(Screen.SETTINGS, false);
                updateGuide(result.message.isEmpty() ? "설정 화면입니다." : result.message);
                speak(lastGuideMessage, true);
                return;
            case "favorite":
                startNavigationFromStoredDestination(true);
                return;
            case "save_favorite":
                saveActiveDestinationAsFavorite();
                scheduleVoiceCommandFollowUp();
                return;
            case "recent_destination":
                startMostRecentDestination();
                return;
            case "start_streaming":
                updateGuide(result.message.isEmpty() ? "카메라 스트리밍은 관제에서 스트림 보기를 누르면 자동으로 시작됩니다." : result.message);
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case "stop_streaming":
                updateGuide(result.message.isEmpty() ? "카메라 스트리밍은 관제에서 종료할 수 있습니다." : result.message);
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case "listen":
                updateGuide(result.message);
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case "speak":
            default:
                if (result.message == null || result.message.trim().isEmpty()) {
                    handleVoiceCommandLocally(utterance);
                    return;
                }

                updateGuide(result.message);
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
        }
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
            case HELP:
                requestControlHelp();
                return;
            case EMERGENCY:
                requestEmergencySupport();
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
            case SAVE_FAVORITE:
                saveActiveDestinationAsFavorite();
                scheduleVoiceCommandFollowUp();
                return;
            case RECENT_DESTINATION:
                startMostRecentDestination();
                return;
            case START_STREAMING:
                updateGuide("카메라 스트리밍은 관제에서 스트림 보기를 누르면 자동으로 시작됩니다.");
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case STOP_STREAMING:
                updateGuide("카메라 스트리밍은 관제에서 종료할 수 있습니다.");
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
                return;
            case UNKNOWN:
            default:
                updateGuide("명령을 이해하지 못했습니다. 목적지로 안내해줘, 다시 안내, 주변 편의점, 위험 정보, 긴급 연락처럼 말씀해 주세요.");
                speak(lastGuideMessage, true);
                scheduleVoiceCommandFollowUp();
        }
    }

    private void startMostRecentDestination() {
        List<String> recent = getRecentDestinations();

        if (recent.isEmpty()) {
            updateGuide("최근 목적지가 없습니다. 목적지를 먼저 말씀해 주세요.");
            speak(lastGuideMessage, true);
            scheduleVoiceCommandFollowUp();
            return;
        }

        updateGuide("최근 목적지 " + recent.get(0) + " 안내를 시작합니다.");
        speak(lastGuideMessage, true);
        startNavigation(recent.get(0));
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
                startWakeListening();
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

    private List<Detection> getLastDetectionSnapshot() {
        return new ArrayList<>(lastDetectionsSnapshot);
    }

    private void requestControlHelp() {
        String message = "사용자가 관제 도움을 요청했습니다. 보행로 장애물로 인한 이동 불가능 상황이 의심됩니다.";
        ServerClient.postSupportRequest(
                getServerUrl(),
                "help",
                DEVICE_ID,
                currentLat,
                currentLng,
                currentHeading,
                guidanceMode,
                lastSceneDescription,
                getLastDetectionSnapshot(),
                message,
                result -> runOnUiThread(() -> setServerStatus(result))
        );

        updateGuide("관제에 도움을 요청했습니다. 장면을 확인 중이니 잠시만 기다려 주세요. 주변 인력 출동이 필요한지 확인하겠습니다.");
        speak(lastGuideMessage, true);
        scheduleVoiceCommandFollowUp();
    }

    private void requestEmergencySupport() {
        String message = "사용자 긴급 상황 발생! 부상 또는 사고가 의심됩니다. 119 지원이 필요할 수 있습니다.";
        ServerClient.postSupportRequest(
                getServerUrl(),
                "emergency",
                DEVICE_ID,
                currentLat,
                currentLng,
                currentHeading,
                guidanceMode,
                lastSceneDescription,
                getLastDetectionSnapshot(),
                message,
                result -> runOnUiThread(() -> setServerStatus(result))
        );

        updateGuide("긴급 상황이 접수되었습니다. 관제 센터에서 즉시 119에 구조를 요청하겠습니다. 안전한 곳에서 잠시만 기다려 주세요.");
        speak(lastGuideMessage, true);
        scheduleVoiceCommandFollowUp();
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
            scaled.compress(Bitmap.CompressFormat.JPEG, 35, outputStream);
            streamWebSocket.send(ByteString.of(outputStream.toByteArray()));
        } catch (Exception error) {
            runOnUiThread(() -> setServerStatus("스트리밍 프레임 전송 실패: " + error.getMessage()));
        }
    }

    private void analyzeImage(ImageProxy imageProxy) {
        try {
            long now = System.currentTimeMillis();
            boolean shouldStream = developerStreamingEnabled
                    && streamWebSocket != null
                    && now - lastStreamedAt >= STREAM_FRAME_INTERVAL_MS;
            boolean shouldAnalyze = now - lastAnalysisAt >= ANALYSIS_INTERVAL_MS;

            if (!shouldStream && !shouldAnalyze) {
                return;
            }

            Bitmap bitmap = ImageUtils.imageProxyToBitmap(imageProxy);
            Bitmap rotatedBitmap = ImageUtils.rotateBitmap(bitmap, imageProxy.getImageInfo().getRotationDegrees());

            if (shouldStream) {
                lastStreamedAt = now;
                maybeSendStreamingFrame(rotatedBitmap);
            }

            if (!shouldAnalyze) {
                return;
            }

            lastAnalysisAt = now;
            List<Detection> detections = detector.detect(rotatedBitmap);
            List<Detection> riskDetections = filterRiskDetections(detections);
            List<Detection> tactileRiskDetections = filterTactileRiskDetections(detections);
            List<Detection> brailleBlocks = filterBrailleBlocks(detections);
            String detectionSummary = buildDetectionSummary(detections);

            runOnUiThread(() -> {
                if (detectionText != null) {
                    detectionText.setText(detectionSummary);
                }

                updateAutomaticGuidanceMode(brailleBlocks, now);

                boolean tactileModeActive = navigationSessionActive && "tactile".equals(guidanceMode);
                List<Detection> activeRiskDetections = tactileModeActive ? tactileRiskDetections : riskDetections;
                Detection tactileObstacle = findObstacleOnBrailleBlock(brailleBlocks, tactileRiskDetections);
                maybePostTactileDebug(brailleBlocks, tactileRiskDetections, tactileObstacle);

                if (!activeRiskDetections.isEmpty()) {
                    Detection primary = activeRiskDetections.get(0);
                    String guideMessage = buildGuideMessage(primary);
                    maybePostDetection(activeRiskDetections, guideMessage);

                    if (tactileModeActive) {
                        if (tactileObstacle != null) {
                            speakHazardWithoutDialog(tactileObstacle, buildTactileGuideMessage(tactileObstacle));
                        }
                    } else {
                        speakGeneralHazards(activeRiskDetections);
                    }
                }

                if (navigationSessionActive && now - lastSceneGuidanceAt >= SCENE_GUIDANCE_INTERVAL_MS) {
                    lastSceneGuidanceAt = now;

                    if (tactileModeActive) {
                        if (tactileObstacle != null) {
                            requestSceneGuidance(detections);
                        }
                    } else {
                        requestSceneGuidance(detections);
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

    private List<Detection> filterTactileRiskDetections(List<Detection> detections) {
        List<Detection> filtered = new ArrayList<>();
        float confidenceThreshold = Math.min(getDetectionThreshold(), TACTILE_OBSTACLE_SCORE_THRESHOLD);

        for (Detection detection : detections) {
            if (detection.confidence >= confidenceThreshold
                    && riskLabels.contains(detection.label)
                    && detection.centerX > 0.08f
                    && detection.centerX < 0.92f) {
                filtered.add(detection);
            }
        }

        return filtered;
    }

    private void updateAutomaticGuidanceMode(List<Detection> brailleBlocks, long now) {
        if (!navigationSessionActive) {
            resetBrailleAutoState();
            return;
        }

        if (!brailleBlocks.isEmpty()) {
            brailleDetectionStreak += 1;
            lastBrailleDetectedAt = now;

            if (!"tactile".equals(guidanceMode) && brailleDetectionStreak >= BRAILLE_STABLE_DETECTION_COUNT) {
                switchGuidanceModeAutomatically(
                        "tactile",
                        "점자블록이 탐지되었습니다. 스마트폰을 아래쪽으로 향해 주세요. 점자블록 위 장애물을 안내하겠습니다."
                );
            }

            return;
        }

        brailleDetectionStreak = 0;

        if ("tactile".equals(guidanceMode)
                && lastBrailleDetectedAt > 0L
                && now - lastBrailleDetectedAt >= BRAILLE_LOST_TIMEOUT_MS) {
            switchGuidanceModeAutomatically(
                    "general",
                    "점자블록이 보이지 않습니다. 스마트폰을 정면으로 들어 주세요. 일반 전방 안내로 전환합니다."
            );
        }
    }

    private void switchGuidanceModeAutomatically(String mode, String message) {
        guidanceMode = mode;
        lastHazardKey = "";
        lastGeneralHazardSpokenAt = 0L;
        lastTactileDebugPostedAt = 0L;
        lastSpokenAt = System.currentTimeMillis();
        hazardSuppressedUntil = lastSpokenAt + 2500L;
        updateGuide(message);
        speak(message, true);
    }

    private void resetBrailleAutoState() {
        brailleDetectionStreak = 0;
        lastBrailleDetectedAt = 0L;
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

    private void speakGeneralHazards(List<Detection> riskDetections) {
        long now = System.currentTimeMillis();

        if (now < hazardSuppressedUntil) {
            return;
        }

        if (now - lastGeneralHazardSpokenAt < GENERAL_HAZARD_SPEAK_INTERVAL_MS) {
            return;
        }

        List<Detection> selected = selectGeneralHazards(riskDetections);

        if (selected.isEmpty()) {
            return;
        }

        String message = buildGeneralHazardMessage(selected);
        lastGeneralHazardSpokenAt = now;
        lastSpokenAt = now;
        vibrateHazard();
        updateGuide(message);
        speak(message, true);
    }

    private List<Detection> selectGeneralHazards(List<Detection> riskDetections) {
        List<Detection> selected = new ArrayList<>();
        Set<String> usedLabels = new HashSet<>();

        for (Detection detection : riskDetections) {
            if (detection.area() < GENERAL_HAZARD_MIN_AREA) {
                continue;
            }

            if (usedLabels.contains(detection.label)) {
                continue;
            }

            selected.add(detection);
            usedLabels.add(detection.label);

            if (selected.size() >= 2) {
                return selected;
            }
        }

        if (selected.isEmpty() && !riskDetections.isEmpty()) {
            selected.add(riskDetections.get(0));
        }

        return selected;
    }

    private String buildGeneralHazardMessage(List<Detection> selected) {
        if (selected.size() == 1) {
            return buildGuideMessage(selected.get(0));
        }

        StringBuilder builder = new StringBuilder("전방에 ");

        for (int index = 0; index < selected.size(); index += 1) {
            if (index > 0) {
                builder.append(", ");
            }

            builder.append(toKoreanLabel(selected.get(index).label));
        }

        builder.append("가 보입니다. 주의하세요.");
        return builder.toString();
    }

    private void requestSceneGuidance(List<Detection> detections) {
        ServerClient.requestSceneGuidance(
                getServerUrl(),
                DEVICE_ID,
                guidanceMode,
                currentLat,
                currentLng,
                detections,
                message -> runOnUiThread(() -> {
                    if (message == null || message.trim().isEmpty()) {
                        return;
                    }

                    updateGuide(message.trim());
                    speakThrottled(lastGuideMessage);
                })
        );
    }

    private boolean hasDetectedBrailleBlock(List<Detection> detections) {
        for (Detection detection : detections) {
            if ("braille_block".equals(detection.label) && detection.confidence >= BRAILLE_DETECTION_THRESHOLD) {
                return true;
            }
        }

        return false;
    }

    private List<Detection> filterBrailleBlocks(List<Detection> detections) {
        List<Detection> brailleBlocks = new ArrayList<>();

        for (Detection detection : detections) {
            if ("braille_block".equals(detection.label) && detection.confidence >= BRAILLE_DETECTION_THRESHOLD) {
                brailleBlocks.add(detection);
            }
        }

        return brailleBlocks;
    }

    private Detection findObstacleOnBrailleBlock(List<Detection> brailleBlocks, List<Detection> riskDetections) {
        if (brailleBlocks.isEmpty()) {
            return null;
        }

        for (Detection obstacle : riskDetections) {
            if (!isTactileObstacleLabel(obstacle.label)) {
                continue;
            }

            for (Detection brailleBlock : brailleBlocks) {
                if (isTactileBlockingCandidate(obstacle, brailleBlock)) {
                    return obstacle;
                }
            }
        }

        return null;
    }

    private boolean isTactileBlockingCandidate(Detection obstacle, Detection brailleBlock) {
        if (calculateIou(obstacle, brailleBlock) >= TACTILE_IOU_THRESHOLD) {
            return true;
        }

        if (isObstacleBottomCenterInsideExpandedBraille(obstacle, brailleBlock)) {
            return true;
        }

        return calculateExpandedObstacleOverlap(obstacle, brailleBlock) >= TACTILE_EXPANDED_OBSTACLE_OVERLAP_THRESHOLD;
    }

    private float calculateMaxTactileIou(List<Detection> brailleBlocks, List<Detection> riskDetections) {
        float maxIou = 0.0f;

        for (Detection obstacle : riskDetections) {
            if (!isTactileObstacleLabel(obstacle.label)) {
                continue;
            }

            for (Detection brailleBlock : brailleBlocks) {
                maxIou = Math.max(maxIou, calculateIou(obstacle, brailleBlock));
            }
        }

        return maxIou;
    }

    private float calculateMaxExpandedObstacleOverlap(List<Detection> brailleBlocks, List<Detection> riskDetections) {
        float maxOverlap = 0.0f;

        for (Detection obstacle : riskDetections) {
            if (!isTactileObstacleLabel(obstacle.label)) {
                continue;
            }

            for (Detection brailleBlock : brailleBlocks) {
                maxOverlap = Math.max(maxOverlap, calculateExpandedObstacleOverlap(obstacle, brailleBlock));
            }
        }

        return maxOverlap;
    }

    private boolean isObstacleBottomCenterInsideExpandedBraille(Detection obstacle, Detection brailleBlock) {
        float[] box = expandedBrailleBox(brailleBlock);
        float bottomCenterX = obstacle.centerX;
        float bottomCenterY = clamp(obstacle.centerY + obstacle.height / 2.0f, 0.0f, 1.0f);

        return bottomCenterX >= box[0]
                && bottomCenterX <= box[2]
                && bottomCenterY >= box[1]
                && bottomCenterY <= box[3];
    }

    private float calculateExpandedObstacleOverlap(Detection obstacle, Detection brailleBlock) {
        float[] box = expandedBrailleBox(brailleBlock);
        float obstacleLeft = obstacle.centerX - obstacle.width / 2.0f;
        float obstacleTop = obstacle.centerY - obstacle.height / 2.0f;
        float obstacleRight = obstacle.centerX + obstacle.width / 2.0f;
        float obstacleBottom = obstacle.centerY + obstacle.height / 2.0f;

        float intersectionLeft = Math.max(obstacleLeft, box[0]);
        float intersectionTop = Math.max(obstacleTop, box[1]);
        float intersectionRight = Math.min(obstacleRight, box[2]);
        float intersectionBottom = Math.min(obstacleBottom, box[3]);
        float intersectionWidth = Math.max(0.0f, intersectionRight - intersectionLeft);
        float intersectionHeight = Math.max(0.0f, intersectionBottom - intersectionTop);
        float intersectionArea = intersectionWidth * intersectionHeight;

        if (obstacle.area() <= 0.0f) {
            return 0.0f;
        }

        return intersectionArea / obstacle.area();
    }

    private float[] expandedBrailleBox(Detection brailleBlock) {
        float halfWidth = Math.max(
                brailleBlock.width * (0.5f + TACTILE_EXPAND_X_RATIO),
                TACTILE_MIN_EXPANDED_HALF_WIDTH
        );
        float halfHeight = Math.max(
                brailleBlock.height * (0.5f + TACTILE_EXPAND_Y_RATIO),
                TACTILE_MIN_EXPANDED_HALF_HEIGHT
        );

        return new float[]{
                clamp(brailleBlock.centerX - halfWidth, 0.0f, 1.0f),
                clamp(brailleBlock.centerY - halfHeight, 0.0f, 1.0f),
                clamp(brailleBlock.centerX + halfWidth, 0.0f, 1.0f),
                clamp(brailleBlock.centerY + halfHeight, 0.0f, 1.0f)
        };
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private boolean isTactileObstacleLabel(String label) {
        return "person".equals(label)
                || "bicycle".equals(label)
                || "kickboard".equals(label)
                || "motorcycle".equals(label)
                || "car".equals(label)
                || "bus".equals(label)
                || "truck".equals(label)
                || "bench".equals(label)
                || "chair".equals(label)
                || "backpack".equals(label)
                || "handbag".equals(label)
                || "suitcase".equals(label)
                || "umbrella".equals(label)
                || "skateboard".equals(label)
                || "potted plant".equals(label);
    }

    private float calculateIou(Detection a, Detection b) {
        float aLeft = a.centerX - a.width / 2.0f;
        float aTop = a.centerY - a.height / 2.0f;
        float aRight = a.centerX + a.width / 2.0f;
        float aBottom = a.centerY + a.height / 2.0f;
        float bLeft = b.centerX - b.width / 2.0f;
        float bTop = b.centerY - b.height / 2.0f;
        float bRight = b.centerX + b.width / 2.0f;
        float bBottom = b.centerY + b.height / 2.0f;

        float intersectionLeft = Math.max(aLeft, bLeft);
        float intersectionTop = Math.max(aTop, bTop);
        float intersectionRight = Math.min(aRight, bRight);
        float intersectionBottom = Math.min(aBottom, bBottom);
        float intersectionWidth = Math.max(0.0f, intersectionRight - intersectionLeft);
        float intersectionHeight = Math.max(0.0f, intersectionBottom - intersectionTop);
        float intersectionArea = intersectionWidth * intersectionHeight;
        float unionArea = a.area() + b.area() - intersectionArea;

        if (unionArea <= 0.0f) {
            return 0.0f;
        }

        return intersectionArea / unionArea;
    }

    private String buildTactileGuideMessage(Detection primary) {
        return "점자블록 위 또는 근처에 "
                + toKoreanLabel(primary.label)
                + "로 보이는 장애물이 있습니다. 주의하세요.";
    }

    private void speakHazardWithoutDialog(Detection detection, String message) {
        String key = detection.label
                + ":"
                + toDirection(detection.centerX)
                + ":"
                + toDistanceLevel(detection.area())
                + ":"
                + guidanceMode;
        long now = System.currentTimeMillis();

        if (now < hazardSuppressedUntil) {
            return;
        }

        if (key.equals(lastHazardKey) && now - lastHazardAlertAt < HAZARD_ALERT_COOLDOWN_MS) {
            return;
        }

        lastHazardKey = key;
        lastHazardAlertAt = now;
        lastSpokenAt = now;
        vibrateHazard();
        updateGuide(message);
        speak(message, true);
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
        lastSpokenAt = now;

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
            requestEmergencySupport();
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

    private void maybePostTactileDebug(
            List<Detection> brailleBlocks,
            List<Detection> riskDetections,
            Detection tactileObstacle
    ) {
        long now = System.currentTimeMillis();

        if (now - lastTactileDebugPostedAt < TACTILE_DEBUG_POST_INTERVAL_MS) {
            return;
        }

        boolean tactileModeActive = navigationSessionActive && "tactile".equals(guidanceMode);

        if (!tactileModeActive && brailleBlocks.isEmpty()) {
            return;
        }

        float maxIou = calculateMaxTactileIou(brailleBlocks, riskDetections);
        float maxExpandedOverlap = calculateMaxExpandedObstacleOverlap(brailleBlocks, riskDetections);
        String obstacleText = tactileObstacle == null ? "장애물 없음" : toKoreanLabel(tactileObstacle.label);
        String message;

        if (brailleBlocks.isEmpty()) {
            message = "점자블록 디버그: 점자블록 미감지";
        } else {
            message = "점자블록 디버그: 점자블록 "
                    + brailleBlocks.size()
                    + "개, 최대 IoU "
                    + String.format(Locale.KOREA, "%.2f", maxIou)
                    + ", 확장겹침 "
                    + String.format(Locale.KOREA, "%.2f", maxExpandedOverlap)
                    + ", "
                    + obstacleText;
        }

        List<Detection> payload = new ArrayList<>();

        if (!brailleBlocks.isEmpty()) {
            payload.add(brailleBlocks.get(0));
        }

        if (tactileObstacle != null) {
            payload.add(tactileObstacle);
        } else if (!riskDetections.isEmpty()) {
            payload.add(riskDetections.get(0));
        }

        lastTactileDebugPostedAt = now;
        ServerClient.postDetection(
                getServerUrl(),
                DEVICE_ID,
                currentLat,
                currentLng,
                currentHeading,
                payload,
                message,
                result -> runOnUiThread(() -> setServerStatus(result))
        );
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            scheduleWakeListening(500L);
        }
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

        if (flush) {
            stopListeningImmediately();
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

        if (speechRecognizer != null) {
            try {
                speechRecognizer.cancel();
            } catch (Exception ignored) {
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
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED && navigationSessionActive) {
                startCamera();
                startWakeListening();
            } else if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO_PERMISSION);
            } else if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startWakeListening();
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
                    startWakeListening();
                }
            } else {
                pendingVoiceCommandAfterPermission = false;
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

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
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

        if ("kickboard".equals(label)) {
            return "킥보드";
        }

        if ("red_light".equals(label)) {
            return "신호";
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
        scrollView.setBackgroundColor(currentScreen == Screen.HOME ? COLOR_NUNI_BLACK : COLOR_BACKGROUND);
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

    private View makeNuniCorner(boolean top, boolean left) {
        FrameLayout corner = new FrameLayout(this);
        int size = dp(40);
        int color = Color.argb(64, 255, 215, 0);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(size, size);
        params.gravity = (top ? Gravity.TOP : Gravity.BOTTOM) | (left ? Gravity.LEFT : Gravity.RIGHT);
        corner.setLayoutParams(params);

        View horizontal = new View(this);
        horizontal.setBackgroundColor(color);
        FrameLayout.LayoutParams horizontalParams = new FrameLayout.LayoutParams(size, dp(1));
        horizontalParams.gravity = top ? Gravity.TOP : Gravity.BOTTOM;
        corner.addView(horizontal, horizontalParams);

        View vertical = new View(this);
        vertical.setBackgroundColor(color);
        FrameLayout.LayoutParams verticalParams = new FrameLayout.LayoutParams(dp(1), size);
        verticalParams.gravity = left ? Gravity.LEFT : Gravity.RIGHT;
        corner.addView(vertical, verticalParams);

        return corner;
    }

    private View makeNuniStatusDot(boolean active) {
        View dot = new View(this);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(active ? COLOR_NUNI_YELLOW : Color.argb(110, 255, 215, 0));
        dot.setBackground(drawable);
        dot.setAlpha(active ? 1.0f : 0.45f);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(8), dp(8));
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(44);
        dot.setLayoutParams(params);
        return dot;
    }

    private View makeNuniStepDots() {
        LinearLayout dots = new LinearLayout(this);
        dots.setOrientation(LinearLayout.HORIZONTAL);
        dots.setGravity(Gravity.CENTER);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.bottomMargin = dp(52);
        dots.setLayoutParams(params);

        for (int index = 0; index < 3; index += 1) {
            View dot = new View(this);
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.RECTANGLE);
            drawable.setCornerRadius(dp(2));
            drawable.setColor(index == 0 ? COLOR_NUNI_YELLOW : Color.argb(64, 255, 215, 0));
            dot.setBackground(drawable);

            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(index == 0 ? dp(24) : dp(8), dp(4));
            dotParams.setMargins(dp(4), 0, dp(4), 0);
            dots.addView(dot, dotParams);
        }

        return dots;
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

    private LinearLayout.LayoutParams margin(LinearLayout.LayoutParams params, int left, int top, int right, int bottom) {
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
