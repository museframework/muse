package dev.museframework.test

import dev.museframework.dsl.api.model.config.MuseConfiguration
import dev.museframework.dsl.api.model.flow.MuseFlow
import dev.museframework.quarkus.MuseConfigProvider
import io.quarkus.runtime.ImageMode
import jakarta.inject.Singleton

@Singleton
class NativeMuseConfigProvider(
    private val apikit: ApikitFlow,
    private val async: AsyncFlow,
    private val choice: ChoiceFlow,
    private val file1: File1,
    private val file2: File2,
    private val forEach: ForEachFlow,
    private val listener: Listener,
    private val parallelForEach: ParallelForEachFlow,
    private val raiseError: RaiseErrorFlow,
    private val removeVariable: RemoveVariableFlow,
    private val scatterGather: ScatterGatherFlow,
    private val tryFlow: TryFlow,
    private val untilSuccessful: UntilSuccessfulFlow,
) : MuseConfigProvider {
    override fun flows(): List<MuseFlow> {
        if (!ImageMode.current().isNativeImage) return emptyList()

        return listOf(
            apikit.getApikitHello,
            apikit.postApikitHello,
            apikit.apikitMain,
            async.asyncDemoFlow,
            choice.choiceDemoFlow,
            file1.declarativeFlow,
            file2.declarativeFlow,
            forEach.forEachDemoFlow,
            listener.declarativeFlow,
            parallelForEach.parallelForEachDemoFlow,
            raiseError.raiseErrorDemoFlow,
            removeVariable.removeVariableDemoFlow,
            scatterGather.scatterGatherDemoFlow,
            tryFlow.tryDemoFlow,
            untilSuccessful.untilSuccessfulDemoFlow,
        )
    }

    override fun configs(): List<MuseConfiguration> {
        if (!ImageMode.current().isNativeImage) return emptyList()

        return listOf(apikit.apiConfig, file2.httpRequestConnection)
    }
}
