cmake_minimum_required(VERSION 3.22.1)
project(shonanandroid_native)

# Cross-built for arm64-v8a with the NDK toolchain; see
# third_party/build_ffmpeg_mpegts.sh (mpegts muxer/demuxer + file protocol
# only, no encoders/decoders -- encode/decode happens on-device via
# MediaCodec).
set(FFMPEG_STACK ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/ffmpeg-mpegts-install-arm64-v8a)

add_library(ts_bridge SHARED ts_bridge.cpp)

target_include_directories(ts_bridge PRIVATE ${FFMPEG_STACK}/include)

target_link_libraries(ts_bridge
    ${FFMPEG_STACK}/lib/libavformat.a
    ${FFMPEG_STACK}/lib/libavcodec.a
    ${FFMPEG_STACK}/lib/libavutil.a
    z
    log
)

target_link_options(ts_bridge PRIVATE "-Wl,--exclude-libs,ALL")

# 機器試験(Diagnostic)機能: Plutoへlibiio経由で直接IQ送受信し、aff3ct/dvbs2で
# DVB-S2変調・復調をオンデバイスで行う別スタック。ts_bridge(UDP TS方式の本番Tx/Rx)とは
# 完全に独立。third_party/build_dvbs2_deps.sh参照。
set(DVBS2_DEPS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/dvbs2-deps-install-arm64-v8a)

add_library(dvbs2_bridge SHARED
    dvbs2_bridge.cpp
    dvbs2_tx_lib.cpp
    dvbs2_rx_lib.cpp
)

target_include_directories(dvbs2_bridge PRIVATE
    ${DVBS2_DEPS}/include
)

# aff3ct.hppはこれらのプリプロセッサ定義に応じて有効化する型/文字列テーブルを切り替える。
# third_party/dvbs2-deps-install-arm64-v8a自体はAFF3CT_PREC=MULTI(既定値)でビルドされて
# おり、8/16/32/64bitを個別マクロで固定してしまうとB型(bit値)がsigned char固定になり
# dvbs2_tx_lib.cpp内のBCH_polynomial_generator<B>(vector<int>期待)が型不一致になる。
target_compile_definitions(dvbs2_bridge PRIVATE
    AFF3CT_EXT_STRINGS
    AFF3CT_MULTI_PREC
)

set_target_properties(dvbs2_bridge PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

target_link_libraries(dvbs2_bridge
    ${DVBS2_DEPS}/lib/libdvbs2_common.a
    ${DVBS2_DEPS}/lib/libaff3ct-4.0.0.a
    ${DVBS2_DEPS}/lib/libstreampu.a
    ${DVBS2_DEPS}/lib/libiio.a
    ${DVBS2_DEPS}/lib/libiiod-responder.a
    ${DVBS2_DEPS}/lib/libxml2.a
    ${DVBS2_DEPS}/lib/libzstd.a
    log
)

target_link_options(dvbs2_bridge PRIVATE "-Wl,--exclude-libs,ALL")

# 本番RX用gr-dvbs2rx復調: aff3ct版(dvbs2_bridge.cpp)が既知のDVB-S2標準信号すら
# 復調できないことがホスト実機検証で判明したため、実績のある独立実装
# (gr-dvbs2rx、igorauad氏、GNU Radio OOTモジュール)へ切り替える。
# third_party/gr-dvbs2rx-install-arm64-v8a参照(Android NDK向けにcrossビルド済みの
# VOLK/GNU Radio本体/gr-iio/gr-dvbs2rxを配置)。
# 実体の.soはapp/src/main/jniLibs/arm64-v8a/に配置済み(Gradleが自動でAPKへ同梱)。
set(GRDVBS2RX_STACK ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/gr-dvbs2rx-install-arm64-v8a)
set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a)

add_library(dvbs2rx_bridge SHARED dvbs2rx_bridge.cpp)

target_include_directories(dvbs2rx_bridge PRIVATE ${GRDVBS2RX_STACK}/include)

set_target_properties(dvbs2rx_bridge PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

foreach(lib gnuradio-runtime gnuradio-blocks gnuradio-fft gnuradio-filter
            gnuradio-analog gnuradio-iio gnuradio-pmt gnuradio-dvbs2rx volk)
    add_library(prebuilt_${lib} SHARED IMPORTED)
    set_target_properties(prebuilt_${lib} PROPERTIES IMPORTED_LOCATION ${JNILIBS_DIR}/lib${lib}.so)
    target_link_libraries(dvbs2rx_bridge prebuilt_${lib})
endforeach()

target_link_libraries(dvbs2rx_bridge log)

# SSB/FM送受信タブ: Langstone-V2(G4EML)のPluto送受信処理をC++へ移植したもの。
# Plutoはdvbs2_bridgeと同じlibiio(v1、静的)で直接操作し、音声はAAudioで入出力する。
add_library(ssbfm_bridge SHARED ssbfm_bridge.cpp)

target_include_directories(ssbfm_bridge PRIVATE ${DVBS2_DEPS}/include)

set_target_properties(ssbfm_bridge PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

target_compile_options(ssbfm_bridge PRIVATE -O3 -ffast-math)

target_link_libraries(ssbfm_bridge
    ${DVBS2_DEPS}/lib/libiio.a
    ${DVBS2_DEPS}/lib/libiiod-responder.a
    ${DVBS2_DEPS}/lib/libxml2.a
    ${DVBS2_DEPS}/lib/libzstd.a
    aaudio
    log
)

target_link_options(ssbfm_bridge PRIVATE "-Wl,--exclude-libs,ALL")
