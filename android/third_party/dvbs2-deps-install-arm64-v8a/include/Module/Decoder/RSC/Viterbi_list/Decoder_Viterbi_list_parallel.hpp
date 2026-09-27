/*!
 * \file
 * \brief Decodes a convolutional code using Viterbi's algorithm
 *
 * \section LICENSE
 * This file is under MIT license (https://opensource.org/licenses/MIT).
 */

#ifndef DECODER_VITERBI_LIST_PARALLEL_HPP_
#define DECODER_VITERBI_LIST_PARALLEL_HPP_

#define DEBUG_BUILD

#include <aff3ct.hpp>

namespace aff3ct
{
namespace module
{

/*!
 * \class Decoder_Viterbi_list_parallel
 *
 * \brief Uses Viterbi's algorithm on a convolutional code.
 *
 */
template<typename B, typename Q>
class Decoder_Viterbi_list_parallel : public Decoder_SIHO<B, Q>
{
  public:
    /*!
     * \brief Constructor.
     */
    Decoder_Viterbi_list_parallel(const int K,
                                  const int N,
                                  const int L,
                                  const module::CRC<B>& crc,
                                  const std::vector<std::vector<int>> trellis,
                                  const bool is_closed);

    /*!
     * \brief Destructor.
     */
    ~Decoder_Viterbi_list_parallel();

    virtual Decoder_Viterbi_list_parallel* clone() const;

    void reset()
    {
        m_tested = 0.;
        m_passed = 0.;
        m_failed = 0.;
        m_first_passed = 0.;
    }

    void set_crc(module::CRC<B>* crc) { m_crc = crc->clone(); }

  protected:
    virtual int _decode_siho(const Q* Y_N, B* X_N, const size_t frame_id);

  private:
    void __reset();
    void _forward_pass(const Q* Y_N);
    void __forward_pass(const Q* Y_N);
    void __forward_pass_closing(const Q* Y_N);
    void _process_step(const int i_step, const int i_next_state);
    void setup();
    void __backwards_pass(const int l, std::vector<B>& data);
    void _backwards_pass(B* X_N);
    void _backwards_pass(std::vector<B>& X_N);
    size_t _argmin(Q* ptr, size_t size);
    size_t _argmin(std::vector<Q> data);

  private:
    int m_n_states;                          // Nb d'etats de l'encodeur
    int m_n_memories;                        // Nb memoires
    int m_K;                                 // taille des messages
    int m_N;                                 // taille des mots de code
    int m_L;                                 // taille de la liste de decodage
    int m_n_steps;                           // Nb d'etapes d'encodage
    std::vector<std::vector<int>> m_trellis; // Treillis de l'encodeur
    std::array<std::vector<int>, 2> m_T;     // Matrice de transition entre etats
    std::array<std::vector<int>, 2> m_T_inv; // Matrice inverse de transition entre etats
    std::array<std::vector<int>, 2> m_C;     // Matrice d'encodage par etat
    std::vector<Q> m_P;                      // Metriques de noeuds
    std::vector<Q> m_branch_metr;            // Metriques de branche
    std::vector<int> m_backwards_path;       // Chemin le plus probable
    std::vector<int> m_previous_rank;
    std::vector<Q> m_step_result;
    std::vector<int> m_M; // Changements d'etat au final de l'encodage
    std::vector<std::vector<int>> m_decoded;
    std::vector<std::vector<Q>> m_bin_vals;
    std::vector<int> m_closing_path;
    std::vector<int> m_closing_inputs;
    std::vector<std::vector<int>> m_closing_T;
    std::vector<std::vector<int>> m_closing_bit_sys;
    CRC<B>* m_crc;

    // STATS
    double m_tested;
    double m_passed;
    double m_failed;
    double m_first_passed;
};
}
}

#endif // DECODER_VITERBI_LIST_PARALLEL_HPP_