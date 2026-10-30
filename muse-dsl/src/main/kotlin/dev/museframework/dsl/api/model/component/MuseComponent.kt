package dev.museframework.dsl.api.model.component

interface MuseComponent {
    val docName: String
    val docId: String
    val docDescription: String?
    val sourceFile: String?
    val sourceLine: Int?
    val sourceClass: String?
}

interface Source : MuseComponent

interface PipelineElement : MuseComponent

interface Processor : PipelineElement

interface Router : PipelineElement
