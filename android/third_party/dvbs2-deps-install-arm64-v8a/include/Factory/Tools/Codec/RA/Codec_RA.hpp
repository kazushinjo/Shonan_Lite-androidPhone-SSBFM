/*!
 * \file
 * \brief Class factory::Codec_RA.
 */
#ifndef FACTORY_CODEC_RA_HPP
#define FACTORY_CODEC_RA_HPP

#include <cli.hpp>
#include <map>
#include <string>

#include "Factory/Tools/Codec/Codec_SIHO.hpp"
#include "Module/CRC/CRC.hpp"
#include "Tools/Codec/RA/Codec_RA.hpp"
#include "Tools/Factory/Header.hpp"

namespace aff3ct
{
namespace factory
{
extern const std::string Codec_RA_name;
extern const std::string Codec_RA_prefix;
class Codec_RA : public Codec_SIHO
{
  public:
    explicit Codec_RA(const std::string& p = Codec_RA_prefix);
    virtual ~Codec_RA() = default;
    Codec_RA* clone() const;

    // parameters construction
    void get_description(cli::Argument_map_info& args) const;
    void store(const cli::Argument_map_value& vals);
    void get_headers(std::map<std::string, tools::header_list>& headers, const bool full = true) const;

    // builder
    template<typename B = int, typename Q = float>
    tools::Codec_RA<B, Q>* build(const module::CRC<B>* crc = nullptr) const;
};
}
}

#endif /* FACTORY_CODEC_RA_HPP */
