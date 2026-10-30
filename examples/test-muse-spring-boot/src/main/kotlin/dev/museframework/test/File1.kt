package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.flowRef
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.springboot.MuseConfigComponent

@MuseConfigComponent
class File1(private val file2: File2) : MuseConfigDefinition() {
    val declarativeFlow = flow("declarative-flow") {
        initialState = FlowInitialState.STARTED
        scheduler {
            docName = "Scheduler"
            fixedFrequency {

                frequency = "10000"

            }
        }
        logger {
            docName = "Logger"
            message = "declarative: #[payload]"
        }
        flowRef(file2.declarativeFlow) {

        }
    }
}
