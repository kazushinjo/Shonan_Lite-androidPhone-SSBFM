// ============================================================
//  W5500_PA_PTT_Control.ino
//  ESP32 + W5500 イーサネット経由 12V電源/PTT制御
//  - Raspberry Pi5からのHTTPコマンドで12V電源・PTTを制御
//  - 12V電源ON要求  : 受信後 設定秒数(既定) 待ってからON
//  - PTT ON要求     : 受信後 設定ms(既定)   待ってからON
//  - OFF要求は安全のため常に即時反映（保留中のON要求もキャンセル）
//  - 遅延時間・自機IPアドレスはWeb UI/HTTP APIから変更可能
//    （設定はNVS=Preferencesに保存され、電源断後も保持される）
//  - 固定IP方式。初期IPは 192.168.0.100/24
//
//  【配線】
//  W5500      ESP32 (VSPI)
//  --------   ------------
//  SCK        GPIO 18
//  MISO       GPIO 19
//  MOSI       GPIO 23
//  CS(SS)     GPIO 5
//  RST        (未使用。EN/3.3Vへ配線 or 未接続可)
//  VCC        3.3V
//  GND        GND
//
//  出力（ラッチ式ON/OFF、いずれもactive-HIGH想定）
//  LNA : GPIO 25 （受信プリアンプ。起動時はON＝受信状態）
//  PTT : GPIO 26 （起動時はOFF）
//  12V : GPIO 27 （PA用12V電源。起動時はOFF）
//
//  【Raspberry Pi5 連携（推奨シーケンス）】
//    1) GET /power?state=on   … 12V電源ON要求（設定秒数後にON）
//       ※ 電源安定後にPTTを送ること
//    2) GET /ptt?state=on     … PTT ON要求（設定ms後にON、LNAは自動OFF）
//    3) GET /ptt?state=off    … PTT即時OFF（LNAは自動ON）
//    4) GET /power?state=off  … 12V電源即時OFF
//
//  【設定変更】
//    GET /config                                        設定画面(HTML)
//    GET /config/delay?pwr_delay_sec=3&ptt_delay_ms=50   遅延時間を保存
//    GET /config/network?ip=192.168.0.100&gateway=192.168.0.1&subnet=255.255.255.0
//                                                         IP設定を保存し自動再起動
//
//  【旧互換】
//    GET /tx?state=on|off  … tanzawa-android連携用の一括シーケンス
//                             (LNA off -> PTT/12V遅延ON / PA・PTT即時OFF -> LNA on)
// ============================================================

#include <SPI.h>
#include <Ethernet.h>
#include <Preferences.h>

// ============================================================
//  設定
// ============================================================

// MACアドレス: 第1オクテット下位2bit目(0x02)をセットした
// ローカル管理アドレス(LAA)。IEEEが実ベンダーに割り当てるOUIとは
// 体系的に重複しないため、国内外問わず実在機器との衝突リスクが低い。
static byte mac[] = { 0x02, 0x4A, 0x50, 0x35, 0x35, 0x30 }; // 覚えやすさ優先の任意値

const int PIN_CS = 5;

const int IDX_LNA = 0;
const int IDX_PTT = 1;
const int IDX_PWR = 2;   // 12V電源（旧PA）

const int PIN_OUT[3] = { 25, 26, 27 };
const char* OUT_LABEL[3] = { "LNA (GPIO25)", "PTT (GPIO26)", "12V PWR (GPIO27)" };

// 基板搭載LED（PTT ON中に点灯）
const int PIN_ONBOARD_LED = 2;

// 起動時はLNA=ON（受信状態）、PTT/12V=OFF
bool outState[3] = { true, false, false };

Preferences prefs;
const char* PREFS_NS = "w5500cfg";

// --- 可変設定（NVSに保存、既定値） ---
uint32_t powerDelayMs = 3000;   // 12V電源ON要求からONまでの遅延[ms]（既定3秒）
uint32_t pttDelayMs   = 50;     // PTT ON要求からONまでの遅延[ms]（既定50ms）

