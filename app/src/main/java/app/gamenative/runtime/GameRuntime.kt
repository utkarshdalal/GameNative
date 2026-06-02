package app.gamenative.runtime

// sealed so a new variant breaks the build at every dispatch site that forgets it.
sealed interface GameRuntime {
    val id: String
}
