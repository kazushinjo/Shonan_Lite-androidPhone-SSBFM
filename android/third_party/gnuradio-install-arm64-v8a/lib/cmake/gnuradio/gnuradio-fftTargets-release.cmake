#----------------------------------------------------------------
# Generated CMake target import file for configuration "Release".
#----------------------------------------------------------------

# Commands may need to know the format version.
set(CMAKE_IMPORT_FILE_VERSION 1)

# Import target "gnuradio::gnuradio-fft" for configuration "Release"
set_property(TARGET gnuradio::gnuradio-fft APPEND PROPERTY IMPORTED_CONFIGURATIONS RELEASE)
set_target_properties(gnuradio::gnuradio-fft PROPERTIES
  IMPORTED_LOCATION_RELEASE "${_IMPORT_PREFIX}/lib/libgnuradio-fft.so"
  IMPORTED_SONAME_RELEASE "libgnuradio-fft.so"
  )

list(APPEND _cmake_import_check_targets gnuradio::gnuradio-fft )
list(APPEND _cmake_import_check_files_for_gnuradio::gnuradio-fft "${_IMPORT_PREFIX}/lib/libgnuradio-fft.so" )

# Commands beyond this point should not need to know the version.
set(CMAKE_IMPORT_FILE_VERSION)