IPAddress currentIP(192, 168, 0, 100);
IPAddress currentGateway(192, 168, 0, 1);
IPAddress currentSubnet(255, 255, 255, 0);

// --- 遅延実行の保留状態（非ブロッキング） ---
bool pwrOnPending = false;
unsigned long pwrOnRequestAt = 0;

bool pttOnPending = false;
unsigned long pttOnRequestAt = 0;

EthernetServer server(80);
bool linkUp = false;

// ============================================================
//  設定の読み書き（NVS）
// ============================================================

void loadConfig() {
    prefs.begin(PREFS_NS, true);
    powerDelayMs = prefs.getULong("pwr_delay_ms", 3000);
    pttDelayMs   = prefs.getULong("ptt_delay_ms", 50);

    String ipStr   = prefs.getString("ip",     "192.168.0.100");
    String gwStr   = prefs.getString("gw",     "192.168.0.1");
    String maskStr = prefs.getString("mask",   "255.255.255.0");
    prefs.end();

    if (!currentIP.fromString(ipStr))          currentIP = IPAddress(192, 168, 0, 100);
    if (!currentGateway.fromString(gwStr))     currentGateway = IPAddress(192, 168, 0, 1);
    if (!currentSubnet.fromString(maskStr))    currentSubnet = IPAddress(255, 255, 255, 0);
}

void saveDelayConfig(uint32_t pwrMs, uint32_t pttMs) {
    prefs.begin(PREFS_NS, false);
    prefs.putULong("pwr_delay_ms", pwrMs);
    prefs.putULong("ptt_delay_ms", pttMs);
    prefs.end();
    powerDelayMs = pwrMs;
    pttDelayMs = pttMs;
}

void saveNetworkConfig(const String& ipStr, const String& gwStr, const String& maskStr) {
    prefs.begin(PREFS_NS, false);
    prefs.putString("ip", ipStr);
    prefs.putString("gw", gwStr);
    prefs.putString("mask", maskStr);
    prefs.end();
}

// ============================================================
//  出力制御
// ============================================================

void applyOutput(int idx) {
    digitalWrite(PIN_OUT[idx], outState[idx] ? HIGH : LOW);
    Serial.printf("[OUT] %s -> %s\n", OUT_LABEL[idx], outState[idx] ? "ON" : "OFF");
    if (idx == IDX_PTT) {
        digitalWrite(PIN_ONBOARD_LED, outState[IDX_PTT] ? HIGH : LOW);
    }
}

void setOutput(int idx, bool on) {
    if (idx < 0 || idx > 2) return;
    outState[idx] = on;
    applyOutput(idx);
}

void toggleOutput(int idx) {
    if (idx < 0 || idx > 2) return;
    setOutput(idx, !outState[idx]);
}

// ============================================================
//  12V電源 / PTT の遅延付き制御（非ブロッキング）
//  - ON要求: 設定した遅延の後にONへ遷移する予約を行う
//  - OFF要求: 安全のため即時OFF。予約中のON要求はキャンセルする
// ============================================================

void requestPowerOn() {
    pwrOnPending = true;
    pwrOnRequestAt = millis();
    Serial.printf("[PWR] ON要求受信 -> %lu ms後にON予定\n", (unsigned long)powerDelayMs);
}

void requestPowerOff() {
    pwrOnPending = false;
    setOutput(IDX_PWR, false);
    Serial.println("[PWR] OFF要求受信 -> 即時OFF");
}

void requestPttOn() {
    pttOnPending = true;
    pttOnRequestAt = millis();
    Serial.printf("[PTT] ON要求受信 -> %lu ms後にON予定\n", (unsigned long)pttDelayMs);
}

void requestPttOff() {
    pttOnPending = false;
    setOutput(IDX_PTT, false);
    Serial.println("[PTT] OFF要求受信 -> 即時OFF");
}

