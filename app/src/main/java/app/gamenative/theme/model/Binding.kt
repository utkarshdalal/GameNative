package app.gamenative.theme.model

/**
 * Describes a variable that can be defined in a theme. Variables may be declared in a separate
 * `variables.xml` or inline within `theme.xml` and can be referenced by bindings.
 */
data class Variable(
    /** Unique identifier for this variable within the theme scope. */
    val id: String,
    /** Type of the variable value (string, int, float, bool, color). */
    val type: ValueType,
    /** Default value encoded as a string; parser/validator will convert to [type]. */
    val defaultValue: String? = null,
)

/**
 * A lightweight binding expression that points at a data source path, e.g. `game.title` or
 * `game.capsule`. Resolved at render time by the BindingEngine.
 */
data class Binding(
    /** Dot-separated path within the data model (e.g., `game.title`). */
    val path: String,
)

/**
 * Represents either a literal string value or a binding expression that resolves to a string.
 * Use this for text fields and URIs.
 */
sealed class StringOrBinding {
    /** A fixed literal string value. */
    data class Literal(val value: String) : StringOrBinding()

    /** A reference to a binding expression. */
    data class Ref(val binding: Binding) : StringOrBinding()
}

/**
 * Represents either a literal float value or a binding expression that resolves to a float.
 */
sealed class FloatOrBinding {
    /** A fixed literal float value. */
    data class Literal(val value: Float) : FloatOrBinding()

    /** A reference to a binding expression. */
    data class Ref(val binding: Binding) : FloatOrBinding()
}

/**
 * Represents either a literal integer (e.g., ARGB color) or a binding expression that resolves to an int.
 */
sealed class IntOrBinding {
    /** A fixed literal int value. */
    data class Literal(val value: Int) : IntOrBinding()

    /** A reference to a binding expression. */
    data class Ref(val binding: Binding) : IntOrBinding()
}
