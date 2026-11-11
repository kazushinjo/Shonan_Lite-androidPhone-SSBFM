/*
 * shonan_apsk_demapper.h の実装。gr-dvbs2rx のデマッパー一式を、この翻訳単位の中だけ
 * 名前空間 dvbs2rx -> shonan_apsk に置き換えてコンパイルする。
 * ★この翻訳単位では gr-dvbs2rx の他のヘッダを include しないこと(インクルードガードで
 *   元の名前空間の宣言が読まれなくなるため)。
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "shonan_apsk_demapper.h"

#define dvbs2rx shonan_apsk
#include "src/xfecframe_demapper_cb_impl.cc"
#undef dvbs2rx

namespace shonan {
gr::block_sptr make_apsk_xfecframe_demapper(int framesize, int code_rate, int constellation)
{
    return gr::shonan_apsk::xfecframe_demapper_cb::make(
        static_cast<gr::shonan_apsk::dvb_framesize_t>(framesize),
        static_cast<gr::shonan_apsk::dvb_code_rate_t>(code_rate),
        static_cast<gr::shonan_apsk::dvb_constellation_t>(constellation));
}
} // namespace shonan
