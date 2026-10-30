package dev.museframework.test

import dev.museframework.dsl.api.config.MuseConfigDefinition
import dev.museframework.dsl.dsl.async
import dev.museframework.dsl.dsl.flow
import dev.museframework.dsl.dsl.setPayload
import dev.museframework.dsl.api.model.flow.FlowInitialState
import dev.museframework.engine.components.processor.dsl.logger
import dev.museframework.dsl.dsl.scheduler
import dev.museframework.springboot.MuseConfigComponent

@MuseConfigComponent
class AsyncFlow : MuseConfigDefinition() {

    val asyncDemoFlow = flow("async-demo-flow") {
        initialState = FlowInitialState.STOPPED
        scheduler {
            docName = "Async Scheduler"
            fixedFrequency {

                frequency = "30000"

            }
        }
        setPayload {
            docName = "Initial Payload"
            value = "main-flow-payload"
        }
        logger {
            docName = "Before Async"
            message = "Starting async processing"
        }
        async {
            docName = "Background Processing"
            maxConcurrency = 5
            setPayload {
                docName = "Async Payload"
                value = "async-operation-result"
            }
            logger {
                docName = "Async Logger"
                message = "Async operation completed: #[payload]"
            }
        }
        logger {
            docName = "After Async"
            message = "Main flow continues immediately. Payload: #[payload]"
        }
    }
}