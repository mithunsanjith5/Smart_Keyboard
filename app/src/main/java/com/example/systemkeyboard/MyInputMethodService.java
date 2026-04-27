package com.example.systemkeyboard;

import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ViewFlipper;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;
import java.io.File;

public class MyInputMethodService extends InputMethodService {

    private boolean capsLockActive = false;
    private boolean shiftActive = false;
    private boolean shiftPressed = false;
    private long lastShiftPressTime = 0;
    private static final long DOUBLE_PRESS_THRESHOLD = 300;
    private Button shiftButton;
    private Vibrator vibrator;
    private SharedPreferences prefs;
    private ViewFlipper keyboardFlipper;
    private View keyboardView;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isBackspacePressed = false;
    private static final long BACKSPACE_REPEAT_DELAY = 100; // milliseconds
    private boolean chatbotModeEnabled = false;
    private Button chatToggleButton;
    private TextView chatStatusText;
    private TextView chatDraftText;
    private TextView chatResponseText;
    private LinearLayout chatPanel;
    private final StringBuilder chatDraft = new StringBuilder();
    private TinyLlamaChatEngine tinyLlamaChatEngine;

    @Override
    public void onCreate() {
        super.onCreate();
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        tinyLlamaChatEngine = new TinyLlamaChatEngine(this);
    }

    @Override
    public void onDestroy() {
        if (tinyLlamaChatEngine != null) {
            tinyLlamaChatEngine.close();
        }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        LayoutInflater inflater = getLayoutInflater();
        View root = inflater.inflate(R.layout.keyboard_view, null);
        keyboardView = root;
        keyboardFlipper = root.findViewById(R.id.keyboard_flipper);
        chatToggleButton = root.findViewById(R.id.chat_toggle_button);
        chatStatusText = root.findViewById(R.id.chat_status_text);
        chatDraftText = root.findViewById(R.id.chat_draft_text);
        chatResponseText = root.findViewById(R.id.chat_response_text);
        chatPanel = root.findViewById(R.id.chat_panel);
        if (chatResponseText != null) {
            chatResponseText.setOnClickListener(v -> commitChatResponseToInput());
        }
        attachButtonListeners(root);
        applyTheme(root);
        applyKeySize(root);
        updateChatUi();
        return root;
    }

