#ifndef datv_dvbs2_bridge_h
#define datv_dvbs2_bridge_h

/*
 * DVBS2_iOS_Port_PoC.md参照。aff3ct/dvbs2由来のDVB-S2変調(Tx)処理をSwiftから呼び出すための
 * Cブリッジ。dvbs2_tx_lib.cppの実体はaff3ct/streampuのC++型を使うため不透明だが、
 * この関数シグネチャ自体はC++型を露出しないのでSwift bridging headerから直接importできる。
 *
 * 呼び出し側(Swift)はバックグラウンドスレッドでこの関数をブロッキング呼び出しすること。
 * argv形式でdvbs2_txコマンドライン相当の引数(--mod-cod, --src-type USER_BIN,
 * --src-path <入力FIFO>, --src-fifo, --rad-type USER_BIN, --rad-tx-file-path <出力FIFO>,
 * --tx-time-limit 等)を渡す。
 *
 * datv_dvbs2_rx_runは復調(Rx)側。--rad-type USER_BIN --rad-rx-file-path <入力FIFO、IQ>
 * --snk-path <出力FIFO、復調後TS> --rx-time-limit 等を渡す。
 * ★Radio_user_binaryはEOF時にauto_resetでseekg(0)を試みるがFIFOはシーク不可のため、
 * 入力側(Swift)は書き込み用FileHandleを閉じずに継続して書き込み続けること
 * (writerを閉じるとEOFになりクラッシュする。DVBS2_iOS_Port_PoC.md参照)。
 */

#ifdef __cplusplus
extern "C" {
#endif

int datv_dvbs2_tx_run(int argc, char** argv);
int datv_dvbs2_rx_run(int argc, char** argv);

/* datv_dvbs2_tx_run/rx_runはブロッキング実行され、Swift側からは外部フラグでしか
 * 停止を要求できない(FIFOを閉じるだけでは内部の実行ループが止まらずCPU/メモリを
 * 消費し続ける事象を実機で確認、DVBS2_iOS_Port_PoC.md参照)。
 * stop()の際、FIFOハンドルを閉じる前にこれらを呼ぶこと。 */
void datv_dvbs2_tx_request_stop(void);
void datv_dvbs2_rx_request_stop(void);

/* ★DVB-S2フレーム同期の現在のロック状態(sync_frame->get_packet_flag()、内部でFLGとして
 * 扱われている値そのもの)。sink(TS出力)はBCH/LDPC復号成否・フレーム同期成立の有無に
 * 関わらず毎フレーム無条件で書き込む(BER/FER計測用シミュレーションハーネス構造のため)ので、
 * 「TSバイトが出力されているか」はロックの指標にならない。この関数はtransmission phase中
 * 継続的に更新される(waiting/learning phase中は未更新=false のまま)。 */
bool datv_dvbs2_rx_is_locked(void);

/* ★デバッグ専用: Rx開始直後の急激なメモリ増加の原因箇所を特定するための計測ブリッジ
 * (MemoryDebugBridge.swift実装、DVBS2_iOS_Port_PoC.md参照)。失敗時-1を返す。 */
double shonan_debug_memory_mb(void);

#ifdef __cplusplus
}
#endif

#endif /* datv_dvbs2_bridge_h */
