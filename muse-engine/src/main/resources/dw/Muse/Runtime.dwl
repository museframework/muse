%dw 2.0

fun p(name: String): String = native("MuseRuntime::p")