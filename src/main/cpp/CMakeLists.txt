# PlayStation Remote Play for the PS5 card: chiaki's protocol library (from the
# CloudPad / chiaki-ng lineage, AGPL-3.0 with the OpenSSL exception, see
# third_party/chiaki/COPYING) and the thin JNI bridge the app talks to.
#
# What is in the tree and what is fetched:
#   third_party/chiaki      the protocol library, unmodified, plus its nanopb
#                           sources pre-generated from takion.proto, so the build
#                           needs neither Python nor protoc
#   third_party/nanopb      nanopb's runtime (the generator is not needed)
#   third_party/jerasure,   the FEC the video stream relies on
#   third_party/gf-complete
#   fetched, pinned by hash: mbedTLS (chiaki's crypto and curl's TLS), curl (PSN
#   requests and the push-notification WebSocket), Opus (audio), json-c and
#   miniupnpc (PSN hole punching). The first configure needs the network, then
#   the archives sit in the build tree.
cmake_minimum_required(VERSION 3.22)
project(psbridge C CXX)

include(FetchContent)
set(FETCHCONTENT_QUIET ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)

# NDK r28 turns a few warnings in third-party code into errors.
set(THIRD_PARTY_C_FLAGS "-Wno-error -Wno-documentation -Wno-unused-but-set-variable -w")
set(SAVED_C_FLAGS "${CMAKE_C_FLAGS}")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${THIRD_PARTY_C_FLAGS}")

# ---------------------------------------------------------------- mbedTLS 2.28
# 2.x on purpose: chiaki uses havege.h, gone in 3.x.
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)
set(USE_SHARED_MBEDTLS_LIBRARY OFF CACHE BOOL "" FORCE)
set(USE_STATIC_MBEDTLS_LIBRARY ON CACHE BOOL "" FORCE)
set(INSTALL_MBEDTLS_HEADERS OFF CACHE BOOL "" FORCE)
FetchContent_Declare(mbedtls
    URL https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v2.28.0.tar.gz
    URL_HASH SHA256=6519579b836ed78cc549375c7c18b111df5717e86ca0eeff4cb64b2674f424cc)
FetchContent_MakeAvailable(mbedtls)

# ---------------------------------------------------------------- curl
# HTTP only, with the WebSocket support PSN's push notifications need, on mbedTLS.
# The mbedTLS archives do not exist yet at configure time: curl is told where they
# will be and made to wait for them.
set(MBEDTLS_INCLUDE_DIR "${mbedtls_SOURCE_DIR}/include" CACHE PATH "" FORCE)
set(MBEDTLS_LIBRARY "${mbedtls_BINARY_DIR}/library/libmbedtls.a" CACHE FILEPATH "" FORCE)
set(MBEDX509_LIBRARY "${mbedtls_BINARY_DIR}/library/libmbedx509.a" CACHE FILEPATH "" FORCE)
set(MBEDCRYPTO_LIBRARY "${mbedtls_BINARY_DIR}/library/libmbedcrypto.a" CACHE FILEPATH "" FORCE)
foreach(opt BUILD_CURL_EXE BUILD_LIBCURL_DOCS BUILD_MISC_DOCS ENABLE_CURL_MANUAL BUILD_EXAMPLES
            CURL_USE_LIBPSL USE_LIBIDN2 USE_NGHTTP2 CURL_USE_LIBSSH2 CURL_USE_LIBSSH CURL_ZLIB
            CURL_BROTLI CURL_ZSTD CURL_USE_OPENSSL CURL_USE_PKGCONFIG CURL_ENABLE_EXPORT_TARGET PICKY_COMPILER)
    set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
foreach(opt BUILD_STATIC_LIBS HTTP_ONLY CURL_USE_MBEDTLS CURL_DISABLE_INSTALL)
    set(${opt} ON CACHE BOOL "" FORCE)
endforeach()
set(CURL_CA_BUNDLE "none" CACHE STRING "" FORCE)
set(CURL_CA_PATH "none" CACHE STRING "" FORCE)
FetchContent_Declare(curl
    URL https://github.com/curl/curl/archive/b1ef0e1a01c0bb6ee5367bd9c186a603bde3615a.tar.gz
    URL_HASH SHA256=1d2e0e7f1efe2a6faac7a378d44f5b1419c9f5a41b99987807e77f2e514dc158)
FetchContent_MakeAvailable(curl)
add_dependencies(libcurl_static mbedtls mbedx509 mbedcrypto)

# ---------------------------------------------------------------- Opus
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(opus
    URL https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
    URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1)
FetchContent_MakeAvailable(opus)
# chiaki includes <opus/opus.h>; the archive keeps the headers in include/.
set(OPUS_WRAPPER_DIR "${CMAKE_CURRENT_BINARY_DIR}/opus-include")
file(MAKE_DIRECTORY "${OPUS_WRAPPER_DIR}/opus")
file(GLOB OPUS_HEADERS "${opus_SOURCE_DIR}/include/*.h")
foreach(h ${OPUS_HEADERS})
    get_filename_component(n ${h} NAME)
    configure_file(${h} "${OPUS_WRAPPER_DIR}/opus/${n}" COPYONLY)
endforeach()

