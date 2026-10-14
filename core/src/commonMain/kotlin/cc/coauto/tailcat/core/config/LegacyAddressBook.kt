package cc.coauto.tailcat.core.config

public data class LegacyEntry(val username: String, val address: String)

public data class LegacyImport(val entries: List<LegacyEntry>, val skipped: Int)
