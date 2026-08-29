package ai.openjarvis.quest;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final String PREFS = "openjarvis_prefs";
    private EditText endpointInput, modelInput, apiKeyInput, promptInput;
    private TextView chatView, statusView;
    private Button speakButton, sendButton;
    private SpeechRecognizer speechRecognizer;
    private TextToSpeech tts;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        hideSystemUi();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bindViews();
        loadSettings();
        setupTts();
        setupSpeechRecognizer();

        sendButton.setOnClickListener(v -> sendPrompt());
        speakButton.setOnClickListener(v -> startListening());
        findViewById(R.id.saveButton).setOnClickListener(v -> saveSettings());
    }

    private void bindViews() {
        endpointInput = findViewById(R.id.endpointInput);
        modelInput = findViewById(R.id.modelInput);
        apiKeyInput = findViewById(R.id.apiKeyInput);
        promptInput = findViewById(R.id.promptInput);
        chatView = findViewById(R.id.chatView);
        statusView = findViewById(R.id.statusView);
        speakButton = findViewById(R.id.speakButton);
        sendButton = findViewById(R.id.sendButton);
    }

    private void loadSettings() {
        endpointInput.setText(prefs.getString("endpoint", "https://api.openai.com/v1/chat/completions"));
        modelInput.setText(prefs.getString("model", "gpt-4.1-mini"));
        apiKeyInput.setText(prefs.getString("apiKey", ""));
    }

    private void saveSettings() {
        prefs.edit()
                .putString("endpoint", endpointInput.getText().toString().trim())
                .putString("model", modelInput.getText().toString().trim())
                .putString("apiKey", apiKeyInput.getText().toString())
                .apply();
        statusView.setText("Settings saved");
    }

    private void setupTts() {
        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) tts.setLanguage(Locale.US);
        });
    }

    private void setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speakButton.setEnabled(false);
            statusView.setText("Speech recognition is unavailable");
            return;
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { statusView.setText("Listening…"); }
            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { statusView.setText("Thinking…"); }
            @Override public void onError(int error) { statusView.setText("Speech error: " + error); }
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    promptInput.setText(matches.get(0));
                    sendPrompt();
                }
            }
            @Override public void onPartialResults(Bundle partialResults) { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        speechRecognizer.startListening(intent);
    }

    private void sendPrompt() {
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) return;
        appendChat("You", prompt);
        promptInput.setText("");
        sendButton.setEnabled(false);
        speakButton.setEnabled(false);
        statusView.setText("Thinking…");

        final String endpoint = endpointInput.getText().toString().trim();
        final String model = modelInput.getText().toString().trim();
        final String apiKey = apiKeyInput.getText().toString();

        executor.execute(() -> {
            try {
                String response = callChatApi(endpoint, model, apiKey, prompt);
                runOnUiThread(() -> {
                    appendChat("Jarvis", response);
                    statusView.setText("Ready");
                    speakButton.setEnabled(true);
                    sendButton.setEnabled(true);
                    if (tts != null) tts.speak(response, TextToSpeech.QUEUE_FLUSH, null, "openjarvis");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    appendChat("Error", e.getMessage() == null ? e.toString() : e.getMessage());
                    statusView.setText("Error");
                    speakButton.setEnabled(true);
                    sendButton.setEnabled(true);
                });
            }
        });
    }

    private String callChatApi(String endpoint, String model, String apiKey, String prompt) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        if (!apiKey.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + apiKey);

        JSONObject body = new JSONObject();
        body.put("model", model);
        JSONArray messages = new JSONArray();
        JSONObject system = new JSONObject();
        system.put("role", "system");
        system.put("content", "You are OpenJarvis, a helpful personal AI assistant running on a Meta Quest 3. Be concise, practical, and voice-friendly.");
        messages.put(system);
        JSONObject user = new JSONObject();
        user.put("role", "user");
        user.put("content", prompt);
        messages.put(user);
        body.put("messages", messages);
        body.put("temperature", 0.7);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = connection.getOutputStream()) { os.write(bytes); }

        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder raw = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) raw.append(line);
        connection.disconnect();

        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + raw);
        JSONObject json = new JSONObject(raw.toString());
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("API returned no choices");
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new Exception("API returned no message");
        return message.optString("content", "(empty response)");
    }

    private void appendChat(String speaker, String text) {
        chatView.append(speaker + ":\n" + text + "\n\n");
        ScrollView scroll = findViewById(R.id.chatScroll);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void hideSystemUi() {
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (speechRecognizer != null) speechRecognizer.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        executor.shutdownNow();
    }
}
