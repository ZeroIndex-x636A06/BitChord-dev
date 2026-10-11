s = open("probe/ExplicitLyrics.kt.src").read()
start = s.index("object ExplicitLyrics {")
end = s.index("/**\n * What lyric checks have found")
open("probe/ExplicitLyricsOnly.kt", "w").write("package probe\n\nimport java.util.Locale\n\n" + s[start:end])
