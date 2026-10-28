package app.tvmate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanTest {
    @Test
    fun variantsCollapseToOneKey() {
        val names = listOf("UK: SKY CINEMA GREATS 4K", "UK: SKY CINEMA GREATS HEVC 4K", "UK: SKY CINEMA GREATS ◉", "UK: Sky Cinema Greats ᴴᴰ/ᴿᴬᵂ")
        assertEquals(1, names.map { Clean.key(Clean.base(it)) }.toSet().size)
        assertEquals("MOVISTAR CINE ESPAÑOL", Clean.base("ES: MOVISTAR CINE ESPAÑOL  ᴴᴰ"))
        assertEquals("CBS 4 (WCCO) MINNEAPOLIS", Clean.base("US: CBS 4 (WCCO) MINNEAPOLIS HD"))
    }

    @Test
    fun junkIsDropped() {
        assertTrue(Clean.junk("- NO EVENT STREAMING - | 8K EXCLUSIVE | US: ESPN+ PPV 401", ""))
        assertTrue(Clean.junk("US: 24/7 NETFLIX 1", ""))
        assertTrue(Clean.junk("(Fanatiz 013) | Vasco da Gama vs Flamengo (2026-10-29 19:00:00)", ""))
        assertTrue(Clean.junk("##### UK SPORTS #####", ""))
        assertFalse(Clean.junk("UK: SKY SPORTS MAIN EVENT ᴴᴰ", "UK| SKY SPORTS"))
    }

    @Test
    fun hdPlaysBeforeHeavyVariants() {
        val sorted = listOf("UK: BBC ONE SD", "UK: BBC ONE 4K", "UK: BBC ONE ᴴᴰ", "UK: BBC ONE").sortedBy(Clean::rank)
        assertEquals("UK: BBC ONE ᴴᴰ", sorted.first())
        assertEquals("UK: BBC ONE SD", sorted.last())
    }

    @Test
    fun categoriesComeFromCountryAndGenre() {
        assertEquals("United Kingdom", Clean.country("UK| NOW TV SPORT ᵁᴴᴰ"))
        assertEquals("Sports", Clean.genre("SKY SPORTS F1 UK| SKY SPORTS"))
        assertEquals("Movies", Clean.genre("SKY CINEMA GREATS"))
    }

    @Test
    fun domainLogosBeatBareIpPicons() {
        val dead = "http://51.158.145.100/picons/logos/logos/1912887.png"
        val good = "http://photo-tmdb.com/stalker_portal/logos/SBTN.png"
        assertEquals(good, Clean.bestLogo(listOf(dead, good)))
        assertTrue(Clean.weakLogo(dead))
        assertFalse(Clean.weakLogo(good))
    }

    @Test
    fun searchMatchesWordsInAnyOrderAndRanksExactFirst() {
        val idx = listOf("Sky Sports Main Event", "Sky Sports F1", "Sports", "BT Sport 1", "Sky News").map { Clean.searchable(it, it) }
        assertEquals(listOf("Sky Sports Main Event"), Clean.search("main sky", idx))
        assertEquals("Sports", Clean.search("sports", idx).first())
        assertEquals(listOf("Sky Sports F1", "Sky Sports Main Event"), Clean.search("sky spo", idx))
        assertTrue(Clean.search("   ", idx).isEmpty())
    }
}