    private void attachButtonListeners(View view) {
        if (view instanceof Button) {
            Button key = (Button) view;
            String tag = key.getTag() != null ? key.getTag().toString() : key.getText().toString();

            if ("CHAT_TOGGLE".equals(tag)) {
                chatToggleButton = key;
                key.setOnClickListener(v -> {
                    chatbotModeEnabled = !chatbotModeEnabled;
                    if (!chatbotModeEnabled) {
                        chatDraft.setLength(0);
                    }
                    updateChatUi();
                    playVibration();
                });
                return;
            }

            if ("SHIFT".equals(tag)) {
                shiftButton = key;
            }

            key.setOnTouchListener((v, event) -> handleKeyTouch(tag, event));
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                attachButtonListeners(group.getChildAt(i));
            }
        }
    }

    private boolean handleBackspaceTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                isBackspacePressed = true;
                performBackspaceAction();
                playVibration();
                // Schedule repeated deletions
                handler.postDelayed(backspaceRepeatRunnable, BACKSPACE_REPEAT_DELAY);
                return true;
            case MotionEvent.ACTION_UP:
                isBackspacePressed = false;
                handler.removeCallbacks(backspaceRepeatRunnable);
                return true;
            default:
                return false;
        }
    }

    private boolean handleKeyTouch(String key, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if ("BACKSPACE".equals(key)) {
                    return handleBackspaceTouch(event);
                }

                if ("SHIFT".equals(key)) {
                    handleShiftPress();
                } else {
                    handleKeyPress(key);
                    playVibration();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if ("BACKSPACE".equals(key)) {
                    return handleBackspaceTouch(event);
                }
                return true;

            default:
                return false;
        }
    }

    private Runnable backspaceRepeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (isBackspacePressed) {
                performBackspaceAction();
                handler.postDelayed(this, BACKSPACE_REPEAT_DELAY);
            }
        }
    };

    private void performBackspaceAction() {
        if (chatbotModeEnabled) {
            if (chatDraft.length() > 0) {
                chatDraft.deleteCharAt(chatDraft.length() - 1);
                updateChatUi();
            }
            return;
        }
        performBackspaceDelete();
    }

    private void performBackspaceDelete() {
        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection != null) {
            inputConnection.deleteSurroundingText(1, 0);
        }
    }

    private void handleShiftPress() {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastShiftPressTime < DOUBLE_PRESS_THRESHOLD) {
            // Double press - toggle caps lock
            capsLockActive = !capsLockActive;
            shiftActive = false;
            updateShiftVisual();
        } else {
            // Single press - toggle shift for next character
            shiftActive = !shiftActive;
            capsLockActive = false;
            updateShiftVisual();
        }
        
        lastShiftPressTime = currentTime;
        playVibration();
        updateLetterCase();
    }

    private void updateShiftVisual() {
        if (shiftButton != null) {
            if (capsLockActive) {
                shiftButton.setText("⇧\u203E"); // Arrow with underline for caps lock
            } else if (shiftActive) {
                shiftButton.setText("⇧"); // Filled arrow for single shift
            } else {
                shiftButton.setText("⇧"); // Regular arrow when off
            }
        }
    }

    private void handleKeyPress(String key) {
        switch (key) {
            case "CHAT_TOGGLE":
                chatbotModeEnabled = !chatbotModeEnabled;
                if (!chatbotModeEnabled) {
                    chatDraft.setLength(0);
                }
                updateChatUi();
                return;
            case "SPACE":
                if (chatbotModeEnabled) {
                    chatDraft.append(" ");
                    updateChatUi();
                    resetShift();
                    return;
                }
                break;
            case "ENTER":
                if (chatbotModeEnabled) {
                    submitChatPrompt();
                    resetShift();
                    return;
                }
                break;
            case "KEY_QUESTION":
                if (chatbotModeEnabled) {
                    chatDraft.append("?");
                    updateChatUi();
                    resetShift();
                    return;
                }
                break;
            case "PAGE_ABC":
                if (keyboardFlipper != null) {
                    keyboardFlipper.setDisplayedChild(0);
                }
                return;
            case "PAGE_NUM":
                if (keyboardFlipper != null) {
                    keyboardFlipper.setDisplayedChild(1);
                }
                return;
            default:
                if (chatbotModeEnabled) {
                    String chatText = key;
                    if (isLetter(key) && (shiftActive || capsLockActive)) {
                        chatText = key.toUpperCase();
                    } else if (isLetter(key)) {
                        chatText = key.toLowerCase();
                    }
                    chatDraft.append(chatText);
                    updateChatUi();
                    resetShift();
                    return;
                }
                break;
        }

        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            return;
        }

        switch (key) {
            case "SPACE":
                inputConnection.commitText(" ", 1);
                resetShift();
                break;
            case "ENTER":
                inputConnection.commitText("\n", 1);
                resetShift();
                break;
            case "KEY_QUESTION":
                inputConnection.commitText("?", 1);
                resetShift();
                break;
            default:
                // Apply case transformation if letter
                String textToCommit = key;
                if (isLetter(key) && (shiftActive || capsLockActive)) {
                    textToCommit = key.toUpperCase();
                } else if (isLetter(key)) {
                    textToCommit = key.toLowerCase();
                }
                inputConnection.commitText(textToCommit, 1);
                resetShift();
                break;
        }

    }

    private void submitChatPrompt() {
        String prompt = chatDraft.toString().trim();
        if (prompt.isEmpty()) {
            return;
        }

        String modelPath = prefs.getString("tiny_llm_model_path", "");
        if (modelPath == null || modelPath.trim().isEmpty()) {
            chatResponseText.setText(getString(R.string.chat_model_missing));
            return;
        }

        String trimmedModelPath = normalizeModelPath(modelPath);
        File modelFile = resolveModelFile(trimmedModelPath);
        if (!modelFile.exists()) {
            chatResponseText.setText(getString(R.string.chat_model_not_found, trimmedModelPath));
            return;
        }

        chatResponseText.setText(getString(R.string.chat_loading_model));
        chatDraft.setLength(0);
        updateChatUi();

        tinyLlamaChatEngine.generateReply(trimmedModelPath, prompt, prefs.getString("tiny_llm_system_prompt", "You are a concise offline keyboard assistant."), new TinyLlamaChatEngine.ChatCallback() {
            @Override
            public void onSuccess(String reply) {
                handler.post(() -> {
                    if (chatResponseText != null) {
                        chatResponseText.setText(reply);
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                handler.post(() -> {
                    if (chatResponseText != null) {
                        chatResponseText.setText(errorMessage);
                    }
                });
            }
        });
    }

    private String normalizeModelPath(String rawPath) {
        String normalized = rawPath.trim();
        if (normalized.startsWith("file://")) {
            normalized = normalized.substring("file://".length());
        } else if (normalized.startsWith("file:")) {
            normalized = normalized.substring("file:".length());
        }

        while (normalized.startsWith(":")) {
            normalized = normalized.substring(1);
        }

        return normalized.trim();
    }

    private File resolveModelFile(String modelPath) {
        File directFile = new File(modelPath);
        if (directFile.exists()) {
            return directFile;
        }

        String lowerDownload = modelPath.replace("/download/", "/Download/");
        File downloadFile = new File(lowerDownload);
        if (downloadFile.exists()) {
            return downloadFile;
        }

        if (!modelPath.endsWith(".gguf")) {
            File ggufFile = new File(modelPath + ".gguf");
            if (ggufFile.exists()) {
                return ggufFile;
            }
        }

        return directFile;
    }

    private void commitChatResponseToInput() {
        if (chatResponseText == null) {
            return;
        }

        String reply = chatResponseText.getText().toString().trim();
        if (reply.isEmpty() || isNonReplyText(reply)) {
            return;
        }

        InputConnection inputConnection = getCurrentInputConnection();
        if (inputConnection == null) {
            return;
        }

        inputConnection.commitText(reply, 1);
        chatbotModeEnabled = false;
        chatDraft.setLength(0);
        updateChatUi();
        playVibration();
    }

    private boolean isNonReplyText(String text) {
        return text.equals(getString(R.string.chat_response_default))
                || text.equals(getString(R.string.chat_thinking))
                || text.equals(getString(R.string.chat_loading_model))
                || text.equals(getString(R.string.chat_model_missing))
                || text.equals(getString(R.string.chat_model_error))
                || text.equals(getString(R.string.chat_chatting_error))
                || text.startsWith(getString(R.string.chat_model_not_found, ""));
    }

    private void updateChatUi() {
        if (chatToggleButton != null) {
            chatToggleButton.setText(chatbotModeEnabled
                    ? getString(R.string.chat_mode_on)
                    : getString(R.string.chat_mode_off));
        }

        if (chatStatusText != null) {
            chatStatusText.setText(chatbotModeEnabled
                    ? getString(R.string.chat_status_chatbot)
                    : getString(R.string.chat_status_keyboard));
        }

        if (chatPanel != null) {
            chatPanel.setVisibility(chatbotModeEnabled ? View.VISIBLE : View.GONE);
        }

        if (chatDraftText != null) {
            chatDraftText.setText(chatDraft.length() > 0
                    ? chatDraft.toString()
                    : getString(R.string.chat_draft_default));
        }

        if (chatResponseText != null && chatResponseText.getText().length() == 0) {
            chatResponseText.setText(getString(R.string.chat_response_default));
        }
    }

    private void resetShift() {
        if (!capsLockActive && shiftActive) {
            shiftActive = false;
            updateLetterCase();
        }
    }

    private void updateLetterCase() {
        if (keyboardView == null) return;
        updateButtonCases(keyboardView);
    }

    private void updateButtonCases(View view) {
        if (view instanceof Button) {
            Button btn = (Button) view;
            String tag = btn.getTag() != null ? btn.getTag().toString() : "";
            
            // Update letter buttons based on shift/caps state
            if (isLetter(tag)) {
                String text = btn.getText().toString();
                if (Character.isLetter(text.charAt(0))) {
                    if (shiftActive || capsLockActive) {
                        btn.setText(text.toUpperCase());
                    } else {
                        btn.setText(text.toLowerCase());
                    }
                }
            }
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                updateButtonCases(group.getChildAt(i));
            }
        }
    }

    private boolean isLetter(String key) {
        return key.length() == 1 && Character.isLetter(key.charAt(0));
    }

    private void applyTheme(View root) {
        boolean isDarkMode = prefs.getBoolean("dark_theme", true);
        int bgColor = isDarkMode ? 0xFF0A0A0A : 0xFFFFFFFF;
        int textColor = isDarkMode ? 0xFFFFFFFF : 0xFF000000;
        int buttonBgColor = isDarkMode ? 0xFF2D2D2D : 0xFFE0E0E0;
        root.setBackgroundColor(bgColor);

        setButtonTheme(root, bgColor, textColor, buttonBgColor);
    }

    private void setButtonTheme(View view, int bgColor, int textColor, int buttonBgColor) {
        if (view instanceof Button) {
            Button btn = (Button) view;
            btn.setTextColor(textColor);
            // Apply rounded drawable background
            int drawableId = bgColor == 0xFF0A0A0A ? R.drawable.rounded_button : R.drawable.rounded_button_light;
            btn.setBackground(ContextCompat.getDrawable(this, drawableId));
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            group.setBackgroundColor(bgColor);
            for (int i = 0; i < group.getChildCount(); i++) {
                setButtonTheme(group.getChildAt(i), bgColor, textColor, buttonBgColor);
            }
        }
    }

    private void applyKeySize(View root) {
        String keySize = prefs.getString("key_size", "medium");
        int height = 48;

        switch (keySize) {
            case "small":
                height = 36;
                break;
            case "large":
                height = 60;
                break;
            case "medium":
            default:
                height = 48;
                break;
        }

        setButtonSize(root, height);
    }

    private void setButtonSize(View view, int heightDp) {
        if (view instanceof Button) {
            Button btn = (Button) view;
            ViewGroup.LayoutParams params = btn.getLayoutParams();
            if (params != null) {
                params.height = dpToPx(heightDp);
                btn.setLayoutParams(params);
            }
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                setButtonSize(group.getChildAt(i), heightDp);
            }
        }
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    private void playVibration() {
        if (!prefs.getBoolean("haptic_feedback", true) || vibrator == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
        } else {
            vibrator.vibrate(30);
        }
    }
}
