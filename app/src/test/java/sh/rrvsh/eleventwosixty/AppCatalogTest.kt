package sh.rrvsh.eleventwosixty

import org.junit.Assert.assertEquals
import org.junit.Test

class AppCatalogTest {
    @Test
    fun prefersLeanbackAndReturnsOneAlphabeticalEntryPerPackage() {
        val candidates = listOf(
            LaunchCandidate("example.beta", "BetaPhone", "beta", false),
            LaunchCandidate("example.alpha", "AlphaPhone", "Alpha", false),
            LaunchCandidate("example.beta", "BetaTv", "Beta TV", true),
            LaunchCandidate("example.alpha", "AlphaTvZ", "Ignored Z", true),
            LaunchCandidate("example.alpha", "AlphaTvA", "Alpha TV", true),
        )

        val selected = AppCatalog.preferLeanback(candidates)

        assertEquals(listOf("example.alpha", "example.beta"), selected.map { it.packageName })
        assertEquals(listOf("AlphaTvA", "BetaTv"), selected.map { it.className })
    }

    @Test
    fun fallsBackToConventionalLauncherCandidate() {
        val selected = AppCatalog.preferLeanback(
            listOf(LaunchCandidate("example.only", "PhoneActivity", "Only", false)),
        )

        assertEquals(1, selected.size)
        assertEquals("PhoneActivity", selected.single().className)
    }
}
