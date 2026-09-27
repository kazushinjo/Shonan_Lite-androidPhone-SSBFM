/*!
 * \file
 * \brief Class tools::Reporter_noise.
 */
#ifndef REPORTER_NOISE_HPP_
#define REPORTER_NOISE_HPP_
#include <streampu.hpp>

#include "Tools/Noise/Noise.hpp"

namespace aff3ct
{
namespace tools
{
template<typename R = float>
class Reporter_noise : public spu::tools::Reporter
{
  protected:
    const Noise<R>& noise;
    const bool show_sigma;
    group_t noise_group;

  public:
    explicit Reporter_noise(const Noise<R>& noise, const bool show_sigma = false);
    virtual ~Reporter_noise() = default;

    report_t report(bool final = false);
};
}
}

#endif /* REPORTER_NOISE_HPP_ */
