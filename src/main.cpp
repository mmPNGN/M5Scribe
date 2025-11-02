/**
 * Project M5Scribe - Bluetooth Audio Streaming
 *
 * M5Stack Core2からAndroidへリアルタイム音声ストリーミング
 *
 * 使い方:
 * 1. M5Stackの電源を入れる
 * 2. Androidアプリで「Scan for M5Stack」をタップ
 * 3. 「M5Stack-M5Scribe」を選択して接続
 * 4. 自動的に音声ストリーミング開始
 */

#include <M5Core2.h>
#include <driver/i2s.h>
#include <BluetoothSerial.h>

// I2Sピン設定
#define CONFIG_I2S_BCK_PIN     12
#define CONFIG_I2S_LRCK_PIN    0
#define CONFIG_I2S_DATA_PIN    2
#define CONFIG_I2S_DATA_IN_PIN 34

#define Speak_I2S_NUMBER I2S_NUM_0

// 音声設定
#define SAMPLE_RATE       16000  // 16kHz（帯域削減）
#define DATA_SIZE         2048   // バッファサイズを大きく（安定性向上）

// Bluetooth
BluetoothSerial SerialBT;
bool btConnected = false;
bool btDiscoverable = false;
unsigned long discoverableStartTime = 0;
const unsigned long DISCOVERABLE_DURATION = 60000; // 60秒

// バッファ
uint8_t audioBuffer[DATA_SIZE];

// UI関連
int audioLevel = 0;              // 音声レベル（0-100）
unsigned long lastAudioUpdate = 0;
float pulseAnimation = 0.0;      // パルスアニメーション用
int lastDisplayState = -1;       // 前回の表示状態（-1=初期、0=待機、1=検索中、2=接続中）
bool needsFullRedraw = true;     // 全画面再描画が必要か

// マイク初期化
bool InitMicrophone() {
    esp_err_t err = ESP_OK;

    i2s_driver_uninstall(Speak_I2S_NUMBER);

    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX | I2S_MODE_PDM),
        .sample_rate = SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_16BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_RIGHT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = 6,      // DMAバッファ数を増やす
        .dma_buf_len = 256,      // DMAバッファ長を増やす
        .use_apll = false,
        .tx_desc_auto_clear = false,
        .fixed_mclk = 0
    };

    err += i2s_driver_install(Speak_I2S_NUMBER, &i2s_config, 0, NULL);

    i2s_pin_config_t tx_pin_config;
#if (ESP_IDF_VERSION > ESP_IDF_VERSION_VAL(4, 3, 0))
    tx_pin_config.mck_io_num = I2S_PIN_NO_CHANGE;
#endif
    tx_pin_config.bck_io_num = CONFIG_I2S_BCK_PIN;
    tx_pin_config.ws_io_num = CONFIG_I2S_LRCK_PIN;
    tx_pin_config.data_out_num = CONFIG_I2S_DATA_PIN;
    tx_pin_config.data_in_num = CONFIG_I2S_DATA_IN_PIN;

    err += i2s_set_pin(Speak_I2S_NUMBER, &tx_pin_config);
    err += i2s_set_clk(Speak_I2S_NUMBER, SAMPLE_RATE, I2S_BITS_PER_SAMPLE_16BIT, I2S_CHANNEL_MONO);

    return (err == ESP_OK);
}

// 音声レベルを計算（感度を高く調整）
void calculateAudioLevel(uint8_t* buffer, size_t length) {
    int16_t* samples = (int16_t*)buffer;
    int sampleCount = length / 2;

    long sum = 0;
    int maxSample = 0;

    for (int i = 0; i < sampleCount; i++) {
        int sample = abs(samples[i]);
        sum += sample;
        if (sample > maxSample) {
            maxSample = sample;
        }
    }

    // 平均値とピーク値を組み合わせて感度を上げる
    int avg = sum / sampleCount;
    int combined = (avg * 3 + maxSample) / 4;  // 平均75%、ピーク25%

    // レンジを調整（500-3000 → 0-100）より敏感に反応
    audioLevel = map(combined, 100, 2000, 0, 100);
    audioLevel = constrain(audioLevel, 0, 100);

    // スムージング（急激な変化を抑制）
    static int lastLevel = 0;
    audioLevel = (audioLevel * 7 + lastLevel * 3) / 10;
    lastLevel = audioLevel;
}

