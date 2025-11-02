# Project M5Scribe - ビルド＆書き込み手順

## M5Stack Core2への書き込み方法

### ターミナルから実行する場合

VSCode内でターミナルを開き（Ctrl+`）、以下のコマンドを実行：

```bash
# ビルドのみ
pio run

# ビルド＆書き込み
pio run --target upload

# シリアルモニタを開く
pio device monitor
```

#### 書き込みに失敗する場合
1. M5Stackを再起動（側面の電源ボタン長押し）
2. USBケーブルを抜き差し
3. 別のUSBポートを試す
4. ドライバーのインストール確認（CP210x USBドライバー）

#### ビルドエラーが出る場合
```bash
# PlatformIOの環境をクリーン
pio run --target clean

# ライブラリを再インストール
pio pkg install
```

## シリアルモニタでログ確認

書き込み後、シリアルモニタで動作ログを確認：

```bash
pio device monitor --baud 115200
```

終了: `Ctrl+C`

## コードについて

このコードは、M5Stack Core2デバイスにBluetooth経由で接続し、リアルタイム音声文字起こしとAI要約機能を提供するAndroidアプリケーションです。

### 機能

- **Bluetoothオーディオストリーミング**: M5Stack Core2に接続してワイヤレス音声入力
- **リアルタイム音声認識**: Androidの標準音声認識APIを使用
- **AI要約**: OpenAIのChatGPT APIを使用して文字起こしを自動要約
- **セッション管理**: 日付ごとに文字起こしセッションを保存・整理
- **安全なストレージ**: APIキーと機密データはEncryptedSharedPreferencesで暗号化保存

## 必要要件

### Androidアプリ
- Android 8.0 (API 26) 以上
- Bluetooth接続
- マイク権限
- AI要約用のインターネット接続

### M5Stack Core2
- PlatformIO環境
- 初期セットアップ用USB接続
- CP210x USBドライバー

### OpenAI API
このアプリケーションは文字起こしの要約にOpenAIのChatGPT APIを使用します。ご自身のAPIキーが必要です：

1. https://platform.openai.com/ でサインアップ
2. APIキーを生成
3. アプリの設定画面でキーを入力

## ライセンス

このプロジェクトはMITライセンスの下で公開されています。詳細は [LICENSE](LICENSE) ファイルを参照してください。

## サードパーティライセンス

このプロジェクトは様々なオープンソースライブラリを使用しています。詳細なライセンス情報は [NOTICE.md](NOTICE.md) を参照してください。
