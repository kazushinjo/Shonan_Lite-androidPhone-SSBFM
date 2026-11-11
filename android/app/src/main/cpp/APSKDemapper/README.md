# APSKDemapper

16APSK・32APSK を受信するためのデマッパー。gr-dvbs2rx 1.4.0(igorauad/gr-dvbs2rx、GPL-3.0-or-later)の
`lib/` から、`gr-dvbs2rx_pi5_bringup.patch` と `gr-dvbs2rx_apsk.patch`(Shonan-V2_Lite-RasPI5 の
`pi5/docs/patches/`)を当てた後のファイルを `src/` に複製したもの。`cpu_features_macros.h` は
google/cpu_features(Apache-2.0)のもの。

同梱のビルド済み gr-dvbs2rx は 16/32APSK を復調できないため、ライブラリを作り直す代わりに
この部品だけを名前空間 `gr::shonan_apsk` に移してアプリに組み込む。アプリは 16APSK・32APSK のときだけ
`shonan::make_apsk_xfecframe_demapper()` を使い、QPSK・8PSK は従来どおりライブラリのデマッパーを使う。
