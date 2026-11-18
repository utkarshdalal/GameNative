package app.gamenative.filedetect

internal fun asciiLower(s: String): String {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c in 'A'..'Z') break
        i++
    }
    if (i == s.length) return s
    val chars = s.toCharArray()
    while (i < chars.size) {
        val c = chars[i]
        if (c in 'A'..'Z') chars[i] = (c.code + 32).toChar()
        i++
    }
    return String(chars)
}

internal fun asciiUpper(s: String): String {
    val chars = s.toCharArray()
    for (i in chars.indices) {
        val c = chars[i]
        if (c in 'a'..'z') chars[i] = (c.code - 32).toChar()
    }
    return String(chars)
}

internal fun phpBasename(path: String): String {
    var end = path.length
    while (end > 0 && path[end - 1] == '/') end--
    if (end == 0) return ""
    val slash = path.lastIndexOf('/', end - 1)
    return path.substring(slash + 1, end)
}

internal fun phpDirname(path: String): String {
    var end = path.length
    while (end > 0 && path[end - 1] == '/') end--
    if (end == 0) return if (path.isEmpty()) "" else "/"
    val slash = path.lastIndexOf('/', end - 1)
    if (slash < 0) return "."
    var dirEnd = slash
    while (dirEnd > 0 && path[dirEnd - 1] == '/') dirEnd--
    return if (dirEnd == 0) "/" else path.substring(0, dirEnd)
}

internal fun phpExtension(path: String): String {
    val base = phpBasename(path)
    val dot = base.lastIndexOf('.')
    return if (dot < 0) "" else base.substring(dot + 1)
}