void servicePendingOutputs() {
    if (pwrOnPending && (unsigned long)(millis() - pwrOnRequestAt) >= powerDelayMs) {
        pwrOnPending = false;
        setOutput(IDX_PWR, true);
        Serial.println("[PWR] 遅延経過 -> ON");
    }
    if (pttOnPending && (unsigned long)(millis() - pttOnRequestAt) >= pttDelayMs) {
        pttOnPending = false;
        setOutput(IDX_PTT, true);
        Serial.println("[PTT] 遅延経過 -> ON");
    }
}

// ============================================================
//  TX/RX シーケンス（tanzawa-android 旧互換）
// ============================================================

void txStart() {
    Serial.println("[TX] 送信シーケンス開始（旧互換 /tx）");
    setOutput(IDX_LNA, false);
    requestPttOn();
    requestPowerOn();
}

void txStop() {
    Serial.println("[TX] 受信復帰シーケンス開始（旧互換 /tx）");
    requestPttOff();
    requestPowerOff();
    setOutput(IDX_LNA, true);
}

// ============================================================
//  クエリパラメータ簡易パーサ
//  reqLine 例: "GET /config/delay?pwr_delay_sec=3&ptt_delay_ms=50 HTTP/1.1"
// ============================================================

static String extractQuery(const String& line) {
    int qIdx = line.indexOf('?');
    if (qIdx < 0) return String();
    int spIdx = line.indexOf(' ', qIdx);
    return line.substring(qIdx + 1, spIdx > 0 ? spIdx : line.length());
}

bool getQueryStr(const String& line, const String& key, String& outVal) {
    String query = extractQuery(line);
    if (query.length() == 0) return false;
    String pattern = key + "=";
    int pos = query.indexOf(pattern);
    if (pos < 0) return false;
    // key の前が '&' か先頭であることを確認（部分一致誤検出を防ぐ）
    if (pos > 0 && query.charAt(pos - 1) != '&') return false;
    int start = pos + pattern.length();
    int end = query.indexOf('&', start);
    if (end < 0) end = query.length();
    outVal = query.substring(start, end);
    return true;
}

bool getQueryLong(const String& line, const String& key, long& outVal) {
    String s;
    if (!getQueryStr(line, key, s)) return false;
    outVal = s.toInt();
    return true;
}

// ============================================================
//  HTTPレスポンス補助
// ============================================================

void sendPlain(EthernetClient& client, const String& body) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: text/plain; charset=UTF-8");
    client.println("Connection: close");
    client.println();
    client.println(body);
}

void sendRedirect(EthernetClient& client, const char* location) {
    client.println("HTTP/1.1 303 See Other");
    client.print("Location: ");
    client.println(location);
    client.println("Connection: close");
    client.println();
}

// ============================================================
//  HTMLページ
// ============================================================

