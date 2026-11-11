/*
 * 16APSK/32APSK対応のXFECFRAMEデマッパー(gr-dvbs2rx 1.4.0 の xfecframe_demapper_cb に
 * Shonan-V2_Lite の gr-dvbs2rx_apsk.patch を当てたもの)。
 *
 * 同梱のビルド済み gr-dvbs2rx ライブラリは 16/32APSK を復調できないため、この部品だけを
 * 名前空間 gr::shonan_apsk に移してアプリに直接組み込む(ライブラリ本体とは衝突しない)。
 * 引数は gr::dvbs2rx の dvb_framesize_t / dvb_code_rate_t / dvb_constellation_t の値を
 * int にしたもの。ブロックの入出力・"llr_pdu" メッセージポートは元のデマッパーと同じ。
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once

#include <gnuradio/block.h>

namespace shonan {
gr::block_sptr make_apsk_xfecframe_demapper(int framesize, int code_rate, int constellation);
} // namespace shonan
