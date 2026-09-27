/*!
 * \file
 * \brief Class factory::Decoder_repetition.
 */
#ifndef FACTORY_DECODER_REPETITION_HPP
#define FACTORY_DECODER_REPETITION_HPP

#include <cli.hpp>
#include <map>
#include <string>

#include "Factory/Module/Decoder/Decoder.hpp"
#include "Module/Decoder/Decoder_SIHO.hpp"
#include "Module/Encoder/Encoder.hpp"
#include "Tools/Factory/Header.hpp"

namespace aff3ct
{
namespace factory
{
extern const std::string Decoder_repetition_name;
extern const std::string Decoder_repetition_prefix;
class Decoder_repetition : public Decoder
{
  public:
    // ----------------------------------------------------------------------------------------------------- PARAMETERS
    // optional parameters
    bool buffered = true;

    // -------------------------------------------------------------------------------------------------------- METHODS
    explicit Decoder_repetition(const std::string& p = Decoder_repetition_prefix);
    virtual ~Decoder_repetition() = default;
    Decoder_repetition* clone() const;

    // parameters construction
    void get_description(cli::Argument_map_info& args) const;
    void store(const cli::Argument_map_value& vals);
    void get_headers(std::map<std::string, tools::header_list>& headers, const bool full = true) const;

    // builder
    template<typename B = int, typename Q = float>
    module::Decoder_SIHO<B, Q>* build(module::Encoder<B>* encoder = nullptr) const;
};
}
}

#endif /* FACTORY_DECODER_REPETITION_HPP */
