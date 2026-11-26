package com.opentune.playback

import com.opentune.data.model.BrowseItem
import com.opentune.data.model.BrowseType
import com.opentune.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMatchTest {
    private val money = Song("yelawolf", "Money (feat. Jelly Roll & Struggle Jennings)", "Yelawolf", null)
    private val moneyForNothing = Song("dire", "Money for Nothing (Remastered 1996)", "Dire Straits", null)
    private val espresso = Song("sabrina", "Espresso", "Sabrina Carpenter", null)
    private val results = listOf(money, moneyForNothing, espresso)

    @Test
    fun theRequestPicksTheSongItNamesNotYouTubesFirst() {
        // What the phone played: the first song of the mixed search, which names one of the words.
        assertEquals(moneyForNothing, bestVoiceMatch(results, "money for nothing dire straits", null, null))
    }

    @Test
    fun theTitleAndArtistTheAssistantSentDecideIt() {
        val cover = Song("x", "Wonderboy", "Some Cover Band", null)
        val wonderboy = Song("y", "Wonderboy", "Tenacious D", null)
        assertEquals(wonderboy, bestVoiceMatch(listOf(cover, wonderboy), "", "Wonderboy", "Tenacious D"))
    }

    @Test
    fun theArtistSettlesTwoSongsOfTheSameName() {
        val cover = Song("a", "Espresso", "The Cover Crew", null)
        assertEquals(espresso, bestVoiceMatch(listOf(cover, espresso), "espresso", "Espresso", "Sabrina Carpenter"))
    }

    @Test
    fun aRequestThatNamesNothingInTheResultsKeepsYouTubesFirst() {
        assertEquals(espresso, bestVoiceMatch(listOf(espresso), "zzzz not a song", "zzzz", null))
    }

    @Test
    fun noResultsIsNoSong() {
        assertNull(bestVoiceMatch(emptyList(), "espresso", null, null))
    }

    @Test
    fun anAlbumSearchPicksTheAlbumTheRequestNamed() {
        val abbey = BrowseItem("MPREb_abbey", "Abbey Road", "Album • The Beatles • 1969", null, BrowseType.ALBUM)
        val letItBe = BrowseItem("MPREb_let", "Let It Be", "Album • The Beatles • 1970", null, BrowseType.ALBUM)
        assertEquals(abbey, bestVoiceBrowse(listOf(letItBe, abbey), "", "Abbey Road", "The Beatles"))
    }

    @Test
    fun twoAlbumsOfTheSameNameAreSettledByTheArtist() {
        // What the phone queued: a covers album listed above The Beatles' own, because both
        // rows are called "Abbey Road" and the kind word sat where the artist was read.
        val cover = BrowseItem("MPREb_autumn", "Abbey Road", "Album • Autumn 33 • 2019", null, BrowseType.ALBUM)
        val abbey = BrowseItem("MPREb_abbey", "Abbey Road", "Album • The Beatles • 1969", null, BrowseType.ALBUM)
        assertEquals(
            abbey,
            bestVoiceBrowse(listOf(cover, abbey), "abbey road the beatles", "Abbey Road", "The Beatles"),
        )
    }

    @Test
    fun noAlbumResultsIsNoAlbum() {
        assertNull(bestVoiceBrowse(emptyList(), "abbey road", null, null))
    }
}