// グラデーション描画ヘルパー
void drawGradientRect(int x, int y, int w, int h, uint16_t color1, uint16_t color2) {
    for (int i = 0; i < h; i++) {
        uint16_t color = M5.Lcd.color565(
            ((color1 >> 11) * (h - i) + (color2 >> 11) * i) / h,
            (((color1 >> 5) & 0x3F) * (h - i) + ((color2 >> 5) & 0x3F) * i) / h,
            ((color1 & 0x1F) * (h - i) + (color2 & 0x1F) * i) / h
        );
        M5.Lcd.drawFastHLine(x, y + i, w, color);
    }
}

// 音声ビジュアライザー描画（最適化版 - 点滅を最小化）
void drawAudioVisualizer(int centerX, int centerY, int radius) {
    static int lastLevelRadius = 0;
    static int lastPulse = 0;
    static int lastBarHeight = 0;

    // 音声レベルに応じた円
    int levelRadius = map(audioLevel, 0, 100, 0, radius - 10);

    // パルスアニメーション
    pulseAnimation += 0.1;
    if (pulseAnimation > 2 * PI) pulseAnimation = 0;
    int pulse = sin(pulseAnimation) * 3;

    int currentRadius = levelRadius + pulse;
    int barHeight = map(audioLevel, 0, 100, 0, 20);

    // 前回より小さくなった部分だけを黒で消す
    if (currentRadius < lastLevelRadius) {
        for (int r = currentRadius + 1; r <= lastLevelRadius; r++) {
            M5.Lcd.drawCircle(centerX, centerY, r, TFT_BLACK);
        }
    }

    // 新しい円を描画（前回より大きい部分のみ）
    int startR = max(0, lastLevelRadius - 5);  // 少し重ねて描画して隙間を防ぐ
    for (int r = startR; r <= currentRadius; r++) {
        uint16_t color = M5.Lcd.color565(
            map(r, 0, radius, 0, 100),
            map(r, 0, radius, 200, 255),
            map(r, 0, radius, 100, 200)
        );
        M5.Lcd.drawCircle(centerX, centerY, r, color);
    }

    // 音声バー（放射状）- 前回のバーを消してから新しいバーを描画
    for (int angle = 0; angle < 360; angle += 30) {
        float rad = angle * PI / 180.0;

        // 前回のバーを消す
        if (lastBarHeight > 0) {
            int x1 = centerX + cos(rad) * (radius - 15);
            int y1 = centerY + sin(rad) * (radius - 15);
            int x2 = centerX + cos(rad) * (radius - 15 + lastBarHeight);
            int y2 = centerY + sin(rad) * (radius - 15 + lastBarHeight);
            M5.Lcd.drawLine(x1, y1, x2, y2, TFT_BLACK);
        }

        // 新しいバーを描画
        if (barHeight > 0) {
            int x1 = centerX + cos(rad) * (radius - 15);
            int y1 = centerY + sin(rad) * (radius - 15);
            int x2 = centerX + cos(rad) * (radius - 15 + barHeight);
            int y2 = centerY + sin(rad) * (radius - 15 + barHeight);
            M5.Lcd.drawLine(x1, y1, x2, y2, TFT_CYAN);
        }
    }

    lastLevelRadius = currentRadius;
    lastPulse = pulse;
    lastBarHeight = barHeight;
}

// モダンなボタン描画
void drawModernButton(int x, int y, int w, int h, const char* text,
                      uint16_t color, bool pressed = false) {
    // 影効果
    if (!pressed) {
        M5.Lcd.fillRoundRect(x + 3, y + 3, w, h, 8, TFT_DARKGREY);
    }

    // ボタン本体
    M5.Lcd.fillRoundRect(x, y, w, h, 8, color);

    // ハイライト（上部）
    uint16_t highlight = M5.Lcd.color565(
        min(255, ((color >> 11) & 0x1F) * 8 + 50),
        min(255, ((color >> 5) & 0x3F) * 4 + 30),
        min(255, (color & 0x1F) * 8 + 50)
    );
    M5.Lcd.fillRoundRect(x, y, w, h / 3, 8, highlight);

    // ボーダー
    M5.Lcd.drawRoundRect(x, y, w, h, 8, TFT_WHITE);

    // テキスト
    M5.Lcd.setTextColor(TFT_WHITE);
    M5.Lcd.setTextDatum(MC_DATUM);
    M5.Lcd.drawString(text, x + w / 2, y + h / 2);
    M5.Lcd.setTextDatum(TL_DATUM);
}

// 稲妻マークを描画
void drawLightningBolt(int x, int y, uint16_t color) {
    // 稲妻の形状（小さめ）
    M5.Lcd.fillTriangle(x + 3, y, x + 6, y, x, y + 5, color);        // 上部
    M5.Lcd.fillTriangle(x, y + 5, x + 4, y + 5, x + 6, y + 10, color); // 下部
}

