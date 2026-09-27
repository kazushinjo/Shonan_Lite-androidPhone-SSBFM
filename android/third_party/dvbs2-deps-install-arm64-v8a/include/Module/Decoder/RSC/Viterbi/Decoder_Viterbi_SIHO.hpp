/*!
 * \file
 * \brief Expansion of `Decoder_Viterbi_SIHO` to prepare for list implem.
 *
 * \section LICENSE
 * This file is under MIT license (https://opensource.org/licenses/MIT).
 */

#ifndef DECODER_VITERBI_SIHO_HPP_
#define DECODER_VITERBI_SIHO_HPP_

#include <array>
#include <cmath>
#include <iomanip>
#include <vector>

#include "Module/Decoder/Decoder_SIHO.hpp"

namespace aff3ct
{
namespace module
{

/*!
 * \class Decoder_Viterbi_SIHO
 *
 * \brief Uses Viterbi's algorithm on a convolutional code.
 *
 */
template<typename B = int, typename R = int>
class Decoder_Viterbi_SIHO : public Decoder_SIHO<B, R>
{
  public:
    /*!
     * \brief Constructor.
     */
    Decoder_Viterbi_SIHO(const int K, const std::vector<std::vector<int>> trellis, const bool is_closed);

    /*!
     * \brief Destructor.
     */
    ~Decoder_Viterbi_SIHO() = default;

    virtual Decoder_Viterbi_SIHO<B, R>* clone() const;

  protected:
    virtual int _decode_siho(const R* Y_N, B* X_N, const size_t frame_id);

  private:
    void __reset();
    void _forward_pass(const R* Y_N);
    void setup();
    void _backwards_pass(B* X_N);
    size_t _argmin(double* ptr, size_t size);
    size_t _argmin(std::vector<double> data);

  private:
    int m_n_states;                          // Nb d'etats de l'encodeur
    int m_n_memories;                        // Nb memoires
    int m_K;                                 // taille des messages
    int m_N;                                 // taille des mots de code
    int m_n_steps;                           // Nb d'etapes d'encodage
    std::vector<std::vector<int>> m_trellis; // Treillis de l'encodeur
    std::array<std::vector<int>, 2> m_T;     // Matrice de transition entre etats
    std::array<std::vector<int>, 2> m_T_inv; // Matrice de transition inversee
    std::array<std::vector<int>, 2> m_C;     // Matrice d'encodage par etat
    std::vector<double> m_P;                 // Metriques de branche
    std::vector<int> m_backwards_path;       // Chemin le plus probable
    std::vector<int> m_M;                    // Changements d'etat au final de l'encodage
    std::vector<int> m_decoded;
    std::vector<std::array<double, 2>> m_bin_vals;
    std::vector<int> m_closing_path;
    std::vector<int> m_closing_inputs;
};
}
}

#endif // DECODER_VITERBI_SIHO_BACKWARD_CLOSED_HPP_