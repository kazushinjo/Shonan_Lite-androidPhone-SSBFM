/*!
 * \file
 * \brief Class factory::Source.
 */
#ifndef FACTORY_SOURCE_HPP
#define FACTORY_SOURCE_HPP

#include <cli.hpp>
#include <map>
#include <streampu.hpp>
#include <string>

#include "Factory/Factory.hpp"
#include "Tools/Factory/Header.hpp"

namespace aff3ct
{
namespace factory
{
extern const std::string Source_name;
extern const std::string Source_prefix;
class Source : public Factory
{
  public:
    // ----------------------------------------------------------------------------------------------------- PARAMETERS
    // required parameters
    int K = 0;

    // optional parameters
    std::string type = "RAND";
    std::string implem = "STD";
    std::string path = "";
    bool auto_reset = true;
    bool fifo_mode = false;
    int seed = 0;
    int start_idx = 0;

    // -------------------------------------------------------------------------------------------------------- METHODS
    explicit Source(const std::string& p = Source_prefix);
    virtual ~Source() = default;
    Source* clone() const;

    // parameters construction
    virtual void get_description(cli::Argument_map_info& args) const;
    virtual void store(const cli::Argument_map_value& vals);
    virtual void get_headers(std::map<std::string, tools::header_list>& headers, const bool full = true) const;

    // builder
    template<typename B = int>
    spu::module::Source<B>* build() const;
};
}
}

#endif /* FACTORY_SOURCE_HPP */
