package com.heyhooky.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

// Le jeton (la seule clé du visiteur), le brouillon et les non-lus, gardés
// par clé de site dans un fichier privé.
class StockageTest {
    @get:Rule
    val dossier = TemporaryFolder()

    @Test
    fun `le jeton survit à un redémarrage de l'app`() {
        StockageFichier(dossier.root, "pk_site").apply {
            jeton = "abc123"
            utilisateur = "client-42"
            brouillon = "Bonjour, je"
            luJusqua = 42
        }
        val relu = StockageFichier(dossier.root, "pk_site")
        assertEquals("abc123", relu.jeton)
        assertEquals("l'identifiant du client est rangé avec le jeton", "client-42", relu.utilisateur)
        assertEquals("Bonjour, je", relu.brouillon)
        assertEquals(42, relu.luJusqua)
    }

    @Test
    fun `chaque site a sa conversation`() {
        StockageFichier(dossier.root, "pk_a").jeton = "jeton-a"
        assertNull(StockageFichier(dossier.root, "pk_b").jeton)
    }

    @Test
    fun `la clé du site n'apparaît pas dans le nom du fichier`() {
        StockageFichier(dossier.root, "pk_secret").jeton = "x"
        val noms = File(dossier.root, "hooky").list()!!.toList()
        assertEquals(1, noms.size)
        assertFalse(noms.single().contains("pk_secret"))
        assertNotEquals(StockageFichier.empreinte("a"), StockageFichier.empreinte("b"))
    }

    @Test
    fun `effacer oublie tout (reset)`() {
        val stockage = StockageFichier(dossier.root, "pk_site").apply {
            jeton = "abc"
            utilisateur = "client-42"
            luJusqua = 3
        }
        stockage.effacer()
        assertNull(stockage.jeton)
        assertNull(stockage.utilisateur)
        assertEquals(0, stockage.luJusqua)
        assertNull(StockageFichier(dossier.root, "pk_site").jeton)
    }

    @Test
    fun `un fichier abîmé ne fait pas planter`() {
        val fichier = File(File(dossier.root, "hooky").apply { mkdirs() }, "site-" + StockageFichier.empreinte("pk") + ".properties")
        fichier.writeBytes(byteArrayOf(0, 1, 2, 92, 117))
        val stockage = StockageFichier(dossier.root, "pk")
        stockage.jeton = "neuf"
        assertEquals("neuf", StockageFichier(dossier.root, "pk").jeton)
        assertTrue(fichier.exists())
    }

    @Test
    fun `un brouillon vide est effacé`() {
        val stockage = StockageFichier(dossier.root, "pk")
        stockage.brouillon = "x"
        stockage.brouillon = ""
        assertNull(StockageFichier(dossier.root, "pk").brouillon)
    }
}