// ステータスバー描画
void drawStatusBar() {
    // 背景（半透明風）
    M5.Lcd.fillRect(0, 0, 320, 25, TFT_NAVY);

    // デバイス名
    M5.Lcd.setTextSize(1);
    M5.Lcd.setTextColor(TFT_CYAN);
    M5.Lcd.setCursor(8, 8);
    M5.Lcd.print("M5Scribe");

    // バッテリー情報
    float batteryLevel = M5.Axp.GetBatteryLevel();
    float batCurrent = M5.Axp.GetBatCurrent();
    bool isCharging = (batCurrent > 0);  // 電流がプラスなら充電中

    // バッテリーアイコン
    int battX = 270;
    int battY = 6;
    M5.Lcd.drawRect(battX, battY, 30, 14, TFT_WHITE);
    M5.Lcd.fillRect(battX + 30, battY + 4, 3, 6, TFT_WHITE);

    // バッテリーレベル表示
    int fillWidth = map(batteryLevel, 0, 100, 0, 26);
    uint16_t battColor;
    if (isCharging) {
        battColor = TFT_GREEN;
    } else if (batteryLevel > 50) {
        battColor = TFT_GREEN;
    } else if (batteryLevel > 20) {
        battColor = TFT_YELLOW;
    } else {
        battColor = TFT_RED;
    }
    M5.Lcd.fillRect(battX + 2, battY + 2, fillWidth, 10, battColor);

    // 充電中は稲妻マークを重ねて表示（電流が10mA以上の時のみ）
    if (isCharging && batCurrent > 10.0) {
        drawLightningBolt(battX + 10, battY + 2, TFT_YELLOW);
    }

    // パーセント表示
    M5.Lcd.setTextSize(1);
    M5.Lcd.setTextColor(TFT_WHITE);
    M5.Lcd.setCursor(battX - 25, battY + 3);
    M5.Lcd.printf("%.0f%%", batteryLevel);
}

