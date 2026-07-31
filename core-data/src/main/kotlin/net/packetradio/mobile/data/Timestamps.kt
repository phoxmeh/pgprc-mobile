package net.packetradio.mobile.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "YYYY-MM-DD HH:MM:SS", local time — the convention already used by [net.packetradio.mobile.model.AddressBookEntry.lastHeard]. */
internal fun nowTimestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
