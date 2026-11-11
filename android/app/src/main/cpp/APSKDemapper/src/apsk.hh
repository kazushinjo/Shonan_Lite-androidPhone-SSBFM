/* -*- c++ -*- */
/*
 * Copyright 2026 Kazu Shinjo.
 *
 * This file is part of gr-dvbs2rx.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#ifndef APSK_HH
#define APSK_HH

#include "modulation.hh"
#include <gnuradio/dvbs2rx/dvb_config.h>
#include <gnuradio/gr_complex.h>
#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>
#include <type_traits>
#include <vector>

/**
 * @brief DVB-S2 16APSK (4+12) and 32APSK (4+12+16) constellations.
 *
 * The constellation points, the ring radius ratios and the bit labels follow ETSI EN
 * 302 307-1 (Section 5.4.3 and 5.4.4) as implemented by the gr-dtv DVB-S2 modulator
 * (dvbs2_modulator_bc_impl.cc), so that a gr-dtv transmitter can be used as reference.
 * Only the DVB-S2 (non-S2X) code rates are supported. The average symbol energy is
 * normalized to one, as in the transmitter.
 *
 * Bit convention: same as the PSK classes, i.e., code value +1 for bit 0 and -1 for bit
 * 1, and b[0] is the most significant bit of the symbol label (the first column of the
 * bit interleaver).
 */
template <typename TYPE, typename CODE>
struct AmplitudePhaseShiftKeying : public Modulation<TYPE, CODE> {
    typedef TYPE complex_type;
    typedef typename TYPE::value_type value_type;
    typedef CODE code_type;

    AmplitudePhaseShiftKeying(gr::dvbs2rx::dvb_constellation_t constellation,
                              gr::dvbs2rx::dvb_code_rate_t rate)
    {
        using namespace gr::dvbs2rx;
        if (constellation == MOD_16APSK) {
            d_bits = 4;
            double gamma;
            switch (rate) {
            case C2_3:
                gamma = 3.15;
                break;
            case C3_4:
                gamma = 2.85;
                break;
            case C4_5:
                gamma = 2.75;
                break;
            case C5_6:
                gamma = 2.70;
                break;
            case C8_9:
                gamma = 2.60;
                break;
            case C9_10:
                gamma = 2.57;
                break;
            default:
                throw std::runtime_error("Unsupported 16APSK code rate");
            }
            double r2 = 1.0;
            double r1 = r2 / gamma;
            double r0 = std::sqrt(4.0 / ((r1 * r1) + 3.0 * (r2 * r2)));
            r1 *= r0;
            r2 *= r0;
            // Phase of each point in units of pi/12 (label order)
            static const int ring[16] = { 2, 2, 2, 2, 2, 2, 2, 2,
                                          2, 2, 2, 2, 1, 1, 1, 1 };
            static const int phase[16] = { 3, -3, 9,  -9, 1,  -1, 11, -11,
                                           5, -5, 7, -7, 3,  -3, 9,  -9 };
            d_points.resize(16);
            for (int i = 0; i < 16; i++) {
                double r = ring[i] == 1 ? r1 : r2;
                double a = phase[i] * M_PI / 12.0;
                d_points[i] = complex_type(r * std::cos(a), r * std::sin(a));
            }
        } else if (constellation == MOD_32APSK) {
            d_bits = 5;
            double gamma1, gamma2;
            switch (rate) {
            case C3_4:
                gamma1 = 2.84;
                gamma2 = 5.27;
                break;
            case C4_5:
                gamma1 = 2.72;
                gamma2 = 4.87;
                break;
            case C5_6:
                gamma1 = 2.64;
                gamma2 = 4.64;
                break;
            case C8_9:
                gamma1 = 2.54;
                gamma2 = 4.33;
                break;
            case C9_10:
                gamma1 = 2.53;
                gamma2 = 4.30;
                break;
            default:
                throw std::runtime_error("Unsupported 32APSK code rate");
            }
            double r3 = 1.0;
            double r1 = r3 / gamma2;
            double r2 = r1 * gamma1;
            double r0 =
                std::sqrt(8.0 / ((r1 * r1) + 3.0 * (r2 * r2) + 4.0 * (r3 * r3)));
            r1 *= r0;
            r2 *= r0;
            r3 *= r0;
            // Phase of each point in units of pi/24 (label order)
            static const int ring[32] = { 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3,
                                          2, 1, 2, 1, 2, 1, 2, 1, 3, 3, 3, 3, 3, 3, 3, 3 };
            static const int phase[32] = { 6,   10,  -6, -10, 18, 14,  -18, -14,
                                           3,   9,   -6, -12, 18, 12,  -21, -15,
                                           2,   6,   -2, -6,  22, 18,  -22, -18,
                                           0,   6,   -3, -9,  21, 15,  24,  -18 };
            d_points.resize(32);
            for (int i = 0; i < 32; i++) {
                double r = ring[i] == 1 ? r1 : (ring[i] == 2 ? r2 : r3);
                double a = phase[i] * M_PI / 24.0;
                d_points[i] = complex_type(r * std::cos(a), r * std::sin(a));
            }
        } else {
            throw std::runtime_error("Unsupported APSK constellation");
        }
    }