// 画面更新
void updateDisplay() {
    static unsigned long lastUpdate = 0;
    static int animFrame = 0;
    static int lastAudioLevel = -1;
    static unsigned long lastRemainingTime = 999;
    static unsigned long lastStatusBarUpdate = 0;

    // 現在の状態を判定
    int currentState = btConnected ? 2 : (btDiscoverable ? 1 : 0);

    // 状態が変わった場合のみ全画面再描画
    if (currentState != lastDisplayState || needsFullRedraw) {
        M5.Lcd.fillScreen(TFT_BLACK);
        drawGradientRect(0, 25, 320, 100, 0x0841, 0x0020);
        drawStatusBar();
        lastDisplayState = currentState;
        needsFullRedraw = false;
        lastAudioLevel = -1;
        lastRemainingTime = 999;
        lastStatusBarUpdate = millis();

        // static変数をリセット（各画面の初期化フラグ）
        // これにより新しい状態で再度初期化される
    }

    // 部分的な更新のみ（高頻度）
    unsigned long now = millis();

    // ステータスバーを定期的に更新（5秒ごと）
    if (now - lastStatusBarUpdate > 5000) {
        drawStatusBar();
        lastStatusBarUpdate = now;
    }

    M5.Lcd.setTextDatum(MC_DATUM);

    if (btConnected) {
        // 初回のみ静的要素を描画
        if (lastAudioLevel == -1) {
            M5.Lcd.setTextSize(3);
            M5.Lcd.setTextColor(TFT_CYAN, TFT_BLACK);
            M5.Lcd.drawString("STREAMING", 160, 45);

            M5.Lcd.setTextSize(2);
            M5.Lcd.setTextColor(TFT_WHITE, TFT_BLACK);
            M5.Lcd.drawString("Level", 160, 195);

            // 停止ボタン
            M5.Lcd.setTextSize(2);
            drawModernButton(195, 185, 110, 45, "STOP", TFT_RED);

            // レベルバー背景
            M5.Lcd.fillRoundRect(60, 210, 200, 8, 4, TFT_DARKGREY);
        }

        // アニメーション更新（適度な頻度で）
        if (now - lastUpdate > 50) {
            // ビジュアライザー初期化（初回のみ背景描画）
            if (lastAudioLevel == -1) {
                M5.Lcd.fillCircle(160, 135, 55, TFT_BLACK);
                M5.Lcd.drawCircle(160, 135, 55, TFT_DARKGREY);
            }

            // 音声ビジュアライザー更新（差分のみ描画）
            drawAudioVisualizer(160, 135, 50);

            // レベルバー更新（変化があった場合のみ）
            if (audioLevel != lastAudioLevel) {
                M5.Lcd.fillRoundRect(62, 212, 196, 4, 2, TFT_DARKGREY);
                int barWidth = map(audioLevel, 0, 100, 0, 196);
                uint16_t barColor = M5.Lcd.color565(
                    map(audioLevel, 0, 100, 0, 255),
                    map(audioLevel, 0, 100, 255, 0),
                    100
                );
                M5.Lcd.fillRoundRect(62, 212, barWidth, 4, 2, barColor);
                lastAudioLevel = audioLevel;
            }

            lastUpdate = now;
        }

    } else if (btDiscoverable) {
        // 初回のみ静的要素を描画
        if (lastAudioLevel == -1) {  // 状態変更直後
            M5.Lcd.setTextSize(3);
            M5.Lcd.setTextColor(TFT_YELLOW, TFT_BLACK);
            M5.Lcd.drawString("SEARCHING", 160, 50);

            M5.Lcd.setTextSize(2);
            M5.Lcd.setTextColor(TFT_WHITE, TFT_BLACK);
            M5.Lcd.drawString("Waiting for", 160, 160);
            M5.Lcd.drawString("Android device...", 160, 180);
            lastAudioLevel = 0;  // 初期化完了マーク
        }

        // アニメーション更新
        if (now - lastUpdate > 50) {
            // 回転するサークルエリアのみクリア
            M5.Lcd.fillCircle(160, 120, 50, TFT_BLACK);

            animFrame = (animFrame + 5) % 360;
            for (int i = 0; i < 8; i++) {
                float angle = (animFrame + i * 45) * PI / 180.0;
                int x = 160 + cos(angle) * 40;
                int y = 120 + sin(angle) * 40;
                int size = 8 - i;
                M5.Lcd.fillCircle(x, y, size, M5.Lcd.color565(255 - i * 30, 255 - i * 30, 0));
            }

            // 残り時間更新
            unsigned long remaining = (DISCOVERABLE_DURATION - (millis() - discoverableStartTime)) / 1000;
            if (remaining != lastRemainingTime) {
                M5.Lcd.fillRect(130, 195, 100, 30, TFT_BLACK);
                M5.Lcd.setTextSize(3);
                M5.Lcd.setTextColor(TFT_YELLOW, TFT_BLACK);
                M5.Lcd.drawString(String(remaining) + "s", 160, 205);
                lastRemainingTime = remaining;
            }

            lastUpdate = now;
        }

    } else {
        // 待機画面（静的なので一度だけ描画）
        if (lastAudioLevel == -1) {  // 状態変更直後
            M5.Lcd.setTextSize(3);
            M5.Lcd.setTextColor(TFT_CYAN, TFT_BLACK);
            M5.Lcd.drawString("READY", 160, 50);

            // マイクアイコン
            M5.Lcd.fillRoundRect(140, 90, 40, 60, 20, TFT_CYAN);
            M5.Lcd.fillRect(155, 150, 10, 20, TFT_CYAN);
            M5.Lcd.fillRoundRect(130, 165, 60, 10, 5, TFT_CYAN);

            M5.Lcd.setTextSize(2);
            M5.Lcd.setTextColor(TFT_WHITE, TFT_BLACK);
            M5.Lcd.drawString("Tap to connect", 160, 185);

            // 接続ボタン
            drawModernButton(70, 190, 180, 50, "CONNECT", TFT_BLUE);
            lastAudioLevel = 0;  // 初期化完了マーク
        }
    }

    M5.Lcd.setTextDatum(TL_DATUM);
}

// Bluetoothコールバック
void btCallback(esp_spp_cb_event_t event, esp_spp_cb_param_t *param) {
    if (event == ESP_SPP_SRV_OPEN_EVT) {
        btConnected = true;
        needsFullRedraw = true;  // 状態変化で再描画
        Serial.println("Bluetooth client connected");
    } else if (event == ESP_SPP_CLOSE_EVT) {
        btConnected = false;
        needsFullRedraw = true;  // 状態変化で再描画
        Serial.println("Bluetooth client disconnected");
    }
}

