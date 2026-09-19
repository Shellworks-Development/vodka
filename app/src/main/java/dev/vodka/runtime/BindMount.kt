package dev.vodka.runtime

data class BindMount(
  val source: String,
  val target: String,
  val readOnly: Boolean = false,
) {
  fun toWire(): String = if (readOnly) "$source:$target:ro" else "$source:$target"
}