    int bits() { return d_bits; }

    const std::vector<complex_type>& points() const { return d_points; }

    /**
     * @brief Index of the constellation point closest to the given symbol.
     */
    int nearest(complex_type c) const
    {
        int best = 0;
        value_type best_d = std::numeric_limits<value_type>::max();
        for (size_t i = 0; i < d_points.size(); i++) {
            value_type d = std::norm(c - d_points[i]);
            if (d < best_d) {
                best_d = d;
                best = i;
            }
        }
        return best;
    }

    void hard(code_type* b, complex_type c)
    {
        int idx = nearest(c);
        for (int k = 0; k < d_bits; k++)
            b[k] = ((idx >> (d_bits - 1 - k)) & 1) ? code_type(-1) : code_type(1);
    }

    /**
     * @brief Max-log soft demapping.
     *
     * The precision argument is 4/N0, as used by the PSK classes. The output keeps the
     * same scale as the PSK classes, namely twice the max-log LLR, so that the LDPC
     * decoder sees comparable values for all constellations.
     */
    void soft(code_type* b, complex_type c, value_type precision)
    {
        value_type min0[5], min1[5];
        for (int k = 0; k < d_bits; k++) {
            min0[k] = std::numeric_limits<value_type>::max();
            min1[k] = std::numeric_limits<value_type>::max();
        }
        for (size_t i = 0; i < d_points.size(); i++) {
            value_type d = std::norm(c - d_points[i]);
            for (int k = 0; k < d_bits; k++) {
                if ((i >> (d_bits - 1 - k)) & 1) {
                    if (d < min1[k])
                        min1[k] = d;
                } else {
                    if (d < min0[k])
                        min0[k] = d;
                }
            }
        }
        // LLR = (min1 - min0) / N0 and precision = 4 / N0, hence 2 * LLR is given by:
        for (int k = 0; k < d_bits; k++)
            b[k] = quantize(0.5 * precision * (min1[k] - min0[k]));
    }

    complex_type map(code_type* b)
    {
        int idx = 0;
        for (int k = 0; k < d_bits; k++)
            idx = (idx << 1) | (b[k] < 0 ? 1 : 0);
        return d_points[idx];
    }

private:
    int d_bits;
    std::vector<complex_type> d_points;

    static code_type quantize(value_type value)
    {
        if (std::is_integral<code_type>::value)
            value = std::nearbyint(value);
        if (std::is_same<code_type, int8_t>::value)
            value = std::min<value_type>(std::max<value_type>(value, -128), 127);
        return value;
    }
};

#endif