void sendStatusPage(EthernetClient& client) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: text/html; charset=UTF-8");
    client.println("Connection: close");
    client.println();

    client.println("<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'>");
    client.println("<meta name='viewport' content='width=device-width,initial-scale=1'>");
    client.println("<title>PA/PTT 電源制御</title>");
    client.println("<style>");
    client.println("body{margin:0;font-family:sans-serif;background:#000;color:#eee;text-align:center;padding-top:30px}");
    client.println("h1{color:#aaa;font-size:1.3em}");
    client.println(".card{display:inline-block;margin:12px;background:#111;border-radius:12px;padding:20px 28px;min-width:180px}");
    client.println(".label{color:#888;font-size:.9em;margin-bottom:10px}");
    client.println(".state{font-size:1.6em;font-weight:bold;margin-bottom:14px}");
    client.println(".on{color:#4c9}.off{color:#c66}.pending{color:#fc6}");
    client.println("a.btn{display:inline-block;padding:10px 26px;border-radius:8px;text-decoration:none;font-size:1em}");
    client.println(".btn-on{background:#1a4;color:#fff}.btn-off{background:#a11;color:#fff}");
    client.println("a.cfg{display:inline-block;margin-top:10px;color:#8cf;text-decoration:none}");
    client.println("</style></head><body>");
    client.println("<h1>&#9889; PA/PTT 電源制御</h1>");

    for (int i = 0; i < 3; i++) {
        bool pending = (i == IDX_PWR && pwrOnPending) || (i == IDX_PTT && pttOnPending);
        client.print("<div class='card'><div class='label'>");
        client.print(OUT_LABEL[i]);
        client.print("</div><div class='state ");
        if (pending) {
            client.print("pending'>ON待ち");
        } else {
            client.print(outState[i] ? "on'>ON" : "off'>OFF");
        }
        client.print("</div><a class='btn ");
        client.print(outState[i] ? "btn-off" : "btn-on");
        client.print("' href='/toggle?ch=");
        client.print(i);
        client.print("'>");
        client.print(outState[i] ? "OFFにする" : "ONにする");
        client.println("</a></div>");
    }

    client.print("<p style='color:#555;margin-top:10px;font-size:.8em'>IP: ");
    client.print(Ethernet.localIP());
    client.println("</p>");
    client.println("<div><a class='cfg' href='/config'>&#9881; 遅延時間・IPアドレス設定</a></div>");
    client.println("</body></html>");
}

void sendConfigPage(EthernetClient& client) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: text/html; charset=UTF-8");
    client.println("Connection: close");
    client.println();

    client.println("<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'>");
    client.println("<meta name='viewport' content='width=device-width,initial-scale=1'>");
    client.println("<title>設定</title>");
    client.println("<style>");
    client.println("body{margin:0;font-family:sans-serif;background:#000;color:#eee;padding:24px}");
    client.println("h1{color:#aaa;font-size:1.2em}");
    client.println("fieldset{border:1px solid #333;border-radius:10px;margin-bottom:20px;padding:16px}");
    client.println("legend{color:#8cf;padding:0 6px}");
    client.println("label{display:block;margin:10px 0 4px;color:#aaa;font-size:.9em}");
    client.println("input{background:#111;border:1px solid #444;color:#eee;border-radius:6px;padding:8px;width:220px}");
    client.println("button{margin-top:14px;padding:10px 22px;border:0;border-radius:8px;background:#1a4;color:#fff;font-size:1em}");
    client.println("a{color:#8cf}");
    client.println("</style></head><body>");
    client.println("<h1>&#9881; 遅延時間・IPアドレス設定</h1>");

    client.println("<form action='/config/delay' method='GET'>");
    client.println("<fieldset><legend>遅延時間</legend>");
    client.print("<label>12V電源 ON 遅延 [秒]（powerOn要求受信からON実行までの待ち時間）</label>");
    client.print("<input type='number' step='0.1' min='0' name='pwr_delay_sec' value='");
    client.print(powerDelayMs / 1000.0, 1);
    client.println("'>");
    client.print("<label>PTT ON 遅延 [ms]（PTT要求受信からON実行までの待ち時間）</label>");
    client.print("<input type='number' step='1' min='0' name='ptt_delay_ms' value='");
    client.print(pttDelayMs);
    client.println("'>");
    client.println("<div><button type='submit'>遅延時間を保存</button></div>");
    client.println("</fieldset></form>");

    client.println("<form action='/config/network' method='GET'>");
    client.println("<fieldset><legend>ネットワーク設定（保存後に自動再起動します）</legend>");
    client.print("<label>IPアドレス</label><input type='text' name='ip' value='");
    client.print(currentIP);
    client.println("'>");
    client.print("<label>ゲートウェイ</label><input type='text' name='gateway' value='");
    client.print(currentGateway);
    client.println("'>");
    client.print("<label>サブネットマスク</label><input type='text' name='subnet' value='");
    client.print(currentSubnet);
    client.println("'>");
    client.println("<div><button type='submit'>保存して再起動</button></div>");
    client.println("</fieldset></form>");

    client.println("<p><a href='/'>&larr; ステータス画面へ戻る</a></p>");
    client.println("</body></html>");
}

