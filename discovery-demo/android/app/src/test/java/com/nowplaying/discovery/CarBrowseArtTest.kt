package com.nowplaying.discovery

import androidx.media3.common.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class CarBrowseArtTest {
  @Test fun radioRowsUseRadioSymbolsInsteadOfGenericFolders() {
    assertEquals(R.drawable.car_station_fav, CarBrowseArt.drawableFor(RadioCatalog.FAVOURITES, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_radio, CarBrowseArt.drawableFor(RadioCatalog.ROOT, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_alphabet, CarBrowseArt.drawableFor(RadioCatalog.ALL, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_alphabet, CarBrowseArt.drawableFor("radio/letter/A/0", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_numbers, CarBrowseArt.drawableFor(RadioCatalog.NUMBERS_FOLDER, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_numbers, CarBrowseArt.drawableFor("radio/letter/7/0", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_search, CarBrowseArt.drawableFor(RadioCatalog.SEARCH, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
  }

  @Test fun radioFiltersUseTheirSemanticArtwork() {
    assertEquals(R.drawable.car_filter, CarBrowseArt.drawableFor(CarRadioFilters.FILTERS, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_genre, CarBrowseArt.drawableFor(CarRadioFilters.setGenre("jazz"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_place, CarBrowseArt.drawableFor(CarRadioFilters.addPlace("country/us"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_language, CarBrowseArt.drawableFor(CarRadioFilters.setLanguage("english"), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_station_fav, CarBrowseArt.drawableFor(CarRadioFilters.STARRED_ON, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
  }

  @Test fun libraryAndSettingsRowsUseUsefulArtworkNotFolders() {
    assertEquals(R.drawable.car_queue, CarBrowseArt.drawableFor("songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_playlist, CarBrowseArt.drawableFor("playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS))
    assertEquals(R.drawable.car_type_albums, CarBrowseArt.drawableFor("albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS))
    assertEquals(R.drawable.car_settings, CarBrowseArt.drawableFor(CarSettings.ROOT, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_equalizer, CarBrowseArt.drawableFor(CarSettings.EQ, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_sparkles, CarBrowseArt.drawableFor(CarMoodDiscover.ID, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_info, CarBrowseArt.drawableFor("hint/no-results", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
    assertEquals(R.drawable.car_type_all, CarBrowseArt.drawableFor("unknown/container", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
  }
}
