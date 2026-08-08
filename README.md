# StylusMemo

ペン主体の手書きメモアプリ (Android)。Jetpack Compose と AndroidX Ink を使って書いたノートの作成・編集・保存ができます。

## 主な機能

- **手書き入力**: スタイラス / 指でのインク描画 (AndroidX Ink)
- **自動くしゃくしゃ消し**: ペンツールのまま「くしゃくしゃ」と書きなぐると消しゴムとして動作 (GoodNotes 方式)
  - 進行方向によらず (左右・上下どちらでも) 判定
  - ストローク単位で消去、Undo / Redo 対応
- **消去ツール**: 通常の消しゴムツール、ストローク単位の消去
- **編集ツール**
  - なげわ (LASSO) による選択
  - 直線ツール
  - テキストボックス (文字入力)
  - 画像ボックス (画像挿入)
- **ページ管理**
  - ページの追加 / 削除 / 移動
  - ページサイズ: 画面ぴったり / A4 / A5 / B5 / B4 / Letter / カスタム
  - 縦・横の向き
- **背景設定**: 方眼 / 罫線 / ドット / 白紙 などの背景
- **PDF / 画像インポート**: PDF や画像を読み込んでノートに貼り付け
- **Undo / Redo**: 編集操作の履歴管理
- **設定**
  - デフォルトのページサイズ・背景・ペン色・ペン太さ
  - 保存先 (アプリ専用領域 or SAF で指定したフォルダ)
  - スタイラスのボタン設定 (ボタン長押しによるアクション割り当て)
  - 指での描画の有効 / 無効

## 保存形式

ノートはローカルフォルダに保存されます。保存先は「アプリ専用領域」または設定で選択したフォルダです。

```
notes/<noteId>/
  note.json       - ノートのメタデータ (ページサイズ・背景・テキストボックス等)
  page-<n>.bin    - ページごとのストロークデータ
  assets/<name>   - インポートした画像 / レンダリングした PDF ページ
```

## 使用技術

- Kotlin 2.2 / Jetpack Compose (Material 3)
- [AndroidX Ink](https://developer.android.com/jetpack/androidx/releases/ink) (インクの描画・ストローク管理)
- DataStore / kotlinx.serialization
- Coil (画像読み込み)

## ビルド

```bash
./gradlew :app:assembleDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` に生成されます。

### 要件

- Android Gradle Plugin 8.13.2
- Kotlin 2.2.21
- JDK 17+
- compileSdk 36 / minSdk 26

## テスト

```bash
./gradlew :app:testDebugUnitTest
```

- `ScribbleDetectorTest`: くしゃくしゃ消しの判定ロジックのユニットテスト (方向非依存・誤発火防止)

## ライセンス

[MIT License](LICENSE)