void sendRestartingPage(EthernetClient& client) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: text/html; charset=UTF-8");
    client.println("Connection: close");
    client.println();
    client.println("<!DOCTYPE html><html lang='ja'><head><meta charset='UTF-8'>");
    client.println("<title>再起動中</title></head><body style='font-family:sans-serif;background:#000;color:#eee;text-align:center;padding-top:60px'>");
    client.println("<h1>設定を保存しました</h1><p>新しいIPアドレスで再起動します...</p>");
    client.println("</body></html>");
}

void sendStatusJson(EthernetClient& client) {
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: application/json");
    client.println("Connection: close");
    client.println();
    client.print("{\"lna\":");
    client.print(outState[IDX_LNA] ? "true" : "false");
    client.print(",\"ptt\":");
    client.print(outState[IDX_PTT] ? "true" : "false");
    client.print(",\"ptt_pending\":");
    client.print(pttOnPending ? "true" : "false");
    client.print(",\"power\":");
    client.print(outState[IDX_PWR] ? "true" : "false");
    client.print(",\"power_pending\":");
    client.print(pwrOnPending ? "true" : "false");
    client.print(",\"power_delay_ms\":");
    client.print(powerDelayMs);
    client.print(",\"ptt_delay_ms\":");
    client.print(pttDelayMs);
    client.print(",\"ip\":\"");
    client.print(Ethernet.localIP());
    client.println("\"}");
}

// ============================================================
//  HTTPリクエスト処理（簡易パーサ）
// ============================================================

void handleClient(EthernetClient& client) {
    String reqLine;
    while (client.connected() && client.available() == 0) delay(1);
    if (client.available()) {
        reqLine = client.readStringUntil('\n');
    }
    // ヘッダー残りを読み捨てる
    while (client.connected()) {
        String line = client.readStringUntil('\n');
        if (line == "\r" || line.length() == 0) break;
    }

    Serial.println("[HTTP] " + reqLine);

    if (reqLine.startsWith("GET /power")) {
        // Raspberry Pi5 からの12V電源制御要求
        if (reqLine.indexOf("state=on") >= 0) {
            requestPowerOn();
        } else if (reqLine.indexOf("state=off") >= 0) {
            requestPowerOff();
        }
        sendPlain(client, outState[IDX_PWR] ? "PWR_ON" : (pwrOnPending ? "PWR_PENDING" : "PWR_OFF"));

    } else if (reqLine.startsWith("GET /ptt")) {
        // Raspberry Pi5 からのPTT制御要求
        if (reqLine.indexOf("state=on") >= 0) {
            requestPttOn();
        } else if (reqLine.indexOf("state=off") >= 0) {
            requestPttOff();
        }
        sendPlain(client, outState[IDX_PTT] ? "PTT_ON" : (pttOnPending ? "PTT_PENDING" : "PTT_OFF"));

    } else if (reqLine.startsWith("GET /config/delay")) {
        long pttMs = -1;
        String pwrSecStr;
        if (getQueryStr(reqLine, "pwr_delay_sec", pwrSecStr)) {
            float pwrSec = pwrSecStr.toFloat();
            if (pwrSec >= 0) {
                uint32_t newPwrMs = (uint32_t)(pwrSec * 1000.0f + 0.5f);
                uint32_t newPttMs = pttDelayMs;
                if (getQueryLong(reqLine, "ptt_delay_ms", pttMs) && pttMs >= 0) {
                    newPttMs = (uint32_t)pttMs;
                }
                saveDelayConfig(newPwrMs, newPttMs);
            }
        } else if (getQueryLong(reqLine, "ptt_delay_ms", pttMs) && pttMs >= 0) {
            saveDelayConfig(powerDelayMs, (uint32_t)pttMs);
        }
        sendRedirect(client, "/config");

    } else if (reqLine.startsWith("GET /config/network")) {
        String ipStr, gwStr, maskStr;
        IPAddress testIp, testGw, testMask;
        bool ok = getQueryStr(reqLine, "ip", ipStr) && testIp.fromString(ipStr)
               && getQueryStr(reqLine, "gateway", gwStr) && testGw.fromString(gwStr)
               && getQueryStr(reqLine, "subnet", maskStr) && testMask.fromString(maskStr);
        if (ok) {
            saveNetworkConfig(ipStr, gwStr, maskStr);
            sendRestartingPage(client);
            client.flush();
            delay(500);
            client.stop();
            delay(1000);
            ESP.restart();
            return;
        } else {
            sendRedirect(client, "/config");
        }

    } else if (reqLine.startsWith("GET /config")) {
        sendConfigPage(client);

    } else if (reqLine.startsWith("GET /tx")) {
        // tanzawa-android からのTX開始/終了通知（旧互換）
        if (reqLine.indexOf("state=on") >= 0) {
            txStart();
        } else if (reqLine.indexOf("state=off") >= 0) {
            txStop();
        }
        sendPlain(client, (outState[IDX_PTT] || pttOnPending) ? "TX" : "RX");

    } else if (reqLine.startsWith("GET /toggle")) {
        // 手動デバッグ用（個別チャンネルのトグル）
        long ch = -1;
        if (getQueryLong(reqLine, "ch", ch)) {
            toggleOutput((int)ch);
        }
        sendRedirect(client, "/");

    } else if (reqLine.startsWith("GET /api/status")) {
        sendStatusJson(client);

    } else {
        sendStatusPage(client);
    }

    delay(1);
    client.stop();
}

