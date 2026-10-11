package probe

import java.io.File

fun main(args: Array<String>) {
    val songs = File(args[0]).readLines().filter { it.isNotBlank() }
    val dir = File(args[1])
    songs.forEachIndexed { i, line ->
        val (expect, title, artist) = line.split("|")
        val f = File(dir, "$i.txt")
        if (!f.exists()) { println("$expect | ---- | $title — $artist | no lyrics found"); return@forEachIndexed }
        val text = f.readText()
        val terms = ExplicitLyrics.matches(text).entries.joinToString(", ") { "${it.key.name}×${it.value}" }
        val score = ExplicitLyrics.score(text)
        val verdict = if (score >= ExplicitLyrics.THRESHOLD) "FLAG" else "pass"
        val mark = if ((verdict == "FLAG") == (expect == "E")) "ok " else "!! "
        println("$mark$expect | $verdict ${score.toString().padStart(2)} | $title — $artist | ${terms.ifEmpty { "-" }}")
    }
}
