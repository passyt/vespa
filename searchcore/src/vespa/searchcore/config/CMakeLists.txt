# Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(searchcore_fconfig STATIC
    SOURCES
    DEPENDS
)
vespa_generate_config(searchcore_fconfig partitions.def)
install(FILES partitions.def RENAME vespa.config.search.core.partitions.def DESTINATION var/db/vespa/config_server/serverdb/classes)
vespa_generate_config(searchcore_fconfig fdispatchrc.def)
install(FILES fdispatchrc.def RENAME vespa.config.search.core.fdispatchrc.def DESTINATION var/db/vespa/config_server/serverdb/classes)
vespa_generate_config(searchcore_fconfig proton.def)
install(FILES proton.def RENAME vespa.config.search.core.proton.def DESTINATION var/db/vespa/config_server/serverdb/classes)
vespa_generate_config(searchcore_fconfig ranking-constants.def)
install(FILES ranking-constants.def RENAME vespa.config.search.core.ranking-constants.def DESTINATION var/db/vespa/config_server/serverdb/classes)
vespa_generate_config(searchcore_fconfig hwinfo.def)
