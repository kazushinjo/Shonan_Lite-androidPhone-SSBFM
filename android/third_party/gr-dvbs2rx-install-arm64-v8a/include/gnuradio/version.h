#ifndef INCLUDED_GR_VERSION
#define INCLUDED_GR_VERSION
// clang-format off
#define GR_VERSION_MAJOR 3
#define GR_VERSION_API   10
#define GR_VERSION_MINOR 12
#define GR_VERSION_MAINT 0
// clang-format on
//! \brief macro to convert versions to comparable integers
#define GR_MAKE_VERSION(major, api, minor, maint) \
    ((maint) + ((minor) << 10) + ((api) << 20) + ((major) << 30))
#define GR_VERSION \
    GR_MAKE_VERSION(GR_VERSION_MAJOR, GR_VERSION_API, GR_VERSION_MINOR, GR_VERSION_MAINT)
#endif
