package app.melo.music.data

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RecommendationPolicyTest {
    private fun song(id:String,artist:String,title:String="Song $id")=Track(id,title,artist,180000,url="https://www.youtube.com/watch?v=$id")
    private val a=song("a","Imagine Dragons","Natural")
    private val b=song("b","Maroon 5","Sugar")
    @Test fun contextChoosesSeveralFamiliarArtistsWithBoundedQueries() {
        val names=RecommendationPolicy.artists(listOf(a,b,b.copy(id="b2"),song("c","DNCE"),song("d","Other"),song("e","More")),a)
        assertEquals(listOf("Imagine Dragons","Maroon 5","DNCE","Other"),names)
        assertTrue(RecommendationPolicy.artists(listOf(a.copy(source="local")),a.copy(source="local")).isEmpty())
    }
    @Test fun filtersUnrelatedArtistsTalkVideosLongMixesAndSeenSongsThenRotatesArtists() {
        val a2=song("a2","Imagine Dragons","Believer");val a3=song("a3","Imagine Dragons","Thunder");val b2=song("b2","Maroon 5","Memories")
        val catalog=mapOf("Imagine Dragons" to listOf(a,song("duplicate","Imagine Dragons","Imagine Dragons - Natural (Official Audio)"),song("talk","Imagine Dragons","Reaction to Believer"),song("wrong","Random Channel"),song("long","Imagine Dragons").copy(durationMs=7200000),a2,a3),"Maroon 5" to listOf(b,b2))
        assertEquals(listOf(a2,b2,a3),RecommendationPolicy.select(listOf(a,b),a,listOf(a,b),catalog))
    }
    @Test fun neverInventsVideoFallbackWhenCatalogHasNoSafeCandidates() {
        assertTrue(RecommendationPolicy.select(listOf(a),a,listOf(a),mapOf("Imagine Dragons" to listOf(song("bad","Vlog Channel")))).isEmpty())
    }
    @Test fun liveFamiliarArtistRecommendationsUseRealSongCatalog() {
        assumeTrue("Explicit live-source opt-in",System.getProperty("melo.live")=="true")
        val context=listOf(a,b,song("c","DNCE","Cake By The Ocean"))
        val result=MusicRepository().recommend(context,a,context)
        println("Familiar music recommendations: ${result.size}")
        assertTrue("Song catalog returned too few recommendations",result.size>=6)
        val known=context.flatMap(RecommendationPolicy::artistNames).map(TrackMatcher::normalized).toSet()
        assertTrue(result.all { track -> RecommendationPolicy.artistNames(track).any { TrackMatcher.normalized(it) in known } })
        assertFalse(result.any { it.id in context.map { original->original.id } })
        val file=java.io.File("../artifacts/mauri-0.5/recommendations-live.tsv").apply { parentFile?.mkdirs() }
        file.writeText("artist\ttitle\tid\n"+result.joinToString("\n"){"${it.artist}\t${it.title}\t${it.id}"})
    }
}
