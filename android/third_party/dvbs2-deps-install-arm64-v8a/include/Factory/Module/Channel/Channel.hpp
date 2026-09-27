/*!
 * \file
 * \brief Class factory::Channel.
 */
#ifndef FACTORY_CHANNEL_HPP
#define FACTORY_CHANNEL_HPP

#include <cli.hpp>
#include <map>
#include <string>

#include "Factory/Factory.hpp"
#include "Module/Channel/Channel.hpp"
#include "Tools/Factory/Header.hpp"
#include "Tools/Math/Distribution/Distributions.hpp"

namespace aff3ct
{
namespace factory
{
extern const std::string Channel_name;
extern const std::string Channel_prefix;
class Channel : public Factory
{
  public:
    // ----------------------------------------------------------------------------------------------------- PARAMETERS
    // required parameters
    int N = 0;

    // optional parameters
    std::string type = "AWGN";
    std::string implem = "STD";
    std::string path = "";
    std::string block_fading = "NO";
    bool add_users = false;
    bool complex = false;
    int seed = 0;
    int gain_occur = 1;

    // -------------------------------------------------------------------------------------------------------- METHODS
    explicit Channel(const std::string& p = Channel_prefix);
    virtual ~Channel() = default;
    Channel* clone() const;

    // parameters construction
    void get_description(cli::Argument_map_info& args) const;
    void store(const cli::Argument_map_value& vals);
    void get_headers(std::map<std::string, tools::header_list>& headers, const bool full = true) const;

    // builder
    template<typename R = float>
    module::Channel<R>* build_gaussian() const;
    template<typename R = float>
    module::Channel<R>* build_event() const;
    template<typename R = float>
    module::Channel<R>* build_userpdf(const tools::Distributions<R>& dist) const;
    template<typename R = float>
    module::Channel<R>* build() const;
    template<typename R = float>
    module::Channel<R>* build(const tools::Distributions<R>& dist) const;
};

}
}

#endif /* FACTORY_CHANNEL_HPP */
