/*!
 * \file
 * \brief Class factory::Decoder.
 */
#ifndef FACTORY_DECODER_HPP_
#define FACTORY_DECODER_HPP_

#include <cli.hpp>
#include <map>
#include <memory>
#include <string>

#include "Factory/Factory.hpp"
#include "Module/Decoder/Decoder_SIHO.hpp"
#include "Module/Encoder/Encoder.hpp"
#include "Tools/Factory/Header.hpp"

namespace aff3ct
{
namespace factory
{
extern const std::string Decoder_name;
extern const std::string Decoder_prefix;
class Decoder : public Factory
{
  public:
    // ----------------------------------------------------------------------------------------------------- PARAMETERS
    // required parameters
    int K = 0;
    int N_cw = 0;

    // optional parameters
    std::string type = "ML";
    std::string implem = "STD";
    bool systematic = true;
    bool hamming = false;
    int tail_length = 0;
    int flips = 3;
    int seed = 0;

    // deduced parameters
    float R = -1.f;

    // -------------------------------------------------------------------------------------------------------- METHODS
    virtual ~Decoder() = default;
    virtual Decoder* clone() const;

    // parameters construction
    virtual void get_description(cli::Argument_map_info& args) const;
    virtual void store(const cli::Argument_map_value& vals);
    virtual void get_headers(std::map<std::string, tools::header_list>& headers, const bool full = true) const;

  protected:
    Decoder(const std::string& n, const std::string& p);

    template<typename B = int, typename Q = float>
    module::Decoder_SIHO<B, Q>* build(module::Encoder<B>* encoder = nullptr) const;
};
}
}

#endif /* FACTORY_DECODER_HPP_ */