void setup() {
    // M5Stack Core2初期化
    M5.begin();

    // ディスプレイを180度回転（上下逆さ）
    M5.Lcd.setRotation(3);

    // シリアル通信
    Serial.begin(115200);
    Serial.println("\n\n=== M5Scribe Bluetooth Streaming Started ===");

    // 画面表示
    M5.Lcd.fillScreen(BLACK);
    M5.Lcd.setTextSize(2);
    M5.Lcd.setTextColor(WHITE);
    M5.Lcd.setCursor(60, 100);
    M5.Lcd.println("Starting...");

    // マイク電源ON
    M5.Axp.SetLDOEnable(3, true);
    delay(100);
    M5.Axp.SetLDOEnable(3, false);

    // マイク初期化
    if (InitMicrophone()) {
        Serial.println("Microphone initialized");
    } else {
        M5.Lcd.fillScreen(RED);
        M5.Lcd.setCursor(10, 100);
        M5.Lcd.println("Mic init failed!");
        Serial.println("ERROR: Microphone initialization failed!");
        while (1) delay(1000);
    }

    // Bluetooth初期化（最初は発見不可）
    if (!SerialBT.begin("M5Stack-M5Scribe", false)) {
        M5.Lcd.fillScreen(RED);
        M5.Lcd.setCursor(10, 100);
        M5.Lcd.println("BT init failed!");
        Serial.println("ERROR: Bluetooth initialization failed!");
        while (1) delay(1000);
    }

    SerialBT.register_callback(btCallback);
    Serial.println("Bluetooth initialized (not discoverable)");
    Serial.println("Press button to enable connection mode");

    updateDisplay();
}

void loop() {
    M5.update();

    // 画面更新
    updateDisplay();

    // タッチ処理
    static bool lastTouchState = false;
    TouchPoint_t pos = M5.Touch.getPressPoint();
    bool touching = (pos.x > 0 && pos.y > 0);

    // CONNECTボタン判定（画面下部中央）
    if (!btConnected && !btDiscoverable && touching && !lastTouchState) {
        if (pos.x >= 70 && pos.x <= 250 && pos.y >= 190 && pos.y <= 240) {
            // ボタン押下フィードバック
            drawModernButton(70, 190, 180, 50, "CONNECT", TFT_DARKGREY, true);
            delay(100);

            // 接続モード有効化
            btDiscoverable = true;
            discoverableStartTime = millis();
            SerialBT.enableSSP();  // ペアリングモード有効
            Serial.println("Connection mode enabled for 60 seconds");
            needsFullRedraw = true;  // 状態変化で再描画
            delay(200);
        }
    }

    // STOPボタン判定（画面右下）
    if (btConnected && touching && !lastTouchState) {
        if (pos.x >= 195 && pos.x <= 305 && pos.y >= 185 && pos.y <= 230) {
            // ボタン押下フィードバック
            drawModernButton(195, 185, 110, 45, "STOP", TFT_MAROON, true);
            delay(100);

            // 切断
            SerialBT.disconnect();
            btConnected = false;
            btDiscoverable = false;
            Serial.println("Disconnected by user");
            needsFullRedraw = true;  // 状態変化で再描画
            delay(200);
        }
    }

    lastTouchState = touching;

    // 接続可能モードのタイムアウト
    if (btDiscoverable && !btConnected) {
        if (millis() - discoverableStartTime > DISCOVERABLE_DURATION) {
            btDiscoverable = false;
            needsFullRedraw = true;  // 状態変化で再描画
            Serial.println("Connection mode timeout");
        }
    }

    // Bluetooth接続中のみストリーミング
    if (btConnected) {
        size_t bytesRead;

        // マイクから音声データ読み込み
        esp_err_t result = i2s_read(
            Speak_I2S_NUMBER,
            audioBuffer,
            DATA_SIZE,
            &bytesRead,
            portMAX_DELAY
        );

        if (result == ESP_OK && bytesRead > 0) {
            // 音声レベル計算
            if (millis() - lastAudioUpdate > 50) {
                calculateAudioLevel(audioBuffer, bytesRead);
                lastAudioUpdate = millis();
            }

            // Bluetooth経由で送信（複数回に分けて確実に送る）
            size_t totalWritten = 0;
            while (totalWritten < bytesRead && btConnected) {
                size_t written = SerialBT.write(audioBuffer + totalWritten, bytesRead - totalWritten);
                if (written > 0) {
                    totalWritten += written;
                } else {
                    delay(1);  // 送信キューが空くまで待つ
                }
            }

            if (totalWritten != bytesRead) {
                Serial.printf("Warning: Only wrote %d/%d bytes\n", totalWritten, bytesRead);
            }
        }
    } else {
        // 接続待機中は少し待つ
        delay(100);
    }
}
