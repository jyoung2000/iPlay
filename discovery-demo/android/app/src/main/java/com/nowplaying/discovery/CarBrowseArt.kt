package com.nowplaying.discovery

import androidx.media3.common.MediaMetadata

/** Semantic embedded artwork for every browsable car row (never a generic folder placeholder). */
object CarBrowseArt {
  fun drawableFor(id: String, mediaType: Int): Int {
    val lower = id.lowercase()
    return when {
      id == RadioCatalog.FAVOURITES || id == CarRadioFilters.STARRED_ON || id == CarRadioFilters.STARRED_OFF -> R.drawable.car_station_fav
      id == RadioCatalog.ROOT || id.startsWith(CarRadioFilters.STATIONS) -> R.drawable.car_radio
      id == RadioCatalog.ALL || id.startsWith("radio/az/") -> {
        if (id.startsWith("radio/az/") && id.removePrefix("radio/az/").toIntOrNull()?.let { it > 0 } == true) R.drawable.car_more
        else R.drawable.car_alphabet
      }
      id == RadioCatalog.NUMBERS_FOLDER -> R.drawable.car_numbers
      RadioCatalog.letterFolder(id) != null -> {
        val (initial, offset) = RadioCatalog.letterFolder(id)!!
        when {
          offset > 0 -> R.drawable.car_more
          initial in RadioCatalog.NUMBERS -> R.drawable.car_numbers
          else -> R.drawable.car_alphabet
        }
      }
      id == RadioCatalog.SEARCH || id == "search" || id == "car/search" -> R.drawable.car_search
      id == CarRadioFilters.FILTERS || id == CarRadioFilters.CLEAR -> R.drawable.car_filter
      id == CarRadioFilters.GENRE || id.startsWith("${CarRadioFilters.GENRE}/") -> R.drawable.car_genre
      id == CarRadioFilters.PLACE || id.startsWith("${CarRadioFilters.PLACE}/") -> R.drawable.car_place
      id == CarRadioFilters.LANGUAGE || id.startsWith("${CarRadioFilters.LANGUAGE}/") -> R.drawable.car_language
      id == CarSettings.ROOT -> R.drawable.car_settings
      id == CarSettings.EQ || id.startsWith("${CarSettings.EQ}/") -> R.drawable.car_equalizer
      id == CarSettings.DISCOVERY || id.startsWith("${CarSettings.DISCOVERY}/") || id == CarMoodDiscover.ID || id.startsWith("car/settings/mood/") -> R.drawable.car_sparkles
      id == "songs" -> R.drawable.car_queue
      id == "playlists" || id.startsWith("playlist/") || lower.startsWith("addto/") || mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS || mediaType == MediaMetadata.MEDIA_TYPE_PLAYLIST -> {
        if (lower.contains("on-phone") || lower.contains("on_phone")) R.drawable.car_on_phone else R.drawable.car_playlist
      }
      id == "albums" || mediaType == MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS -> R.drawable.car_type_albums
      lower.startsWith("hint/") -> R.drawable.car_info
      lower.contains("phone") -> R.drawable.car_on_phone
      lower.startsWith("radio/") -> R.drawable.car_radio
      else -> R.drawable.car_type_all
    }
  }
}
