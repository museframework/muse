package dev.museframework.dsl.api.model.config

interface MuseConfiguration {
    val docName: String
    val docId: String
    val docDescription: String?
    val sourceFile: String?
    val sourceLine: Int?
    val sourceClass: String?
}
