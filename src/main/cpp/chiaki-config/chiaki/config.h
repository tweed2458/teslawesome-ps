// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

// chiaki's build-time switches, fixed for this app: Opus is built in (audio is
// decoded on the phone), there is no Raspberry Pi decoder. Generated from
// config.h.in upstream; kept as a plain file so the build needs no configure step.
#ifndef CHIAKI_CONFIG_H
#define CHIAKI_CONFIG_H

#define CHIAKI_LIB_ENABLE_OPUS 1
#define CHIAKI_LIB_ENABLE_PI_DECODER 0

#endif // CHIAKI_CONFIG_H