# ---------------------------------------------------------------- json-c
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(BUILD_APPS OFF CACHE BOOL "" FORCE)
set(DISABLE_WERROR ON CACHE BOOL "" FORCE)
# Unpacked into a folder named json-c, so that its parent serves <json-c/...>.
FetchContent_Declare(jsonc
    URL https://github.com/json-c/json-c/archive/refs/tags/json-c-0.17-20230812.tar.gz
    URL_HASH SHA256=024d302a3aadcbf9f78735320a6d5aedf8b77876c8ac8bbb95081ca55054c7eb
    SOURCE_DIR ${CMAKE_CURRENT_BINARY_DIR}/_deps/json-c)
FetchContent_MakeAvailable(jsonc)

# ---------------------------------------------------------------- miniupnpc
set(UPNPC_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(UPNPC_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(UPNPC_BUILD_SAMPLE OFF CACHE BOOL "" FORCE)
set(UPNPC_NO_INSTALL ON CACHE BOOL "" FORCE)
FetchContent_Declare(miniupnp
    URL https://github.com/miniupnp/miniupnp/archive/refs/tags/miniupnpc_2_2_8.tar.gz
    URL_HASH SHA256=3d0d49f761f09c1321ceb663d1b008825361d7bd242640c60ae467e82642502a
    SOURCE_SUBDIR miniupnpc)
FetchContent_MakeAvailable(miniupnp)
# chiaki includes <miniupnpc/miniupnpc.h>; the repository keeps them in include/.
set(MINIUPNPC_WRAPPER_DIR "${CMAKE_CURRENT_BINARY_DIR}/miniupnpc-include")
file(MAKE_DIRECTORY "${MINIUPNPC_WRAPPER_DIR}/miniupnpc")
file(GLOB MINIUPNPC_HEADERS "${miniupnp_SOURCE_DIR}/miniupnpc/include/*.h")
foreach(h ${MINIUPNPC_HEADERS})
    get_filename_component(n ${h} NAME)
    configure_file(${h} "${MINIUPNPC_WRAPPER_DIR}/miniupnpc/${n}" COPYONLY)
endforeach()

# ---------------------------------------------------------------- in-tree helpers
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

add_library(nanopb STATIC ${TP}/nanopb/pb_common.c ${TP}/nanopb/pb_decode.c ${TP}/nanopb/pb_encode.c)
target_include_directories(nanopb PUBLIC ${TP}/nanopb)
target_compile_definitions(nanopb PUBLIC PB_C99_STATIC_ASSERT)

add_library(gf_complete STATIC
    ${TP}/gf-complete/src/gf.c ${TP}/gf-complete/src/gf_wgen.c ${TP}/gf-complete/src/gf_w4.c
    ${TP}/gf-complete/src/gf_w8.c ${TP}/gf-complete/src/gf_w16.c ${TP}/gf-complete/src/gf_w32.c
    ${TP}/gf-complete/src/gf_w64.c ${TP}/gf-complete/src/gf_w128.c ${TP}/gf-complete/src/gf_rand.c
    ${TP}/gf-complete/src/gf_general.c ${TP}/gf-complete/src/gf_cpu.c)
target_include_directories(gf_complete PUBLIC ${TP}/gf-complete/include)

add_library(jerasure STATIC
    ${TP}/jerasure/src/galois.c ${TP}/jerasure/src/jerasure.c ${TP}/jerasure/src/reed_sol.c
    ${TP}/jerasure/src/cauchy.c ${TP}/jerasure/src/liberation.c)
target_include_directories(jerasure PUBLIC ${TP}/jerasure/include)
target_link_libraries(jerasure gf_complete)

set(CMAKE_C_FLAGS "${SAVED_C_FLAGS}")

# ---------------------------------------------------------------- chiaki
set(CHIAKI ${TP}/chiaki)
file(GLOB CHIAKI_SOURCES ${CHIAKI}/src/*.c ${CHIAKI}/src/remote/*.c)
add_library(chiaki STATIC ${CHIAKI_SOURCES} ${CHIAKI}/generated/takion.pb.c)
target_include_directories(chiaki PUBLIC
    ${CHIAKI}/include
    ${CMAKE_CURRENT_SOURCE_DIR}/chiaki-config
    ${CHIAKI}/generated
    ${OPUS_WRAPPER_DIR})
target_include_directories(chiaki PRIVATE
    ${CMAKE_CURRENT_BINARY_DIR}/_deps
    ${jsonc_BINARY_DIR}
    ${MINIUPNPC_WRAPPER_DIR}
    ${miniupnp_BINARY_DIR})
target_compile_definitions(chiaki PUBLIC CHIAKI_LIB_ENABLE_MBEDTLS)
target_compile_options(chiaki PRIVATE -Wno-error -w)
target_link_libraries(chiaki
    nanopb jerasure opus json-c libminiupnpc-static CURL::libcurl_static
    mbedtls mbedx509 mbedcrypto)

# ---------------------------------------------------------------- the bridge
add_library(psbridge SHARED ps-jni.c)
target_link_libraries(psbridge chiaki log)
# 16 KB pages (Android 15+ devices): NDK r28 aligns by default, stated anyway.
target_link_options(psbridge PRIVATE "-Wl,-z,max-page-size=16384")