// ============================================================
//  setup / loop
// ============================================================

void setup() {
    Serial.begin(115200);
    delay(3000);
    Serial.println("=== W5500_PA_PTT_Control 起動 ===");

    loadConfig();
    Serial.printf("[CFG] 12V電源ON遅延=%lums, PTT ON遅延=%lums\n",
                   (unsigned long)powerDelayMs, (unsigned long)pttDelayMs);

    pinMode(PIN_ONBOARD_LED, OUTPUT);
    digitalWrite(PIN_ONBOARD_LED, LOW);

    for (int i = 0; i < 3; i++) {
        pinMode(PIN_OUT[i], OUTPUT);
        applyOutput(i);   // 起動時状態を反映（LNA=ON, PTT/12V=OFF、LED=OFF）
    }

    Ethernet.init(PIN_CS);

    Serial.print("固定IPで初期化中... IP=");
    Serial.print(currentIP);
    Serial.print(" GW=");
    Serial.print(currentGateway);
    Serial.print(" MASK=");
    Serial.println(currentSubnet);

    Ethernet.begin(mac, currentIP, currentGateway, currentGateway, currentSubnet);

    if (Ethernet.hardwareStatus() == EthernetNoHardware) {
        Serial.println("W5500が検出できません。配線を確認してください。");
    }

    Serial.print("IPアドレス: ");
    Serial.println(Ethernet.localIP());

    server.begin();
    Serial.println("Webサーバー起動 (port 80)");
}

void loop() {
    // リンク状態の監視
    bool nowUp = (Ethernet.linkStatus() != LinkOFF);
    if (nowUp != linkUp) {
        linkUp = nowUp;
        Serial.println(linkUp ? "[LINK] イーサネット接続" : "[LINK] イーサネット切断");
    }

    // 12V電源/PTTの遅延ON予約を処理（非ブロッキング）
    servicePendingOutputs();

    EthernetClient client = server.available();
    if (client) {
        handleClient(client);
    }
}
